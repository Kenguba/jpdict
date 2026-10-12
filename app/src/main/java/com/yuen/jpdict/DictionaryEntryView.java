package com.yuen.jpdict;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.*;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.List;

/** Untrusted dictionary HTML renderer; the Java engine stays outside WebView. */
final class DictionaryEntryView extends WebView {
    interface Navigation { void lookup(String word); }
    private final DictionaryRepository repository;
    private final DictionaryRepository.Dictionary dictionary;
    private final Navigation navigation;
    private final String origin, entryUrl;
    private byte[] entryHtml;
    private static final String POLICY = "default-src 'none'; style-src 'unsafe-inline' 'self'; "
            + "script-src 'unsafe-inline' 'unsafe-eval' 'self'; img-src 'self' data:; font-src 'self' data:; "
            + "media-src 'self'; connect-src 'self'; frame-src 'none'; object-src 'none'; "
            + "base-uri 'self'; form-action 'none'; worker-src 'none'; child-src 'none'";
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private DictionaryScheduler.Ticket soundTask;
    private MediaPlayer player;
    private File soundFile;
    private int soundGeneration;

    DictionaryEntryView(Context context, DictionaryRepository repository,
            DictionaryRepository.Dictionary dictionary, List<String> entries, Navigation navigation) {
        super(context);
        this.repository = repository; this.dictionary = dictionary; this.navigation = navigation;
        origin = "https://" + dictionary.id + ".dict.invalid/";
        entryUrl = origin + "__jpdict_entry__.html";
        WebSettings settings = getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setGeolocationEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setBlockNetworkLoads(true);
        settings.setDomStorageEnabled(false);
        settings.setSaveFormData(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setSupportMultipleWindows(false);
        setFocusable(false);
        setBackgroundColor(android.graphics.Color.WHITE);
        setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (closed || !"GET".equals(request.getMethod())) return denied();
                try {
                    if (request.isForMainFrame()) {
                        return entryUrl.equals(request.getUrl().toString())
                                ? response("text/html", new ByteArrayInputStream(entryHtml), true) : denied();
                    }
                    String path = resourcePath(request.getUrl());
                    InputStream stream = repository.resourceStream(dictionary, path);
                    return stream == null ? denied() : response(DictionaryPaths.mime(path), stream, false);
                } catch (IOException | RuntimeException failure) { return denied(); }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return navigate(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return navigate(Uri.parse(url)); }
        });
        StringBuilder html = new StringBuilder("<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta http-equiv=\"Content-Security-Policy\" content=\"")
                .append(POLICY).append("\"><style>body{margin:12px;color:#171819;overflow-wrap:anywhere}img{max-width:100%;height:auto}audio{max-width:100%}</style>");
        for (DictionaryRepository.Document file : dictionary.files) {
            if (file.path.toLowerCase(java.util.Locale.ROOT).endsWith(".css") && file.path.indexOf('/') < 0) {
                if (html.length() < 65536) html.append("<link rel=\"stylesheet\" href=\"").append(Uri.encode(file.path)).append("\">");
            }
        }
        html.append("</head><body>");
        for (String entry : entries) {
            html.append("<section>").append(entry).append("</section>");
        }
        html.append("</body></html>");
        entryHtml = html.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        loadUrl(entryUrl);
    }
    private static WebResourceResponse response(String mime, InputStream stream, boolean document) {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Cache-Control", "no-store"); headers.put("X-Content-Type-Options", "nosniff");
        if (document) headers.put("Content-Security-Policy", POLICY);
        return new WebResourceResponse(mime, mime.startsWith("text/") ? "UTF-8" : null, 200, "OK", headers, stream);
    }
    private String resourcePath(Uri uri) throws IOException {
        if (!"https".equals(uri.getScheme()) || !(dictionary.id + ".dict.invalid").equals(uri.getHost())
                || uri.getPort() != -1 || uri.getUserInfo() != null) throw new IOException("禁止外部资源");
        String path = uri.getEncodedPath();
        // Each dictionary has its own origin; absolute and relative assets share its root.
        return DictionaryPaths.urlPath(path);
    }
    private boolean navigate(Uri uri) {
        if (closed) return true;
        if (entryUrl.equals(uri.buildUpon().fragment(null).build().toString()) && uri.getFragment() != null) return false;
        try {
            if ("entry".equalsIgnoreCase(uri.getScheme())) {
                String word = uri.toString().substring("entry://".length());
                navigation.lookup(URLDecoder.decode(word.replace("+", "%2B"), "UTF-8"));
            } else if ("sound".equalsIgnoreCase(uri.getScheme())) {
                play(DictionaryPaths.urlPath(uri.toString().substring("sound://".length())));
            } else if ("https".equals(uri.getScheme()) && (dictionary.id + ".dict.invalid").equals(uri.getHost())) {
                String path = resourcePath(uri);
                if (!path.contains(".")) navigation.lookup(path);
            }
        } catch (IOException | RuntimeException invalid) { android.widget.Toast.makeText(getContext(), invalid.getMessage(), android.widget.Toast.LENGTH_SHORT).show(); }
        return true;
    }
    private static WebResourceResponse denied() {
        return new WebResourceResponse("text/plain", "UTF-8", 404, "Unavailable",
                Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }
    private void play(String path) {
        int generation = ++soundGeneration;
        if (soundTask != null) soundTask.cancel();
        releaseSound();
        soundTask = repository.queries.submit(0, () -> {
            File temporary = null;
            try {
                byte[] data = repository.resource(dictionary, path);
                if (data == null) throw new IOException("词典音频不存在");
                temporary = File.createTempFile("dict-audio-", ".audio", getContext().getCacheDir());
                try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(data); }
                MdictReader.checkCancelled();
                File ready = temporary;
                main.post(() -> {
                    if (closed || soundGeneration != generation) { ready.delete(); return; }
                    releaseSound(); soundFile = ready;
                    player = new MediaPlayer();
                    try {
                        player.setDataSource(ready.getPath());
                        player.setOnPreparedListener(MediaPlayer::start);
                        player.setOnCompletionListener(p -> releaseSound());
                        player.setOnErrorListener((p, what, extra) -> { releaseSound(); return true; });
                        player.prepareAsync();
                    } catch (IOException error) { releaseSound(); }
                });
                temporary = null;
            } catch (IOException error) {
                main.post(() -> { if (!closed) android.widget.Toast.makeText(getContext(), "音频读取失败：" + error.getMessage(), android.widget.Toast.LENGTH_SHORT).show(); });
            } finally { if (temporary != null) temporary.delete(); }
        });
    }
    private void releaseSound() {
        if (player != null) { player.release(); player = null; }
        if (soundFile != null) { soundFile.delete(); soundFile = null; }
    }
    void close() {
        if (closed) return;
        closed = true;
        if (soundTask != null) soundTask.cancel();
        releaseSound(); stopLoading(); loadUrl("about:blank");
        entryHtml = null;
        setWebViewClient(new WebViewClient()); removeAllViews(); destroy();
    }
}
