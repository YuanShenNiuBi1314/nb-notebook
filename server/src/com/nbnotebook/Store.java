package com.nbnotebook;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 笔记存储层。
 * 笔记本体是 HTML 文件（用户的核心要求），meta.json 是索引/元数据，tree.json 是分类树。
 * 目录结构：
 *   data/notes/<id>/note.html      笔记正文（HTML，可含图片/视频）
 *   data/notes/<id>/meta.json      元数据
 *   data/notes/<id>/media/         该笔记的图片/视频/音频
 *   data/tree.json                 分类树
 */
public final class Store {
    public final Path root;
    public final Path notesDir;
    public final Path treeFile;
    public static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public Store(Path root) {
        this.root = root;
        this.notesDir = root.resolve("notes");
        this.treeFile = root.resolve("tree.json");
    }

    public void init() throws IOException {
        Files.createDirectories(notesDir);
        if (!Files.exists(treeFile)) saveTree(defaultTree());
    }

    // ================= 笔记 CRUD =================

    public String newId() {
        return UUID.randomUUID().toString().substring(0, 8) + "-" + System.currentTimeMillis() % 1000000;
    }

    public static String now() { return LocalDateTime.now().format(TS); }

    public Map<String, Object> create(String title, String scope, String content, List<String> category, String categoryId) throws IOException {
        String id = newId();
        Path dir = notesDir.resolve(id);
        Files.createDirectories(dir.resolve("media"));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("id", id);
        // subject = 标题（用户指定的专属键；AI 起标题也写入这里）
        meta.put("subject", title == null || title.isBlank() ? "无标题笔记" : title.trim());
        meta.put("scope", scope == null ? "默认" : scope.trim());
        meta.put("category", category == null ? new ArrayList<String>() : category);
        meta.put("categoryId", categoryId == null ? "" : categoryId);
        meta.put("created", now());
        meta.put("updated", now());
        meta.put("media", scanMedia(content));
        writeMeta(meta);
        writeNote(id, content == null ? "" : content);
        return meta;
    }

    public void update(String id, String subject, String scope, String content, String categoryId, List<String> category) throws IOException {
        Map<String, Object> meta = getMeta(id);
        if (subject != null) meta.put("subject", subject.trim());
        if (scope != null) meta.put("scope", scope.trim());
        if (content != null) { writeNote(id, content); meta.put("media", scanMedia(content)); }
        if (categoryId != null) meta.put("categoryId", categoryId);
        if (category != null) meta.put("category", category);
        meta.put("updated", now());
        writeMeta(meta);
    }

    public void delete(String id) throws IOException {
        Path dir = notesDir.resolve(id);
        if (Files.exists(dir)) deleteRecursively(dir);
    }

    /** 供图片提取功能使用：把任意临时图存为笔记媒体文件，返回可访问的相对 URL */
    public String saveMedia(String noteId, byte[] bytes, String ext) throws IOException {
        String name = System.currentTimeMillis() + "." + ext;
        Path dir = notesDir.resolve(noteId).resolve("media");
        Files.createDirectories(dir);
        Files.write(dir.resolve(name), bytes);
        return "/media/" + noteId + "/" + name;
    }

    public Path mediaPath(String noteId, String fileName) {
        return notesDir.resolve(noteId).resolve("media").resolve(fileName);
    }

    public Map<String, Object> getMeta(String id) throws IOException {
        Path f = notesDir.resolve(id).resolve("meta.json");
        if (!Files.exists(f)) throw new IOException("笔记不存在: " + id);
        Map<String, Object> m = Json.asMap(Json.parse(Files.readString(f, StandardCharsets.UTF_8)));
        // 兼容旧数据：subject 缺失时从旧 title 字段补
        if (!m.containsKey("subject") && m.containsKey("title")) {
            m.put("subject", m.get("title"));
        }
        return m;
    }

    /** 读取标题：优先 subject，兼容旧 title */
    public static String subjectOf(Map<String, Object> meta) {
        String s = Json.str(meta, "subject", "");
        if (s != null && !s.isBlank()) return s;
        return Json.str(meta, "title", "");
    }

    public String readNote(String id) throws IOException {
        Path f = notesDir.resolve(id).resolve("note.html");
        return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : "";
    }

    public void writeMeta(Map<String, Object> meta) throws IOException {
        Files.writeString(notesDir.resolve((String) meta.get("id")).resolve("meta.json"),
                Json.stringify(meta), StandardCharsets.UTF_8);
    }

    public void writeNote(String id, String content) throws IOException {
        Files.writeString(notesDir.resolve(id).resolve("note.html"), content, StandardCharsets.UTF_8);
    }

    public boolean exists(String id) { return Files.exists(notesDir.resolve(id).resolve("meta.json")); }

    /** 列出全部笔记（不含正文，节省流量），可选按 scope / categoryId 过滤 */
    public List<Map<String, Object>> list(String scope, String categoryId) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (var stream = Files.list(notesDir)) {
            for (Path p : stream.toList()) {
                if (!Files.isDirectory(p)) continue;
                Path mf = p.resolve("meta.json");
                if (!Files.exists(mf)) continue;
                try {
                    Map<String, Object> meta = Json.asMap(Json.parse(Files.readString(mf, StandardCharsets.UTF_8)));
                    if (!meta.containsKey("subject") && meta.containsKey("title")) {
                        meta.put("subject", meta.get("title"));
                    }
                    if (scope != null && !scope.isBlank() && !scope.equals(Json.str(meta, "scope", ""))) continue;
                    if (categoryId != null && !categoryId.isBlank() && !categoryId.equals(Json.str(meta, "categoryId", ""))) continue;
                    // 附带内容摘要（前 400 字），供前端正文搜索
                    String html = readNote((String) meta.get("id"));
                    String text = Classifier.htmlToText(html);
                    if (text.length() > 400) text = text.substring(0, 400) + "…";
                    meta.put("content", text);
                    out.add(meta);
                } catch (Exception ignored) {}
            }
        }
        out.sort((a, b) -> String.valueOf(Json.str(b, "updated", "")).compareTo(String.valueOf(Json.str(a, "updated", ""))));
        return out;
    }

    /** 扫描 HTML 中的媒体标签，判断笔记是否含视频/音频/图片 */
    public static Map<String, Object> scanMedia(String html) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("video", html != null && html.toLowerCase().contains("<video"));
        m.put("audio", html != null && html.toLowerCase().contains("<audio"));
        int imgs = 0;
        if (html != null) {
            String low = html.toLowerCase();
            int idx = 0;
            while ((idx = low.indexOf("<img", idx)) >= 0) { imgs++; idx += 4; }
        }
        m.put("images", imgs);
        return m;
    }

    private void deleteRecursively(Path p) throws IOException {
        try (var s = Files.walk(p)) {
            for (Path f : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(f);
        }
    }

    // ================= 分类树 =================

    @SuppressWarnings("unchecked")
    public Map<String, Object> defaultTree() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", "全部笔记");
        root.put("id", "root");
        root.put("children", new ArrayList<>());
        return root;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> loadTree() {
        try {
            if (Files.exists(treeFile)) {
                return Json.asMap(Json.parse(Files.readString(treeFile, StandardCharsets.UTF_8)));
            }
        } catch (Exception ignored) {}
        return defaultTree();
    }

    public void saveTree(Map<String, Object> tree) throws IOException {
        Files.writeString(treeFile, Json.stringify(tree), StandardCharsets.UTF_8);
    }

    /** 根据笔记元数据重建"分类树缺的节点"：笔记存在 category 但树上没有 → 补上 */
    public Map<String, Object> ensureCategoryInTree(String scope, List<String> category, String categoryId) throws IOException {
        Map<String, Object> tree = loadTree();
        Map<String, Object> scopeNode = findChild(tree, scope);
        if (scopeNode == null) {
            scopeNode = node(scope, "scope-" + slug(scope));
            addChild(tree, scopeNode);
        }
        Map<String, Object> cur = scopeNode;
        StringBuilder path = new StringBuilder();
        List<String> cat = category == null ? new ArrayList<>() : category;
        for (String part : cat) {
            if (part == null || part.isBlank()) continue;
            Map<String, Object> child = findChild(cur, part);
            if (child == null) { child = node(part, curId(cur) + "-" + slug(part)); addChild(cur, child); }
            cur = child;
        }
        saveTree(tree);
        return cur; // 返回最终叶子节点
    }

    /** 导入外部知识结构 JSON：{"name":..., "children":[{"name":..., "children":[...]}]} 合并到指定 scope 下 */
    public Map<String, Object> importStructure(String scope, Object imported) throws IOException {
        Map<String, Object> tree = loadTree();
        Map<String, Object> scopeNode = findChild(tree, scope);
        if (scopeNode == null) { scopeNode = node(scope, "scope-" + slug(scope)); addChild(tree, scopeNode); }
        mergeChildren(scopeNode, imported);
        saveTree(tree);
        return tree;
    }

    @SuppressWarnings("unchecked")
    private void mergeChildren(Map<String, Object> parent, Object imported) {
        if (!(imported instanceof Map<?, ?> im)) return;
        Object kids = im.get("children");
        if (!(kids instanceof List<?> kl)) return;
        for (Object k : kl) {
            if (!(k instanceof Map<?, ?> km)) continue;
            String name = String.valueOf(km.get("name"));
            if (name == null || name.isBlank()) continue;
            Map<String, Object> child = findChild(parent, name);
            if (child == null) { child = node(name, curId(parent) + "-" + slug(name)); addChild(parent, child); }
            mergeChildren(child, km);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> findChild(Map<String, Object> parent, String name) {
        Object kids = parent.get("children");
        if (kids instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m && name != null && name.equals(String.valueOf(m.get("name")))) {
                    return (Map<String, Object>) m;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public void addChild(Map<String, Object> parent, Map<String, Object> child) {
        Object kids = parent.get("children");
        List<Object> l = kids instanceof List<?> ? (List<Object>) kids : new ArrayList<>();
        l.add(child);
        parent.put("children", l);
    }

    public Map<String, Object> node(String name, String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("id", id);
        m.put("children", new ArrayList<>());
        return m;
    }

    private String curId(Map<String, Object> n) {
        String id = Json.str(n, "id");
        return id == null || id.isBlank() ? "n" : id;
    }

    private static String slug(String s) {
        if (s == null || s.isBlank()) return "x" + Math.abs(s.hashCode() % 10000);
        String ascii = s.toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-").replaceAll("^-|-$", "");
        return ascii.isEmpty() ? "x" + Math.abs(s.hashCode() % 10000) : ascii;
    }

    /** 扁平化分类树：返回 ["路径/到/节点"] 列表，供 AI 分类提示词使用 */
    public List<String> flattenTree(Map<String, Object> node, String prefix) {
        List<String> out = new ArrayList<>();
        String name = Json.str(node, "name", "");
        String full = prefix.isEmpty() ? name : prefix + " / " + name;
        out.add(full);
        Object kids = node.get("children");
        if (kids instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) out.addAll(flattenTree((Map<String, Object>) m, full));
            }
        }
        return out;
    }
}
