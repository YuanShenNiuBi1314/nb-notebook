package com.nbnotebook.app;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 离线采集：拍照 / 写字 / 描边 → 打包 zip → 微信发送到电脑。
 * 不依赖网络，与服务器无关。
 */
public class OfflineCaptureActivity extends AppCompatActivity {

    private static final int REQ_CAMERA = 1001;
    private static final String PROVIDER_AUTH = "com.nbnotebook.app.fileprovider";

    private File captureDir;          // 条目文件目录
    private File pendingPhoto;        // 等待确认的拍照文件
    private final List<Item> items = new ArrayList<>();
    private LinearLayout listBox;
    private TextView countText;

    private static final class Item {
        final String type;       // photo | text | draw
        String file;             // 相对文件名（null 表示无）
        String text;             // 文字内容
        Item(String type, String file, String text) {
            this.type = type; this.file = file; this.text = text;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        captureDir = new File(getFilesDir(), "capture");
        if (!captureDir.exists()) captureDir.mkdirs();

        // ===== 顶部 =====
        TextView title = new TextView(this);
        title.setText("📴 离线采集");
        title.setTextSize(20);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(18, 0, 0, 0);
        title.setBackgroundColor(Color.rgb(30, 42, 58));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText("← 返回");
        back.setAllCaps(false);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setTextColor(Color.WHITE);
        back.setOnClickListener(v -> finish());
        titleRow.addView(back, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.2f));
        titleRow.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2.5f));
        titleRow.setMinimumHeight(54);

        // ===== 条目列表 =====
        countText = new TextView(this);
        countText.setText("还没有条目");
        countText.setTextColor(Color.rgb(107, 119, 133));
        countText.setPadding(18, 12, 18, 4);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(12, 4, 12, 8);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(listBox);

        // ===== 底部操作栏 =====
        Button btnCamera = bigBtn("📷 拍照");
        Button btnText = bigBtn("✏️ 写字");
        Button btnDraw = bigBtn("🎨 描边");
        Button btnExport = bigBtn("📦 导出压缩包");
        btnExport.setBackgroundColor(Color.rgb(47, 109, 246));

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
        bottom.setPadding(14, 10, 14, 18);
        bottom.addView(row1);
        LinearLayout.LayoutParams row2lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row2lp.topMargin = 10;
        bottom.addView(row2, row2lp);

        TextView tip = new TextView(this);
        tip.setText("采集完成后「导出压缩包」→ 选微信发送到电脑 → 电脑端网页点「📦 导入手机包」自动整理");
        tip.setTextSize(11);
        tip.setTextColor(Color.rgb(150, 150, 150));
        tip.setPadding(18, 0, 18, 12);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bottom.addView(tip, tipLp);

        // ===== 根布局 =====
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(245, 247, 250));
        root.addView(titleRow);
        root.addView(countText);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bottom);
        setContentView(root);

        btnCamera.setOnClickListener(v -> takePhoto());
        btnText.setOnClickListener(v -> addText());
        btnDraw.setOnClickListener(v -> openDraw());
        btnExport.setOnClickListener(v -> exportZip());

        refreshList();
    }

    private Button bigBtn(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackgroundColor(Color.rgb(80, 96, 116));
        b.setPadding(0, 14, 0, 14);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = 8;
        b.setLayoutParams(lp);
        return b;
    }

    // ================= 拍照 =================
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
            items.add(new Item("photo", pendingPhoto.getName(), null));
            pendingPhoto = null;
            refreshList();
        } else if (pendingPhoto != null && !pendingPhoto.exists()) {
            pendingPhoto = null;
        }
    }

    // ================= 写字 =================
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
                        refreshList();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================= 描边 =================
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
        draw.setBackgroundColor(Color.WHITE);
        draw.setPadding(0, 0, 0, 0);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(colorBar);
        box.addView(draw, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        new AlertDialog.Builder(this)
                .setTitle("🎨 描边（画完点保存）")
                .setView(box)
                .setPositiveButton("💾 保存", (d, w) -> {
                    draw.save(captureDir.getAbsolutePath());
                    // save 回调里添加条目
                })
                .setNegativeButton("清空", (d, w) -> draw.clear())
                .setNeutralButton("✕ 取消", null)
                .show();
        draw.setOnSavedListener(file -> {
            if (file != null && file.exists()) {
                items.add(new Item("draw", file.getName(), null));
                refreshList();
                Toast.makeText(this, "描边已保存 ✓", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ================= 列表 =================
    private void refreshList() {
        listBox.removeAllViews();
        countText.setText("共 " + items.size() + " 个条目（离线保存，不依赖网络）");
        if (items.isEmpty()) {
            countText.setText("还没有条目，点下面按钮开始采集");
        }
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

        // 缩略图或图标
        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setAdjustViewBounds(true);
        int iconSize = 64;
        if (item.type.equals("photo") || item.type.equals("draw")) {
            File f = new File(captureDir, item.file);
            if (f.exists()) {
                icon.setImageBitmap(BitmapFactory.decodeFile(f.getAbsolutePath()));
            }
        } else {
            icon.setBackgroundColor(Color.rgb(234, 241, 255));
        }
        icon.setMinimumWidth(iconSize);
        icon.setMinimumHeight(iconSize);
        card.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));

        // 描述
        TextView desc = new TextView(this);
        desc.setPadding(12, 0, 0, 0);
        String label = switch (item.type) {
            case "photo" -> "📷 照片";
            case "draw" -> "🎨 描边图";
            default -> "✏️ 文字";
        };
        String text = item.type.equals("text")
                ? (item.text.length() > 30 ? item.text.substring(0, 30) + "…" : item.text)
                : item.file;
        desc.setText(label + "\n" + text);
        desc.setTextSize(13);
        desc.setTextColor(Color.rgb(31, 45, 61));
        card.addView(desc, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 删除
        Button del = new Button(this);
        del.setText("🗑");
        del.setAllCaps(false);
        del.setBackgroundColor(Color.TRANSPARENT);
        final int idx = index;
        del.setOnClickListener(v -> {
            if (item.file != null) new File(captureDir, item.file).delete();
            items.remove(idx);
            refreshList();
        });
        card.addView(del);
        return card;
    }

    // ================= 导出 zip =================
    private void exportZip() {
        if (items.isEmpty()) {
            Toast.makeText(this, "还没有条目，先采集一些吧", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date());
            File zipFile = new File(getCacheDir(), "nb_offline_" + stamp + ".zip");
            buildZip(zipFile);
            if (!zipFile.exists() || zipFile.length() == 0) {
                Toast.makeText(this, "打包失败", Toast.LENGTH_SHORT).show();
                return;
            }
            // 微信 / 任意应用分享
            Uri uri = FileProvider.getUriForFile(this, PROVIDER_AUTH, zipFile);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("application/zip");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.putExtra(Intent.EXTRA_TEXT, "牛逼笔记本离线采集包，用电脑端网页「📦 导入手机包」导入");
            startActivity(Intent.createChooser(share, "发送到电脑"));
            Toast.makeText(this, "压缩包已生成 ✓ 选微信发送", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void buildZip(File zipFile) throws Exception {
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            // manifest.json
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
            zos.write(manifest.toString().getBytes("UTF-8"));
            zos.closeEntry();

            // 条目文件
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
    }
}
