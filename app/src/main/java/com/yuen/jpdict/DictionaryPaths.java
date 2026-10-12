package com.yuen.jpdict;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.Locale;

/** Resource namespace rules shared by SAF, archives and WebView interception. */
final class DictionaryPaths {
    private DictionaryPaths() {}
    static String safe(String raw) throws IOException {
        if (raw == null || raw.length() > 4096 || raw.indexOf('\u0000') >= 0) throw new IOException("无效资源路径");
        String path = raw.replace('\\', '/');
        while (path.startsWith("/")) path = path.substring(1);
        if (path.contains(":") || path.isEmpty()) throw new IOException("资源路径必须位于词典内");
        StringBuilder result = new StringBuilder();
        for (String segment : path.split("/", -1)) {
            if (segment.equals("..")) throw new IOException("资源路径越过词典目录");
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (result.length() > 0) result.append('/');
            result.append(segment);
        }
        if (result.length() == 0) throw new IOException("无效资源路径");
        return result.toString();
    }
    static String urlPath(String raw) throws IOException {
        try {
            // URLDecoder is form-based: literal '+' must remain a path character.
            return safe(URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8"));
        } catch (IllegalArgumentException error) { throw new IOException("无效 URL 编码", error); }
    }
    static String mime(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "text/javascript";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".spx")) return "audio/ogg";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".otf")) return "font/otf";
        return "application/octet-stream";
    }
}
