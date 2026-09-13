package com.nbnotebook;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;

/**
 * OLLAMA 本地 AI 分类器。
 * 流程：分类树扁平化 → 构造提示词 → qwen3:8b 强制 JSON 输出 → 解析容错 → 后端语义归一化匹配。
 * 健壮性：模型输出不规范时多重降级解析；OLLAMA 不可用时优雅降级（返回空 path + 错误提示）。
 */
public final class Classifier {
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final String ollamaBase;
    private String model;

    public Classifier(String ollamaBase, String model) {
        this.ollamaBase = ollamaBase;
        this.model = model;
    }

    public String getModel() { return model; }

    /** 启动时探测 OLLAMA：验证可达，并选择一个可用的文本模型 */
    public String probe() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaBase + "/api/tags"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return "OLLAMA 服务异常 (HTTP " + resp.statusCode() + ")";
            Map<String, Object> body = Json.asMap(Json.parse(resp.body()));
            List<Object> models = Json.arr(body, "models");
            if (models.isEmpty()) return "OLLAMA 已运行，但没有任何模型，请先 ollama pull qwen3:8b";
            // 默认 qwen3:8b；没有则挑第一个不含 -vl 的模型
            List<String> names = new ArrayList<>();
            for (Object m : models) {
                String n = Json.str(Json.asMap(m), "name", "");
                if (!n.isBlank()) names.add(n);
            }
            if (names.isEmpty()) return "OLLAMA 模型列表为空";
            if (names.contains(model)) return null;
            String fallback = names.stream().filter(n -> !n.contains("-vl") && !n.contains("vision")).findFirst().orElse(names.get(0));
            this.model = fallback;
            return null;
        } catch (Exception e) {
            return "无法连接 OLLAMA (" + e.getMessage() + ")，请确认已运行 ollama serve";
        }
    }

    /**
     * 分类一篇笔记。
     * @param scope 用户选择的标题域，如"生物竞赛"
     * @param title 笔记标题
     * @param contentHtml 笔记正文 HTML
     * @param flatTree scope 子树的扁平路径列表（可空）
     * @return {"path": [...], "reason": "...", "ok": bool, "error": "..."}
     */
    public Map<String, Object> classify(String scope, String title, String contentHtml, List<String> flatTree) {
        String text = htmlToText(contentHtml);
        if (text.length() > 1200) text = text.substring(0, 1200) + "…";

        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个严谨的学科笔记分类器。用户正在积累【").append(scope).append("】的知识笔记。\n\n");
        prompt.append("现有分类结构（用 / 表示层级，第一级就是该范围下的直接子类别，不含范围名）：\n");
        if (flatTree == null || flatTree.isEmpty()) {
            prompt.append("（目前还没有分类，你可以建议一个合理的分类路径）\n");
        } else {
            for (String p : flatTree) prompt.append("- ").append(p).append("\n");
        }
        prompt.append("\n现在有一篇新笔记需要归类。归类原则：\n");
        prompt.append("1. path 的第一级必须从上面分类结构的第一级中选择（或在其基础上新增精炼的一级），绝不要把范围名「").append(scope).append("」当作 path 的一级。\n");
        prompt.append("2. 优先归入【已有】分类结构中最贴切的位置，路径名称尽量与已有类别一致（允许语义相同但表述不同，例如已有\"维管束\"，就不要再建\"输导组织\"）。\n");
        prompt.append("3. 如果已有结构确实没有合适类别，允许新增，但路径最多两级，且命名精炼。\n");
        prompt.append("4. 领域归属必须正确：例如「动物」相关内容绝不能归到「植物」类下；主题明显超出已有顶层类别领域时，必须新建顶层类别（第一级）。\n");
        prompt.append("5. 如果笔记内容非常概括（涉及多个子主题），归入其上级类别即可。\n\n");
        prompt.append("笔记标题：").append(title == null ? "" : title).append("\n");
        prompt.append("笔记内容：\n").append(text.isEmpty() ? "（无正文）" : text).append("\n\n");
        prompt.append("只输出 JSON，格式：{\"path\": [\"一级类别\", \"二级类别\"], \"reason\": \"一句话理由\"}\n");
        prompt.append("path 数组长度 1 或 2。用中文。");

        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String raw = generate(prompt.toString());
            List<String> path = parsePath(raw);
            String reason = parseReason(raw);
            result.put("path", path);
            result.put("reason", reason);
            result.put("ok", true);
        } catch (Exception e) {
            result.put("path", new ArrayList<String>());
            result.put("reason", "");
            result.put("ok", false);
            result.put("error", "AI 分类失败: " + e.getMessage());
        }
        return result;
    }

    /** 调用 OLLAMA /api/generate，强制 JSON 输出 */
    private String generate(String prompt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("stream", false);
        body.put("format", "json");
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", 0.1);
        options.put("num_predict", 300);
        body.put("options", options);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ollamaBase + "/api/generate"))
                .timeout(Duration.ofSeconds(240))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("OLLAMA HTTP " + resp.statusCode() + ": " + resp.body());
        Map<String, Object> r = Json.asMap(Json.parse(resp.body()));
        String out = Json.str(r, "response", "");
        if (out.isBlank()) throw new IOException("OLLAMA 返回空响应");
        return out;
    }

    /** 从模型输出中提取分类路径（容错解析） */
    static List<String> parsePath(String raw) {
        if (raw == null) return new ArrayList<>();
        String s = raw.trim();
        // 去掉可能的 ```json ... ``` 包裹
        s = s.replaceAll("(?s)^```(?:json)?\\s*", "").replaceAll("(?s)\\s*```$", "").trim();
        // 去掉 "根据以上..." 之类的后缀说明，只保留第一个 { ... } 块
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) s = s.substring(start, end + 1);
        List<String> path = new ArrayList<>();
        try {
            Map<String, Object> m = Json.asMap(Json.parse(s));
            Object p = m.get("path");
            if (p instanceof List<?> l) {
                for (Object o : l) {
                    String v = String.valueOf(o).trim();
                    if (!v.isEmpty() && !"null".equals(v)) path.add(v);
                }
            }
        } catch (Exception e) {
            // 兜底：正则抓 ["a","b"] 或 ["a"]
            Matcher ma = Pattern.compile("\"path\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(s);
            if (ma.find()) {
                for (String seg : ma.group(1).split(",")) {
                    String v = seg.trim().replaceAll("^\"|\"$", "");
                    if (!v.isEmpty()) path.add(v);
                }
            }
        }
        // 限制最多两级
        while (path.size() > 2) path.remove(path.size() - 1);
        return path;
    }

    static String parseReason(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) s = s.substring(start, end + 1);
        try {
            Map<String, Object> m = Json.asMap(Json.parse(s));
            return Json.str(m, "reason", "");
        } catch (Exception e) {
            Matcher ma = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]*)\"").matcher(s);
            return ma.find() ? ma.group(1) : "";
        }
    }

    /** 后端语义归一化：把模型给的 path 匹配/挂接到分类树，返回最终分类路径与节点 id */
    @SuppressWarnings("unchecked")
    public Map<String, Object> normalizePath(Store store, String scope, List<String> modelPath) throws IOException {
        Map<String, Object> tree = store.loadTree();
        Map<String, Object> scopeNode = store.findChild(tree, scope);
        if (scopeNode == null) {
            scopeNode = store.node(scope, "scope-" + slug(scope));
            store.addChild(tree, scopeNode);
        }
        List<String> finalPath = new ArrayList<>();
        Map<String, Object> cur = scopeNode;
        // 防御：剔除模型误把范围名当成的一级
        List<String> cleaned = new ArrayList<>();
        for (String w : modelPath) {
            if (w != null && !w.isBlank() && !w.equals(scope)) cleaned.add(w);
        }
        for (String want : cleaned) {
            Map<String, Object> match = bestMatch(cur, want);
            if (match != null) {
                cur = match;
                finalPath.add(Json.str(match, "name", want));
                continue;
            }
            // 兜底：在 scope 整棵子树中找同名节点（任意层级），归入其真实路径，避免重复建类
            deepPath = new ArrayList<>();
            Map<String, Object> deep = findDeepMatch(scopeNode, want, new ArrayList<>());
            if (deep != null) {
                cur = deep;
                finalPath.clear();
                finalPath.addAll(deepPath);
                break;
            }
            Map<String, Object> created = store.node(want, curId(cur) + "-" + slug(want));
            store.addChild(cur, created);
            cur = created;
            finalPath.add(want);
        }
        store.saveTree(tree);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("path", finalPath);
        out.put("categoryId", curId(cur));
        return out;
    }

    private List<String> deepPath = new ArrayList<>();

    /** 深度优先在子树中找精确同名节点，命中则填充 deepPath 为该节点从 scope 起的路径 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findDeepMatch(Map<String, Object> node, String want, List<String> path) {
        Object kids = node.get("children");
        if (!(kids instanceof List<?> l)) return null;
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> child = (Map<String, Object>) m;
            List<String> p = new ArrayList<>(path);
            p.add(Json.str(child, "name", ""));
            if (want.equals(Json.str(child, "name", ""))) {
                deepPath = p;
                return child;
            }
            Map<String, Object> found = findDeepMatch(child, want, p);
            if (found != null) return found;
        }
        return null;
    }

    /** 在 children 中找最佳匹配：精确 > 包含 > 拼音/相似度 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> bestMatch(Map<String, Object> parent, String want) {
        Object kids = parent.get("children");
        if (!(kids instanceof List<?> l)) return null;
        Map<String, Object> exact = null, contains = null, similar = null;
        int bestSim = 0;
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> node = (Map<String, Object>) m;
            String name = Json.str(node, "name", "");
            if (name.isBlank()) continue;
            if (name.equals(want)) { exact = node; break; }
            if (name.contains(want) || want.contains(name)) contains = node;
            int sim = similarity(name, want);
            if (sim > bestSim) { bestSim = sim; similar = node; }
        }
        if (exact != null) return exact;
        if (contains != null) return contains;
        if (bestSim >= 2) return similar; // 字符级相似度阈值
        return null;
    }

    /** 简单字符相似度：公共字符数 */
    private static int similarity(String a, String b) {
        int score = 0;
        for (char c : a.toCharArray()) if (b.indexOf(c) >= 0) score++;
        return score;
    }

    private static String curId(Map<String, Object> n) {
        String id = Json.str(n, "id");
        return id == null || id.isBlank() ? "n" : id;
    }

    private static String slug(String s) {
        if (s == null || s.isBlank()) return "x" + Math.abs(s.hashCode() % 10000);
        String ascii = s.toLowerCase().replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-").replaceAll("^-|-$", "");
        return ascii.isEmpty() ? "x" + Math.abs(s.hashCode() % 10000) : ascii;
    }

    /** HTML → 纯文本（去标签、解码实体） */
    static String htmlToText(String html) {
        if (html == null || html.isBlank()) return "";
        String s = html;
        s = s.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?is)<br\\s*/?>", "\n");
        s = s.replaceAll("(?is)</(p|div|h[1-6]|li|tr)>", "\n");
        s = s.replaceAll("(?s)<[^>]+>", " ");
        s = s.replaceAll("&nbsp;", " ").replaceAll("&amp;", "&").replaceAll("&lt;", "<").replaceAll("&gt;", ">").replaceAll("&quot;", "\"");
        s = s.replaceAll("[\\t ]+", " ").replaceAll("(?m)^\\s+|\\s+$", "").replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }
}
