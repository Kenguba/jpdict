package com.yuen.jpdict;

import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
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
    interface Navigation {
        void lookup(String word);
        default void editingChanged(DictionaryEntryView editor, boolean editing) { }
        default boolean closeOnBack() { return false; }
    }
    private final DictionaryRepository repository;
    private final DictionaryRepository.Dictionary dictionary;
    private final Navigation navigation;
    private final String origin, entryUrl;
    private final Runnable onReady;
    private final DictionaryRemoteClient remote;
    private String remoteError;
    private long remoteErrorAt;
    private boolean ready;
    private boolean editing;
    private int editingProbe;
    // Touch callbacks run on the UI thread; a newer dictionary touch owns IME.
    private static long touches;
    private long latestTouch;
    private float editorTouchX, editorTouchY;
    private int editorTouchWidth;
    private boolean touchEditorPending, focusingEditor;
    private volatile byte[] entryHtml;
    private volatile DictionaryHtmlDocument document;
    private static final String POLICY = DictionaryHtmlDocument.POLICY;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private DictionaryScheduler.Ticket soundTask;
    private MediaPlayer player;
    private File soundFile;
    private int soundGeneration;
    private boolean heightPending;
    private final Runnable measureHeight = this::measureContentHeight;

    DictionaryEntryView(Context context, DictionaryRepository repository,
            DictionaryRepository.Dictionary dictionary, List<String> entries, Navigation navigation) {
        this(context, repository, dictionary, entries, navigation, null);
    }
    DictionaryEntryView(Context context, DictionaryRepository repository,
            DictionaryRepository.Dictionary dictionary, List<String> entries, Navigation navigation, Runnable onReady) {
        this(context, repository, dictionary, entries, null, navigation, onReady, null);
    }
    DictionaryEntryView(Context context, DictionaryRepository repository,
            DictionaryRepository.Dictionary dictionary, List<String> entries, Navigation navigation,
            Runnable onReady, DictionaryRemoteClient.Connections connections) {
        this(context, repository, dictionary, entries, null, navigation, onReady, connections);
    }
    DictionaryEntryView(Context context, DictionaryRepository repository,
            DictionaryRepository.Dictionary dictionary, DictionaryHtmlDocument document, Navigation navigation, Runnable onReady) {
        this(context, repository, dictionary, null, document, navigation, onReady, null);
    }
    private DictionaryEntryView(Context context, DictionaryRepository repository,
            DictionaryRepository.Dictionary dictionary, List<String> entries, DictionaryHtmlDocument document,
            Navigation navigation, Runnable onReady, DictionaryRemoteClient.Connections connections) {
        super(new android.view.ContextThemeWrapper(context, android.R.style.Theme_Material_Light_NoActionBar));
        this.document = document == null ? null : document.retain();
        this.repository = repository; this.dictionary = dictionary; this.navigation = navigation;
        this.onReady = onReady;
        origin = "https://" + dictionary.id + ".dict.invalid/";
        entryUrl = origin + "__jpdict_entry__.html";
        remote = connections == null ? new DictionaryRemoteClient(origin.substring(0, origin.length() - 1))
                : new DictionaryRemoteClient(origin.substring(0, origin.length() - 1), connections);
        WebSettings settings = getSettings();
        // The native shell can change theme without changing the dictionary author's colors.
        if (android.os.Build.VERSION.SDK_INT >= 33) settings.setAlgorithmicDarkeningAllowed(false);
        if (android.os.Build.VERSION.SDK_INT >= 29) setForceDarkAllowed(false);
        settings.setJavaScriptEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setGeolocationEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setBlockNetworkLoads(true);
        settings.setDomStorageEnabled(true); // Tokens remain isolated by each dictionary's private HTTPS origin.
        settings.setSaveFormData(false);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setSupportMultipleWindows(false);
        // The containing overlay stays NOT_FOCUSABLE until a touched HTML editor
        // is active. WebView itself must accept focus for its native IME adapter.
        setFocusable(true);
        setFocusableInTouchMode(true);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setBackgroundColor(android.graphics.Color.WHITE);
        setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (!closed && !ready && entryUrl.equals(url)) {
                    ready = true;
                    if (DictionaryEntryView.this.onReady != null) DictionaryEntryView.this.onReady.run();
                }
                scheduleHeight();
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (closed) return denied();
                if (!request.isForMainFrame()) {
                    DictionaryRemoteClient.Response allowed = remote.request(request.getMethod(),
                            request.getUrl().toString(), request.getRequestHeaders());
                    if (allowed != null) {
                        if (allowed.error != null) main.post(() -> {
                            long now = android.os.SystemClock.uptimeMillis();
                            if (!closed && (!allowed.error.equals(remoteError) || now - remoteErrorAt >= 4000)) {
                                remoteError = allowed.error;
                                remoteErrorAt = now;
                                android.widget.Toast.makeText(getContext(), allowed.error, android.widget.Toast.LENGTH_LONG).show();
                            }
                        });
                        return new WebResourceResponse(allowed.mime, "UTF-8", allowed.status,
                                allowed.status < 400 ? "OK" : "Unavailable", allowed.headers,
                                new ByteArrayInputStream(allowed.body));
                    }
                }
                if (!"GET".equals(request.getMethod())) return denied();
                try {
                    if (request.isForMainFrame()) {
                        return entryUrl.equals(request.getUrl().toString())
                                ? response("text/html", DictionaryEntryView.this.document == null
                                    ? new ByteArrayInputStream(entryHtml) : DictionaryEntryView.this.document.open(), true) : denied();
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
        if (document == null) {
            StringWriter html = new StringWriter();
            try { DictionaryHtmlDocument.header(html, dictionary); }
            catch (IOException impossible) { throw new IllegalStateException(impossible); }
            for (String entry : entries) { html.write("<section>"); html.write(entry); html.write("</section>"); }
            html.write("</body></html>");
            entryHtml = html.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        loadUrl(entryUrl);
    }
    boolean isReady() { return ready; }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            editingProbe++; touchEditorPending = focusingEditor = false;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { latestTouch = ++touches; requestFocus(); }
        }
        boolean handled = super.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            int probe = ++editingProbe;
            editorTouchX = event.getX(); editorTouchY = event.getY(); editorTouchWidth = getWidth();
            touchEditorPending = true;
            post(() -> {
                if (closed || probe != editingProbe || latestTouch != touches || editorTouchWidth <= 0) return;
                // A NOT_FOCUSABLE window may not activate the DOM on its own.
                // Detect the touched editor before asking the owner for focus.
                // Only a boolean leaves WebView; no values or Android bridge.
                evaluateJavascript(editorAtTouch(false), value -> {
                    if (!closed && probe == editingProbe && latestTouch == touches) {
                        // A later native headword/Key edit owns focus even though
                        // it does not increment the dictionary touch sequence.
                        if (!(getRootView().findFocus() instanceof android.widget.EditText)) setEditing("true".equals(value));
                    }
                });
            });
        }
        return handled;
    }
    private void setEditing(boolean value) {
        if (!value) { stopEditing(); return; }
        if (editing != value) {
            editing = value;
            navigation.editingChanged(this, value);
        }
        requestFocus(); showEditorKeyboard();
    }
    private void showEditorKeyboard() {
        post(() -> {
            if (closed || !editing || latestTouch != touches || !hasWindowFocus() || !isFocused()) return;
            if (focusingEditor) return;
            int probe = editingProbe;
            if (touchEditorPending) {
                focusingEditor = true;
                // Focus the actual clicked node after the window can own an IME.
                evaluateJavascript(editorAtTouch(true), value -> {
                    if (closed || !editing || probe != editingProbe || latestTouch != touches || !isFocused()) return;
                    touchEditorPending = focusingEditor = false;
                    if ("true".equals(value)) post(() -> openEditorKeyboard(probe));
                    else stopEditing();
                });
            } else openEditorKeyboard(probe);
        });
    }
    private String editorAtTouch(boolean focus) {
        // Native touch coordinates refer to the visible viewport. VisualViewport
        // also accounts for page zoom; elementFromPoint accounts for DOM scrolling.
        return "(function(){var v=window.visualViewport,s=(v?v.width:innerWidth)/" + editorTouchWidth
                + ",x=" + editorTouchX + "*s+(v?v.offsetLeft:0),y=" + editorTouchY + "*s+(v?v.offsetTop:0);"
                + "var e=document.elementFromPoint(x,y);if(e&&e.tagName==='LABEL'&&e.control)e=e.control;"
                + "var ok=!!(e&&!e.disabled&&!e.readOnly&&(e.isContentEditable||e.tagName==='TEXTAREA'||"
                + "(e.tagName==='INPUT'&&/^(text|search|email|url|tel|number|password)$/.test(e.type))));"
                + (focus ? "if(ok){while(e.parentElement&&e.parentElement.isContentEditable)e=e.parentElement;"
                    + "try{e.focus({preventScroll:true});}catch(ignore){e.focus();}}" : "")
                + "return ok;})()";
    }
    private void openEditorKeyboard(int probe) {
        if (closed || !editing || probe != editingProbe || latestTouch != touches || !hasWindowFocus() || !isFocused()) return;
        InputMethodManager ime = (InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (ime != null) { ime.restartInput(this); ime.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT); }
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused && editing) showEditorKeyboard();
    }
    @Override protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        if (!focused) { editingProbe++; touchEditorPending = focusingEditor = false; if (editing) stopEditing(); }
    }
    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility != VISIBLE && editing) stopEditing();
    }
    @Override public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (editing && keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()
                && navigation.closeOnBack()) return true;
        return super.onKeyPreIme(keyCode, event);
    }
    void stopEditing() {
        editingProbe++;
        touchEditorPending = focusingEditor = false;
        if (!editing) return;
        editing = false;
        InputMethodManager ime = (InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(getWindowToken(), 0);
        navigation.editingChanged(this, false);
        clearFocus();
        if (!closed) evaluateJavascript("(function(){var e=document.activeElement;if(e&&e.blur)e.blur();})()", null);
    }
    @Override protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);
        scheduleHeight(); // Also observes image/font loading and local script layout changes.
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw) scheduleHeight();
    }
    private void scheduleHeight() {
        if (closed || heightPending || getWidth() == 0) return;
        heightPending = true;
        main.postDelayed(measureHeight, 80);
    }
    private void measureContentHeight() {
        if (closed) return;
        evaluateJavascript("(function(){var b=document.body;if(!b)return 0;var s=getComputedStyle(b);"
                + "return Math.ceil(b.getBoundingClientRect().height+parseFloat(s.marginTop||0)+parseFloat(s.marginBottom||0));})()", value -> {
            heightPending = false;
            if (closed) return;
            try {
                double cssHeight = Double.parseDouble(value);
                if (Double.isNaN(cssHeight) || Double.isInfinite(cssHeight) || cssHeight <= 0) return;
                int height = (int) Math.min(1048576, Math.ceil(cssHeight * getResources().getDisplayMetrics().density));
                android.view.ViewGroup.LayoutParams layout = getLayoutParams();
                if (layout != null && Math.abs(layout.height - height) > 1) {
                    layout.height = height; setLayoutParams(layout);
                }
            } catch (NumberFormatException ignored) { }
        });
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
        stopEditing();
        closed = true;
        remote.close();
        main.removeCallbacks(measureHeight);
        if (soundTask != null) soundTask.cancel();
        releaseSound(); stopLoading(); loadUrl("about:blank");
        entryHtml = null;
        if (document != null) { document.close(); document = null; }
        setWebViewClient(new WebViewClient()); removeAllViews(); destroy();
    }
}
