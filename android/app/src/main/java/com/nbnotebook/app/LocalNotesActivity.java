package com.nbnotebook.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 本地笔记：导入电脑端导出的压缩包（nb-notebook-export.zip），离线浏览。
 * 结构：filesDir/sync/<noteId>/{note.html, meta.json, media/*}
 */
public class LocalNotesActivity extends AppCompatActivity {

    private static final int REQ_PICK_ZIP = 3001;
    private File syncDir;
    private LinearLayout listBox;
    private TextView headText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        syncDir = new File(getFilesDir(), "sync");
        if (!syncDir.exists()) syncDir.mkdirs();

        // 顶部
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setBackgroundColor(Color.rgb(30, 42, 58));
        titleRow.setMinimumHeight(52);

        Button back = new Button(this);
        back.setText("← 返回");
        back.setAllCaps(false);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setTextColor(Color.WHITE);
        back.setOnClickListener(v -> finish());

        TextView title = new TextView(this);
        title.setText("📄 本地笔记（离线可看）");
        title.setTextSize(16);
        title.setTextColor(Color.WHITE);
        title.setPadding(8, 0, 0, 0);

        titleRow.addView(back, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.1f));
        titleRow.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2.4f));

        // 操作按钮
        Button importPack = new Button(this);
        importPack.setText("📥 导入电脑端导出的压缩包");
        importPack.setTextSize(14);
        importPack.setAllCaps(false);
        importPack.setTextColor(Color.WHITE);
        importPack.setBackgroundColor(Color.rgb(47, 109, 246));
        LinearLayout.LayoutParams ipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ipLp.leftMargin = 20; ipLp.rightMargin = 20; ipLp.topMargin = 14;
        importPack.setLayoutParams(ipLp);
        importPack.setOnClickListener(v -> {
            Intent it = new Intent(Intent.ACTION_GET_CONTENT);
            it.setType("application/zip");
            it.addCategory(Intent.CATEGORY_OPENABLE);
            try { startActivityForResult(Intent.createChooser(it, "选择电脑导出的压缩包"), REQ_PICK_ZIP); }
            catch (Exception e) { Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show(); }
        });

        headText = new TextView(this);
        headText.setTextSize(12);
        headText.setTextColor(Color.rgb(107, 119, 133));
        headText.setPadding(20, 12, 20, 0);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(12, 4, 12, 16);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(listBox);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(245, 247, 250));
        root.addView(titleRow);
        root.addView(importPack);
        root.addView(headText);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        refresh();
    }

    private void refresh() {
        listBox.removeAllViews();
        File[] dirs = syncDir.listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) {
            headText.setText("还没有本地笔记 —— 用电脑端网页勾选笔记点「📤 导出包」，压缩包传到这里导入");
            return;
        }
        headText.setText("共 " + dirs.length + " 篇本地笔记（离线可看，含图片）");
        List<File> sorted = new ArrayList<>();
        for (File d : dirs) {
            if (new File(d, "note.html").exists()) sorted.add(d);
        }
        sorted.sort((a, b) -> Long.compare(lastModified(b), lastModified(a)));
        for (File d : sorted) listBox.addView(card(d));
    }

    private long lastModified(File dir) {
        long t = 0;
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) t = Math.max(t, f.lastModified());
        return t;
    }

    private View card(final File dir) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(10, 10, 10, 10);
        card.setBackgroundColor(Color.WHITE);
        card.setElevation(2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 10;
        card.setLayoutParams(lp);

        String title = dir.getName();
        String scope = "";
        try {
            File mf = new File(dir, "meta.json");
            if (mf.exists()) {
                JSONObject meta = new JSONObject(new String(java.nio.file.Files.readAllBytes(mf.toPath()), StandardCharsets.UTF_8));
                title = meta.optString("title", title);
                scope = meta.optString("scope", "");
            }
        } catch (Exception ignored) {}

        // 缩略图：media 第一张图
        ImageView thumb = null;
        File md = new File(dir, "media");
        File[] imgs = md.exists() ? md.listFiles(f -> f.getName().matches(".*\\.(jpg|jpeg|png|webp)$")) : null;
        if (imgs != null && imgs.length > 0) {
            thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.setImageBitmap(BitmapFactory.decodeFile(imgs[0].getAbsolutePath()));
            thumb.setMinimumWidth(64);
            thumb.setMinimumHeight(64);
            card.addView(thumb, new LinearLayout.LayoutParams(64, 64));
        } else {
            TextView icon = new TextView(this);
            icon.setText("📄");
            icon.setTextSize(30);
            icon.setGravity(Gravity.CENTER);
            icon.setMinimumWidth(64);
            icon.setMinimumHeight(64);
            icon.setBackgroundColor(Color.rgb(234, 241, 255));
            card.addView(icon, new LinearLayout.LayoutParams(64, 64));
        }

        TextView desc = new TextView(this);
        desc.setPadding(12, 0, 0, 0);
        desc.setText(title + "\n" + (scope.isEmpty() ? "" : scope + " · ") + dir.getName() + (imgs != null && imgs.length > 0 ? " · " + imgs.length + " 图" : ""));
        desc.setTextSize(13);
        desc.setTextColor(Color.rgb(31, 45, 61));
        card.addView(desc, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        card.setOnClickListener(v -> openNote(dir));
        return card;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void openNote(File dir) {
        WebView wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        wv.setBackgroundColor(Color.WHITE);
        wv.setWebViewClient(new WebViewClient());

        try {
            File nf = new File(dir, "note.html");
            String html = new String(java.nio.file.Files.readAllBytes(nf.toPath()), StandardCharsets.UTF_8);
            // 本地图片路径解析：content 里 /media/ 相对路径 → 本地文件
            html = html.replace("src=\"/media/", "src=\"file://" + dir.getAbsolutePath() + "/media/");
            wv.loadDataWithBaseURL("file://" + dir.getAbsolutePath() + "/", html, "text/html", "utf-8", null);
        } catch (IOException e) {
            wv.loadDataWithBaseURL(null, "<p>读取失败：" + e.getMessage() + "</p>", "text/html", "utf-8", null);
        }

        // 全屏展示：AlertDialog 或新视图？简单：覆盖整个屏幕的 FrameLayout
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT);
        setContentView(wv, lp);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_ZIP && resultCode == RESULT_OK && data != null && data.getData() != null) {
            importZip(data.getData());
        }
    }

    private void importZip(final Uri uri) {
        new Thread(() -> {
            int count = 0;
            try (InputStream is = getContentResolver().openInputStream(uri);
                 ZipInputStream zis = new ZipInputStream(is)) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String name = e.getName();
                    if (!name.startsWith("notes/") || !name.contains("/")) continue;
                    String[] parts = name.split("/", 3);
                    if (parts.length < 3) continue;
                    String noteId = parts[1];
                    String rel = parts[2];
                    byte[] data = readAll(zis);
                    File d = new File(syncDir, noteId);
                    File target;
                    if (rel.equals("note.html") || rel.equals("meta.json")) {
                        target = new File(d, rel);
                    } else if (rel.startsWith("media/")) {
                        target = new File(d, rel);
                    } else {
                        continue;
                    }
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(target)) {
                        fos.write(data);
                    }
                    if (rel.equals("note.html")) count++;
                }
            } catch (Exception ex) {
                runOnUiThread(() -> Toast.makeText(this, "导入失败：" + ex.getMessage(), Toast.LENGTH_LONG).show());
                return;
            }
            final int n = count;
            runOnUiThread(() -> {
                Toast.makeText(this, "导入完成 ✓ " + n + " 篇笔记（可离线查看）", Toast.LENGTH_LONG).show();
                refresh();
            });
        }).start();
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }
}
