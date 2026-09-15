package com.nbnotebook.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;

import org.json.JSONArray;
import org.json.JSONObject;
import org.opencv.android.OpenCVLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 离线采集 / 实时直传采集。
 * - 在线（同一 Wi-Fi 连上电脑）：拍照/写字/描边后【每一条自动直传】到电脑待整理区（缓冲区），本地同时留底。
 * - 离线：条目留在本地，可导出 zip 通过微信发电脑（兜底）。
 * - 拍照自动做红框识别：手绘图用红笔圈框，自动裁剪去红框；失败回退整图。
 */
public class OfflineCaptureActivity extends AppCompatActivity {

    private static final int REQ_CAMERA = 1001;
    private static final String PROVIDER_AUTH = "com.nbnotebook.app.fileprovider";
    private static final String PREFS = "nb_prefs";
    private static final String KEY_URL = "server_url";

    private File captureDir;
    private File itemsFile;
    private File pendingPhoto;
    private final List<Item> items = new ArrayList<>();
    private LinearLayout listBox;
    private TextView countText, connText;
    private EditText scopeInput;
    private String serverUrl = "";
    private boolean online = false;

    private static final class Item {
        final String type;   // photo | text | draw
        String file;
        String text;
        String status;       // local | uploaded
        String note;         // 附加说明（如 红框裁剪）
        Item(String type, String file, String text) {
            this.type = type; this.file = file; this.text = text;
            this.status = "local";
            this.note = "";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        captureDir = new File(getFilesDir(), "capture");
        if (!captureDir.exists()) captureDir.mkdirs();
        itemsFile = new File(getFilesDir(), "capture_items.json");
        serverUrl = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_URL, "");
        // OpenCV 初始化（红框识别用）
        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, "OpenCV 初始化失败，红框识别不可用（仍可整图采集）", Toast.LENGTH_LONG).show();
        }

        // ===== 顶部 =====
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

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("📴 采集终端");
        title.setTextSize(17);
        title.setTextColor(Color.WHITE);
        connText = new TextView(this);
        connText.setTextSize(11);
        connText.setTextColor(Color.argb(200, 255, 255, 255));
        titleBox.addView(title);
        titleBox.addView(connText);

        titleRow.addView(back, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.1f));
        titleRow.addView(titleBox, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2.4f));

        // ===== 范围 + 连接检查 =====
        LinearLayout scopeRow = new LinearLayout(this);
        scopeRow.setOrientation(LinearLayout.HORIZONTAL);
        scopeRow.setGravity(Gravity.CENTER_VERTICAL);
        scopeRow.setPadding(16, 10, 16, 2);
        TextView scopeLabel = new TextView(this);
        scopeLabel.setText("归入范围：");
        scopeLabel.setTextSize(13);
        scopeLabel.setTextColor(Color.rgb(31, 45, 61));
        scopeInput = new EditText(this);
        scopeInput.setText("默认");
        scopeInput.setTextSize(13);
        scopeInput.setSingleLine(true);
        scopeInput.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams scopeLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        scopeInput.setLayoutParams(scopeLp);
        scopeRow.addView(scopeLabel);
        scopeRow.addView(scopeInput);

        // ===== 条目列表 =====
        countText = new TextView(this);
        countText.setText("正在检查与电脑的连接…");
        countText.setTextColor(Color.rgb(107, 119, 133));
        countText.setPadding(16, 6, 16, 2);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(12, 4, 12, 8);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(listBox);

        // ===== 底部操作 =====
        Button btnCamera = bigBtn("📷 拍照（自动红框识别）");
        Button btnText = bigBtn("✏️ 写字");
        Button btnDraw = bigBtn("🎨 描边");
        Button btnExport = bigBtn("📦 导出压缩包（离线兜底）");
        btnExport.setBackgroundColor(Color.rgb(80, 96, 116));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.addView(btnCamera, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row1.addView(btnText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row1.addView(btnDraw, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.addView(btnExport, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(14, 8, 14, 16);
        bottom.addView(row1);
        LinearLayout.LayoutParams row2lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row2lp.topMargin = 10;
        bottom.addView(row2, row2lp);

        // ===== 根 =====
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(245, 247, 250));
        root.addView(titleRow);
        root.addView(scopeRow);
        root.addView(countText);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bottom);
        setContentView(root);

        btnCamera.setOnClickListener(v -> takePhoto());
        btnText.setOnClickListener(v -> addText());
        btnDraw.setOnClickListener(v -> openDraw());
        btnExport.setOnClickListener(v -> exportZip());

        loadItems();
        refreshList();
        checkConnection();
    }

    private Button bigBtn(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.rgb(47, 109, 246));
        b.setPadding(0, 14, 0, 14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = 8;
        b.setLayoutParams(lp);
        return b;
    }

    // ================= 连接检测 =================
    private void checkConnection() {
        connText.setText("检查连接…");
        new Thread(() -> {
            boolean ok = false;
            if (!serverUrl.isEmpty()) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(serverUrl + "/api/status").openConnection();
                    c.setConnectTimeout(3000);
                    c.setReadTimeout(3000);
                    ok = c.getResponseCode() == 200;
                    c.disconnect();
                } catch (Exception ignored) {}
            }
            final boolean connected = ok;
            runOnUiThread(() -> {
                online = connected;
                if (connected) {
                    connText.setText("● 已连电脑，每一条自动直传待整理区");
                    connText.setTextColor(Color.rgb(120, 255, 150));
                } else {
                    connText.setText("○ 未连接（离线采集，导出 zip 发电脑）");
                    connText.setTextColor(Color.argb(200, 255, 220, 140));
                }
                countText.setText("共 " + items.size() + " 个条目" + (connected ? "" : " · 离线中"));
                refreshList();
            });
        }).start();
    }

    // ================= 拍照 + 红框识别 =================
    private void takePhoto() {
        try {
            pendingPhoto = new File(captureDir, "IMG_" + System.currentTimeMillis() + ".jpg");
            Uri uri = FileProvider.getUriForFile(this, PROVIDER_AUTH, pendingPhoto);
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(intent, REQ_CAMERA);
        } catch (Exception e) {
            Toast.makeText(this, "无法启动相机：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAMERA && resultCode == RESULT_OK && pendingPhoto != null && pendingPhoto.exists()) {
            final File photo = pendingPhoto;
            pendingPhoto = null;
            // 红框识别可能较慢，后台处理
            new Thread(() -> {
                String note = "";
                try {
                    Bitmap bmp = BitmapFactory.decodeFile(photo.getAbsolutePath());
                    if (bmp != null) {
                        bmp = fixExifRotation(photo, bmp);
                        Bitmap cropped = RedBoxCrop.crop(bmp);
                        if (cropped != null && cropped.getWidth() > 20 && cropped.getHeight() > 20) {
                            try (FileOutputStream fos = new FileOutputStream(photo)) {
                                cropped.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                            }
                            cropped.recycle();
                            note = "已自动红框裁剪";
                        }
                        bmp.recycle();
                    }
                } catch (Exception e) {
                    note = "";
                }
                final String n = note;
                runOnUiThread(() -> {
                    Item it = new Item("photo", photo.getName(), null);
                    it.note = n;
                    items.add(it);
                    persist();
                    refreshList();
                    autoUpload();
                });
            }).start();
        } else if (pendingPhoto != null && !pendingPhoto.exists()) {
            pendingPhoto = null;
        }
    }

    /** 按 EXIF 方向旋转照片 */
    private static Bitmap fixExifRotation(File f, Bitmap bmp) {
        try {
            ExifInterface exif = new ExifInterface(f);
            int ori = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int deg = switch (ori) {
                case ExifInterface.ORIENTATION_ROTATE_90 -> 90;
                case ExifInterface.ORIENTATION_ROTATE_180 -> 180;
                case ExifInterface.ORIENTATION_ROTATE_270 -> 270;
                default -> 0;
            };
            if (deg != 0) {
                Matrix m = new Matrix();
                m.postRotate(deg);
                Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (r != bmp) bmp.recycle();
                return r;
            }
        } catch (Exception ignored) {}
        return bmp;
    }

    // ================= 写字 / 描边 =================
    private void addText() {
        final EditText input = new EditText(this);
        input.setHint("记下这个知识点…");
        input.setMinLines(4);
        input.setGravity(Gravity.TOP);
        new AlertDialog.Builder(this)
                .setTitle("✏️ 文字笔记")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String t = input.getText().toString().trim();
                    if (!t.isEmpty()) {
                        items.add(new Item("text", null, t));
                        persist();
                        refreshList();
                        autoUpload();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openDraw() {
        final DrawView draw = new DrawView(this);
        final LinearLayout colorBar = new LinearLayout(this);
        colorBar.setOrientation(LinearLayout.HORIZONTAL);
        colorBar.setPadding(8, 8, 8, 4);
        int[] colors = {Color.rgb(31, 45, 61), Color.rgb(229, 72, 77), Color.rgb(24, 160, 88), Color.rgb(47, 109, 246), Color.rgb(255, 159, 67)};
        for (int c : colors) {
            Button swatch = new Button(this);
            swatch.setBackgroundColor(c);
            swatch.setWidth(52);
            swatch.setHeight(52);
            swatch.setOnClickListener(v -> draw.setColor(c));
            colorBar.addView(swatch);
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(colorBar);
        box.addView(draw, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        new AlertDialog.Builder(this)
                .setTitle("🎨 描边（画完点保存）")
                .setView(box)
                .setPositiveButton("💾 保存", (d, w) -> draw.save(captureDir.getAbsolutePath()))
                .setNegativeButton("清空", (d, w) -> draw.clear())
                .setNeutralButton("✕ 取消", null)
                .show();
        draw.setOnSavedListener(file -> {
            if (file != null && file.exists()) {
                items.add(new Item("draw", file.getName(), null));
                persist();
                refreshList();
                autoUpload();
                Toast.makeText(this, "描边已保存 ✓", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ================= 持久化 =================
    private void persist() {
        try {
            JSONArray arr = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("type", it.type);
                o.put("status", it.status);
                o.put("note", it.note == null ? "" : it.note);
                if (it.file != null) o.put("file", it.file);
                if (it.text != null) o.put("text", it.text);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("scope", scopeInput.getText().toString().trim());
            root.put("items", arr);
            try (FileOutputStream fos = new FileOutputStream(itemsFile)) {
                fos.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Toast.makeText(this, "本地保存失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void loadItems() {
        items.clear();
        if (!itemsFile.exists()) return;
        try {
            String s = new String(java.nio.file.Files.readAllBytes(itemsFile.toPath()), StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(s);
            scopeInput.setText(root.optString("scope", "默认"));
            JSONArray arr = root.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Item it = new Item(o.optString("type", "text"), o.has("file") ? o.optString("file") : null, o.has("text") ? o.optString("text") : null);
                    it.status = o.optString("status", "local");
                    it.note = o.optString("note", "");
                    items.add(it);
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "本地读取失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ================= 列表 =================
    private void refreshList() {
        listBox.removeAllViews();
        countText.setText("共 " + items.size() + " 个条目 · " + (online ? "在线直传" : "离线"));
        if (items.isEmpty()) countText.setText("还没有条目，拍照 / 写字 / 描边，自动直传电脑待整理区");
        for (int i = items.size() - 1; i >= 0; i--) {
            listBox.addView(itemCard(items.get(i), i));
        }
    }

    private View itemCard(Item item, int index) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(10, 8, 10, 8);
        card.setBackgroundColor(Color.WHITE);
        card.setElevation(2);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = 10;
        card.setLayoutParams(lp);

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setAdjustViewBounds(true);
        int iconSize = 64;
        if (item.type.equals("photo") || item.type.equals("draw")) {
            File f = new File(captureDir, item.file);
            if (f.exists()) icon.setImageBitmap(BitmapFactory.decodeFile(f.getAbsolutePath()));
        } else {
            icon.setBackgroundColor(Color.rgb(234, 241, 255));
        }
        icon.setMinimumWidth(iconSize);
        icon.setMinimumHeight(iconSize);
        card.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

        TextView desc = new TextView(this);
        desc.setPadding(12, 0, 0, 0);
        String label = switch (item.type) {
            case "photo" -> "📷 照片";
            case "draw" -> "🎨 描边";
            default -> "✏️ 文字";
        };
        if (!item.note.isEmpty()) label += " " + item.note;
        String text = item.type.equals("text")
                ? (item.text.length() > 26 ? item.text.substring(0, 26) + "…" : item.text)
                : item.file;
        String st = item.status.equals("uploaded") ? " ✓已传待整理区" : " ⏳待传";
        desc.setText(label + "\n" + text + "\n" + st);
        desc.setTextSize(12);
        desc.setTextColor(Color.rgb(31, 45, 61));
        card.addView(desc, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button del = new Button(this);
        del.setText("🗑");
        del.setAllCaps(false);
        del.setBackgroundColor(Color.TRANSPARENT);
        final int idx = index;
        del.setOnClickListener(v -> {
            if (item.file != null) new File(captureDir, item.file).delete();
            items.remove(idx);
            persist();
            refreshList();
        });
        card.addView(del);
        return card;
    }

    // ================= 自动直传（每一条新增后触发） =================
    private void autoUpload() {
        if (!online || serverUrl.isEmpty()) return;
        // 收集所有未上传条目
        final List<Item> todo = new ArrayList<>();
        for (Item it : items) if (!it.status.equals("uploaded")) todo.add(it);
        if (todo.isEmpty()) return;

        final String scope = scopeInput.getText().toString().trim();
        connText.setText("正在直传 " + todo.size() + " 条…");
        new Thread(() -> {
            try {
                JSONObject meta = new JSONObject();
                meta.put("scope", scope.isEmpty() ? "默认" : scope);
                meta.put("title", "");
                JSONArray arr = new JSONArray();
                List<File> imgs = new ArrayList<>();
                for (Item it : todo) {
                    JSONObject o = new JSONObject();
                    o.put("type", it.type);
                    if (it.type.equals("text")) o.put("text", it.text == null ? "" : it.text);
                    else {
                        File f = new File(captureDir, it.file);
                        if (f.exists()) {
                            o.put("file", it.file);
                            imgs.add(f);
                        }
                    }
                    arr.put(o);
                }
                meta.put("items", arr);
                String resp = multipartUpload(serverUrl + "/api/capture", meta, imgs);
                JSONObject r = new JSONObject(resp);
                if (r.optBoolean("ok", false)) {
                    runOnUiThread(() -> {
                        for (Item it : todo) it.status = "uploaded";
                        persist();
                        refreshList();
                        connText.setText("● 已连电脑，每一条自动直传待整理区");
                    });
                } else {
                    runOnUiThread(() -> {
                        connText.setText("直传失败：" + r.optString("error", "未知错误") + "（已留在本地）");
                        Toast.makeText(this, "直传失败，内容保留在本地", Toast.LENGTH_LONG).show();
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    connText.setText("直传失败：" + e.getMessage() + "（已留在本地）");
                    Toast.makeText(this, "直传失败，内容保留在本地", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    /** 手写 multipart 上传：meta JSON + 多图 */
    private static String multipartUpload(String url, JSONObject meta, List<File> images) throws Exception {
        String boundary = "----nb" + System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
        body.write(("--" + boundary + CRLF).getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"meta\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(meta.toString().getBytes(StandardCharsets.UTF_8));
        body.write(CRLF);

        for (File f : images) {
            body.write(("--" + boundary + CRLF).getBytes(StandardCharsets.UTF_8));
            body.write(("Content-Disposition: form-data; name=\"images\"; filename=\"" + f.getName() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            body.write("Content-Type: image/jpeg\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            try (FileInputStream fis = new FileInputStream(f)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) > 0) body.write(buf, 0, n);
            }
            body.write(CRLF);
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toByteArray());
        }
        int code = conn.getResponseCode();
        String resp = new String(conn.getInputStream() == null ? new byte[0] : conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        conn.disconnect();
        if (code >= 400) throw new IOException("HTTP " + code + ": " + resp);
        return resp;
    }

    // ================= 导出 zip（离线兜底） =================
    private void exportZip() {
        if (items.isEmpty()) {
            Toast.makeText(this, "还没有条目", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date());
            File zipFile = new File(getCacheDir(), "nb_offline_" + stamp + ".zip");
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
                JSONObject manifest = new JSONObject();
                manifest.put("app", "nb-notebook");
                manifest.put("version", "1.0");
                manifest.put("created", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date()));
                JSONArray arr = new JSONArray();
                for (Item it : items) {
                    JSONObject o = new JSONObject();
                    o.put("type", it.type);
                    if (it.file != null) o.put("file", it.file);
                    if (it.text != null) o.put("text", it.text);
                    arr.put(o);
                }
                manifest.put("items", arr);
                zos.putNextEntry(new ZipEntry("manifest.json"));
                zos.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                for (Item it : items) {
                    if (it.file == null) continue;
                    File f = new File(captureDir, it.file);
                    if (!f.exists()) continue;
                    zos.putNextEntry(new ZipEntry(it.file));
                    try (FileInputStream fis = new FileInputStream(f)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = fis.read(buf)) > 0) zos.write(buf, 0, n);
                    }
                    zos.closeEntry();
                }
            }
            Uri uri = FileProvider.getUriForFile(this, PROVIDER_AUTH, zipFile);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/zip");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.putExtra(Intent.EXTRA_TEXT, "牛逼笔记本离线采集包，用电脑端网页「📦 手机包」导入");
            startActivity(Intent.createChooser(share, "发送到电脑"));
            Toast.makeText(this, "压缩包已生成 ✓ 选微信发送", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
