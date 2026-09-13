package com.nbnotebook.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 牛逼笔记本 · Android 客户端
 * 首页双入口：
 *   1. 🌐 连接电脑 —— 局域网直连电脑服务器（需同一 Wi-Fi）
 *   2. 📴 离线采集 —— 不依赖网络：拍照 / 写字 / 描边 → 压缩包 → 微信发电脑整理
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "nb_prefs";
    private static final String KEY_URL = "server_url";

    private WebView webView;
    private ProgressBar progressBar;
    private FrameLayout setupLayout;
    private EditText ipInput;
    private EditText portInput;
    private FrameLayout homeLayout;

    private String serverUrl = "";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ============ 首页（双入口） ============
        homeLayout = new FrameLayout(this);
        LinearLayout homeBox = new LinearLayout(this);
        homeBox.setOrientation(LinearLayout.VERTICAL);
        homeBox.setGravity(Gravity.CENTER_HORIZONTAL);
        homeBox.setPadding(44, 60, 44, 30);

        TextView logo = new TextView(this);
        logo.setText("🔥");
        logo.setTextSize(52);
        logo.setGravity(Gravity.CENTER);
        homeBox.addView(logo);

        TextView appName = new TextView(this);
        appName.setText("牛逼笔记本");
        appName.setTextSize(26);
        appName.setGravity(Gravity.CENTER);
        appName.setTextColor(Color.rgb(31, 45, 61));
        appName.setPadding(0, 8, 0, 0);
        homeBox.addView(appName);

        TextView sub = new TextView(this);
        sub.setText("刷卷子 · 记知识点 · AI 整理");
        sub.setTextSize(13);
        sub.setTextColor(Color.rgb(107, 119, 133));
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 6, 0, 40);
        homeBox.addView(sub);

        Button btnOnline = bigHomeBtn("🌐 连接电脑", "同一 Wi-Fi 下直连电脑服务器，AI 整理/打印");
        homeBox.addView(btnOnline);
        Button btnOffline = bigHomeBtn("📴 离线采集", "不联网也能用：拍照 / 写字 / 描边 → 打包发电脑");
        homeBox.addView(btnOffline);

        TextView footer = new TextView(this);
        footer.setText("© 2026 类人群星闪耀时 @豆包 · 北中小作坊");
        footer.setTextSize(11);
        footer.setTextColor(Color.rgb(160, 160, 160));
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, 40, 0, 0);
        homeBox.addView(footer);

        homeLayout.addView(homeBox);

        btnOnline.setOnClickListener(v -> enterOnline());
        btnOffline.setOnClickListener(v ->
                startActivity(new Intent(this, OfflineCaptureActivity.class)));

        // ============ 设置界面（连接电脑） ============
        setupLayout = new FrameLayout(this);
        LinearLayout setupBox = new LinearLayout(this);
        setupBox.setOrientation(LinearLayout.VERTICAL);
        setupBox.setGravity(Gravity.CENTER);
        setupBox.setPadding(60, 0, 60, 0);

        TextView title = new TextView(this);
        title.setText("🌐 连接电脑");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 24);

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
        LinearLayout.LayoutParams portLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        portLp.topMargin = 12;
        portInput.setLayoutParams(portLp);

        Button connectBtn = new Button(this);
        connectBtn.setText("连 接");
        connectBtn.setTextSize(16);
        connectBtn.setAllCaps(false);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = 24;
        connectBtn.setLayoutParams(btnLp);

        TextView hintText = new TextView(this);
        hintText.setText("提示：手机和电脑需连同一个 Wi-Fi。\n电脑端启动服务器后会显示访问地址，例如 http://192.168.0.2:8080");
        hintText.setTextSize(12);
        hintText.setTextColor(Color.rgb(136, 136, 136));
        hintText.setPadding(0, 30, 0, 0);

        setupBox.addView(title);
        setupBox.addView(ipInput);
        setupBox.addView(portInput);
        setupBox.addView(connectBtn);
        setupBox.addView(hintText);
        setupLayout.addView(setupBox);
        connectBtn.setOnClickListener(v -> tryConnect());

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
        root.addView(homeLayout, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 12);
        pbLp.gravity = Gravity.TOP;
        root.addView(progressBar, pbLp);
        setContentView(root);

        webView.setVisibility(View.GONE);
    }

    private Button bigHomeBtn(String main, String desc) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(24, 20, 24, 20);
        TextView t1 = new TextView(this);
        t1.setText(main);
        t1.setTextSize(18);
        t1.setTextColor(Color.WHITE);
        t1.setGravity(Gravity.CENTER);
        TextView t2 = new TextView(this);
        t2.setText(desc);
        t2.setTextSize(11);
        t2.setTextColor(Color.argb(200, 255, 255, 255));
        t2.setGravity(Gravity.CENTER);
        t2.setPadding(0, 4, 0, 0);
        box.addView(t1);
        box.addView(t2);
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setBackgroundColor(Color.rgb(47, 109, 246));
        b.setElevation(4);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 210);
        lp.bottomMargin = 18;
        b.setLayoutParams(lp);
        b.setText("\n" + main + "\n" + desc + "\n");
        return b;
    }

    private void enterOnline() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        serverUrl = prefs.getString(KEY_URL, "");
        if (!serverUrl.isEmpty()) {
            showWeb(serverUrl);
        } else {
            showSetup("");
        }
    }

    private void showSetup(String error) {
        homeLayout.setVisibility(View.GONE);
        webView.setVisibility(View.GONE);
        setupLayout.setVisibility(View.VISIBLE);
        if (error != null && !error.isEmpty()) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        }
    }

    private void showWeb(String url) {
        homeLayout.setVisibility(View.GONE);
        setupLayout.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
    }

    private void tryConnect() {
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
        } else if (webView.getVisibility() == View.VISIBLE || setupLayout.getVisibility() == View.VISIBLE) {
            // 返回首页
            webView.setVisibility(View.GONE);
            setupLayout.setVisibility(View.GONE);
            homeLayout.setVisibility(View.VISIBLE);
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
