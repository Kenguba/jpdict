package com.yuen.jpdict;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Read-only compatibility for the two remote resources used by workClick.js. */
final class DictionaryRemoteClient implements Closeable {
    static final String NOTE_URL = "https://api.frdic.com/api/open/v1/studylist/note";
    static final String DEBUG_URL = "https://cdn.jsdelivr.net/npm/eruda";
    private static final String DEBUG_PIN = "https://cdnjs.cloudflare.com/ajax/libs/eruda/3.4.3/eruda.min.js";
    private static final String DEBUG_FALLBACK = "https://raw.githubusercontent.com/cdnjs/cdnjs/master/ajax/libs/eruda/3.4.3/eruda.min.js";
    private static final String DEBUG_SHA256 = "5fc9610c3f6443d7d9089b8e14b42060f5ddf50ea95e4e8d1abbd170a4f38731";
    static final int MAX_BYTES = 512 * 1024;
    private static volatile byte[] debugCache;
    interface Connections { HttpURLConnection open(URL url) throws IOException; }
    static final class Response {
        final String mime;
        final int status;
        final Map<String, String> headers;
        final byte[] body;
        final String error;
        Response(String mime, int status, Map<String, String> headers, byte[] body, String error) {
            this.mime = mime; this.status = status; this.headers = headers; this.body = body; this.error = error;
        }
    }
    private final String origin;
    private final Connections connections;
    private final Set<HttpURLConnection> active = new HashSet<>();
    private boolean closed;
    DictionaryRemoteClient(String origin) { this(origin, url -> (HttpURLConnection) url.openConnection()); }
    DictionaryRemoteClient(String origin, Connections connections) {
        this.origin = origin; this.connections = connections;
    }

    /** null means the URL is outside the exact allowlist; never delegates to WebView networking. */
    Response request(String method, String url, Map<String, String> requestHeaders) {
        String note = noteUrl(url);
        boolean debug = DEBUG_URL.equals(url);
        if (note == null && !debug) return null;
        boolean preflight = note != null && "OPTIONS".equals(method);
        if (!"GET".equals(method) && !preflight) return null;
        String requester = header(requestHeaders, "Origin");
        if (requester != null && !origin.equals(requester)) return null;
        if (preflight) {
            if (!"GET".equals(header(requestHeaders, "Access-Control-Request-Method"))) return null;
            String requested = header(requestHeaders, "Access-Control-Request-Headers");
            if (requested != null) for (String name : requested.split(","))
                if (!"authorization".equalsIgnoreCase(name.trim()) && !"content-type".equalsIgnoreCase(name.trim())) return null;
            return response(false, 204, new byte[0], null);
        }
        synchronized (active) { if (closed) return response(debug, 410, new byte[0], null); }
        String authorization = note == null ? null : header(requestHeaders, "Authorization");
        if (note != null && (authorization == null || authorization.trim().isEmpty()
                || authorization.length() > 4096 || !plainText(authorization)))
            return failure(false, 401, "欧路笔记需要词库内的授权令牌，请检查授权令牌后重试");
        try {
            long deadline = System.nanoTime() + 20_000_000_000L;
            if (debug) {
                byte[] cached = debugCache;
                if (cached != null) return response(true, 200, cached, null);
                try {
                    Response result = download(DEBUG_PIN, null, true, deadline);
                    if (result.status == 200) return result;
                } catch (IOException failure) { /* The fixed mirror must pass the same hash. */ }
                return download(DEBUG_FALLBACK, null, true, deadline);
            }
            return download(note, authorization, false, deadline);
        } catch (IOException | RuntimeException failure) {
            // Neither credentials, request URLs nor upstream error bodies enter diagnostics.
            return failure(debug, 502, debug ? "词库调试脚本加载失败，请检查网络后重试" : "欧路笔记连接失败，请检查网络后重试");
        }
    }

    private Response download(String url, String authorization, boolean debug, long deadline) throws IOException {
        HttpURLConnection connection = connections.open(new URL(url));
        synchronized (active) {
            if (closed || active.size() >= 2) { connection.disconnect(); throw new IOException("Unavailable"); }
            active.add(connection);
        }
        try {
            int timeout = Math.min(8000, remaining(deadline));
            connection.setConnectTimeout(timeout); connection.setReadTimeout(timeout);
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", debug ? "application/javascript" : "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (authorization != null) connection.setRequestProperty("Authorization", authorization);
            int status = connection.getResponseCode();
            // Never carry a dictionary token to a redirect or render an upstream HTML error page.
            if (status >= 300 && status < 400) throw new IOException("Redirect forbidden");
            if (status < 200 || status >= 300) {
                String error = debug ? "词库调试脚本请求失败：HTTP " + status
                        : status == 401 ? "欧路授权令牌无效或已过期，请在词库内更新授权令牌"
                        : status == 403 || status == 429 ? "欧路笔记请求过于频繁，请稍后重试"
                        : "欧路笔记请求失败：HTTP " + status;
                return failure(debug, status >= 400 && status <= 599 ? status : 502, error);
            }
            if (connection.getContentLength() > MAX_BYTES) throw new IOException("Response too large");
            String type = connection.getContentType();
            if (!debug && (type == null || !"application/json".equalsIgnoreCase(type.split(";", 2)[0].trim())))
                throw new IOException("Unexpected response format");
            byte[] body;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                while (true) {
                    connection.setReadTimeout(Math.min(8000, remaining(deadline)));
                    synchronized (active) { if (closed) throw new IOException("Closed"); }
                    int count = input.read(buffer);
                    if (count < 0) break;
                    if (output.size() + count > MAX_BYTES) throw new IOException("Response too large");
                    output.write(buffer, 0, count);
                }
                body = output.toByteArray();
            }
            if (debug) {
                if (!validDebugScript(body)) throw new IOException("Integrity mismatch");
                debugCache = body;
            }
            return response(debug, 200, body, null);
        } finally {
            synchronized (active) { active.remove(connection); }
            connection.disconnect();
        }
    }
    private Response failure(boolean debug, int status, String error) {
        // Native UI reports the error even when the dictionary script ignores HTTP status.
        return response(debug, status, (debug ? "/* Script unavailable */" : "{\"message\":\"" + error + "\"}")
                .getBytes(StandardCharsets.UTF_8), error);
    }
    private Response response(boolean debug, int status, byte[] body, String error) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-store"); headers.put("X-Content-Type-Options", "nosniff");
        headers.put("Access-Control-Allow-Origin", origin); headers.put("Vary", "Origin");
        headers.put("Access-Control-Allow-Methods", "GET");
        headers.put("Access-Control-Allow-Headers", "authorization, content-type");
        return new Response(debug ? "application/javascript" : "application/json", status, headers, body, error);
    }
    static String noteUrl(String url) {
        try {
            URI uri = new URI(url);
            if (!"https".equals(uri.getScheme()) || !"api.frdic.com".equalsIgnoreCase(uri.getHost())
                    || uri.getPort() != -1 || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || !"/api/open/v1/studylist/note".equals(uri.getRawPath())) return null;
            String query = uri.getRawQuery();
            if (query == null || query.length() > 4096) return null;
            Map<String, String> values = new HashMap<>();
            for (String pair : query.split("&", -1)) {
                int equals = pair.indexOf('='); if (equals < 1) return null;
                String key = URLDecoder.decode(pair.substring(0, equals), "UTF-8");
                if (!"language".equals(key) && !"word".equals(key)) return null;
                if (values.put(key, URLDecoder.decode(pair.substring(equals + 1), "UTF-8")) != null) return null;
            }
            String language = values.get("language"), word = values.get("word");
            if (values.size() != 2 || !("en".equals(language) || "fr".equals(language)
                    || "de".equals(language) || "es".equals(language)) || word == null || word.trim().isEmpty()
                    || word.length() > 512 || !plainText(word)) return null;
            return NOTE_URL + "?language=" + language + "&word=" + URLEncoder.encode(word, "UTF-8");
        } catch (Exception invalid) { return null; }
    }
    private static String header(Map<String, String> headers, String name) {
        if (headers == null) return null;
        for (Map.Entry<String, String> header : headers.entrySet())
            if (name.equalsIgnoreCase(header.getKey())) return header.getValue();
        return null;
    }
    private static boolean plainText(String text) {
        for (int i = 0; i < text.length(); i++) if (Character.isISOControl(text.charAt(i))) return false;
        return true;
    }
    private static int remaining(long deadline) throws IOException {
        long left = (deadline - System.nanoTime()) / 1_000_000L;
        if (left <= 0) throw new IOException("Timeout");
        return (int) left;
    }
    static boolean validDebugScript(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte value : hash) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return DEBUG_SHA256.equals(hex.toString());
        } catch (Exception unavailable) { return false; }
    }
    @Override public void close() {
        synchronized (active) {
            closed = true;
            for (HttpURLConnection connection : active) connection.disconnect();
            active.clear();
        }
    }
}
