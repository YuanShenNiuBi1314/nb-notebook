package com.nbnotebook;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * 打印版 HTML 生成器。
 * 规则：
 *   1. <video>/<audio> 及嵌套 <source> 一律移除，换成提示占位（用户要求：打印时去除并提示）。
 *   2. 每张 <img> 读取真实像素尺寸，按原始长宽比适配 A4 打印宽度（不拉伸不变形），
 *      超过一页宽度自动收缩，小图保持自然大小。
 *   3. 多篇笔记按选择顺序分页（每篇独占一页起）。
 *   4. 附带打印 CSS（A4、页边距、分页、无链接色）。
 */
public final class PrintHtml {

    private static final Pattern IMG_TAG = Pattern.compile("(?is)<img\\b[^>]*>");
    private static final Pattern SRC_ATTR = Pattern.compile("(?i)src\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern MEDIA_TAG = Pattern.compile("(?is)<(video|audio)\\b[^>]*>.*?</\\1>|<(video|audio)\\b[^>]*/>|<source\\b[^>]*>|<track\\b[^>]*>");
    private static final Pattern SCRIPT_TAG = Pattern.compile("(?is)<script\\b[^>]*>.*?</script>|<script\\b[^>]*/>");
    private static final Pattern STYLE_TAG = Pattern.compile("(?is)<style\\b[^>]*>.*?</style>");

    /**
     * 生成打印 HTML。
     * @param store 存储
     * @param ids 笔记 id 列表（保持选择顺序）
     */
    public static String build(Store store, List<String> ids) throws IOException {
        StringBuilder body = new StringBuilder();
        List<String> warnings = new ArrayList<>();
        boolean anyMedia = false;
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            if (!store.exists(id)) continue;
            Map<String, Object> meta = store.getMeta(id);
            String content = store.readNote(id);
            String clean = transformContent(store, id, content);
            Map<String, Object> media = store.scanMedia(content);
            boolean hasV = Boolean.TRUE.equals(media.get("video"));
            boolean hasA = Boolean.TRUE.equals(media.get("audio"));
            if (hasV || hasA) anyMedia = true;
            body.append("<section class=\"note-page\">");
            body.append("<div class=\"note-head\"><div class=\"note-title\">")
                 .append(esc(Json.str(meta, "title", "无标题")))
                 .append("</div><div class=\"note-meta\">")
                 .append(esc(Json.str(meta, "scope", "")));
            List<Object> cat = Json.arr(meta, "category");
            for (Object o : cat) body.append(" / ").append(esc(String.valueOf(o)));
            body.append(" · 记于 ").append(esc(Json.str(meta, "created", "")))
                 .append("</div>");
            if (hasV) { body.append("<div class=\"media-removed\">⚠ 本笔记含视频，打印版已去除（请在电脑端查看）</div>"); warnings.add("《" + Json.str(meta, "title", "") + "》视频已去除"); }
            if (hasA) { body.append("<div class=\"media-removed\">⚠ 本笔记含音频，打印版已去除（请在电脑端查看）</div>"); warnings.add("《" + Json.str(meta, "title", "") + "》音频已去除"); }
            body.append("</div>");
            body.append("<div class=\"note-body\">").append(clean).append("</div>");
            body.append("</section>");
        }
        if (ids.size() > 1 && body.length() == 0) throw new IOException("没有找到任何有效笔记");
        if (ids.size() == 1 && body.length() == 0) throw new IOException("笔记不存在");

        String mediaTip = anyMedia ? "<div class='print-tip'>提示：本批笔记含视频/音频内容，打印时已自动去除，仅保留文字与图片。</div>" : "";
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
             + "<title>牛逼笔记本 · 打印</title>"
             + "<style>"
             + "@page { size: A4; margin: 18mm 16mm; }"
             + "body { font-family: \"Microsoft YaHei\", \"PingFang SC\", sans-serif; color: #1a1a1a; line-height: 1.75; font-size: 12pt; }"
             + ".print-tip { background:#fff7e6; border:1px solid #ffd591; border-radius:8px; padding:10px 16px; margin-bottom:16px; color:#874d00; }"
             + ".note-page { page-break-after: always; }"
             + ".note-page:last-child { page-break-after: auto; }"
             + ".note-head { border-bottom: 2px solid #2f54eb; padding-bottom: 8px; margin-bottom: 14px; }"
             + ".note-title { font-size: 18pt; font-weight: bold; color: #1d39c4; }"
             + ".note-meta { color: #666; font-size: 10pt; margin-top: 4px; }"
             + ".media-removed { margin-top:8px; background:#fff1f0; border:1px dashed #ffa39e; color:#a8071a; border-radius:6px; padding:6px 12px; font-size:10.5pt; }"
             + ".note-body { word-break: break-word; }"
             + ".note-body img { display: block; margin: 10px auto; max-width: 100%; height: auto; page-break-inside: avoid; border-radius: 4px; }"
             + ".note-body table { border-collapse: collapse; max-width:100%; page-break-inside: avoid; }"
             + ".note-body td, .note-body th { border: 1px solid #bbb; padding: 4px 10px; }"
             + ".note-body pre, .note-body code { font-family: Consolas, monospace; font-size: 10.5pt; white-space: pre-wrap; word-break: break-all; }"
             + ".note-body .media-placeholder { display:none; }"
             + "a { color: #1d39c4; text-decoration: none; }"
             + "</style></head><body>"
             + mediaTip
             + body
             + "<script>window.onload=function(){window.print();};</script>"
             + "</body></html>";
    }

    /** 转换正文：去 script/style、去视频音频（换占位）、图片自动比例适配 */
    private static String transformContent(Store store, String noteId, String content) throws IOException {
        if (content == null) return "";
        String s = content;
        s = SCRIPT_TAG.matcher(s).replaceAll("");
        s = STYLE_TAG.matcher(s).replaceAll("");
        // 视频/音频 → 占位
        s = MEDIA_TAG.matcher(s).replaceAll("<span class=\"media-placeholder\">[媒体内容已去除]</span>");
        // 图片比例适配
        Matcher m = IMG_TAG.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String tag = m.group();
            String imgHtml = adaptImage(store, noteId, tag);
            m.appendReplacement(sb, Matcher.quoteReplacement(imgHtml));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 单张 <img>：读取真实尺寸，输出带长宽比约束的标签 */
    private static String adaptImage(Store store, String noteId, String tag) {
        Matcher sm = SRC_ATTR.matcher(tag);
        if (!sm.find()) return tag;
        String src = sm.group(1);
        int[] dim = imageSize(store, noteId, src);
        if (dim == null) return tag;
        int iw = dim[0], ih = dim[1];
        if (iw <= 0 || ih <= 0) return tag;
        // A4 内容宽度约 175mm ≈ 660px；按原比例缩放显示
        double targetW = Math.min(iw, 660);
        double targetH = targetW * ih / iw;
        String style = "max-width:100%;height:auto;width:" + Math.round(targetW) + "px;aspect-ratio:" + iw + "/" + ih + ";";
        return "<img src=\"" + src + "\" style=\"" + style + "\" loading=\"lazy\">";
    }

    /** 解析 <img> 的 src 指向的本地图片尺寸（支持 /media/<noteId>/<file>） */
    private static int[] imageSize(Store store, String noteId, String src) {
        try {
            String clean = src.split("[?#]")[0];
            if (clean.startsWith("/media/")) {
                String[] parts = clean.split("/");
                if (parts.length >= 4) {
                    Path p = store.mediaPath(parts[2], parts[3]);
                    if (Files.exists(p)) {
                        BufferedImage bi = ImageIO.read(p.toFile());
                        if (bi != null) return new int[]{bi.getWidth(), bi.getHeight()};
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
