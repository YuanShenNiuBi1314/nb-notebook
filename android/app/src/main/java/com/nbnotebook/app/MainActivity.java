package com.nbnotebook.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 牛逼笔记本 · Android 原生客户端
 * 三选项卡：📄 笔记（在线浏览电脑笔记） / ✍️ 采集（离线采集） / ⚙️ 设置（服务器）
 * 连接电脑后支持多级菜单浏览分类 → 笔记列表 → 详情（音视频自动下载）。
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "nb_prefs";
    private static final String KEY_URL = "server_url";

    // 服务器
    private String serverUrl = "";
    private boolean connected = false;

    // 数据
    private final List<String> scopes = new ArrayList<>();
    private final List<JSONObject> pathStack = new ArrayList<>(); // 类别导航栈

    // 视图
    private TextView statusDot, statusText;
    private Spinner scopeSpinner;
    private LinearLayout breadcrumbBar, categoryBox, noteListBox;
    private TextView navHint, noteCount;
    private LinearLayout notesPanel, capturePanel, settingsPanel;
    private EditText ipInput, portInput;
    private Button tabNotes, tabCapture, tabSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        serverUrl = prefs.getString(KEY_URL, "");

        // ============ 顶部 AppBar ============
        TextView title = new TextView(this);
        title.setText("🔥 牛逼笔记本");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(16, 0, 8, 0);
        title.setGravity(Gravity.CENTER_VERTICAL);

        statusDot = new TextView(this);
        statusDot.setText("●");
        statusDot.setTextSize(14);
        statusDot.setPadding(0, 0, 14, 0);
        updateStatus();

        LinearLayout appBar = new LinearLayout(this);
        appBar.setOrientation(LinearLayout.HORIZONTAL);
        appBar.setGravity(Gravity.CENTER_VERTICAL);
        appBar.setBackgroundColor(Color.rgb(30, 42, 58));
        appBar.setMinimumHeight(52);
        appBar.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        appBar.addView(statusDot);

        // ============ 三个面板 ============
        notesPanel = buildNotesPanel();
        capturePanel = buildCapturePanel();
        settingsPanel = buildSettingsPanel();

        FrameLayout content = new FrameLayout(this);
        content.addView(notesPanel);
        content.addView(capturePanel);
        content.addView(settingsPanel);
        showPanel(0);

        // ============ 底部 Tab ============
        tabNotes = tabBtn("📄 笔记");
        tabCapture = tabBtn("✍️ 采集");
        tabSettings = tabBtn("⚙️ 设置");
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(Color.WHITE);
        bottomBar.setElevation(12);
        bottomBar.addView(tabNotes, new LinearLayout.LayoutParams(0, 118, 1));
        bottomBar.addView(tabCapture, new LinearLayout.LayoutParams(0, 118, 1));
        bottomBar.addView(tabSettings, new LinearLayout.LayoutParams(0, 118, 1));

        tabNotes.setOnClickListener(v -> { showPanel(0); refreshTabStyle(); });
        tabCapture.setOnClickListener(v -> { showPanel(1); refreshTabStyle(); });
        tabSettings.setOnClickListener(v -> { showPanel(2); refreshTabStyle(); });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(245, 247, 250));
        root.addView(appBar);
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bottomBar);
        setContentView(root);

        // 已保存地址则自动连接
        if (!serverUrl.isEmpty()) {
            navHint.setText("正在连接 " + serverUrl + " …");
            tryConnect(true);
        } else {
            showPanel(2);
            refreshTabStyle();
        }
    }

    // ================= 笔记面板 =================
    private LinearLayout buildNotesPanel() {
        // 范围行
        TextView scopeLabel = new TextView(this);
        scopeLabel.setText("范围");
        scopeLabel.setTextSize(12);
        scopeLabel.setTextColor(Color.rgb(107, 119, 133));
        scopeLabel.setPadding(16, 10, 0, 0);

        scopeSpinner = new Spinner(this);
        scopeSpinner.setPadding(8, 4, 8, 4);

        LinearLayout scopeRow = new LinearLayout(this);
        scopeRow.setOrientation(LinearLayout.VERTICAL);
        scopeRow.addView(scopeLabel);
        scopeRow.addView(scopeSpinner);

        scopeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (!connected) return;
                // 切换范围 → 重置导航栈到该 scope 根
                if (pos < scopes.size()) {
                    resetNavigationToScope(scopes.get(pos));
                }
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        // 面包屑
        navHint = new TextView(this);
        navHint.setText("未连接服务器");
        navHint.setTextSize(12);
        navHint.setTextColor(Color.rgb(107, 119, 133));
        navHint.setPadding(16, 10, 16, 0);

        breadcrumbBar = new LinearLayout(this);
        breadcrumbBar.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView crumbScroll = new HorizontalScrollView(this);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        crumbScroll.addView(breadcrumbBar);

        // 类别区
        categoryBox = new LinearLayout(this);
        categoryBox.setOrientation(LinearLayout.VERTICAL);
        categoryBox.setPadding(12, 6, 12, 0);

        // 笔记计数
        noteCount = new TextView(this);
        noteCount.setText("");
        noteCount.setTextSize(12);
        noteCount.setTextColor(Color.rgb(107, 119, 133));
        noteCount.setPadding(16, 8, 16, 0);

        // 笔记列表
        noteListBox = new LinearLayout(this);
        noteListBox.setOrientation(LinearLayout.VERTICAL);
        noteListBox.setPadding(12, 4, 12, 12);
        ScrollView noteScroll = new ScrollView(this);
        noteScroll.addView(noteListBox);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(scopeRow);
        panel.addView(crumbScroll);
        panel.addView(categoryBox);
        panel.addView(noteCount);
        panel.addView(noteScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return panel;
    }

    // ================= 采集面板 =================
    private LinearLayout buildCapturePanel() {
        TextView head = new TextView(this);
        head.setText("📴 采集终端");
        head.setTextSize(18);
        head.setTextColor(Color.rgb(31, 45, 61));
        head.setPadding(20, 24, 20, 4);

        connStatus = new TextView(this);
        connStatus.setTextSize(13);
        connStatus.setPadding(20, 0, 20, 8);
        refreshCaptureStatus();

        TextView desc = new TextView(this);
        desc.setText("拍照 / 写字 / 描边，每一条自动直传到电脑「待整理区」。\n" +
                "手绘图用红笔圈框，自动识别裁剪；没连上时内容留在本地，可导出 zip 发电脑兜底。");
        desc.setTextSize(13);
        desc.setTextColor(Color.rgb(80, 96, 116));
        desc.setLineSpacing(5, 1);
        desc.setPadding(20, 0, 20, 18);

        Button enter = new Button(this);
        enter.setText("进入采集");
        enter.setTextSize(16);
        enter.setAllCaps(false);
        enter.setTextColor(Color.WHITE);
        enter.setBackgroundColor(Color.rgb(47, 109, 246));
        LinearLayout.LayoutParams enterLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 170);
        enterLp.leftMargin = 40; enterLp.rightMargin = 40;
        enter.setLayoutParams(enterLp);
        enter.setOnClickListener(v ->
                startActivity(new Intent(this, OfflineCaptureActivity.class)));

        Button local = new Button(this);
        local.setText("📄 本地笔记（导入电脑包 · 离线看）");
        local.setTextSize(14);
        local.setAllCaps(false);
        local.setTextColor(Color.rgb(31, 45, 61));
        local.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams localLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 150);
        localLp.leftMargin = 40; localLp.rightMargin = 40; localLp.topMargin = 12;
        local.setLayoutParams(localLp);
        local.setOnClickListener(v ->
                startActivity(new Intent(this, LocalNotesActivity.class)));

        TextView footer = new TextView(this);
        footer.setText("© 2026 类人群星闪耀时 @豆包 · 北中小作坊");
        footer.setTextSize(11);
        footer.setTextColor(Color.rgb(160, 160, 160));
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, 40, 0, 0);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(head);
        panel.addView(connStatus);
        panel.addView(desc);
        panel.addView(enter);
        panel.addView(local);
        panel.addView(footer);
        return panel;
    }

    private TextView connStatus;

    private void refreshCaptureStatus() {
        if (connStatus == null) return;
        if (connected) {
            connStatus.setText("● 已连电脑 " + serverUrl + " —— 采集即直传待整理区");
            connStatus.setTextColor(Color.rgb(24, 160, 88));
        } else {
            connStatus.setText("○ 未连接 —— 离线采集，本地留底，可导出 zip 兜底");
            connStatus.setTextColor(Color.rgb(229, 72, 77));
        }
    }

    // ================= 设置面板 =================
    private LinearLayout buildSettingsPanel() {
        TextView head = new TextView(this);
        head.setText("⚙️ 设置");
        head.setTextSize(18);
        head.setTextColor(Color.rgb(31, 45, 61));
        head.setPadding(20, 30, 20, 12);

        TextView labelIp = new TextView(this);
        labelIp.setText("电脑局域网 IP / 地址");
        labelIp.setTextSize(12);
        labelIp.setTextColor(Color.rgb(107, 119, 133));
        labelIp.setPadding(20, 8, 20, 4);

        ipInput = new EditText(this);
        ipInput.setHint("如 192.168.0.2 或 http://192.168.0.2");
        ipInput.setTextSize(15);
        ipInput.setSingleLine(true);
        LinearLayout.LayoutParams ipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ipLp.leftMargin = 20; ipLp.rightMargin = 20;
        ipInput.setLayoutParams(ipLp);

        TextView labelPort = new TextView(this);
        labelPort.setText("端口");
        labelPort.setTextSize(12);
        labelPort.setTextColor(Color.rgb(107, 119, 133));
        labelPort.setPadding(20, 12, 20, 4);

        portInput = new EditText(this);
        portInput.setText("8080");
        portInput.setTextSize(15);
        portInput.setSingleLine(true);
        LinearLayout.LayoutParams portLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        portLp.leftMargin = 20; portLp.rightMargin = 20;
        portInput.setLayoutParams(portLp);

        Button connect = new Button(this);
        connect.setText("保存并连接");
        connect.setTextSize(16);
        connect.setAllCaps(false);
        connect.setTextColor(Color.WHITE);
        connect.setBackgroundColor(Color.rgb(47, 109, 246));
        LinearLayout.LayoutParams connLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 190);
        connLp.leftMargin = 40; connLp.rightMargin = 40; connLp.topMargin = 24;
        connect.setLayoutParams(connLp);
        connect.setOnClickListener(v -> saveAndConnect());

        statusText = new TextView(this);
        statusText.setText(serverUrl.isEmpty() ? "尚未连接" : "已保存地址：" + serverUrl);
        statusText.setTextSize(12);
        statusText.setTextColor(Color.rgb(107, 119, 133));
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(20, 16, 20, 0);

        TextView hint = new TextView(this);
        hint.setText("提示：手机和电脑需连同一个 Wi-Fi；\n电脑端需先启动服务器并放行防火墙。\n若连不上，请用「离线采集」模式，不依赖网络。");
        hint.setTextSize(12);
        hint.setTextColor(Color.rgb(150, 150, 150));
        hint.setPadding(20, 20, 20, 0);
        hint.setLineSpacing(4, 1);

        TextView about = new TextView(this);
        about.setText("牛逼笔记本 v1.1 · 安卓 6.0+\n© 2026 类人群星闪耀时 @豆包 · 北中小作坊");
        about.setTextSize(11);
        about.setTextColor(Color.rgb(160, 160, 160));
        about.setGravity(Gravity.CENTER);
        about.setPadding(0, 60, 0, 0);

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(head);
        panel.addView(labelIp);
        panel.addView(ipInput);
        panel.addView(labelPort);
        panel.addView(portInput);
        panel.addView(connect);
        panel.addView(statusText);
        panel.addView(hint);
        panel.addView(about);
        return panel;
    }

    // ================= 工具 =================
    private Button tabBtn(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setBackgroundColor(Color.WHITE);
        return b;
    }

    private void refreshTabStyle() {
        tabNotes.setTextColor(notesPanel.getVisibility() == View.VISIBLE ? Color.rgb(47, 109, 246) : Color.rgb(130, 140, 152));
        tabCapture.setTextColor(capturePanel.getVisibility() == View.VISIBLE ? Color.rgb(47, 109, 246) : Color.rgb(130, 140, 152));
        tabSettings.setTextColor(settingsPanel.getVisibility() == View.VISIBLE ? Color.rgb(47, 109, 246) : Color.rgb(130, 140, 152));
    }

    private void showPanel(int idx) {
        notesPanel.setVisibility(idx == 0 ? View.VISIBLE : View.GONE);
        capturePanel.setVisibility(idx == 1 ? View.VISIBLE : View.GONE);
        settingsPanel.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
    }

    private void updateStatus() {
        statusDot.setTextColor(connected ? Color.rgb(52, 199, 89) : Color.rgb(255, 59, 48));
    }

    // ================= 连接 =================
    private void saveAndConnect() {
        String ip = ipInput.getText().toString().trim();
        String port = portInput.getText().toString().trim();
        if (ip.isEmpty()) { Toast.makeText(this, "请填写电脑的局域网 IP", Toast.LENGTH_SHORT).show(); return; }
        if (port.isEmpty()) port = "8080";
        serverUrl = ip.startsWith("http") ? ip : "http://" + ip + ":" + port;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, serverUrl).apply();
        statusText.setText("正在连接 " + serverUrl + " …");
        tryConnect(false);
    }

    private void tryConnect(final boolean silent) {
        connected = false;
        updateStatus();
        new Thread(() -> {
            try {
                String tree = Api.get(serverUrl + "/api/tree");
                JSONObject root = new JSONObject(tree);
                JSONArray sc = root.optJSONArray("children");
                final List<String> scopeList = new ArrayList<>();
                if (sc != null) {
                    for (int i = 0; i < sc.length(); i++) {
                        JSONObject s = sc.getJSONObject(i);
                        String name = s.optString("name", "");
                        if (!name.isEmpty()) scopeList.add(name);
                    }
                }
                runOnUiThread(() -> onConnected(scopeList));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    connected = false;
                    updateStatus();
                    refreshCaptureStatus();
                    if (silent) {
                        navHint.setText("连接失败：" + e.getMessage() + "\n点「⚙️ 设置」重新配置，或直接用「✍️ 采集」离线模式");
                    } else {
                        statusText.setText("连接失败：" + e.getMessage());
                        Toast.makeText(this, "连接失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                        showPanel(2);
                        refreshTabStyle();
                    }
                });
            }
        }).start();
    }

    private void onConnected(List<String> scopeList) {
        connected = true;
        updateStatus();
        scopes.clear();
        scopes.addAll(scopeList);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, scopes);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        scopeSpinner.setAdapter(adapter);
        if (!scopeList.isEmpty()) {
            resetNavigationToScope(scopeList.get(0));
        } else {
            navHint.setText("已连接，但还没有任何范围，请在电脑端创建笔记");
            noteCount.setText("");
            noteListBox.removeAllViews();
        }
        statusText.setText("已连接 " + serverUrl);
        refreshCaptureStatus();
        showPanel(0);
        refreshTabStyle();
        Toast.makeText(this, "已连接电脑 ✓", Toast.LENGTH_SHORT).show();
    }

    /** 导航到指定范围根 */
    private void resetNavigationToScope(String scope) {
        pathStack.clear();
        navHint.setText(scope);
        breadcrumbBar.removeAllViews();
        categoryBox.removeAllViews();
        addCrumb("📁 " + scope, 0);
        new Thread(() -> {
            try {
                String tree = Api.get(serverUrl + "/api/tree");
                JSONObject root = new JSONObject(tree);
                JSONArray sc = root.optJSONArray("children");
                if (sc != null) {
                    for (int i = 0; i < sc.length(); i++) {
                        JSONObject s = sc.getJSONObject(i);
                        if (scope.equals(s.optString("name", ""))) {
                            final JSONObject scopeNode = s;
                            runOnUiThread(() -> {
                                pathStack.add(scopeNode);
                                renderCategories();
                            });
                            return;
                        }
                    }
                }
                runOnUiThread(() -> navHint.setText(scope + "（无子分类）"));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "加载分类失败：" + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void addCrumb(String label, final int level) {
        final Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setTextColor(level == pathStack.size() ? Color.rgb(47, 109, 246) : Color.rgb(107, 119, 133));
        b.setOnClickListener(v -> {
            while (pathStack.size() > level) pathStack.remove(pathStack.size() - 1);
            renderCategories();
        });
        breadcrumbBar.addView(b);
    }

    /** 渲染当前层级的子类别按钮 + 加载笔记 */
    private void renderCategories() {
        breadcrumbBar.removeAllViews();
        categoryBox.removeAllViews();
        // 面包屑
        addCrumb("📁 " + scopes.get(Math.min(Math.max(0, scopeSpinner.getSelectedItemPosition()), scopes.size() - 1)), 0);
        for (int i = 0; i < pathStack.size(); i++) {
            addCrumb(Json.safeName(pathStack.get(i)), i);
        }
        navHint.setText(Json.pathText(pathStack));

        JSONObject cur = pathStack.isEmpty() ? null : pathStack.get(pathStack.size() - 1);
        JSONArray kids = cur == null ? null : cur.optJSONArray("children");
        if (kids != null && kids.length() > 0) {
            // 多级菜单：显示该级所有子类别
            LinearLayout wrap = new LinearLayout(this);
            wrap.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < kids.length(); i++) {
                final JSONObject kid = kids.optJSONObject(i);
                if (kid == null) continue;
                String name = kid.optString("name", "");
                boolean hasKids = kid.optJSONArray("children") != null && kid.optJSONArray("children").length() > 0;
                Button b = new Button(this);
                b.setText(name + (hasKids ? " ›" : ""));
                b.setTextSize(13);
                b.setAllCaps(false);
                b.setPadding(16, 10, 16, 10);
                b.setBackgroundColor(Color.WHITE);
                b.setTextColor(Color.rgb(31, 45, 61));
                b.setElevation(2);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = 10; lp.bottomMargin = 10;
                b.setLayoutParams(lp);
                b.setOnClickListener(v -> {
                    pathStack.add(kid);
                    renderCategories();
                });
                wrap.addView(b);
            }
            categoryBox.addView(wrap);
        }

        // 加载该类别下笔记
        String categoryId = pathStack.isEmpty() ? "" : Json.safeId(pathStack.get(pathStack.size() - 1));
        loadNotes(categoryId);
    }

    private void loadNotes(final String categoryId) {
        noteCount.setText("加载中…");
        final String scope = scopes.isEmpty() ? "" : scopes.get(Math.min(Math.max(0, scopeSpinner.getSelectedItemPosition()), scopes.size() - 1));
        new Thread(() -> {
            try {
                String url = serverUrl + "/api/notes?scope=" + java.net.URLEncoder.encode(scope, "UTF-8");
                if (!categoryId.isEmpty()) url += "&categoryId=" + java.net.URLEncoder.encode(categoryId, "UTF-8");
                String json = Api.get(url);
                final JSONObject resp = new JSONObject(json);
                runOnUiThread(() -> renderNotes(resp.optJSONArray("notes")));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    noteCount.setText("加载笔记失败：" + e.getMessage());
                });
            }
        }).start();
    }

    private void renderNotes(JSONArray notes) {
        noteListBox.removeAllViews();
        if (notes == null || notes.length() == 0) {
            noteCount.setText("这个分类下还没有笔记（在电脑端新建，或离线采集导入）");
            return;
        }
        noteCount.setText("共 " + notes.length() + " 篇笔记");
        for (int i = 0; i < notes.length(); i++) {
            JSONObject n = notes.optJSONObject(i);
            if (n == null) continue;
            noteListBox.addView(noteCard(n));
        }
    }

    private View noteCard(final JSONObject note) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(16, 12, 16, 12);
        card.setBackgroundColor(Color.WHITE);
        card.setElevation(2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 10;
        card.setLayoutParams(lp);

        TextView t = new TextView(this);
        t.setText(Json.safeName(note));
        t.setTextSize(15);
        t.setTextColor(Color.rgb(31, 45, 61));
        t.setTypeface(Typeface.DEFAULT_BOLD);

        TextView sub = new TextView(this);
        JSONObject media = note.optJSONObject("media");
        String tags = "";
        if (media != null) {
            if (media.optBoolean("video", false)) tags += " 🎬视频";
            if (media.optBoolean("audio", false)) tags += " 🔊音频";
            if (media.optInt("images", 0) > 0) tags += " 🖼" + media.optInt("images", 0) + "图";
        }
        if (tags.isEmpty()) tags = " 无媒体";
        sub.setText(note.optString("updated", "").replace("T", " ").substring(0, Math.min(16, note.optString("updated", "").length())) + tags);
        sub.setTextSize(11);
        sub.setTextColor(Color.rgb(140, 150, 162));
        sub.setPadding(0, 4, 0, 0);

        card.addView(t);
        card.addView(sub);
        card.setOnClickListener(v -> {
            Intent it = new Intent(this, NoteDetailActivity.class);
            it.putExtra("noteId", Json.safeId(note));
            it.putExtra("serverUrl", serverUrl);
            it.putExtra("noteTitle", Json.safeName(note));
            startActivity(it);
        });
        return card;
    }

    @Override
    public void onBackPressed() {
        if (pathStack.size() > 0) {
            pathStack.remove(pathStack.size() - 1);
            renderCategories();
        } else {
            super.onBackPressed();
        }
    }
}
