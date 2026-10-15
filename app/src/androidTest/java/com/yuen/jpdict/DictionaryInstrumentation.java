package com.yuen.jpdict;

import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.widget.LinearLayout;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Platform-only instrumentation; no JUnit, Espresso or test runtime dependency. */
@android.annotation.SuppressLint("NewApi")
public final class DictionaryInstrumentation extends Instrumentation {
    private int assertions;
    private String ocrMode;
    private String htmlEditingMode;
    private boolean composerMode;
    private boolean operationMode;
    private boolean remoteMode;
    private void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void write(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
    }
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        ocrMode = arguments == null ? null : arguments.getString("ocr");
        htmlEditingMode = arguments == null ? null : arguments.getString("html_editing");
        composerMode = arguments != null && arguments.containsKey("composer");
        operationMode = arguments != null && arguments.containsKey("dictionary_operation");
        remoteMode = arguments != null && arguments.containsKey("dictionary_remote");
        start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (remoteMode) {
                result.putString("stream", DictionaryRemoteValidation.run(this));
                finish(android.app.Activity.RESULT_OK, result); return;
            }
            if (operationMode) {
                result.putString("stream", DictionaryOperationValidation.run(this));
                finish(android.app.Activity.RESULT_OK, result); return;
            }
            if (composerMode) {
                result.putString("stream", SearchComposerValidation.run(this));
                finish(android.app.Activity.RESULT_OK, result); return;
            }
            if (htmlEditingMode != null) {
                result.putString("stream", DictionaryEditingValidation.run(this));
                finish(android.app.Activity.RESULT_OK, result); return;
            }
            if (ocrMode != null) {
                result.putString("stream", OcrValidation.run(this, "fonts".equals(ocrMode)));
                finish(android.app.Activity.RESULT_OK, result); return;
            }
            integration();
            result.putString("stream", "Dictionary integration: " + assertions + " assertions passed\n");
            finish(android.app.Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace));
            result.putString("stream", trace.toString()); finish(android.app.Activity.RESULT_CANCELED, result);
        }
    }
    private void integration() throws Exception {
        Context context = getTargetContext(); DictionaryRepository repo = DictionaryRepository.get(context); repo.reload();
        if (!repo.list().isEmpty()) throw new AssertionError("Use an empty disposable emulator registry for integration tests");
        check(DictionaryBookIcon.letterFor("OALDPE 精装版").equals("精") && DictionaryBookIcon.letterFor("Longman").equals("L"), "Book badges prefer one Han character then one initial");
        boolean contrast = true;
        for (int i = 0; i < 10000; i++) {
            int color = DictionaryBookIcon.colorFor("dictionary-" + i);
            double luminance = .2126 * channel(android.graphics.Color.red(color)) + .7152 * channel(android.graphics.Color.green(color)) + .0722 * channel(android.graphics.Color.blue(color));
            contrast &= 1.05 / (luminance + .05) >= 4.5;
        }
        check(contrast, "Ten thousand name-generated book colors retain readable white text");
        MultilingualOcrRecognizer.Region first = new MultilingualOcrRecognizer.Region("avocado", new android.graphics.Rect(20, 200, 120, 225));
        MultilingualOcrRecognizer.Region next = new MultilingualOcrRecognizer.Region("energetic", new android.graphics.Rect(130, 200, 240, 225));
        MultilingualOcrRecognizer.Region lower = new MultilingualOcrRecognizer.Region("日本語", new android.graphics.Rect(20, 250, 120, 275));
        List<MultilingualOcrRecognizer.Region> regionFixtures = Arrays.asList(first, next, lower);
        check(OcrPickupController.regionAt(regionFixtures, 60, 212, 18) == first, "OCR selects only the word under the aim point");
        check(OcrPickupController.regionAt(regionFixtures, 180, 212, 18) == next, "Dragging across adjacent words changes the exact selected region");
        check(OcrPickupController.regionAt(regionFixtures, 60, 264, 18) == lower, "OCR selection keeps the raw CJK text and screen row");
        check(OcrPickupController.regionAt(regionFixtures, 500, 500, 18) == null, "OCR never snaps to distant recognized text");
        List<DictionaryRepository.Document> scanned = repo.scanTree(Uri.parse("content://com.yuen.jpdict.test.fixtures/tree/fixture"));
        check(scanned.size() == 2 && scanned.get(1).path.equals("assets/local.css"), "SAF tree preserves nested resource paths and skips hidden tool directories");
        try(DictionarySource source=new DictionarySource(context.getContentResolver(),Uri.parse("content://com.yuen.jpdict.test.fixtures/seek"))) {
            byte[] tail=new byte[6];source.read(250,tail,0,6);
            check(source.size()==256&&(tail[0]&255)==250&&(tail[5]&255)==255,"SAF descriptor positional reads");
        }
        boolean pipeRejected=false;try(DictionarySource source=new DictionarySource(context.getContentResolver(),Uri.parse("content://com.yuen.jpdict.test.fixtures/pipe"))){}catch(IOException expected){pipeRejected=true;}
        check(pipeRejected,"Streaming SAF provider rejected with copy guidance");
        File originals = new File(context.getCacheDir(), "original-dict-tests"); originals.mkdirs();
        File mdx = new File(originals,"original.mdx"), mainMdd = new File(originals,"original.mdd"), volume = new File(originals,"original.1.mdd");
        File script = new File(originals, "local.js"), css = new File(originals, "local.css"), json = new File(originals, "data.json"), largeFont = new File(originals, "large.otf");
        write(script, ("document.body.dataset.local='ok';fetch('data.json').then(r=>r.json()).then(d=>document.body.dataset.json=d.value);"
                + "document.querySelector('meta[http-equiv=\"Content-Security-Policy\"]').remove();"
                + "fetch('https://example.invalid/blocked').then(()=>document.body.dataset.network='leaked').catch(()=>document.body.dataset.network='blocked');").getBytes(StandardCharsets.UTF_8));
        write(css, "@import url('/style.css');#probe{font-weight:bold}".getBytes(StandardCharsets.UTF_8));
        write(json, "{\"value\":\"ok\"}".getBytes(StandardCharsets.UTF_8));
        try (RandomAccessFile font = new RandomAccessFile(largeFont, "rw")) { font.setLength(9L * 1024 * 1024); font.write(42); }
        write(mdx, MdictReaderTest.headerChange(MdictReaderTest.fixture(8,StandardCharsets.UTF_8,2,false,new String[]{"alpha","日本語"},
                new byte[][]{"<p>English</p>".getBytes(StandardCharsets.UTF_8), "<link rel='stylesheet' href='style.css'><img src='image.png'><a href='entry://alpha'>alpha</a><p>日本語释义</p>".getBytes(StandardCharsets.UTF_8)}),
                "/>", " Compact=\"Yes\" Compat=\"Yes\" StyleSheet=\"\"/>"));
        write(mainMdd, MdictReaderTest.encryptedIndex(MdictReaderTest.fixture(8,StandardCharsets.UTF_16LE,2,true,new String[]{"\\image.png","\\style.css","\\logic.js"},
                new byte[][]{android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==",0), "p{color:#006bff}".getBytes(StandardCharsets.UTF_8), "document.body.dataset.mdd='ok'".getBytes(StandardCharsets.UTF_8)})));
        write(volume, MdictReaderTest.withDownloadTrailer(MdictReaderTest.fixture(8,StandardCharsets.UTF_16LE,1,true,new String[]{"\\font.woff"},new byte[][]{{1,2,3,4}})));
        List<DictionaryRepository.Document> files = Arrays.asList(new DictionaryRepository.Document(mdx.getName(),Uri.fromFile(mdx),mdx.length()),
                new DictionaryRepository.Document(mainMdd.getName(),Uri.fromFile(mainMdd),mainMdd.length()),new DictionaryRepository.Document(volume.getName(),Uri.fromFile(volume),volume.length()),
                new DictionaryRepository.Document("src/local.js", Uri.fromFile(script), script.length()),
                new DictionaryRepository.Document("src/local.css", Uri.fromFile(css), css.length()),
                new DictionaryRepository.Document("src/data.json", Uri.fromFile(json), json.length()),
                new DictionaryRepository.Document("assets/large.otf", Uri.fromFile(largeFont), largeFont.length()));
        long start = System.nanoTime();
        DictionaryRepository.Dictionary dictionary = repo.install(files,true,null,(m,d,t)->{}).get(0);
        check(!dictionary.lookupExpanded, "New libraries default to collapsed lookup sections");
        check(dictionary.files.size()==7,"Multi-volume and nested resource association");
        File[] indexes = new File(context.getCacheDir(), "dict-indexes").listFiles((dir, name) -> name.endsWith(".idx"));
        check(indexes != null && indexes.length >= 3, "Import persists MDX and MDD indexes before the first lookup");
        try (InputStream font = repo.resourceStream(dictionary, "assets/large.otf")) {
            check(font != null && font.read() == 42 && font.skip(8L * 1024 * 1024) > 0 && font.read() == 0, "Large external font streams beyond the old 8 MB limit");
        }
        check(repo.lookup(dictionary,"日本語").get(0).contains("日本語释义"),"Internal lookup");
        check(repo.suggest("al").equals(Collections.singletonList("alpha")), "Repository returns installed dictionary completions");
        System.out.println("Device cold import/query ms="+((System.nanoTime()-start)/1_000_000.0));
        start=System.nanoTime(); repo.lookup(dictionary,"日本語"); System.out.println("Device hot query ms="+((System.nanoTime()-start)/1_000_000.0));
        check(Arrays.equals(repo.resource(dictionary,"font.woff"),new byte[]{1,2,3,4}),"MDD numbered volume with complete 416 download trailer");
        check(repo.resource(dictionary,"style.css").length>0,"CSS resource");
        boolean duplicate=false; try{repo.install(files,true,null,(m,d,t)->{});}catch(IOException e){duplicate=true;} check(duplicate,"Duplicate rejected");
        repo.rename(dictionary.id,"重命名词典"); repo.enable(dictionary.id,false); repo.reload();
        dictionary=repo.list().get(0); check(dictionary.name.equals("重命名词典")&&!dictionary.enabled,"Settings persistence");
        repo.enable(dictionary.id,true); repo.reload(); dictionary=repo.list().get(0);
        check(mdx.delete()&&mainMdd.delete()&&volume.delete(),"Original removed");
        check(repo.lookup(dictionary,"alpha").get(0).contains("English"),"Internal copy independent of originals");
        boolean traversal=false; try{repo.resource(dictionary,"../outside");}catch(IOException e){traversal=true;} check(traversal,"Resource traversal denied");
        String id=dictionary.id;
        repo.setLookupExpanded(id, true); repo.reload();
        check(repo.list().get(0).lookupExpanded, "Lookup expansion preference survives repository reload");
        File full=new File(originals,"full.zip"), light=new File(originals,"light.zip");
        DictionaryBackup.write(repo,Uri.fromFile(full),true,(m,d,t)->{});
        DictionaryBackup.write(repo,Uri.fromFile(light),false,(m,d,t)->{});
        DictionaryBackup.restore(repo,Uri.fromFile(light),(m,d,t)->{});repo.reload();
        check(repo.list().get(0).lookupExpanded, "Light backup preserves lookup expansion preference");
        check(repo.lookup(repo.list().get(0),"alpha").get(0).contains("English"),"Light restore retains existing installed files");
        repo.delete(repo.list().get(0));repo.reload();
        DictionaryBackup.restore(repo,Uri.fromFile(light),(m,d,t)->{});repo.reload();
        check(repo.list().get(0).status.equals("NEEDS_RELINK"),"Light backup requires files");
        DictionaryBackup.restore(repo,Uri.fromFile(full),(m,d,t)->{});repo.reload();dictionary=repo.list().get(0);
        check(dictionary.lookupExpanded, "Full restore preserves lookup expansion preference");
        check(dictionary.id.equals(id)&&dictionary.name.equals("重命名词典")&&dictionary.enabled,"Full restore preserves identity and settings");
        check(repo.lookup(dictionary,"alpha").get(0).contains("English"),"Full restore verified data");
        File attack=new File(originals,"bad.zip");
        try(java.util.zip.ZipOutputStream out=new java.util.zip.ZipOutputStream(new FileOutputStream(attack))) { out.putNextEntry(new java.util.zip.ZipEntry("../escape"));out.write(1);out.closeEntry(); }
        boolean zipTraversal=false;try{DictionaryBackup.restore(repo,Uri.fromFile(attack),(m,d,t)->{});}catch(IOException e){zipTraversal=true;}check(zipTraversal,"Archive traversal denied");
        check(repo.lookup(dictionary,"alpha").get(0).contains("English"),"Failed restore leaves existing files");
        int before=repo.list().size();
        Thread.currentThread().interrupt();
        boolean cancelled=false;try{repo.install(files,true,null,(m,d,t)->{});}catch(MdictReader.Failure error){cancelled=error.error==MdictReader.Error.CANCELLED;}finally{Thread.interrupted();}
        check(cancelled,"Import cancellation recognized");repo.reload();check(repo.list().size()==before,"Cancelled import does not register files");
        File external=new File(originals,"external.mdx");
        write(external,MdictReaderTest.headerChange(MdictReaderTest.fixture(8,StandardCharsets.UTF_8,0,false,new String[]{"external"},new byte[][]{"external-original".getBytes(StandardCharsets.UTF_8)}),
                "/>", " Compat=\"Yes\"/>"));
        DictionaryRepository.Dictionary linked=repo.install(Collections.singletonList(new DictionaryRepository.Document("external.mdx",Uri.fromFile(external),external.length())),false,null,(m,d,t)->{}).get(0);
        check(repo.lookup(linked,"external").get(0).equals("external-original"),"External storage lookup");
        long originalModified = external.lastModified();
        write(external, MdictReaderTest.headerChange(MdictReaderTest.fixture(8, StandardCharsets.UTF_8, 0, false,
                new String[]{"external"}, new byte[][]{"external-updated!".getBytes(StandardCharsets.UTF_8)}), "/>", " Compat=\"Yes\"/>"));
        check(external.setLastModified(originalModified), "External fixture preserves its timestamp for fingerprint invalidation");
        check(repo.lookup(linked, "external").get(0).equals("external-updated!"), "External fingerprint changes invalidate cached definitions even at the same timestamp");
        repo.delete(linked);repo.reload();check(external.exists(),"Unlink never deletes external original");
        linked=repo.install(Collections.singletonList(new DictionaryRepository.Document("external.mdx",Uri.fromFile(external),external.length())),false,null,(m,d,t)->{}).get(0);
        external.delete();boolean missing=false;try{repo.lookup(linked,"external");}catch(IOException expected){missing=true;}check(missing,"Missing external original reported");repo.delete(linked);repo.reload();
        long memoryBefore=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();
        for(int n=0;n<29;n++){
            File test=new File(originals,"dictionary-"+n+".mdx");
            write(test,MdictReaderTest.fixture(8,StandardCharsets.UTF_8,2,false,new String[]{"alpha"},new byte[][]{("definition-"+n).getBytes(StandardCharsets.UTF_8)}));
            repo.install(Collections.singletonList(new DictionaryRepository.Document(test.getName(),Uri.fromFile(test),test.length())),true,null,(m,d,t)->{});
        }
        check(repo.list().size()==30,"Thirty independently registered dictionaries");
        List<String> ids=new ArrayList<>();for(DictionaryRepository.Dictionary d:repo.list())ids.add(d.id);
        Collections.reverse(ids);repo.reorder(ids);repo.reload();check(repo.list().get(0).id.equals(ids.get(0)),"Display order persistence");
        long coldStart = System.nanoTime();
        for (DictionaryRepository.Dictionary d : repo.list()) repo.lookup(d, "alpha");
        long coldElapsed = System.nanoTime() - coldStart;
        DictionaryRepository.Dictionary cachedDictionary = repo.list().get(0);
        List<String> cachedDefinition = repo.lookup(cachedDictionary, "alpha");
        long warmStart = System.nanoTime();
        for (DictionaryRepository.Dictionary d : repo.list()) repo.lookup(d, "alpha");
        long warmElapsed = System.nanoTime() - warmStart;
        check(cachedDefinition == repo.lookup(cachedDictionary, "alpha"), "Definition cache survives two-parser eviction across thirty dictionaries");
        System.out.println("Device generated-fixture lookup 30 libraries: first=" + coldElapsed / 1000000.0 + "ms, cached=" + warmElapsed / 1000000.0 + "ms");
        System.out.println("Device 30 registrations heap delta bytes="+((Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())-memoryBefore));
        DictionaryLoadingValidation.run(this, repo, originals);
        check(true, "Evicted offscreen definitions wait for visibility before reloading, then load successfully");
        for(DictionaryRepository.Dictionary d:new ArrayList<>(repo.list()))if(!d.id.equals(id))repo.delete(d);
        repo.reload();dictionary=repo.list().get(0);
        List<DictionaryCatalog.Package> catalog=DictionaryCatalog.load(context);
        check(catalog.get(0).mode==DictionaryCatalog.Mode.PREVIEW,"Catalog preview never available");
        DictionaryRepository.Dictionary renderDictionary=dictionary;
        AtomicReference<String> navigation = new AtomicReference<>();
        AtomicReference<LinearLayout> closedHost = new AtomicReference<>();
        java.util.concurrent.CountDownLatch pageLoaded=new java.util.concurrent.CountDownLatch(1);
        AtomicReference<String> pageError=new AtomicReference<>();
        AtomicReference<DictionaryEntryView> loadedView=new AtomicReference<>();
        runOnMainSync(()->{
            DictionaryEntryView view=new DictionaryEntryView(context,repo,renderDictionary,Collections.singletonList(
                    "<link rel='stylesheet' href='local.css'><p id='probe'>test</p><script src='local.js'></script><script src='/logic.js'></script>"),navigation::set);
            loadedView.set(view);
            android.webkit.WebViewClient client=view.getWebViewClient();
            view.setWebViewClient(new android.webkit.WebViewClient(){
                @Override public WebResourceResponse shouldInterceptRequest(android.webkit.WebView v,WebResourceRequest request){return client.shouldInterceptRequest(v,request);}
                @Override public void onPageFinished(android.webkit.WebView v,String url){pageLoaded.countDown();}
                @Override public void onReceivedError(android.webkit.WebView v,WebResourceRequest request,android.webkit.WebResourceError error){if(request.isForMainFrame())pageError.set(error.getDescription().toString());}
                @Override public void onReceivedHttpError(android.webkit.WebView v,WebResourceRequest request,WebResourceResponse error){if(request.isForMainFrame())pageError.set("HTTP "+error.getStatusCode());}
            });
            check(view.getSettings().getJavaScriptEnabled(),"Local dictionary JavaScript enabled");
                check(view.getSettings().getBlockNetworkLoads() && view.getSettings().getDomStorageEnabled(), "Network remains blocked while dictionary-origin DOM storage is enabled");
            check(!view.getSettings().getAllowFileAccess()&&!view.getSettings().getAllowContentAccess(),"Private file access disabled");
            client.shouldOverrideUrlLoading(view,"entry://%E6%97%A5%E6%9C%AC%E8%AA%9E");
            check("日本語".equals(navigation.get()),"Entry navigation decodes Unicode");
            WebResourceResponse denied=view.getWebViewClient().shouldInterceptRequest(view,request("https://dict.invalid/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/style.css"));
            check(denied.getStatusCode()==404,"Cross-dictionary request denied");
            String origin = "https://" + renderDictionary.id + ".dict.invalid/";
            denied=client.shouldInterceptRequest(view,request(origin + "%2E%2E/private"));
            check(denied.getStatusCode()==404,"Encoded resource traversal denied");
            denied=client.shouldInterceptRequest(view,request("file:///data/data/com.yuen.jpdict/private"));
            check(denied.getStatusCode()==404,"Private file request denied");
            LinearLayout host=new LinearLayout(context);
            closedHost.set(host);
            DictionaryLookupController controller=new DictionaryLookupController(context,host,"alpha",navigation::set);
            controller.close();
        });
        check(pageLoaded.await(5,java.util.concurrent.TimeUnit.SECONDS)&&pageError.get()==null,"Local HTML main frame loads successfully: "+pageError.get());
        String dom = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            java.util.concurrent.CountDownLatch evaluated = new java.util.concurrent.CountDownLatch(1);
            AtomicReference<String> value = new AtomicReference<>();
            runOnMainSync(() -> loadedView.get().evaluateJavascript("JSON.stringify([document.body.dataset.local,document.body.dataset.mdd,document.body.dataset.json,document.body.dataset.network,getComputedStyle(document.getElementById('probe')).color,getComputedStyle(document.getElementById('probe')).fontWeight,typeof window.jpdict])", result -> {value.set(result); evaluated.countDown();}));
            check(evaluated.await(2, java.util.concurrent.TimeUnit.SECONDS), "DOM evaluation completes");
            dom = value.get(); if (dom != null && dom.contains("blocked") && dom.contains("rgb(0, 107, 255)")) break;
            Thread.sleep(100);
        }
        check(dom != null && dom.contains("ok\\\",\\\"ok\\\",\\\"ok") && dom.contains("blocked") && dom.contains("rgb(0, 107, 255)") && dom.contains("700") && dom.contains("undefined"),
                "External/MDD JS, local fetch, nested CSS and immutable network policy work: " + dom);
        runOnMainSync(()->loadedView.get().close());
        java.util.concurrent.CountDownLatch barrier = new java.util.concurrent.CountDownLatch(1);
        repo.execute(()->{},error->barrier.countDown());
        if(!barrier.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Management callbacks timed out");
        runOnMainSync(()->check(closedHost.get().getChildCount()==0,"Closed lookup does not receive asynchronous UI updates"));
        AtomicReference<DictionaryLookupController> retainedController = new AtomicReference<>();
        AtomicReference<LinearLayout> retainedHost = new AtomicReference<>();
        java.util.concurrent.CountDownLatch workersBlocked = new java.util.concurrent.CountDownLatch(2), releaseQueries = new java.util.concurrent.CountDownLatch(1);
        Runnable holdQuery = () -> {
            workersBlocked.countDown();
            try { releaseQueries.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        };
        repo.queries.submit(0, holdQuery); repo.queries.submit(0, holdQuery);
        try {
            check(workersBlocked.await(5, java.util.concurrent.TimeUnit.SECONDS), "Lookup loading probe occupies both workers");
            runOnMainSync(() -> {
                LinearLayout host = new LinearLayout(context); retainedHost.set(host);
                retainedController.set(new DictionaryLookupController(context, host, "alpha", navigation::set));
            });
            runOnMainSync(() -> {
                LinearLayout host = retainedHost.get();
                check(host.getChildCount() == 2 && host.getChildAt(1).getVisibility() == android.view.View.GONE,
                        "A saved expanded preference stays collapsed while its query is queued");
            });
        } finally { releaseQueries.countDown(); }
        AtomicReference<android.view.View> retainedWeb = new AtomicReference<>();
        for (int attempt = 0; attempt < 50 && retainedWeb.get() == null; attempt++) {
            Thread.sleep(100);
            runOnMainSync(() -> {
                LinearLayout host = retainedHost.get();
                if (host.getChildCount() < 2) return;
                LinearLayout body = (LinearLayout)host.getChildAt(1);
                if (body.getVisibility() == android.view.View.VISIBLE && body.getChildCount() > 0
                        && body.getChildAt(0) instanceof DictionaryEntryView) retainedWeb.set(body.getChildAt(0));
            });
        }
        runOnMainSync(() -> {
            check(retainedWeb.get() != null, "Expanded dictionary finishes its first render");
            LinearLayout host = retainedHost.get(), body = (LinearLayout)host.getChildAt(1);
            host.getChildAt(0).performClick();
            check(body.getVisibility() == android.view.View.GONE && body.getChildAt(0) == retainedWeb.get(), "Collapse preserves the loaded dictionary DOM");
            host.getChildAt(0).performClick();
            check(body.getVisibility() == android.view.View.VISIBLE && body.getChildAt(0) == retainedWeb.get(), "Reopen reuses the same dictionary page without reload");
            retainedController.get().close();
        });
        String[] prefixes = new String[12]; byte[][] meanings = new byte[12][];
        for (int i = 0; i < 12; i++) { prefixes[i] = "myself" + i; meanings[i] = "prefix fixture".getBytes(StandardCharsets.UTF_8); }
        File prefixesMdx = new File(originals, "prefixes.mdx"), exactMdx = new File(originals, "exact.mdx");
        write(prefixesMdx, MdictReaderTest.fixture(8, StandardCharsets.UTF_8, 2, false, prefixes, meanings));
        write(exactMdx, MdictReaderTest.fixture(8, StandardCharsets.UTF_8, 2, false, new String[]{"myself"}, new byte[][]{"exact fixture".getBytes(StandardCharsets.UTF_8)}));
        repo.install(Collections.singletonList(new DictionaryRepository.Document("prefixes.mdx", Uri.fromFile(prefixesMdx), prefixesMdx.length())), true, null, (m,d,t)->{});
        repo.install(Collections.singletonList(new DictionaryRepository.Document("exact.mdx", Uri.fromFile(exactMdx), exactMdx.length())), true, null, (m,d,t)->{});
        List<String> merged = repo.suggest("myself");
        check(merged.size() == 12 && merged.get(0).equals("myself"), "Later dictionary exact match outranks twelve earlier prefixes");
        check(repo.suggest("myslef").get(0).equals("myself"), "Repository merges fuzzy spelling candidates across dictionaries");
        // Keep one original generated dictionary installed for manual UI checks.
        repo.closeReaders();
    }
    private static WebResourceRequest request(String url) {
        return new WebResourceRequest() {
            public Uri getUrl(){return Uri.parse(url);}public boolean isForMainFrame(){return false;}
            public boolean isRedirect(){return false;}public boolean hasGesture(){return false;}
            public String getMethod(){return "GET";}public Map<String,String> getRequestHeaders(){return Collections.emptyMap();}
        };
    }
    private static double channel(int value) { double x = value / 255.0; return x <= .04045 ? x / 12.92 : Math.pow((x + .055) / 1.055, 2.4); }
}
