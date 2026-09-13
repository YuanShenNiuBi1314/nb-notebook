package com.nbnotebook;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;

/**
 * 卷子照片 → 自动提取图片区域。
 * 原理（用户提出的"对比色差"思路的工程化）：
 *   1. 卷子白纸 ≈ 纯白（亮度 > 235）；文字 ≈ 纯黑（亮度 < 40）；
 *      图片区域 ≈ 大量中间灰阶/彩色像素（40~235 之间，纹理丰富）。
 *   2. 分块统计"中间灰像素占比"，成片出现的块聚类成矩形区域 → 即图片。
 *   3. 附带手动框选裁剪：前端给矩形坐标，后端裁剪（兜底方案）。
 */
public final class ImageExtractor {

    /** 自动检测结果 */
    public static final class Region { public int x, y, w, h; }

    /**
     * 自动检测照片中的图片区域，并裁剪返回每张图。
     * @return 裁剪后的图片字节数组（PNG 编码）列表
     */
    public static List<byte[]> extract(byte[] srcBytes) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(srcBytes));
        if (src == null) throw new IOException("无法解码图片");

        // 缩放处理，长边 ≤ 1400，加速并抑制噪点
        double scale = Math.min(1.0, 1400.0 / Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();

        // 1. 像素分类
        boolean[][] isMid = new boolean[h][w];   // 中间灰/彩色像素（图片特征）
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, gr = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
                int max = Math.max(r, Math.max(gr, b)), min = Math.min(r, Math.min(gr, b));
                int lum = (r * 299 + gr * 587 + b * 114) / 1000;
                boolean colored = (max - min) > 40;                    // 彩色像素
                boolean grayish = lum >= 35 && lum <= 233;             // 中间灰阶
                isMid[y][x] = colored || grayish;
            }
        }

        // 2. 分块统计（24px 块，步长 12 重叠，提高召回）
        final int BLOCK = 24, STEP = 12;
        int bw = (w - BLOCK) / STEP + 1, bh = (h - BLOCK) / STEP + 1;
        boolean[][] candidate = new boolean[bh][bw];
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                int ox = bx * STEP, oy = by * STEP;
                int midCount = 0, blackCount = 0, total = BLOCK * BLOCK;
                for (int y = oy; y < oy + BLOCK && y < h; y++) {
                    for (int x = ox; x < ox + BLOCK && x < w; x++) {
                        if (isMid[y][x]) midCount++;
                        else if (lumAt(img, x, y) < 30) blackCount++;
                    }
                }
                double midRatio = (double) midCount / total;
                double blackRatio = (double) blackCount / total;
                // 图片块：中间灰占比较高（>0.2），且纯黑文字占比不高；文字行因抗锯齿中间灰占比通常 <0.15
                candidate[by][bx] = midRatio > 0.20 && blackRatio < 0.55;
            }
        }

        // 3. 连通域聚类
        List<Region> regions = cluster(candidate, STEP, BLOCK, w, h);

        // 4. 区域级二次验证 + 过滤 + 映射回原图裁剪
        List<byte[]> out = new ArrayList<>();
        for (Region r : regions) {
            // 区域整体"中间灰像素占比"：图片是灰主导实体块；文字行包围盒内白纸占多数
            long mid = 0, tot = 0;
            for (int y = r.y; y < r.y + r.h && y < h; y++) {
                for (int x = r.x; x < r.x + r.w && x < w; x++) {
                    tot++;
                    if (isMid[y][x]) mid++;
                }
            }
            if (tot > 0 && (double) mid / tot < 0.30) continue;
            int rx = (int) Math.floor(r.x / scale), ry = (int) Math.floor(r.y / scale);
            int rw = (int) Math.ceil(r.w / scale), rh = (int) Math.ceil(r.h / scale);
            rx = Math.max(0, rx); ry = Math.max(0, ry);
            rw = Math.min(src.getWidth() - rx, rw); rh = Math.min(src.getHeight() - ry, rh);
            if (rw < 60 || rh < 40) continue; // 太小不算
            // 细长条（文字行特征：矮而宽）丢弃
            if (rh < 70 && (double) rw / rh > 3.0) continue;
            BufferedImage crop = src.getSubimage(rx, ry, rw, rh);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(crop, "png", bos);
            out.add(bos.toByteArray());
        }
        return out;
    }

    /** 按前端框选坐标裁剪（坐标基于原图） */
    public static byte[] crop(byte[] srcBytes, int x, int y, int w, int h) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(srcBytes));
        if (src == null) throw new IOException("无法解码图片");
        x = Math.max(0, Math.min(x, src.getWidth() - 1));
        y = Math.max(0, Math.min(y, src.getHeight() - 1));
        w = Math.min(w, src.getWidth() - x);
        h = Math.min(h, src.getHeight() - y);
        if (w < 4 || h < 4) throw new IOException("框选区域太小");
        BufferedImage crop = src.getSubimage(x, y, w, h);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(crop, "png", bos);
        return bos.toByteArray();
    }

    private static int lumAt(BufferedImage img, int x, int y) {
        int rgb = img.getRGB(x, y);
        int r = (rgb >> 16) & 0xFF, gr = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return (r * 299 + gr * 587 + b * 114) / 1000;
    }

    /** 8 邻域连通聚类候选块 → 包围盒列表（膨胀一圈后输出） */
    private static List<Region> cluster(boolean[][] c, int step, int block, int imgW, int imgH) {
        int bh = c.length, bw = c[0].length;
        int[] parent = new int[bh * bw];
        Arrays.fill(parent, -1);
        // 并查集
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                if (!c[y][x]) continue;
                int idx = y * bw + x;
                parent[idx] = idx;
                if (x > 0 && c[y][x - 1]) union(parent, idx, y * bw + x - 1);
                if (y > 0 && c[y - 1][x]) union(parent, idx, (y - 1) * bw + x);
                if (y > 0 && x > 0 && c[y - 1][x - 1]) union(parent, idx, (y - 1) * bw + x - 1);
                if (y > 0 && x + 1 < bw && c[y - 1][x + 1]) union(parent, idx, (y - 1) * bw + x + 1);
            }
        }
        // 聚合每个根的最小/最大块坐标
        Map<Integer, int[]> bounds = new LinkedHashMap<>(); // root -> {minBx,minBy,maxBx,maxBy}
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                int idx = y * bw + x;
                if (parent[idx] < 0) continue;
                int root = find(parent, idx);
                int[] bb = bounds.computeIfAbsent(root, k -> new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE, -1, -1});
                bb[0] = Math.min(bb[0], x); bb[1] = Math.min(bb[1], y);
                bb[2] = Math.max(bb[2], x); bb[3] = Math.max(bb[3], y);
            }
        }
        List<Region> out = new ArrayList<>();
        for (int[] bb : bounds.values()) {
            int minX = Math.max(0, bb[0] * step - 10);
            int minY = Math.max(0, bb[1] * step - 10);
            int maxX = Math.min(imgW, (bb[2] + 1) * step + 10);
            int maxY = Math.min(imgH, (bb[3] + 1) * step + 10);
            Region r = new Region();
            r.x = minX; r.y = minY; r.w = maxX - minX; r.h = maxY - minY;
            out.add(r);
        }
        return out;
    }

    private static int find(int[] p, int i) {
        while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; }
        return i;
    }
    private static void union(int[] p, int a, int b) {
        int ra = find(p, a), rb = find(p, b);
        if (ra != rb) p[ra] = rb;
    }
}
