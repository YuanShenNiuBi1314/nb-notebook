package com.nbnotebook.app;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 牛逼笔记本 · Android 客户端
 * 首次使用：输入电脑的局域网 IP 和端口（电脑端启动服务器时会显示，如 http://192.168.0.2:8080）
 * 之后自动连接，可随时点右上角"切换"换服务器。
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "nb_prefs";
    private static final String KEY_URL = "server_url";

    private WebView webView;
    private ProgressBar progressBar;
    private FrameLayout setupLayout;
    private EditText ipInput;
    private EditText portInput;
    private TextView hintText;

    private String serverUrl = "";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ============ 设置界面 ============
        setupLayout = new FrameLayout(this);
        android.widget.LinearLayout setupBox = new android.widget.LinearLayout(this);
        setupBox.setOrientation(android.widget.LinearLayout.VERTICAL);
        setupBox.setGravity(android.view.Gravity.CENTER);
        setupBox.setPadding(60, 0, 60, 0);

        TextView title = new TextView(this);
        title.setText("🔥 牛逼笔记本");
        title.setTextSize(24);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, 0, 0, 10);

        TextView sub = new TextView(this);
        sub.setText("连接你的电脑，随时随地记笔记");
        sub.setTextSize(14);
        sub.setTextColor(0xFF666666);
        sub.setGravity(android.view.Gravity.CENTER);
        sub.setPadding(0, 0, 0, 40);

        ipInput = new EditText(this);
        ipInput.setHint("电脑局域网 IP（如 192.168.0.2）");
        ipInput.setTextSize(16);
        ipInput.setSingleLine(true);
        ipInput.setPadding(20, 12, 20, 12);

        portInput = new EditText(this);
        portInput.setHint("端口（默认 8080）");
        portInput.setText("8080");
        portInput.setTextSize(16);
        portInput.setSingleLine(true);
        portInput.setPadding(20, 12, 20, 12);
        android.widget.LinearLayout.LayoutParams portLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        portLp.topMargin = 12;
        portInput.setLayoutParams(portLp);

        Button connectBtn = new Button(this);
        connectBtn.setText("连 接");
        connectBtn.setTextSize(16);
        connectBtn.setAllCaps(false);
        android.widget.LinearLayout.LayoutParams btnLp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = 24;
        connectBtn.setLayoutParams(btnLp);

        hintText = new TextView(this);
        hintText.setText("提示：手机和电脑需连同一个 Wi-Fi。\n电脑端启动服务器后会显示访问地址，例如 http://192.168.0.2:8080");
        hintText.setTextSize(12);
        hintText.setTextColor(0xFF888888);
        hintText.setPadding(0, 30, 0, 0);

        setupBox.addView(title);
        setupBox.addView(sub);
        setupBox.addView(ipInput);
        setupBox.addView(portInput);
        setupBox.addView(connectBtn);
        setupBox.addView(hintText);
        setupLayout.addView(setupBox);

        connectBtn.setOnClickListener(v -> tryConnect(false));

        // ============ WebView ============
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }
            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
            }
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                progressBar.setVisibility(View.GONE);
                if (request.isForMainFrame()) {
                    showSetup("连接失败：" + error.getDescription() + "，请确认电脑端服务器已启动且在同一 Wi-Fi");
                }
            }
        });
        webView.setWebChromeClient(new WebChromeClient());

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setVisibility(View.GONE);

        FrameLayout root = new FrameLayout(this);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 12);
        pbLp.gravity = android.view.Gravity.TOP;
        root.addView(progressBar, pbLp);
        setContentView(root);

        // ============ 恢复或首次设置 ============
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        serverUrl = prefs.getString(KEY_URL, "");
        if (!serverUrl.isEmpty()) {
            showWeb(serverUrl);
        } else {
            showSetup("");
        }
    }

    private void showSetup(String error) {
        setupLayout.bringToFront();
        setupLayout.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        if (error != null && !error.isEmpty()) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        }
        // 自动填充电脑 IP：从 Wi-Fi 信息拿不到主机 IP，提示用户查看电脑端显示的地址
    }

    private void showWeb(String url) {
        webView.setVisibility(View.VISIBLE);
        setupLayout.setVisibility(View.GONE);
        webView.loadUrl(url);
    }

    private void tryConnect(boolean force) {
        String ip = ipInput.getText().toString().trim();
        String port = portInput.getText().toString().trim();
        if (ip.isEmpty()) { Toast.makeText(this, "请填写电脑的局域网 IP", Toast.LENGTH_SHORT).show(); return; }
        if (port.isEmpty()) port = "8080";
        String url = ip.startsWith("http") ? ip : "http://" + ip + ":" + port;
        serverUrl = url;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, url).apply();
        showWeb(url);
    }

    @Override
    public void onBackPressed() {
        if (webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }
}
