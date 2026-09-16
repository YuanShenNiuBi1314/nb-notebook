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
import java.util.zip.*;

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
    static PendingStore pending;

    public static void main(String[] args) throws Exception {
        Path base = Path.of(System.getProperty("nb.base", System.getProperty("user.dir")));
        DATA_DIR = base.resolve("data");
        WEB_DIR = base.resolve("web");
        if (!Files.exists(WEB_DIR.resolve("index.html"))) {
            WEB_DIR = Path.of(System.getProperty("nb.web", "web"));
        }
        store = new Store(DATA_DIR);
        store.init();
        pending = new PendingStore(DATA_DIR);

        // OLLAMA 探测
        classifier = new Classifier("http://127.0.0.1:11434", "qwen3:8b", "qwen3-vl:30b");
        String ollamaErr = classifier.probe();
        if (ollamaErr != null) System.out.println("[警告] " + ollamaErr);
        else System.out.println("[OK] OLLAMA 可用，分类模型: " + classifier.getModel() + "，OCR 视觉模型: " + classifier.getVisionModel());

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
            if (path.startsWith("/pending-media/")) { servePendingMedia(ex, path); return; }
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
            out.put("visionModel", classifier.getVisionModel());
            out.put("lanIps", lanIps());
            out.put("port", ex.getLocalAddress().getPort());
            json(ex, 200, out);
            return;
        }

        // ---- 手机实时采集：进待整理区（缓冲区） ----
        if (seg[0].equals("capture") && method.equals("POST")) {
            Map<String, Object> parts = parseMultipart(ex);
            String scope = "默认";
            String subject = "";
            List<Map<String, Object>> items = new ArrayList<>();
            Object metaObj = parts.get("meta");
            if (metaObj instanceof byte[] bb) metaObj = new String(bb, StandardCharsets.UTF_8);
            if (metaObj != null) {
                Map<String, Object> meta = Json.asMap(Json.parse(metaObj.toString()));
                scope = Json.str(meta, "scope", scope);
                subject = Json.str(meta, "subject", Json.str(meta, "title", ""));
                for (Object o : Json.arr(meta, "items")) {
                    if (o instanceof Map<?, ?> m) items.add(Json.asMap(m));
                }
            }
            // 图片：文件名 → bytes（同一 part 内的 name 与 filename 严格对应）
            Map<String, byte[]> media = new LinkedHashMap<>();
            List<Object> images = partsOf(parts, "images");
            List<Object> fnames = partsOf(parts, "images.filename");
            for (int i = 0; i < images.size(); i++) {
                if (images.get(i) instanceof byte[] b) {
                    String fn = i < fnames.size() && fnames.get(i) != null
                            ? fnames.get(i).toString() : "img" + (i + 1) + ".png";
                    if (fn == null || fn.isBlank()) fn = "img" + (i + 1) + ".png";
                    media.put(sanitizeFileName(fn), b);
                }
            }
            // OCR 专用图（红框区已涂白，识别时跳过图片区）：文件名与主图对应
            List<Object> ocrImgs = partsOf(parts, "ocr_images");
            List<Object> ocrNames = partsOf(parts, "ocr_images.filename");
            for (int i = 0; i < ocrImgs.size(); i++) {
                if (ocrImgs.get(i) instanceof byte[] b) {
                    String fn = i < ocrNames.size() && ocrNames.get(i) != null
                            ? ocrNames.get(i).toString() : "ocr" + (i + 1) + ".jpg";
                    if (fn == null || fn.isBlank()) fn = "ocr" + (i + 1) + ".jpg";
                    media.put(sanitizeFileName(fn), b);
                }
            }
            // 拼 HTML（图片用 /pm/ 占位，create 后替换为 /pending-media/<id>/）
            StringBuilder html = new StringBuilder();
            int imgCount = 0;
            for (Map<String, Object> it : items) {
                String type = Json.str(it, "type", "text");
                if (type.equals("text")) {
                    String t = Json.str(it, "text", "");
                    if (t != null && !t.isBlank()) {
                        html.append("<p>").append(t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")).append("</p>\n");
                    }
                } else {
                    String f = Json.str(it, "file", "");
                    if (f == null || f.isBlank()) continue;
                    byte[] b = media.get(f);
                    if (b == null) {
                        String base = basename(f);
                        for (Map.Entry<String, byte[]> e : media.entrySet()) {
                            if (basename(e.getKey()).equals(base)) { b = e.getValue(); break; }
                        }
                    }
                    if (b == null) continue;
                    imgCount++;
                    html.append("<p><img src=\"/pm/").append(f).append("\" alt=\"采集图片\"></p>\n");
                }
            }
            Map<String, Object> meta = pending.create(scope, subject, html.toString(), media);
            // 替换占位 → 真实待整理媒体路径
            String id = (String) meta.get("id");
            Path cf = pending.dir().resolve(id).resolve("content.html");
            String content = Files.readString(cf, StandardCharsets.UTF_8)
                    .replace("/pm/", "/pending-media/" + id + "/");
            Files.write(cf, content.getBytes(StandardCharsets.UTF_8));
            Map<String, Object> out = new LinkedHashMap<>(meta);
            out.put("items", items.size());
            out.put("ok", true);
            json(ex, 200, out);
            return;
        }

        // ---- 待整理区管理 ----
        if (seg[0].equals("pending")) {
            if (seg.length == 1 && method.equals("GET")) {
                json(ex, 200, Map.of("ok", true, "pending", pending.list()));
                return;
            }
            if (seg.length >= 2) {
                String id = seg[1];
                Map<String, Object> pmeta = pending.get(id);
                if (pmeta == null) throw new IOException("待整理条目不存在");
                if (seg.length == 2 && method.equals("GET")) {
                    Map<String, Object> out = new LinkedHashMap<>(pmeta);
                    out.put("content", pending.readContent(id));
                    json(ex, 200, out);
                    return;
                }
                if (seg.length == 2 && method.equals("DELETE")) {
                    pending.delete(id);
                    json(ex, 200, Map.of("ok", true));
                    return;
                }
                if (seg.length == 3 && seg[2].equals("accept") && method.equals("POST")) {
                    Map<String, Object> body = readJson(ex);
                    String scope = Json.str(body, "scope", Json.str(pmeta, "scope", "默认"));
                    String subject = Json.str(body, "subject", Json.str(body, "title", ""));
                    String categoryId = Json.str(body, "categoryId", "");
                    List<String> category = Json.strList(body, "category");
                    boolean auto = Json.bool(body, "autoClassify", false);
                    boolean doOcr = Json.bool(body, "ocr", false);
                    String content = pending.readContent(id);
                    // OCR：识别图片文字（优先红框涂白的 OCR 专用图，跳过图片区）
                    if (doOcr) {
                        StringBuilder ocrHtml = new StringBuilder();
                        List<String> ocrFiles = new ArrayList<>();
                        List<String> allFiles = new ArrayList<>();
                        Map<String, byte[]> mediaMap = pending.readMedia(id);
                        for (String f : mediaMap.keySet()) {
                            if (f.contains(".ocr.")) ocrFiles.add(f);
                            else if (!f.matches(".*\\.ocr\\.[a-z0-9]+$")) allFiles.add(f);
                        }
                        if (ocrFiles.isEmpty()) ocrFiles.addAll(allFiles);
                        ocrFiles.sort(Comparator.naturalOrder());
                        int done = 0, failed = 0;
                        for (String f : ocrFiles) {
                            try {
                                String text = classifier.ocrImage(mediaMap.get(f));
                                if (text != null && !text.isBlank()) {
                                    ocrHtml.append("<p class=\"ocr-block\">📝 [OCR 识别 · ").append(f).append("]\n")
                                            .append(text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>"))
                                            .append("</p>\n");
                                    done++;
                                }
                            } catch (Exception ex2) {
                                failed++;
                            }
                        }
                        if (done > 0) content = ocrHtml.toString() + content;
                        if (failed > 0 && done == 0) {
                            // 全部失败不阻塞入库，仅提示
                            json(ex, 200, Map.of("ok", false, "error", "OCR 识别失败（" + failed + " 张图），请稍后重试或检查 OLLAMA 视觉模型"));
                            return;
                        }
                    }
                    // 标题 AI 补全
                    boolean aiTitle = false;
                    if (isPlaceholderTitle(subject) && !content.isBlank()) {
                        Map<String, Object> tRes = classifier.suggestTitle(scope, content);
                        if (Boolean.TRUE.equals(tRes.get("ok"))) {
                            subject = Json.str(tRes, "subject", Json.str(tRes, "title", "无标题笔记"));
                            aiTitle = true;
                        }
                    }
                    // 创建正式笔记
                    Map<String, Object> meta = store.create(subject, scope, "", category, categoryId);
                    String noteId = (String) meta.get("id");
                    // 复制媒体并替换 img src
                    Map<String, byte[]> media = pending.readMedia(id);
                    for (Map.Entry<String, byte[]> e : media.entrySet()) {
                        String f = e.getKey();
                        String ext = f.contains(".") ? f.substring(f.lastIndexOf('.') + 1).toLowerCase() : "png";
                        if (ext.equals("jpeg")) ext = "jpg";
                        if (!ext.matches("[a-z0-9]{2,5}")) ext = "png";
                        String url = store.saveMedia(noteId, e.getValue(), ext);
                        content = content.replace("/pending-media/" + id + "/" + f, url);
                    }
                    // 可选 AI 分类
                    String reason = "";
                    if (auto) {
                        Map<String, Object> tree = store.loadTree();
                        Map<String, Object> scopeNode = store.findChild(tree, scope);
                        List<String> flat = scopeNode == null ? new ArrayList<>()
                                : store.flattenTree(scopeNode, "");
                        Map<String, Object> res = classifier.classify(scope, subject, content, flat);
                        Map<String, Object> norm = classifier.normalizePath(store, scope, Json.strList(res, "path"));
                        category = Json.strList(norm, "path");
                        categoryId = Json.str(norm, "categoryId", "");
                        reason = Json.str(res, "reason", "");
                    } else if (categoryId == null || categoryId.isBlank()) {
                        Map<String, Object> leaf = store.ensureCategoryInTree(scope, category, null);
                        categoryId = Json.str(leaf, "id", "");
                    }
                    store.update(noteId, null, scope, content, categoryId, category);
                    Map<String, Object> meta2 = store.getMeta(noteId);
                    if (aiTitle) { meta2.put("aiTitle", true); store.writeMeta(meta2); }
                    if (auto) { meta2.put("aiReason", reason); store.writeMeta(meta2); }
                    pending.delete(id);
                    json(ex, 200, meta2);
                    return;
                }
            }
        }

        // ---- 导出笔记包（zip，供手机接收同步） ----
        if (seg[0].equals("export-pack") && method.equals("POST")) {
            Map<String, Object> body = readJson(ex);
            List<String> ids = Json.strList(body, "ids");
            if (ids.isEmpty()) throw new IOException("请先勾选要导出的笔记");
            byte[] zip = exportPack(ids);
            ex.getResponseHeaders().set("Content-Type", "application/zip");
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=nb-notebook-export.zip");
            ex.sendResponseHeaders(200, zip.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(zip); }
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
            String subject = Json.str(body, "subject", Json.str(body, "title", ""));
            String content = Json.str(body, "content", "");
            Map<String, Object> tree = store.loadTree();
            Map<String, Object> scopeNode = store.findChild(tree, scope);
            List<String> flat = scopeNode == null ? new ArrayList<>()
                    : store.flattenTree(scopeNode, "");
            json(ex, 200, classifier.classify(scope, subject, content, flat));
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
                String subject = Json.str(body, "subject", Json.str(body, "title", "")).trim();
                String scope = Json.str(body, "scope", "默认");
                String content = Json.str(body, "content", "");
                String categoryId = Json.str(body, "categoryId", "");
                boolean auto = Json.bool(body, "autoClassify", false);
                List<String> category = Json.strList(body, "category");
                // AI 自动补全标题：用户未明确指定标题且有正文时
                boolean aiTitle = false;
                if (isPlaceholderTitle(subject) && !content.isBlank()) {
                    Map<String, Object> tRes = classifier.suggestTitle(scope, content);
                    if (Boolean.TRUE.equals(tRes.get("ok"))) {
                        subject = Json.str(tRes, "subject", Json.str(tRes, "title", "无标题笔记"));
                        aiTitle = true;
                    }
                }
                Map<String, Object> meta;
                if (auto) {
                    Map<String, Object> tree = store.loadTree();
                    Map<String, Object> scopeNode = store.findChild(tree, scope);
                    List<String> flat = scopeNode == null ? new ArrayList<>()
                            : store.flattenTree(scopeNode, "");
                    Map<String, Object> res = classifier.classify(scope, subject, content, flat);
                    Map<String, Object> norm = classifier.normalizePath(store, scope, Json.strList(res, "path"));
                    category = Json.strList(norm, "path");
                    categoryId = Json.str(norm, "categoryId", "");
                    meta = store.create(subject, scope, content, category, categoryId);
                    meta.put("aiReason", Json.str(res, "reason", ""));
                    meta.put("aiOk", res.get("ok"));
                    store.writeMeta(meta);
                } else {
                    meta = store.create(subject, scope, content, category, categoryId);
                    if (categoryId == null || categoryId.isBlank()) {
                        Map<String, Object> leaf = store.ensureCategoryInTree(scope, category, null);
                        meta.put("categoryId", leaf.get("id"));
                        store.writeMeta(meta);
                    }
                }
                if (aiTitle) {
                    meta.put("aiTitle", true);
                    store.writeMeta(meta);
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
                Map<String, Object> res = classifier.classify(scope, Store.subjectOf(meta), content, flat);
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
                String orig = strPart(parts, "filename", "upload");
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
                String subject = body.containsKey("subject") || body.containsKey("title")
                        ? Json.str(body, "subject", Json.str(body, "title", "")) : null;
                String scope = body.containsKey("scope") ? Json.str(body, "scope") : null;
                String content = body.containsKey("content") ? Json.str(body, "content") : null;
                String categoryId = body.containsKey("categoryId") ? Json.str(body, "categoryId") : null;
                List<String> category = body.containsKey("category") ? Json.strList(body, "category") : null;
                boolean aiTitle = false;
                if (subject != null && isPlaceholderTitle(subject)
                        && content != null && !content.isBlank()) {
                    String sc = scope != null && !scope.isBlank() ? scope
                            : Json.str(store.getMeta(id), "scope", "默认");
                    Map<String, Object> tRes = classifier.suggestTitle(sc, content);
                    if (Boolean.TRUE.equals(tRes.get("ok"))) {
                        subject = Json.str(tRes, "subject", Json.str(tRes, "title", "无标题笔记"));
                        aiTitle = true;
                    }
                }
                store.update(id, subject, scope, content, categoryId, category);
                Map<String, Object> meta = store.getMeta(id);
                if (aiTitle) {
                    meta.put("aiTitle", true);
                    store.writeMeta(meta);
                }
                json(ex, 200, meta);
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

        // ---- 导入手机离线采集包（zip） ----
        if (seg[0].equals("import-zip") && method.equals("POST")) {
            Map<String, Object> parts = parseMultipart(ex);
            byte[] file = (byte[]) parts.get("file");
            if (file == null) throw new IOException("未收到压缩包");
            String scope = parts.get("scope") == null ? "默认" : parts.get("scope").toString();
            String subject = strPart(parts, "subject", strPart(parts, "title", ""));
            String filename = strPart(parts, "filename", "");
            String ac = strPart(parts, "autoClassify", "0");
            boolean auto = ac.equals("1") || ac.equalsIgnoreCase("true");
            json(ex, 200, importZip(file, scope, subject, filename, auto));
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

    /** 待整理区媒体文件 */
    static void servePendingMedia(HttpExchange ex, String path) throws IOException {
        String rest = path.substring("/pending-media/".length());
        int i = rest.indexOf('/');
        if (i < 0) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
        String id = rest.substring(0, i);
        String file = rest.substring(i + 1);
        Path base = pending.dir().resolve(id).resolve("media").normalize();
        Path f = base.resolve(file).normalize();
        if (!f.startsWith(base) || !Files.exists(f) || !Files.isRegularFile(f)) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        byte[] b = Files.readAllBytes(f);
        String ext = file.contains(".") ? file.substring(file.lastIndexOf('.') + 1).toLowerCase() : "png";
        ex.getResponseHeaders().set("Content-Type", mimeType(ext));
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

    static String basename(String p) {
        String s = p.replace("\\", "/");
        int i = s.lastIndexOf('/');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    static String sanitizeFileName(String s) {
        String r = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_").trim();
        if (r.isEmpty()) r = "img.png";
        if (r.length() > 80) r = r.substring(r.length() - 80);
        return r;
    }

    /** 导出笔记包 zip：manifest.json + notes/<id>/{note.html, meta.json, media/*} */
    static byte[] exportPack(List<String> ids) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            StringBuilder manifest = new StringBuilder();
            manifest.append("{\"app\":\"nb-notebook\",\"version\":\"1.0\",\"exported\":\"")
                    .append(Store.now()).append("\",\"notes\":[");
            boolean first = true;
            for (String id : ids) {
                if (!store.exists(id)) continue;
                Map<String, Object> meta = store.getMeta(id);
                String content = store.readNote(id);
                if (!first) manifest.append(",");
                first = false;
                manifest.append("{\"id\":\"").append(Json.quote(id))
                        .append("\",\"subject\":\"").append(Json.quote(Store.subjectOf(meta)))
                        .append("\",\"scope\":\"").append(Json.quote(Json.str(meta, "scope", "")))
                        .append("\",\"category\":").append(Json.stringify(Json.arr(meta, "category")))
                        .append("}");
                zos.putNextEntry(new ZipEntry("notes/" + id + "/note.html"));
                zos.write(content.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                zos.putNextEntry(new ZipEntry("notes/" + id + "/meta.json"));
                zos.write(Json.stringify(meta).getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                Path md = DATA_DIR.resolve("notes").resolve(id).resolve("media");
                if (Files.isDirectory(md)) {
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(md)) {
                        for (Path p : ds) {
                            if (!Files.isRegularFile(p)) continue;
                            zos.putNextEntry(new ZipEntry("notes/" + id + "/media/" + p.getFileName()));
                            zos.write(Files.readAllBytes(p));
                            zos.closeEntry();
                        }
                    }
                }
            }
            manifest.append("]}");
            zos.putNextEntry(new ZipEntry("manifest.json"));
            zos.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return bos.toByteArray();
    }

    /** 占位标题判断：用户未明确指定标题 */
    static boolean isPlaceholderTitle(String t) {
        if (t == null || t.isBlank()) return true;
        String s = t.trim();
        return s.equals("无标题笔记") || s.equals("新笔记") || s.equals("草稿笔记")
                || s.equals("未命名笔记") || s.equals("未命名") || s.equals("新建笔记");
    }

    /**
     * 导入手机离线采集包（zip）。
     * 包结构：manifest.json {app, version, items:[{type:photo|text|draw, file, text}]} + 图片文件
     * 无 manifest 时按扩展名自动归类。可触发 AI 分类。
     */
    static Map<String, Object> importZip(byte[] zipBytes, String scope, String title, String filename, boolean autoClassify) throws IOException {
        // 标题（subject）：显式给了就用；否则先用文件名占位，内容生成后 AI 补全
        boolean titleFromFile = title == null || title.isBlank() || title.equals(filename);
        if (title == null || title.isBlank()) {
            title = filename;
            if (title.toLowerCase().endsWith(".zip")) title = title.substring(0, title.length() - 4);
            if (title.isBlank()) title = "手机采集笔记 " + Store.now();
        }
        // 1. 解压
        Map<String, byte[]> files = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                files.put(e.getName(), zis.readAllBytes());
            }
        }
        if (files.isEmpty()) throw new IOException("压缩包为空");

        // 2. 解析条目
        List<Map<String, Object>> items = new ArrayList<>();
        byte[] manifestBytes = files.get("manifest.json");
        if (manifestBytes != null) {
            try {
                Map<String, Object> manifest = Json.asMap(Json.parse(new String(manifestBytes, StandardCharsets.UTF_8)));
                for (Object o : Json.arr(manifest, "items")) {
                    if (o instanceof Map<?, ?> m) items.add(Json.asMap(m));
                }
            } catch (Exception ignored) { items = new ArrayList<>(); }
        }
        if (items.isEmpty()) {
            for (Map.Entry<String, byte[]> f : files.entrySet()) {
                String name = f.getKey();
                if (name.equals("manifest.json")) continue;
                String low = name.toLowerCase();
                Map<String, Object> it = new LinkedHashMap<>();
                if (low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png") || low.endsWith(".webp")) {
                    it.put("type", "photo"); it.put("file", name);
                } else if (low.endsWith(".txt")) {
                    it.put("type", "text"); it.put("text", new String(f.getValue(), StandardCharsets.UTF_8));
                } else continue;
                items.add(it);
            }
        }

        // 3. 创建笔记
        Map<String, Object> meta = store.create(title, scope, "", null, null);
        String id = (String) meta.get("id");
        StringBuilder html = new StringBuilder();
        int imgCount = 0;
        for (Map<String, Object> it : items) {
            String type = Json.str(it, "type", "text");
            if (type.equals("text")) {
                String t = Json.str(it, "text", "");
                if (t != null && !t.isBlank()) {
                    html.append("<p>").append(t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")).append("</p>\n");
                }
            } else {
                String file = Json.str(it, "file", "");
                if (file == null || file.isBlank()) continue;
                // 容错取文件：直接匹配 → 反斜杠替换 → 按 basename 匹配（兼容不同 zip 打包工具）
                byte[] imgBytes = files.get(file);
                if (imgBytes == null) imgBytes = files.get(file.replace("\\", "/"));
                if (imgBytes == null) {
                    String base = basename(file);
                    for (Map.Entry<String, byte[]> f : files.entrySet()) {
                        if (basename(f.getKey()).equals(base)) { imgBytes = f.getValue(); break; }
                    }
                }
                if (imgBytes == null) continue;
                String base = basename(file);
                String ext = base.contains(".") ? base.substring(base.lastIndexOf('.') + 1).toLowerCase() : "png";
                if (ext.equals("jpeg")) ext = "jpg";
                if (!ext.matches("[a-z0-9]{2,5}")) ext = "png";
                String url = store.saveMedia(id, imgBytes, ext);
                html.append("<p><img src=\"").append(url).append("\" alt=\"采集图片\"></p>\n");
                imgCount++;
            }
        }
        store.update(id, null, scope, html.toString(), null, null);

        // 4b. 标题 AI 补全（占位标题且正文非空）
        String aiTitle = "";
        if (titleFromFile && html.length() > 0) {
            Map<String, Object> tRes = classifier.suggestTitle(scope, html.toString());
            if (Boolean.TRUE.equals(tRes.get("ok"))) {
                aiTitle = Json.str(tRes, "subject", Json.str(tRes, "title", ""));
                title = aiTitle;
                store.update(id, title, scope, null, null, null);
            }
        }

        // 4. 可选 AI 分类
        String reason = "";
        if (autoClassify) {
            Map<String, Object> tree = store.loadTree();
            Map<String, Object> scopeNode = store.findChild(tree, scope);
            List<String> flat = scopeNode == null ? new ArrayList<>() : store.flattenTree(scopeNode, "");
            Map<String, Object> res = classifier.classify(scope, title, html.toString(), flat);
            Map<String, Object> norm = classifier.normalizePath(store, scope, Json.strList(res, "path"));
            store.update(id, null, null, null, Json.str(norm, "categoryId", ""), Json.strList(norm, "path"));
            reason = Json.str(res, "reason", "");
        } else {
            Map<String, Object> leaf = store.ensureCategoryInTree(scope, new ArrayList<>(), null);
            store.update(id, null, null, null, Json.str(leaf, "id", ""), new ArrayList<>());
        }
        meta = store.getMeta(id);
        Map<String, Object> out = new LinkedHashMap<>(meta);
        out.put("items", items.size());
        out.put("images", imgCount);
        out.put("aiReason", reason);
        if (!aiTitle.isEmpty()) out.put("aiTitle", true);
        out.put("ok", true);
        return out;
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
                    putPart(out, name, data);
                    putPart(out, name + ".filename", filename);
                    out.put("filename", filename); // 兼容旧调用（单文件场景取最后一个）
                } else {
                    putPart(out, name, new String(data, StandardCharsets.UTF_8));
                }
            }
            pos = next;
        }
        return out;
    }

    /** 读取 multipart 文本字段：单值或 List 取最后一个 */
    static String strPart(Map<String, Object> parts, String name, String dflt) {
        Object v = parts.get(name);
        if (v == null) return dflt;
        if (v instanceof List<?> l) return l.isEmpty() ? dflt : String.valueOf(l.get(l.size() - 1));
        return v.toString();
    }

    /** 同名 multipart 字段出现多次时合并为 List，单次保持原类型 */
    @SuppressWarnings("unchecked")
    static void putPart(Map<String, Object> out, String name, Object value) {
        Object old = out.get(name);
        if (old == null) {
            out.put(name, value);
        } else if (old instanceof List<?>) {
            List<Object> l = new ArrayList<>((List<Object>) old);
            l.add(value);
            out.put(name, l);
        } else {
            List<Object> l = new ArrayList<>();
            l.add(old);
            l.add(value);
            out.put(name, l);
        }
    }

    /** 读取 multipart 字段：可能是单值或 List（同名多 part） */
    @SuppressWarnings("unchecked")
    static List<Object> partsOf(Map<String, Object> parts, String name) {
        Object v = parts.get(name);
        if (v == null) return List.of();
        if (v instanceof List<?>) return (List<Object>) v;
        List<Object> l = new ArrayList<>();
        l.add(v);
        return l;
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
