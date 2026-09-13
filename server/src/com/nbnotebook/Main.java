package com.nbnotebook;

import com.sun.net.httpserver.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 牛逼笔记本 · 本地服务器
 * 纯 JDK17 实现，零第三方依赖。监听 0.0.0.0，同一局域网手机/平板可直接访问。
 */
public final class Main {
    static final String VERSION = "1.0.0";
    static Path DATA_DIR;
    static Path WEB_DIR;
    static Store store;
    static Classifier classifier;

    public static void main(String[] args) throws Exception {
        Path base = Path.of(System.getProperty("nb.base", System.getProperty("user.dir")));
        DATA_DIR = base.resolve("data");
        WEB_DIR = base.resolve("web");
        if (!Files.exists(WEB_DIR.resolve("index.html"))) {
            WEB_DIR = Path.of(System.getProperty("nb.web", "web"));
        }
        store = new Store(DATA_DIR);
        store.init();

        // OLLAMA 探测
        classifier = new Classifier("http://127.0.0.1:11434", "qwen3:8b");
        String ollamaErr = classifier.probe();
        if (ollamaErr != null) System.out.println("[警告] " + ollamaErr);
        else System.out.println("[OK] OLLAMA 可用，分类模型: " + classifier.getModel());

        // 清理历史临时提取文件
        cleanTmp();

        int port = Integer.parseInt(System.getProperty("nb.port", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/", Main::handle);
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();

        System.out.println();
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║   牛逼笔记本 v" + VERSION + " 已启动           ║");
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println("本机访问   : http://127.0.0.1:" + port);
        for (String ip : lanIps()) {
            System.out.println("局域网访问 : http://" + ip + ":" + port + "   ← 手机/平板连同一 Wi-Fi 后访问这个");
        }
        System.out.println("按 Ctrl+C 停止。数据目录: " + DATA_DIR.toAbsolutePath());
    }

    // ================= 路由 =================

    static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        try {
            cors(ex);
            if (path.equals("/") || path.equals("/index.html")) { serveFile(ex, "index.html"); return; }
            if (path.equals("/app.js")) { serveFile(ex, "app.js"); return; }
            if (path.equals("/style.css")) { serveFile(ex, "style.css"); return; }
            if (path.startsWith("/media/")) { serveMedia(ex, path); return; }
            if (path.startsWith("/extract/")) { serveExtract(ex, path); return; }
            if (path.startsWith("/api/")) { api(ex, path, method); return; }
            ex.sendResponseHeaders(404, -1);
            ex.close();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "服务器错误" : e.getMessage();
            byte[] b = ("{\"ok\":false,\"error\":" + Json.quote(msg) + "}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(500, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        }
    }

    static void api(HttpExchange ex, String path, String method) throws Exception {
        String rest = path.substring("/api/".length());
        String[] seg = rest.split("/");

        // ---- 状态 ----
        if (seg[0].equals("status") && method.equals("GET")) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("version", VERSION);
            out.put("model", classifier.getModel());
            out.put("lanIps", lanIps());
            out.put("port", ex.getLocalAddress().getPort());
            json(ex, 200, out);
            return;
        }

        // ---- 分类树 ----
        if (seg[0].equals("tree")) {
            if (method.equals("GET")) { json(ex, 200, store.loadTree()); return; }
            if (method.equals("POST") && seg.length >= 2 && seg[1].equals("import")) {
                Map<String, Object> body = readJson(ex);
                String scope = Json.str(body, "scope", "默认");
                Object structure = body.get("structure");
                if (structure == null) throw new IOException("缺少 structure 字段（知识结构 JSON）");
                json(ex, 200, store.importStructure(scope, structure));
                return;
            }
        }

        // ---- AI 分类 ----
        if (seg[0].equals("classify") && method.equals("POST")) {
            Map<String, Object> body = readJson(ex);
            String scope = Json.str(body, "scope", "默认");
            String title = Json.str(body, "title", "");
            String content = Json.str(body, "content", "");
            Map<String, Object> tree = store.loadTree();
            Map<String, Object> scopeNode = store.findChild(tree, scope);
            List<String> flat = scopeNode == null ? new ArrayList<>()
                    : store.flattenTree(scopeNode, "");
            json(ex, 200, classifier.classify(scope, title, content, flat));
            return;
        }

        // ---- 笔记 CRUD ----
        if (seg[0].equals("notes")) {
            if (seg.length == 1 && method.equals("GET")) {
                Map<String, String> q = query(ex);
                json(ex, 200, Map.of("ok", true, "notes", store.list(q.get("scope"), q.get("categoryId"))));
                return;
            }
            if (seg.length == 1 && method.equals("POST")) {
                Map<String, Object> body = readJson(ex);
                String title = Json.str(body, "title", "");
                String scope = Json.str(body, "scope", "默认");
                String content = Json.str(body, "content", "");
                String categoryId = Json.str(body, "categoryId", "");
                boolean auto = Json.num(body, "autoClassify", 0) == 1;
                List<String> category = Json.strList(body, "category");
                Map<String, Object> meta;
                if (auto) {
                    Map<String, Object> tree = store.loadTree();
                    Map<String, Object> scopeNode = store.findChild(tree, scope);
                    List<String> flat = scopeNode == null ? new ArrayList<>()
                            : store.flattenTree(scopeNode, "");
                    Map<String, Object> res = classifier.classify(scope, title, content, flat);
                    Map<String, Object> norm = classifier.normalizePath(store, scope, Json.strList(res, "path"));
                    category = Json.strList(norm, "path");
                    categoryId = Json.str(norm, "categoryId", "");
                    meta = store.create(title, scope, content, category, categoryId);
                    meta.put("aiReason", Json.str(res, "reason", ""));
                    meta.put("aiOk", res.get("ok"));
                    store.writeMeta(meta);
                } else {
                    meta = store.create(title, scope, content, category, categoryId);
                    if (categoryId == null || categoryId.isBlank()) {
                        Map<String, Object> leaf = store.ensureCategoryInTree(scope, category, null);
                        meta.put("categoryId", leaf.get("id"));
                        store.writeMeta(meta);
                    }
                }
                json(ex, 200, meta);
                return;
            }
            if (seg.length >= 2 && seg[1].equals("classify-again") && method.equals("POST")) {
                // 对已有笔记重新 AI 分类
                Map<String, Object> body = readJson(ex);
                String id = Json.str(body, "id", "");
                if (!store.exists(id)) throw new IOException("笔记不存在");
                Map<String, Object> meta = store.getMeta(id);
                String scope = Json.str(body, "scope", Json.str(meta, "scope", "默认"));
                String content = store.readNote(id);
                Map<String, Object> tree = store.loadTree();
                Map<String, Object> scopeNode = store.findChild(tree, scope);
                List<String> flat = scopeNode == null ? new ArrayList<>()
                        : store.flattenTree(scopeNode, "");
                Map<String, Object> res = classifier.classify(scope, Json.str(meta, "title", ""), content, flat);
                Map<String, Object> norm = classifier.normalizePath(store, scope, Json.strList(res, "path"));
                store.update(id, null, scope, null, Json.str(norm, "categoryId", ""), Json.strList(norm, "path"));
                Map<String, Object> out = new LinkedHashMap<>(res);
                out.put("ok", true);
                json(ex, 200, out);
                return;
            }
            if (seg.length >= 2 && seg[1].equals("upload") && method.equals("POST")) {
                Map<String, Object> parts = parseMultipart(ex);
                byte[] file = (byte[]) parts.get("file");
                String noteId = parts.get("noteId") == null ? "" : parts.get("noteId").toString();
                String orig = parts.get("filename") == null ? "upload" : parts.get("filename").toString();
                if (file == null || file.length == 0) throw new IOException("未收到文件");
                String ext = orig.contains(".") ? orig.substring(orig.lastIndexOf('.') + 1).toLowerCase() : "bin";
                if (!ext.matches("[a-z0-9]{2,5}")) ext = "bin";
                String url = store.saveMedia(noteId, file, ext);
                json(ex, 200, Map.of("ok", true, "url", url, "type", mimeType(ext), "name", orig));
                return;
            }
            if (seg.length == 2 && method.equals("GET")) {
                if (!store.exists(seg[1])) throw new IOException("笔记不存在");
                Map<String, Object> meta = store.getMeta(seg[1]);
                meta.put("content", store.readNote(seg[1]));
                json(ex, 200, meta);
                return;
            }
            if (seg.length == 2 && method.equals("DELETE")) {
                store.delete(seg[1]);
                json(ex, 200, Map.of("ok", true));
                return;
            }
            if (seg.length == 2 && method.equals("PUT")) {
                Map<String, Object> body = readJson(ex);
                String id = seg[1];
                if (!store.exists(id)) throw new IOException("笔记不存在");
                String title = body.containsKey("title") ? Json.str(body, "title") : null;
                String scope = body.containsKey("scope") ? Json.str(body, "scope") : null;
                String content = body.containsKey("content") ? Json.str(body, "content") : null;
                String categoryId = body.containsKey("categoryId") ? Json.str(body, "categoryId") : null;
                List<String> category = body.containsKey("category") ? Json.strList(body, "category") : null;
                store.update(id, title, scope, content, categoryId, category);
                json(ex, 200, store.getMeta(id));
                return;
            }
            if (seg.length >= 3 && seg[1].equals("adopt") && seg[2].equals("extract") && method.equals("POST")) {
                // 把临时提取的图导入指定笔记
                Map<String, Object> body = readJson(ex);
                String noteId = Json.str(body, "noteId", "");
                List<String> urls = Json.strList(body, "urls");
                List<String> newUrls = new ArrayList<>();
                for (String u : urls) {
                    if (!u.startsWith("/extract/")) continue;
                    String name = u.substring("/extract/".length());
                    Path tmp = DATA_DIR.resolve("extracted").resolve(name);
                    if (!Files.exists(tmp)) continue;
                    byte[] bytes = Files.readAllBytes(tmp);
                    String url = store.saveMedia(noteId, bytes, "png");
                    Files.deleteIfExists(tmp);
                    newUrls.add(url);
                }
                json(ex, 200, Map.of("ok", true, "urls", newUrls));
                return;
            }
        }

        // ---- 卷子图片提取 ----
        if (seg[0].equals("extract-image") && method.equals("POST")) {
            Map<String, Object> parts = parseMultipart(ex);
            byte[] file = (byte[]) parts.get("file");
            if (file == null) throw new IOException("未收到卷子照片");
            String mode = parts.get("mode") == null ? "auto" : parts.get("mode").toString();
            List<Map<String, Object>> images = new ArrayList<>();
            if (mode.equals("crop")) {
                int x = Integer.parseInt(parts.getOrDefault("x", "0").toString());
                int y = Integer.parseInt(parts.getOrDefault("y", "0").toString());
                int w = Integer.parseInt(parts.getOrDefault("w", "0").toString());
                int h = Integer.parseInt(parts.getOrDefault("h", "0").toString());
                byte[] crop = ImageExtractor.crop(file, x, y, w, h);
                String url = saveExtracted(crop);
                images.add(Map.of("url", url, "width", sizeOf(crop)[0], "height", sizeOf(crop)[1]));
            } else {
                for (byte[] crop : ImageExtractor.extract(file)) {
                    String url = saveExtracted(crop);
                    int[] d = sizeOf(crop);
                    images.add(Map.of("url", url, "width", d[0], "height", d[1]));
                }
            }
            json(ex, 200, Map.of("ok", true, "images", images));
            return;
        }

        // ---- 打印 ----
        if (seg[0].equals("print") && method.equals("POST")) {
            Map<String, Object> body = readJson(ex);
            List<String> ids = Json.strList(body, "ids");
            if (ids.isEmpty()) throw new IOException("请先勾选要打印的笔记");
            String html = PrintHtml.build(store, ids);
            byte[] b = html.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            return;
        }

        json(ex, 404, Map.of("ok", false, "error", "接口不存在: " + path));
    }

    // ================= 工具 =================

    static void json(HttpExchange ex, int code, Object o) throws IOException {
        byte[] b = Json.stringify(o).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return q;
        for (String kv : raw.split("&")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2) q.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8), URLDecoder.decode(p[1], StandardCharsets.UTF_8));
        }
        return q;
    }

    static Map<String, Object> readJson(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readAllBytes();
        return Json.asMap(Json.parse(new String(b, StandardCharsets.UTF_8)));
    }

    static void cors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    static void serveFile(HttpExchange ex, String name) throws IOException {
        Path p = WEB_DIR.resolve(name).normalize();
        if (!p.startsWith(WEB_DIR) || !Files.exists(p)) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
        byte[] b = Files.readAllBytes(p);
        String ct = name.endsWith(".js") ? "application/javascript; charset=utf-8"
                : name.endsWith(".css") ? "text/css; charset=utf-8"
                : "text/html; charset=utf-8";
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static void serveMedia(HttpExchange ex, String path) throws IOException {
        String[] seg = path.split("/");
        if (seg.length < 4) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
        String noteId = seg[2];
        // 防路径穿越
        String file = seg[3].replace("..", "");
        Path p = store.mediaPath(noteId, file);
        if (!Files.exists(p)) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
        byte[] b = Files.readAllBytes(p);
        String ext = file.contains(".") ? file.substring(file.lastIndexOf('.') + 1).toLowerCase() : "";
        ex.getResponseHeaders().set("Content-Type", mimeType(ext));
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static void serveExtract(HttpExchange ex, String path) throws IOException {
        String name = path.substring("/extract/".length()).replace("..", "");
        Path p = DATA_DIR.resolve("extracted").resolve(name);
        if (!Files.exists(p)) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
        byte[] b = Files.readAllBytes(p);
        ex.getResponseHeaders().set("Content-Type", "image/png");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static String saveExtracted(byte[] png) throws IOException {
        Path dir = DATA_DIR.resolve("extracted");
        Files.createDirectories(dir);
        String name = UUID.randomUUID().toString().substring(0, 12) + ".png";
        Files.write(dir.resolve(name), png);
        return "/extract/" + name;
    }

    static int[] sizeOf(byte[] img) {
        try {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(img));
            return new int[]{bi.getWidth(), bi.getHeight()};
        } catch (Exception e) { return new int[]{0, 0}; }
    }

    static void cleanTmp() {
        try {
            Path dir = DATA_DIR.resolve("extracted");
            if (Files.exists(dir)) {
                try (var s = Files.list(dir)) {
                    for (Path p : s.toList()) Files.deleteIfExists(p);
                }
            }
        } catch (Exception ignored) {}
    }

    static List<String> lanIps() {
        List<String> ips = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        ips.add(a.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {}
        return ips;
    }

    static String mimeType(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "m4a" -> "audio/mp4";
            case "pdf" -> "application/pdf";
            case "html", "htm" -> "text/html; charset=utf-8";
            default -> "application/octet-stream";
        };
    }

    /** 简单 multipart/form-data 解析：返回 字段名 → 文本 或 byte[] */
    static Map<String, Object> parseMultipart(HttpExchange ex) throws IOException {
        Map<String, Object> out = new HashMap<>();
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.contains("multipart/form-data")) throw new IOException("需要 multipart/form-data");
        MatcherBoundary mb = boundaryOf(ct);
        byte[] body = ex.getRequestBody().readAllBytes();
        byte[] delimiter = ("--" + mb.boundary).getBytes(StandardCharsets.UTF_8);
        int pos = 0;
        while (true) {
            int dl = indexOf(body, delimiter, pos);
            if (dl < 0) break;
            int dlEnd = dl + delimiter.length;
            if (dlEnd + 2 <= body.length && body[dlEnd] == '-' && body[dlEnd + 1] == '-') break; // 结尾
            // 跳过 CRLF
            int start = dlEnd;
            while (start < body.length && (body[start] == '\r' || body[start] == '\n')) start++;
            int next = indexOf(body, delimiter, start);
            if (next < 0) break;
            int partEnd = next;
            // 去掉尾部 CRLF
            while (partEnd > start && (body[partEnd - 1] == '\r' || body[partEnd - 1] == '\n')) partEnd--;
            // 解析头
            int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.UTF_8), start);
            if (headerEnd < 0 || headerEnd > partEnd) { pos = next; continue; }
            String headers = new String(body, start, headerEnd - start, StandardCharsets.UTF_8);
            String name = headerField(headers, "name");
            String filename = headerField(headers, "filename");
            byte[] data = Arrays.copyOfRange(body, headerEnd + 4, partEnd);
            if (name != null) {
                if (filename != null) {
                    out.put(name, data);
                    out.put("filename", filename);
                } else {
                    out.put(name, new String(data, StandardCharsets.UTF_8));
                }
            }
            pos = next;
        }
        return out;
    }

    record MatcherBoundary(String boundary) {}
    static MatcherBoundary boundaryOf(String ct) {
        for (String part : ct.split(";")) {
            String p = part.trim();
            if (p.startsWith("boundary=")) {
                String b = p.substring("boundary=".length()).trim();
                if (b.length() >= 2 && (b.startsWith("\"") && b.endsWith("\""))) b = b.substring(1, b.length() - 1);
                return new MatcherBoundary(b);
            }
        }
        throw new RuntimeException("Content-Type 缺少 boundary");
    }

    static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = from; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    static String headerField(String headers, String key) {
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-disposition")) {
                for (String part : line.split(";")) {
                    String p = part.trim();
                    String prefix = key + "=";
                    if (p.startsWith(prefix)) {
                        String v = p.substring(prefix.length()).trim();
                        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\""))) v = v.substring(1, v.length() - 1);
                        return v;
                    }
                }
            }
        }
        return null;
    }
}
