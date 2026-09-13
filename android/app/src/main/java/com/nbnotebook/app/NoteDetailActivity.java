package com.nbnotebook.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 笔记详情：加载电脑端笔记 HTML。
 * 打开时自动解析笔记内的 <video>/<audio>/<source>，下载到
 * 文件管理器可见的专属文件夹 Download/NBNotebook/。
 */
public class NoteDetailActivity extends AppCompatActivity {

    private static final int REQ_STORAGE = 2001;

    private WebView webView;
    private ProgressBar progress;
    private TextView statusText;
    private String serverUrl = "";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent it = getIntent();
        String noteId = it.getStringExtra("noteId");
        String serverUrl = it.getStringExtra("serverUrl");
        String title = it.getStringExtra("noteTitle");
        this.serverUrl = serverUrl;

        // 顶部
        TextView appBar = new TextView(this);
        appBar.setText("📄 " + (title == null || title.isEmpty() ? "笔记" : title));
        appBar.setTextColor(Color.WHITE);
        appBar.setTextSize(15);
        appBar.setPadding(16, 14, 16, 14);
        appBar.setBackgroundColor(Color.rgb(30, 42, 58));

        Button back = new Button(this);
        back.setText("← 返回");
        back.setAllCaps(false);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setTextColor(Color.WHITE);
        back.setOnClickListener(v -> finish());

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.rgb(30, 42, 58));
        bar.addView(back);
        bar.addView(appBar, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        // 状态栏（下载提示）
        statusText = new TextView(this);
        statusText.setText("加载中…");
        statusText.setTextSize(12);
        statusText.setTextColor(Color.rgb(107, 119, 133));
        statusText.setPadding(16, 8, 16, 8);

        // WebView
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        webView.setBackgroundColor(Color.WHITE);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
            }
        });

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setVisibility(View.GONE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.addView(bar);
        root.addView(statusText);
        root.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 10));
        setContentView(root);

        loadNote(noteId);
    }

    private void loadNote(final String noteId) {
        progress.setVisibility(View.VISIBLE);
        statusText.setText("连接电脑拉取笔记…");
        new Thread(() -> {
            try {
                String json = Api.get(serverUrl + "/api/notes/" + noteId);
                JSONObject note = new JSONObject(json);
                String content = note.optString("content", "");
                final String title = note.optString("title", "笔记");
                final List<String[]> media = parseMediaSrc(content);

                runOnUiThread(() -> {
                    // 展示
                    webView.loadDataWithBaseURL(serverUrl + "/", content, "text/html", "utf-8", null);
                    if (media.isEmpty()) {
                        statusText.setText("已加载 · 无音视频");
                    } else {
                        statusText.setText("检测到 " + media.size() + " 个音视频，自动下载中…");
                    }
                    // 自动下载音视频到专属文件夹
                    startDownload(media);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    statusText.setText("加载失败：" + e.getMessage());
                    Toast.makeText(this, "加载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** 解析 HTML 中 video/audio/source 的 src（相对或绝对路径） */
    private List<String[]> parseMediaSrc(String html) {
        List<String[]> out = new ArrayList<>();
        if (html == null || html.isEmpty()) return out;
        Pattern p = Pattern.compile(
                "(?is)<(?:video|audio|source)\\b[^>]*?(?:src\\s*=\\s*[\"']([^\"']+)[\"']|\\bsrc\\s*=\\s*([^\\s\"'>]+))[^>]*>");
        Matcher m = p.matcher(html);
        int idx = 0;
        while (m.find()) {
            String src = m.group(1) != null ? m.group(1) : m.group(2);
            if (src == null || src.isBlank()) continue;
            // 跳过 data: 与空协议
            if (src.startsWith("data:") || src.startsWith("javascript:")) continue;
            String abs = src.startsWith("http") ? src : serverUrl + (src.startsWith("/") ? "" : "/") + src;
            String name = src.substring(src.lastIndexOf('/') + 1);
            if (name.isEmpty()) name = "media_" + (++idx);
            out.add(new String[]{abs, name});
        }
        return out;
    }

    private void startDownload(List<String[]> media) {
        if (media.isEmpty()) return;
        // Android 9- 需要存储权限
        if (Build.VERSION.SDK_INT < 29) {
            if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
                statusText.setText("需要存储权限才能保存音视频");
                return;
            }
        }
        downloadAll(media);
    }

    private void downloadAll(List<String[]> media) {
        new Thread(() -> {
            int ok = 0;
            for (String[] m : media) {
                String saved = MediaDownloader.download(this, m[0], m[1]);
                if (saved != null) ok++;
            }
            final int done = ok;
            final int total = media.size();
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                if (done == total) {
                    statusText.setText("✓ " + done + " 个音视频已保存到 Download/NBNotebook/");
                } else {
                    statusText.setText("已保存 " + done + "/" + total + " 到 Download/NBNotebook/（失败见提示）");
                }
            });
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE && grantResults.length > 0 && grantResults[0] == 0) {
            statusText.setText("已授权，正在下载音视频…");
            // 重新解析并下载（媒体列表丢失，重新加载即可触发）
            String noteId = getIntent().getStringExtra("noteId");
            loadNote(noteId);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }
}
