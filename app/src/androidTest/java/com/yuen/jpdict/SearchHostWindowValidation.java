package com.yuen.jpdict;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.EditText;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;

/** Real Activity/Service windows, with only the external app launch replaced by a transparent fixture. */
final class SearchHostWindowValidation {
    private int checks;
    private void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    static String run(Instrumentation test) throws Exception {
        return new SearchHostWindowValidation().windows(test);
    }
    private String windows(Instrumentation test) throws Exception {
        Context app = test.getTargetContext(), fixture = test.getContext();
        if ("com.yuen.jpdict".equals(app.getPackageName())) throw new AssertionError("Use an independent disposable QA applicationId");
        if (!Settings.canDrawOverlays(app)) throw new AssertionError("Grant overlays to the disposable QA package first");
        Application application = (Application)app.getApplicationContext();
        AtomicReference<LookupLinkActivity> latest = new AtomicReference<>();
        List<LookupLinkActivity> hosts = new CopyOnWriteArrayList<>();
        Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity activity, Bundle state) {
                if (activity instanceof LookupLinkActivity) { latest.set((LookupLinkActivity)activity); hosts.add((LookupLinkActivity)activity); }
            }
            public void onActivityStarted(Activity activity) {}
            public void onActivityResumed(Activity activity) {}
            public void onActivityPaused(Activity activity) {}
            public void onActivityStopped(Activity activity) {}
            public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            public void onActivityDestroyed(Activity activity) {}
        };
        AtomicReference<String> nonce = new AtomicReference<>(UUID.randomUUID().toString());
        AtomicBoolean externalFocused = new AtomicBoolean(), externalClosed = new AtomicBoolean();
        BroadcastReceiver status = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!nonce.get().equals(intent.getStringExtra("nonce"))) return;
                String state = intent.getStringExtra("state");
                externalFocused.set("focused".equals(state));
                if ("closed".equals(state)) externalClosed.set(true);
            }
        };
        LookupDestinationSettings destination = LookupDestinationSettings.get(app);
        LookupDestinationSettings.Model previous = destination.model();
        AtomicReference<FloatingService> floating = new AtomicReference<>();
        AtomicReference<FloatingSearchView> search = new AtomicReference<>();
        AtomicReference<EditText> input = new AtomicReference<>();
        Rect text = new Rect();
        Bitmap before = null, external = null, rebuilt = null;
        String draft = "HostQa982";
        application.registerActivityLifecycleCallbacks(lifecycle);
        ContextCompat.registerReceiver(app,status,new IntentFilter(app.getPackageName()+".SEARCH_HOST_QA_STATUS"),ContextCompat.RECEIVER_EXPORTED);
        try {
            app.stopService(new Intent(app,FloatingService.class));
            await(test, () -> service()==null, "Former QA service did not stop");
            test.startActivitySync(new Intent(app,LookupLinkActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(FloatingService.EXTRA_HOST_SEARCH,true).putExtra(FloatingService.EXTRA_SEARCH_QUERY,"older Intent draft"));
            await(test, () -> latest.get()!=null && service()!=null && field(service(),"searchPanel") instanceof FloatingSearchView
                    && ((View)field(service(),"searchPanel")).isAttachedToWindow(), "Hosted search did not attach");
            main(test, () -> {
                floating.set(service()); search.set((FloatingSearchView)field(floating.get(),"searchPanel"));
                input.set((EditText)field(search.get(),"input"));
                ((View)field(search.get(),"privateButton")).performClick();
                input.get().setText(draft); input.get().setCursorVisible(false);
                call(floating.get(),"rootSearchForLookup");
                assertSearch(floating.get(),search.get(),input.get(),draft,latest.get());
            });
            await(test, () -> input.get().getLayout()!=null && input.get().getWidth()>0 && !imeVisible(search.get()), "Input did not settle after IME dismissal");
            Thread.sleep(250);
            main(test, () -> {
                int[] xy = new int[2]; input.get().getLocationOnScreen(xy);
                int left = xy[0]+input.get().getTotalPaddingLeft()+(int)input.get().getLayout().getLineLeft(0);
                int top = xy[1]+input.get().getTotalPaddingTop()+input.get().getLayout().getLineTop(0);
                text.set(left,top,left+(int)Math.ceil(input.get().getPaint().measureText(draft)),
                        xy[1]+input.get().getTotalPaddingTop()+input.get().getLayout().getLineBottom(0));
            });
            before = screenshot(test,app,"search-host-before.png");
            launchExternal(test,latest.get(),fixture,app,nonce.get());
            await(test,externalFocused::get,"External fixture never acquired real window focus");
            main(test, () -> {
                check(!latest.get().getWindow().getDecorView().hasWindowFocus(),"The real host loses focus behind the external package");
                assertSearch(floating.get(),search.get(),input.get(),draft,latest.get());
            });
            external = screenshot(test,app,"search-host-external.png");
            assertTextVisible(before,external,text,"External translucent foreground");
            LookupLinkActivity old = latest.get();
            main(test,old::recreate);
            await(test, () -> latest.get()!=old && latest.get()!=null
                    && search.get().isAttachedToWindow() && params(floating.get()).token==latest.get().getWindow().getDecorView().getWindowToken(),
                    "A recreated unfocused host did not rebind the retained search root");
            main(test, () -> {
                check(externalFocused.get() && !latest.get().getWindow().getDecorView().hasWindowFocus(),
                        "Host recreation reattaches while the external window still owns focus");
                assertSearch(floating.get(),search.get(),input.get(),draft,latest.get());
            });
            rebuilt = screenshot(test,app,"search-host-recreated.png");
            assertTextVisible(before,rebuilt,text,"Unfocused host recreation");
            closeExternal(app,fixture,nonce.get());
            await(test, () -> externalClosed.get() && !search.get().isLookupPaused(), "Closing the external window did not resume the retained search");
            main(test, () -> assertSearch(floating.get(),search.get(),input.get(),draft,latest.get()));

            nonce.set(UUID.randomUUID().toString()); externalClosed.set(false); externalFocused.set(false);
            main(test, () -> call(floating.get(),"rootSearchForLookup"));
            launchExternal(test,latest.get(),fixture,app,nonce.get());
            await(test,externalFocused::get,"Second external fixture never received focus");
            main(test, () -> call(floating.get(),"toggleSearchPanel"));
            await(test, () -> !externalFocused.get() && !search.get().isLookupPaused()
                    && search.get().isAttachedToWindow(), "Tapping the bubble did not restore search over the external window");
            main(test, () -> assertSearch(floating.get(),search.get(),input.get(),draft,latest.get()));
            closeExternal(app,fixture,nonce.get());
            await(test,externalClosed::get,"External fixture did not close after bubble return");

            main(test, () -> {
                destination.select(LookupDestinationSettings.Model.FAST);
                ((FloatingSearchView.Callback)field(search.get(),"callback")).onLookup("window fixture",true);
            });
            await(test, () -> field(floating.get(),"lookupOverlay") instanceof View
                    && ((View)field(floating.get(),"lookupOverlay")).isAttachedToWindow(), "Internal lookup did not attach");
            LookupLinkActivity lookupHost = latest.get();
            main(test,lookupHost::recreate);
            await(test, () -> latest.get()!=lookupHost && search.get().isAttachedToWindow()
                    && params(floating.get()).token==latest.get().getWindow().getDecorView().getWindowToken()
                    && latest.get().getWindow().getDecorView().hasWindowFocus() && field(latest.get(),"pendingForward")==null
                    && field(floating.get(),"lookupOverlay")!=null, "Lookup recreation did not retain the hosted search");
            main(test, () -> {
                assertSearch(floating.get(),search.get(),input.get(),draft,latest.get());
                latest.get().onBackPressed();
            });
            await(test, () -> field(floating.get(),"lookupOverlay")==null && !search.get().isLookupPaused(), "Back did not close lookup and restore search");
            main(test, () -> assertSearch(floating.get(),search.get(),input.get(),draft,latest.get()));
            for (int round=0; round<2; round++) {
                await(test, () -> readyForInput(search.get(),input.get()) && !imeVisible(search.get()),
                        "Search did not finish its previous IME hide before the input tap");
                tapInput(test,input.get());
                await(test, () -> input.get().isFocused() && input.get().hasWindowFocus() && imeVisible(search.get()),
                        "The attached search input did not receive IME focus");
                if (round==1) {
                    LookupLinkActivity editingHost = latest.get();
                    main(test,editingHost::recreate);
                    await(test, () -> latest.get()!=editingHost && search.get().isAttachedToWindow()
                            && params(floating.get()).token==latest.get().getWindow().getDecorView().getWindowToken()
                            && readyForInput(search.get(),input.get()),
                            "IME host recreation did not bind the retained search to the new token");
                    if (!input.get().isFocused() || !input.get().hasWindowFocus() || !imeVisible(search.get())) tapInput(test,input.get());
                    await(test, () -> input.get().isFocused() && input.get().hasWindowFocus() && imeVisible(search.get()),
                            "The rebound input did not recover IME focus");
                }
                if (Build.VERSION.SDK_INT>=33) await(test, () -> field(floating.get(),"searchBackCallback")!=null
                        && field(floating.get(),"searchBackDispatcher")==search.get().findOnBackInvokedDispatcher(),
                        "Search Back was not registered on its current application subwindow");
                test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
                await(test, () -> !imeVisible(search.get()) && field(search.get(),"activeImeAnimation")==null,
                        "Back did not finish hiding the search IME");
                main(test, () -> assertSearch(floating.get(),search.get(),input.get(),draft,latest.get()));
            }
            return "Search host windows: "+checks+" assertions passed; actual host/Service + separate-package translucent fixture; screenshots in "
                    +app.getCacheDir()+"/search-host-*.png; IME Back before/after host recreation. Eudic protocol/OEM compatibility is a separate check.\n";
        } catch (Throwable failure) {
            try {
                AtomicReference<String> details = new AtomicReference<>();
                main(test, () -> {
                    FloatingSearchView root = search.get(); EditText editor = input.get();
                    details.set("host="+latest.get()+" hostFocus="+(latest.get()!=null && latest.get().getWindow().getDecorView().hasWindowFocus())
                            +"\nroot="+root+" attached="+(root!=null && root.isAttachedToWindow())+" visible="+(root!=null && root.isShown())
                            +"\ninputFocus="+(editor!=null && editor.isFocused())+" inputWindowFocus="+(editor!=null && editor.hasWindowFocus())
                            +"\nparams="+(floating.get()==null ? null : params(floating.get()))
                            +(floating.get()==null || params(floating.get())==null ? "" : " flags=0x"+Integer.toHexString(params(floating.get()).flags)
                            +" softInputMode=0x"+Integer.toHexString(params(floating.get()).softInputMode))
                            +(root==null ? "" : "\nimeVisible="+imeVisible(root)+" animation="+field(root,"activeImeAnimation")
                            +" imeBottom="+field(root,"imeBottom")+" pendingImeHeight="+field(root,"pendingImeHeight")
                            +" renderedKeyboardHeight="+field(root,"renderedKeyboardHeight")
                            +" cachedHeight="+((SearchKeyboardSpace)field(root,"keyboardSpace")).height()));
                });
                try (FileOutputStream out = new FileOutputStream(new File(app.getCacheDir(),"search-host-failure.txt"))) {
                    out.write(details.get().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                Bitmap screen = test.getUiAutomation().takeScreenshot();
                if (screen!=null) try (FileOutputStream out = new FileOutputStream(new File(app.getCacheDir(),"search-host-failure.png"))) {
                    screen.compress(Bitmap.CompressFormat.PNG,100,out);
                } finally { if (screen!=null) screen.recycle(); }
            } catch (Throwable diagnosticFailure) { failure.addSuppressed(diagnosticFailure); }
            throw new AssertionError("Search host failure; focus/IME diagnostics in "+app.getCacheDir()+"/search-host-failure.*",failure);
        } finally {
            closeExternal(app,fixture,nonce.get());
            main(test, () -> {
                destination.select(previous);
                for (LookupLinkActivity host : hosts) if (!host.isFinishing()) host.finish();
                app.stopService(new Intent(app,FloatingService.class));
                app.unregisterReceiver(status); application.unregisterActivityLifecycleCallbacks(lifecycle);
            });
            if (before!=null) before.recycle(); if (external!=null) external.recycle(); if (rebuilt!=null) rebuilt.recycle();
        }
    }
    private void assertSearch(FloatingService service, FloatingSearchView root, EditText input, String draft, LookupLinkActivity host) {
        check(field(service,"searchPanel")==root && root.isAttachedToWindow() && root.getVisibility()==View.VISIBLE,"One original visible search root is retained");
        check(params(service).type==WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
                && params(service).token==host.getWindow().getDecorView().getWindowToken(),"Search belongs to the current Activity token, below foreign foreground windows");
        check(draft.equals(input.getText().toString()) && draft.equals(field(service,"currentSearchQuery"))
                && (Boolean)field(service,"searchPrivateMode"),"The original input and private draft survive each window transition");
        try {
            Class<?> type = Class.forName("android.view.WindowManagerGlobal");
            Object global = type.getDeclaredMethod("getInstance").invoke(null);
            int roots = 0;
            for (Object view : (List<?>)field(global,"mViews")) if (view instanceof FloatingSearchView) roots++;
            check(roots==1,"Exactly one search WindowManager root is mounted");
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void assertTextVisible(Bitmap before, Bitmap after, Rect text, String phase) {
        check(before!=null && after!=null && text.width()>0 && text.height()>0 && text.right<=after.getWidth() && text.bottom<=after.getHeight(),"Screenshot contains the real input text bounds");
        int ink = 0, changed = 0, paper = before.getPixel(text.left,text.top);
        for (int y=text.top;y<text.bottom;y++) for (int x=text.left;x<text.right;x++) {
            int original = before.getPixel(x,y);
            if (different(original,paper)) ink++;
            if (different(original,after.getPixel(x,y))) changed++;
        }
        check(ink>text.width()*text.height()/30,"The baseline contains rendered draft glyphs, not an empty white rectangle");
        check(changed<=Math.max(6,ink/3),phase+" keeps the real draft visible; changed="+changed+", glyph pixels="+ink);
    }
    private static boolean different(int a, int b) {
        return Math.abs(((a>>16)&255)-((b>>16)&255))>24 || Math.abs(((a>>8)&255)-((b>>8)&255))>24 || Math.abs((a&255)-(b&255))>24;
    }
    private static Bitmap screenshot(Instrumentation test, Context app, String name) throws Exception {
        Thread.sleep(150); Bitmap result = test.getUiAutomation().takeScreenshot();
        if (result==null) throw new AssertionError("Unable to capture window composition");
        try (FileOutputStream out = new FileOutputStream(new File(app.getCacheDir(),name))) { result.compress(Bitmap.CompressFormat.PNG,100,out); }
        return result;
    }
    private static void launchExternal(Instrumentation test, LookupLinkActivity host, Context fixture, Context app, String nonce) {
        main(test, () -> {
            // Substitute only the successful launch: the production route sets these same guards.
            set(host,"externalLookupPending",true); set(host,"pageInFront",true);
            host.startActivity(new Intent().setComponent(new ComponentName(fixture.getPackageName(),SearchHostTransparentActivity.class.getName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .putExtra("reply_package",app.getPackageName()).putExtra("nonce",nonce));
            host.overridePendingTransition(0,0);
        });
    }
    private static void closeExternal(Context app, Context fixture, String nonce) {
        app.sendBroadcast(new Intent(fixture.getPackageName()+".SEARCH_HOST_QA_CLOSE").setPackage(fixture.getPackageName()).putExtra("nonce",nonce));
    }
    private static WindowManager.LayoutParams params(FloatingService service) { return (WindowManager.LayoutParams)field(service,"searchPanelLp"); }
    private static boolean imeVisible(View root) { return Build.VERSION.SDK_INT>=30 && root.getRootWindowInsets()!=null && root.getRootWindowInsets().isVisible(WindowInsets.Type.ime()); }
    private static boolean readyForInput(FloatingSearchView root, EditText input) {
        return root.isAttachedToWindow() && root.isShown() && root.getWindowVisibility()==View.VISIBLE
                && input.isLaidOut() && input.isShown() && field(root,"activeImeAnimation")==null;
    }
    private static void tapInput(Instrumentation test, EditText input) {
        Rect bounds = new Rect(); main(test, () -> {
            if (!input.getGlobalVisibleRect(bounds)) throw new AssertionError("Search input has no visible tap target");
        });
        long down = SystemClock.uptimeMillis();
        for (int action : new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try { if (!test.getUiAutomation().injectInputEvent(event,true)) throw new AssertionError("Real input tap rejected"); }
            finally { event.recycle(); }
        }
    }
    private static FloatingService service() {
        try {
            Class<?> type = Class.forName("android.app.ActivityThread"); Object current = type.getMethod("currentActivityThread").invoke(null);
            for (Object instance : ((Map<?,?>)field(current,"mServices")).values()) if (instance instanceof FloatingService) return (FloatingService)instance;
            return null;
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static Object field(Object owner, String name) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void set(Object owner, String name, boolean value) {
        try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.setBoolean(owner,value); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void call(Object owner, String name) {
        try { java.lang.reflect.Method method = owner.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(owner); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void main(Instrumentation test, Runnable action) {
        if (Looper.myLooper()==Looper.getMainLooper()) { action.run(); return; }
        AtomicReference<Throwable> failure = new AtomicReference<>();
        test.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get()!=null) throw new AssertionError(failure.get());
    }
    private static void await(Instrumentation test, java.util.function.BooleanSupplier ready, String message) throws Exception {
        long until = SystemClock.uptimeMillis()+8000; AtomicBoolean complete = new AtomicBoolean();
        do { main(test, () -> complete.set(ready.getAsBoolean())); if (complete.get()) return; Thread.sleep(25); }
        while (SystemClock.uptimeMillis()<until);
        throw new AssertionError(message);
    }
}
