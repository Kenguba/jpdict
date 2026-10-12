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
    private void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void write(File file, byte[] bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
    }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
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
        try(DictionarySource source=new DictionarySource(context.getContentResolver(),Uri.parse("content://com.yuen.jpdict.test.fixtures/seek"))) {
            byte[] tail=new byte[6];source.read(250,tail,0,6);
            check(source.size()==256&&(tail[0]&255)==250&&(tail[5]&255)==255,"SAF descriptor positional reads");
        }
        boolean pipeRejected=false;try(DictionarySource source=new DictionarySource(context.getContentResolver(),Uri.parse("content://com.yuen.jpdict.test.fixtures/pipe"))){}catch(IOException expected){pipeRejected=true;}
        check(pipeRejected,"Streaming SAF provider rejected with copy guidance");
        File originals = new File(context.getCacheDir(), "original-dict-tests"); originals.mkdirs();
        File mdx = new File(originals,"original.mdx"), mainMdd = new File(originals,"original.mdd"), volume = new File(originals,"original.1.mdd");
        write(mdx, MdictReaderTest.headerChange(MdictReaderTest.fixture(8,StandardCharsets.UTF_8,2,false,new String[]{"alpha","日本語"},
                new byte[][]{"<p>English</p>".getBytes(StandardCharsets.UTF_8), "<link rel='stylesheet' href='style.css'><img src='image.png'><a href='entry://alpha'>alpha</a><p>日本語释义</p>".getBytes(StandardCharsets.UTF_8)}),
                "/>", " Compact=\"Yes\" Compat=\"Yes\" StyleSheet=\"\"/>"));
        write(mainMdd, MdictReaderTest.encryptedIndex(MdictReaderTest.fixture(8,StandardCharsets.UTF_16LE,2,true,new String[]{"\\image.png","\\style.css"},
                new byte[][]{android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==",0), "p{color:#006bff}".getBytes(StandardCharsets.UTF_8)})));
        write(volume, MdictReaderTest.fixture(8,StandardCharsets.UTF_16LE,1,true,new String[]{"\\font.woff"},new byte[][]{{1,2,3,4}}));
        List<DictionaryRepository.Document> files = Arrays.asList(new DictionaryRepository.Document(mdx.getName(),Uri.fromFile(mdx),mdx.length()),
                new DictionaryRepository.Document(mainMdd.getName(),Uri.fromFile(mainMdd),mainMdd.length()),new DictionaryRepository.Document(volume.getName(),Uri.fromFile(volume),volume.length()));
        long start = System.nanoTime();
        DictionaryRepository.Dictionary dictionary = repo.install(files,true,null,(m,d,t)->{}).get(0);
        check(dictionary.files.size()==3,"Multi-volume association");
        check(repo.lookup(dictionary,"日本語").get(0).contains("日本語释义"),"Internal lookup");
        System.out.println("Device cold import/query ms="+((System.nanoTime()-start)/1_000_000.0));
        start=System.nanoTime(); repo.lookup(dictionary,"日本語"); System.out.println("Device hot query ms="+((System.nanoTime()-start)/1_000_000.0));
        check(Arrays.equals(repo.resource(dictionary,"font.woff"),new byte[]{1,2,3,4}),"MDD numbered volume");
        check(repo.resource(dictionary,"style.css").length>0,"CSS resource");
        boolean duplicate=false; try{repo.install(files,true,null,(m,d,t)->{});}catch(IOException e){duplicate=true;} check(duplicate,"Duplicate rejected");
        repo.rename(dictionary.id,"重命名词典"); repo.enable(dictionary.id,false); repo.reload();
        dictionary=repo.list().get(0); check(dictionary.name.equals("重命名词典")&&!dictionary.enabled,"Settings persistence");
        repo.enable(dictionary.id,true); repo.reload(); dictionary=repo.list().get(0);
        check(mdx.delete()&&mainMdd.delete()&&volume.delete(),"Original removed");
        check(repo.lookup(dictionary,"alpha").get(0).contains("English"),"Internal copy independent of originals");
        boolean traversal=false; try{repo.resource(dictionary,"../outside");}catch(IOException e){traversal=true;} check(traversal,"Resource traversal denied");
        String id=dictionary.id;
        File full=new File(originals,"full.zip"), light=new File(originals,"light.zip");
        DictionaryBackup.write(repo,Uri.fromFile(full),true,(m,d,t)->{});
        DictionaryBackup.write(repo,Uri.fromFile(light),false,(m,d,t)->{});
        DictionaryBackup.restore(repo,Uri.fromFile(light),(m,d,t)->{});repo.reload();
        check(repo.lookup(repo.list().get(0),"alpha").get(0).contains("English"),"Light restore retains existing installed files");
        repo.delete(repo.list().get(0));repo.reload();
        DictionaryBackup.restore(repo,Uri.fromFile(light),(m,d,t)->{});repo.reload();
        check(repo.list().get(0).status.equals("NEEDS_RELINK"),"Light backup requires files");
        DictionaryBackup.restore(repo,Uri.fromFile(full),(m,d,t)->{});repo.reload();dictionary=repo.list().get(0);
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
        System.out.println("Device 30 registrations heap delta bytes="+((Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())-memoryBefore));
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
            DictionaryEntryView view=new DictionaryEntryView(context,repo,renderDictionary,Collections.singletonList("<p>test</p>"),navigation::set);
            loadedView.set(view);
            android.webkit.WebViewClient client=view.getWebViewClient();
            view.setWebViewClient(new android.webkit.WebViewClient(){
                @Override public WebResourceResponse shouldInterceptRequest(android.webkit.WebView v,WebResourceRequest request){return client.shouldInterceptRequest(v,request);}
                @Override public void onPageFinished(android.webkit.WebView v,String url){pageLoaded.countDown();}
                @Override public void onReceivedError(android.webkit.WebView v,WebResourceRequest request,android.webkit.WebResourceError error){if(request.isForMainFrame())pageError.set(error.getDescription().toString());}
                @Override public void onReceivedHttpError(android.webkit.WebView v,WebResourceRequest request,WebResourceResponse error){if(request.isForMainFrame())pageError.set("HTTP "+error.getStatusCode());}
            });
            check(!view.getSettings().getJavaScriptEnabled(),"JavaScript disabled");
            check(!view.getSettings().getAllowFileAccess()&&!view.getSettings().getAllowContentAccess(),"Private file access disabled");
            client.shouldOverrideUrlLoading(view,"entry://%E6%97%A5%E6%9C%AC%E8%AA%9E");
            check("日本語".equals(navigation.get()),"Entry navigation decodes Unicode");
            WebResourceResponse denied=view.getWebViewClient().shouldInterceptRequest(view,request("https://dict.invalid/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/style.css"));
            check(denied.getStatusCode()==404,"Cross-dictionary request denied");
            LinearLayout host=new LinearLayout(context);
            closedHost.set(host);
            DictionaryLookupController controller=new DictionaryLookupController(context,host,"alpha",navigation::set);
            controller.close();
        });
        check(pageLoaded.await(5,java.util.concurrent.TimeUnit.SECONDS)&&pageError.get()==null,"Inline HTML main frame loads successfully: "+pageError.get());
        runOnMainSync(()->loadedView.get().close());
        java.util.concurrent.CountDownLatch barrier = new java.util.concurrent.CountDownLatch(1);
        repo.execute(()->{},error->barrier.countDown());
        if(!barrier.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Management callbacks timed out");
        runOnMainSync(()->check(closedHost.get().getChildCount()==0,"Closed lookup does not receive asynchronous UI updates"));
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
}
