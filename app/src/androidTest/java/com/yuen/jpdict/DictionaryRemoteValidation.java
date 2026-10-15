package com.yuen.jpdict;

import android.app.Instrumentation;
import android.content.Context;
import android.database.MatrixCursor;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONTokener;

/** Real Chromium/CSP/CORS with original QA HTML and fake HTTPS connections only. */
final class DictionaryRemoteValidation {
    private int checks;
    private void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    static String run(Instrumentation instrumentation) throws Exception {
        DictionaryRemoteValidation validation = new DictionaryRemoteValidation();
        validation.page(instrumentation);
        return "Dictionary remote WebView: " + validation.checks + " assertions passed\n";
    }
    private void page(Instrumentation instrumentation) throws Exception {
        Context context = instrumentation.getTargetContext();
        File debugFixture = new File(context.getCacheDir(), "eruda-validation.js");
        if (!debugFixture.isFile()) throw new AssertionError("Push the pinned official eruda-validation.js into the disposable QA app cache first");
        byte[] debug;
        try (InputStream input = new FileInputStream(debugFixture); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            debug = output.toByteArray();
        }
        check(DictionaryRemoteClient.validDebugScript(debug), "QA debug fixture has the verified release hash");
        String[] columns = {"dict_id", "name", "package_id", "installed_version", "source", "storage", "enabled",
                "sort_order", "index_state", "status", "fingerprint", "installed_at", "updated_at", "files", "lookup_expanded"};
        DictionaryRepository.Dictionary dictionary;
        try (MatrixCursor cursor = new MatrixCursor(columns)) {
            cursor.addRow(new Object[]{"html-remote-fixture", "HTML remote QA", "", "", "USER_IMPORT", "APP_PRIVATE", 1,
                    0, "READY", "INSTALLED", "", 0, 0, "[]", 1});
            cursor.moveToFirst(); dictionary = new DictionaryRepository.Dictionary(cursor);
        }
        List<HttpURLConnection> requests = Collections.synchronizedList(new ArrayList<>());
        DictionaryRemoteClient.Connections connections = url -> {
            HttpURLConnection connection = new HttpURLConnection(url) {
                public void connect() { }
                public void disconnect() { }
                public boolean usingProxy() { return false; }
                public int getResponseCode() { return 200; }
                public String getContentType() { return "api.frdic.com".equals(url.getHost()) ? "application/json" : "application/javascript"; }
                public InputStream getInputStream() {
                    return new ByteArrayInputStream("api.frdic.com".equals(url.getHost())
                            ? "{\"data\":{\"word\":\"father\",\"note\":\"original QA note\"}}".getBytes(StandardCharsets.UTF_8) : debug);
                }
            };
            requests.add(connection); return connection;
        };
        WindowManager windows = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(600, 650,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        AtomicReference<FrameLayout> root = new AtomicReference<>();
        AtomicReference<DictionaryEntryView> web = new AtomicReference<>();
        CountDownLatch loaded = new CountDownLatch(1);
        try {
            instrumentation.runOnMainSync(() -> {
                FrameLayout host = new FrameLayout(context); root.set(host);
                String html = "<p>Original QA note request</p><script>"
                        + "fetch('" + DictionaryRemoteClient.NOTE_URL + "?language=en&word=father',"
                        + "{headers:{Authorization:'NIS original-test-only','Content-Type':'application/json'}})"
                        + ".then(r=>r.json()).then(d=>{document.body.dataset.note=d.data.word+' '+d.data.note;})"
                        + ".catch(e=>{document.body.dataset.note='failed '+e.message;});"
                        + "fetch('https://api.frdic.com/api/open/v1/studylist/category?language=en',"
                        + "{headers:{Authorization:'NIS original-test-only'}}).then(()=>{document.body.dataset.other='unexpected';})"
                        + ".catch(()=>{document.body.dataset.other='blocked';});"
                        + "var s=document.createElement('script');s.src='" + DictionaryRemoteClient.DEBUG_URL + "';"
                        + "s.onload=function(){try{eruda.init({tool:['console']});document.body.dataset.debug='initialized';}"
                        + "catch(e){document.body.dataset.debug='failed '+e.message;}};"
                        + "s.onerror=function(){document.body.dataset.debug='failed';};document.head.appendChild(s);</script>";
                DictionaryEntryView view = new DictionaryEntryView(context, DictionaryRepository.get(context), dictionary,
                        Collections.singletonList(html), ignored -> { }, loaded::countDown, connections);
                web.set(view); host.addView(view, new FrameLayout.LayoutParams(-1, -1)); windows.addView(host, params);
            });
            check(loaded.await(10, TimeUnit.SECONDS), "QA page loads");
            JSONArray state = null;
            for (int n = 0; n < 100; n++) {
                state = new JSONArray(new JSONTokener(javascript(instrumentation, web.get(),
                        "JSON.stringify([document.body.dataset.note,document.body.dataset.other,document.body.dataset.debug])")).nextValue().toString());
                if (!state.isNull(0) && !state.isNull(1) && !state.isNull(2)) break;
                Thread.sleep(50);
            }
            check(state != null && "father original QA note".equals(state.optString(0)),
                    "Real WebView cross-origin Authorization fetch succeeds with native interception and per-origin CORS: " + state);
            check("blocked".equals(state.optString(1)), "Other paths on the same API host remain blocked");
            check("initialized".equals(state.optString(2)), "Exact remote script URL loads the pinned release and initializes Eruda: " + state);
            instrumentation.runOnMainSync(() -> check(web.get().getSettings().getBlockNetworkLoads(),
                    "WebView's own network engine remains disabled"));
            synchronized (requests) {
                check(requests.size() == 2, "Only one note read and one pinned debug script reach native transport");
                HttpURLConnection note = null, script = null;
                for (HttpURLConnection request : requests) {
                    if (request.getURL().getHost().equals("api.frdic.com")) note = request;
                    else script = request;
                }
                check(note != null && "NIS original-test-only".equals(note.getRequestProperty("Authorization")),
                        "Chromium forwards only the QA script's Authorization header to the note adapter");
                check(script != null && script.getURL().toString().contains("/3.4.3/eruda.min.js")
                        && script.getRequestProperty("Authorization") == null, "Pinned script receives no dictionary credential");
            }
        } finally {
            instrumentation.runOnMainSync(() -> {
                if (web.get() != null) web.get().close();
                if (root.get() != null && root.get().isAttachedToWindow()) windows.removeView(root.get());
            });
            debugFixture.delete();
        }
    }
    private static String javascript(Instrumentation instrumentation, DictionaryEntryView view, String code) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> view.evaluateJavascript(code, value -> { result.set(value); done.countDown(); }));
        if (!done.await(5, TimeUnit.SECONDS)) throw new AssertionError("Javascript timed out");
        return result.get();
    }
}
