package com.nbnotebook.app;

import android.graphics.Bitmap;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * 红框识别：识别手绘图外圈的红色边框并透视裁剪（自动去红框）。
 * 用户约定：手绘图用红笔圈一个框。
 * 识别失败返回 null（调用方回退整图 + 手动裁剪）。
 */
public final class RedBoxCrop {

    private RedBoxCrop() {}

    /** 尝试识别并裁剪红框；失败返回 null */
    public static Bitmap crop(Bitmap src) {
        Mat srcMat = new Mat();
        Utils.bitmapToMat(src, srcMat);
        try {
            Mat hsv = new Mat();
            Mat bgr = new Mat();
            Imgproc.cvtColor(srcMat, bgr, Imgproc.COLOR_RGBA2BGR);
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV);
            bgr.release();

            // 红色掩膜（HSV 红 = H 0-10 或 170-180）
            Mat mask1 = new Mat(), mask2 = new Mat();
            Core.inRange(hsv, new Scalar(0, 60, 60), new Scalar(12, 255, 255), mask1);
            Core.inRange(hsv, new Scalar(168, 60, 60), new Scalar(180, 255, 255), mask2);
            Core.bitwise_or(mask1, mask2, mask1);
            Mat mask = mask1;
            mask2.release();
            hsv.release();

            // 闭运算连接断线，开运算去噪
            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(7, 7));
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel);
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel);
            kernel.release();

            // 轮廓
            List<MatOfPoint> contours = new ArrayList<>();
            Mat hierarchy = new Mat();
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            hierarchy.release();
            mask.release();

            double totalArea = srcMat.size().area();
            MatOfPoint2f best = null;
            double bestArea = 0;
            for (MatOfPoint c : contours) {
                double area = Imgproc.contourArea(c);
                if (area < totalArea * 0.02) { c.release(); continue; }
                MatOfPoint2f c2 = new MatOfPoint2f(c.toArray());
                c.release();
                MatOfPoint2f approx = new MatOfPoint2f();
                double peri = Imgproc.arcLength(c2, true);
                Imgproc.approxPolyDP(c2, approx, 0.04 * peri, true);
                c2.release();
                if (approx.total() == 4 && area > bestArea) {
                    if (best != null) best.release();
                    best = approx;
                    bestArea = area;
                } else {
                    approx.release();
                }
            }
            if (best == null) return null;

            // 四点排序：左上 右上 右下 左下
            Point[] pts = orderPoints(best.toArray());
            double w = Math.max(dist(pts[0], pts[1]), dist(pts[2], pts[3]));
            double h = Math.max(dist(pts[0], pts[3]), dist(pts[1], pts[2]));
            if (w < 20 || h < 20) { best.release(); return null; }

            // 去掉红框边缘（内缩 1.5%）
            double ix = w * 0.015, iy = h * 0.015;
            MatOfPoint2f dst = new MatOfPoint2f(
                    new Point(ix, iy),
                    new Point(w - ix, iy),
                    new Point(w - ix, h - iy),
                    new Point(ix, h - iy));
            Mat warpMat = Imgproc.getPerspectiveTransform(best, dst);
            best.release();
            dst.release();

            Mat out = new Mat();
            Imgproc.warpPerspective(srcMat, out, warpMat, new Size(w, h));
            warpMat.release();

            Bitmap bmp = Bitmap.createBitmap((int) Math.round(w), (int) Math.round(h), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(out, bmp);
            out.release();
            return bmp;
        } catch (Exception e) {
            return null;
        } finally {
            srcMat.release();
        }
    }

    private static Point[] orderPoints(Point[] pts) {
        Point[] p = new Point[4];
        // 按 y 分上下两组，组内按 x 排
        Point[] up = new Point[2], down = new Point[2];
        int ui = 0, di = 0;
        for (Point pt : pts) {
            if (pt.y < (pts[0].y + pts[1].y + pts[2].y + pts[3].y) / 4) up[ui++] = pt;
            else down[di++] = pt;
        }
        if (ui != 2 || di != 2) { // 兜底
            Point[] sorted = pts.clone();
            java.util.Arrays.sort(sorted, (a, b) -> Double.compare(a.y, b.y));
            return sorted;
        }
        p[0] = up[0].x < up[1].x ? up[0] : up[1];   // 左上
        p[1] = up[0].x < up[1].x ? up[1] : up[0];   // 右上
        p[2] = down[0].x < down[1].x ? down[1] : down[0]; // 右下
        p[3] = down[0].x < down[1].x ? down[0] : down[1]; // 左下
        return p;
    }

    private static double dist(Point a, Point b) {
        return Math.hypot(a.x - b.x, a.y - b.y);
    }
}
