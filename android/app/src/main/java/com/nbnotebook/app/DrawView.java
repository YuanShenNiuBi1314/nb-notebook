package com.nbnotebook.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 描边画布：手指绘制白板，支持颜色切换与保存 PNG。
 */
public class DrawView extends View {

    public interface OnSavedListener {
        void onSaved(File png);
    }

    private final Paint paint = new Paint();
    private final List<Stroke> strokes = new ArrayList<>();
    private Path currentPath;
    private int currentColor = Color.rgb(31, 45, 61);
    private float strokeWidth = 6f;
    private OnSavedListener listener;

    private static final class Stroke {
        final Path path;
        final int color;
        final float width;
        Stroke(Path p, int c, float w) { path = p; color = c; width = w; }
    }

    public DrawView(Context context) { this(context, null); }
    public DrawView(Context context, AttributeSet attrs) { super(context, attrs); }

    public void setColor(int color) { currentColor = color; }
    public void setStrokeWidth(float w) { strokeWidth = w; }
    public void setOnSavedListener(OnSavedListener l) { listener = l; }

    public void clear() {
        strokes.clear();
        currentPath = null;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.WHITE);
        // 网格底纹，方便写字
        Paint grid = new Paint();
        grid.setColor(0xFFEEF1F5);
        grid.setStrokeWidth(1f);
        for (int x = 0; x < getWidth(); x += 40) canvas.drawLine(x, 0, x, getHeight(), grid);
        for (int y = 0; y < getHeight(); y += 40) canvas.drawLine(0, y, getWidth(), y, grid);

        for (Stroke s : strokes) {
            paint.setColor(s.color);
            paint.setStrokeWidth(s.width);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setAntiAlias(true);
            canvas.drawPath(s.path, paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX(), y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                currentPath = new Path();
                currentPath.moveTo(x, y);
                strokes.add(new Stroke(currentPath, currentColor, strokeWidth));
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (currentPath != null) {
                    currentPath.lineTo(x, y);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                currentPath = null;
                return true;
        }
        return super.onTouchEvent(event);
    }

    /** 保存为 PNG 到指定目录 */
    public void save(String dirPath) {
        Bitmap bmp = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        for (Stroke s : strokes) {
            paint.setColor(s.color);
            paint.setStrokeWidth(s.width);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setAntiAlias(true);
            c.drawPath(s.path, paint);
        }
        File dir = new File(dirPath);
        if (!dir.exists()) dir.mkdirs();
        File out = new File(dir, "DRAW_" + System.currentTimeMillis() + ".png");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            if (listener != null) listener.onSaved(out);
        } catch (Exception e) {
            if (listener != null) listener.onSaved(null);
        }
    }
}
