package com.nbnotebook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 待整理区（缓冲区）：手机实时采集上传的内容先落这里，
 * 电脑端确认后（可 AI 分类）才正式入库。
 * 结构：data/pending/<id>/{meta.json, content.html, media/*}
 */
public final class PendingStore {

    private final Path pendingDir;

    public PendingStore(Path dataDir) {
        this.pendingDir = dataDir.resolve("pending");
    }

    public Path dir() { return pendingDir; }

    public String now() { return Store.now(); }

    /** 创建待整理条目。media: 文件名(已消毒) → 内容 */
    public Map<String, Object> create(String scope, String title, String html,
                                      Map<String, byte[]> media) throws IOException {
        Files.createDirectories(pendingDir);
        String id = UUID.randomUUID().toString().substring(0, 12);
        Path d = pendingDir.resolve(id);
        Files.createDirectories(d.resolve("media"));

        List<Map<String, Object>> files = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : media.entrySet()) {
            String name = e.getKey();
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "png";
            Files.write(d.resolve("media").resolve(name), e.getValue());
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", name);
            f.put("type", mimeOf(ext));
            files.add(f);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", id);
        meta.put("created", now());
        meta.put("scope", scope == null || scope.isBlank() ? "默认" : scope);
        meta.put("title", title == null || title.isBlank() ? "待整理 " + now() : title);
        meta.put("images", files.size());
        meta.put("media", files);
        Files.write(d.resolve("meta.json"), Json.stringify(meta).getBytes(StandardCharsets.UTF_8));
        Files.write(d.resolve("content.html"), html.getBytes(StandardCharsets.UTF_8));
        return meta;
    }

    public List<Map<String, Object>> list() throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(pendingDir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(pendingDir)) {
            for (Path p : ds) {
                if (!Files.isDirectory(p)) continue;
                Path mf = p.resolve("meta.json");
                if (!Files.exists(mf)) continue;
                try {
                    Map<String, Object> m = Json.asMap(Json.parse(Files.readString(mf, StandardCharsets.UTF_8)));
                    out.add(m);
                } catch (Exception ignored) {}
            }
        }
        out.sort((a, b) -> String.valueOf(b.get("created")).compareTo(String.valueOf(a.get("created"))));
        return out;
    }

    public Map<String, Object> get(String id) throws IOException {
        Path mf = pendingDir.resolve(id).resolve("meta.json");
        if (!Files.exists(mf)) return null;
        return Json.asMap(Json.parse(Files.readString(mf, StandardCharsets.UTF_8)));
    }

    public String readContent(String id) throws IOException {
        Path cf = pendingDir.resolve(id).resolve("content.html");
        return Files.exists(cf) ? Files.readString(cf, StandardCharsets.UTF_8) : "";
    }

    /** 媒体文件：返回 文件名 → bytes */
    public Map<String, byte[]> readMedia(String id) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        Path md = pendingDir.resolve(id).resolve("media");
        if (!Files.isDirectory(md)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(md)) {
            for (Path p : ds) {
                if (Files.isRegularFile(p)) out.put(p.getFileName().toString(), Files.readAllBytes(p));
            }
        }
        return out;
    }

    public void delete(String id) throws IOException {
        Path d = pendingDir.resolve(id);
        if (Files.isDirectory(d)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
                for (Path p : ds) {
                    if (Files.isDirectory(p)) {
                        try (DirectoryStream<Path> ds2 = Files.newDirectoryStream(p)) {
                            for (Path q : ds2) Files.deleteIfExists(q);
                        }
                    }
                    Files.deleteIfExists(p);
                }
            }
            Files.deleteIfExists(d);
        }
    }

    private static String mimeOf(String ext) {
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            default -> "application/octet-stream";
        };
    }
}
