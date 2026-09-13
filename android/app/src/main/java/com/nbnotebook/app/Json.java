package com.nbnotebook.app;

import org.json.JSONObject;

import java.util.List;

/** Android 端 JSON 辅助（配合 org.json） */
public final class Json {

    private Json() {}

    /** 节点名：name（分类树）或 title（笔记） */
    static String safeName(JSONObject o) {
        if (o == null) return "";
        String n = o.optString("name", "");
        return n.isEmpty() ? o.optString("title", "") : n;
    }

    static String safeId(JSONObject o) {
        return o == null ? "" : o.optString("id", "");
    }

    /** 导航栈 → "生物竞赛 › 植物结构 › 维管束" */
    static String pathText(List<JSONObject> stack) {
        StringBuilder sb = new StringBuilder();
        for (JSONObject o : stack) {
            if (sb.length() > 0) sb.append(" › ");
            sb.append(safeName(o));
        }
        return sb.toString();
    }
}
