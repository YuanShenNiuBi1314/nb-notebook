package com.nbnotebook.app;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * 音视频下载器：保存到文件管理器可见的专属文件夹「Download/NBNotebook/」。
 * - Android 10+ (API 29+)：MediaStore.Downloads 写入，无需存储权限。
 * - Android 9-：公共 Download/NBNotebook 目录，需要 WRITE_EXTERNAL_STORAGE 权限。
 */
public final class MediaDownloader {

    public static final String DIR_NAME = "NBNotebook";
    private static final String TAG = "MediaDownloader";

    private MediaDownloader() {}

    /** 文件夹显示名（提示用） */
    public static String displayPath() {
        return "Download/" + DIR_NAME + "/";
    }

    /**
     * 下载并保存单个文件。
     * @return 保存路径描述；失败返回 null（已 toast）
     */
    public static String download(Context ctx, String url, String fileName) {
        try {
            byte[] data = Api.getBytes(url);
            String name = sanitize(fileName);
            if (name.isEmpty()) name = "media_" + System.currentTimeMillis();
            // 确保有扩展名
            if (!name.contains(".")) name += guessExt(url);

            if (Build.VERSION.SDK_INT >= 29) {
                return saveViaMediaStore(ctx, data, name);
            } else {
                return saveViaFile(ctx, data, name);
            }
        } catch (Exception e) {
            Log.e(TAG, "下载失败 " + url, e);
            Toast.makeText(ctx, "下载失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
            return null;
        }
    }

    /** API 29+：MediaStore Downloads 集合 */
    private static String saveViaMediaStore(Context ctx, byte[] data, String name) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, mime(name));
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + DIR_NAME);
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new Exception("无法创建文件");
        try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new Exception("无法打开输出流");
            os.write(data);
        }
        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        ctx.getContentResolver().update(uri, values, null, null);
        return "Download/" + DIR_NAME + "/" + name;
    }

    /** API < 29：公共 Download 目录 */
    private static String saveViaFile(Context ctx, byte[] data, String name) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("无法创建目录 " + dir);
        File f = new File(dir, name);
        int i = 1;
        while (f.exists()) {
            String base = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
            f = new File(dir, base + "_" + (i++) + ext);
        }
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        }
        return f.getAbsolutePath();
    }

    private static String sanitize(String s) {
        if (s == null) return "";
        String r = s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_").trim();
        if (r.length() > 80) r = r.substring(r.length() - 80);
        return r;
    }

    private static String guessExt(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        if (u.contains(".mp4")) return ".mp4";
        if (u.contains(".webm")) return ".webm";
        if (u.contains(".mp3")) return ".mp3";
        if (u.contains(".m4a")) return ".m4a";
        if (u.contains(".wav")) return ".wav";
        if (u.contains(".ogg")) return ".ogg";
        return ".bin";
    }

    private static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".ogg")) return "audio/ogg";
        return "application/octet-stream";
    }
}
