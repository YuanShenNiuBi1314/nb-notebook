package com.nbnotebook.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 极简 HTTP 客户端：GET / POST JSON / 下载字节。
 * 所有方法同步执行，调用方自行放后台线程。
 */
public final class Api {

    private Api() {}

    public static String get(String url) throws IOException {
        return new String(getBytes(url), StandardCharsets.UTF_8);
    }

    public static String postJson(String url, String json) throws IOException {
        HttpURLConnection conn = open(url, "POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(conn);
    }

    public static byte[] getBytes(String url) throws IOException {
        HttpURLConnection conn = open(url, "GET");
        return readBytes(conn);
    }

    private static HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(30000);
        return conn;
    }

    private static String readResponse(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (in == null) throw new IOException("HTTP " + code + "，服务器无响应");
        byte[] b = readAll(in);
        String body = new String(b, StandardCharsets.UTF_8);
        if (code >= 400) throw new IOException("HTTP " + code + ": " + body);
        return body;
    }

    private static byte[] readBytes(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        if (code >= 400) {
            InputStream err = conn.getErrorStream();
            String msg = err == null ? "" : new String(readAll(err), StandardCharsets.UTF_8);
            throw new IOException("HTTP " + code + ": " + msg);
        }
        InputStream in = conn.getInputStream();
        return in == null ? new byte[0] : readAll(in);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (in; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }
}
