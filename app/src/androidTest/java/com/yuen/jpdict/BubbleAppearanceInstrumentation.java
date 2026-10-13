package com.yuen.jpdict;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.os.SystemClock;
import android.widget.ScrollView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Device tests use an isolated applicationId, never the user's JPDict package. */
public final class BubbleAppearanceInstrumentation extends Instrumentation {
    private int assertions;
    private String mode;
    private String label;
    private String pickerKind;
    private void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    @Override public void onCreate(Bundle args) {
        super.onCreate(args); mode = args == null ? null : args.getString("mode");
        pickerKind = args == null ? null : args.getString("kind");
        label = args == null ? "default" : args.getString("label", "default"); start();
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            check(getTargetContext().getPackageName().endsWith(".v18qa"), "Use a disposable .v18qa build");
            if ("capture".equals(mode)) capture();
            else if ("ocr".equals(mode)) {
                result.putString("stream", OcrValidation.run(this, false));
                finish(Activity.RESULT_OK, result); return;
            }
            else if ("restart-save".equals(mode)) restartSave();
            else if ("restart-check".equals(mode)) restartCheck();
            else if ("picker".equals(mode)) picker();
            else { model(); assertions += BubbleEditorValidation.run(this);
                assertions += BubbleTextRenderingValidation.run(this); ui(); }
            result.putString("stream", "Bubble v18: " + assertions + " device assertions passed\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            StringWriter trace = new StringWriter(); error.printStackTrace(new PrintWriter(trace));
            result.putString("stream", trace.toString()); finish(Activity.RESULT_CANCELED, result);
        }
    }
    private void onMain(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        super.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure.set(error); } });
        if (failure.get() != null) throw new AssertionError("Main thread check failed", failure.get());
    }
    /** External acceptance mode: a human/controller chooses an actual document in the system SAF. */
    private void picker() throws Exception {
        check("image".equals(pickerKind) || "svg".equals(pickerKind), "Picker kind must be image or svg");
        Context c = getTargetContext();
        FloatingBubbleAppearance.Config committed = FloatingBubbleAppearance.load(c);
        List<FloatingBubbleAppearance.Config> history = new ArrayList<>(FloatingBubbleAppearance.savedStyles(c));
        List<File> protectedImages = new ArrayList<>();
        if (!committed.imageName.isEmpty() && FloatingBubbleAppearance.hasCustomImage(c, committed))
            protectedImages.add(new File(c.getFilesDir(), committed.imageName));
        for (FloatingBubbleAppearance.Config item : history)
            if (!item.imageName.isEmpty() && FloatingBubbleAppearance.hasCustomImage(c, item))
                protectedImages.add(new File(c.getFilesDir(), item.imageName));
        Activity activity = startActivitySync(new Intent(c, FloatingBubbleSettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        AtomicReference<FloatingBubbleSettingsView> ref = new AtomicReference<>();
        try {
            waitForIdleSync();
            onMain(() -> ref.set(findSettings(activity.getWindow().getDecorView())));
            FloatingBubbleSettingsView editor = ref.get();
            check(editor != null, "Picker integration uses the actual native settings page");
            FloatingBubbleAppearance.Config[] before = {null};
            onMain(() -> {
                View modeButton = findText(editor, "image".equals(pickerKind) ? "图片" : "默认");
                check(modeButton != null && modeButton.performClick(), "Picker content mode is reachable");
                before[0] = editor.draft();
                View entry = "image".equals(pickerKind) ? findDescription(editor, "上传按钮图片")
                        : before[0].customSvg.isEmpty() ? findDescription(editor, "导入 SVG")
                        : findText(editor, "更换 SVG");
                check(entry != null && entry.performClick(), "Original native picker entry opens SAF");
            });
            Bundle waiting = new Bundle();
            waiting.putString("stream", "QA picker " + pickerKind
                    + ": waiting up to 60 seconds for an actual system document selection\n");
            sendStatus(1, waiting);
            AtomicReference<FloatingBubbleAppearance.Config> imported = new AtomicReference<>();
            long deadline = SystemClock.uptimeMillis() + 60000;
            while (SystemClock.uptimeMillis() < deadline && imported.get() == null) {
                onMain(() -> {
                    check(!activity.isDestroyed() && !activity.isFinishing(), "Settings stays alive during SAF import");
                    FloatingBubbleAppearance.Config draft = editor.draft();
                    boolean changed = draft != before[0] && ("image".equals(pickerKind)
                            ? draft.style == FloatingBubbleAppearance.STYLE_IMAGE
                                && !draft.imageName.isEmpty() && !draft.imageName.equals(before[0].imageName)
                            : draft.style == FloatingBubbleAppearance.STYLE_SEARCH
                                && draft.icon == FloatingBubbleAppearance.ICON_CUSTOM && !draft.customSvg.isEmpty());
                    if (changed && activity.hasWindowFocus()) imported.set(draft);
                });
                if (imported.get() == null) SystemClock.sleep(200);
            }
            check(imported.get() != null, "Actual SAF selection returns a changed draft and foreground focus within 60 seconds");
            FloatingBubbleAppearance.Config draft = imported.get();
            check(draft.presetId.equals(before[0].presetId), "Import preserves selected preset identity");
            FloatingBubbleAppearance.Config after = FloatingBubbleAppearance.load(c);
            check(committed.matches(after) && committed.presetId.equals(after.presetId),
                    "Actual picker import cannot commit any appearance field or preset ID");
            List<FloatingBubbleAppearance.Config> actual = FloatingBubbleAppearance.savedStyles(c);
            check(history.size() == actual.size(), "Actual picker import keeps history count");
            for (int i = 0; i < history.size(); i++)
                check(history.get(i).matches(actual.get(i)) && history.get(i).presetId.equals(actual.get(i).presetId),
                        "Actual picker import preserves every complete ordered saved style");
            if ("image".equals(pickerKind)) {
                check(draft.imageName.matches("floating-bubble-image-[a-zA-Z0-9-]+\\.png"),
                        "SAF image retains an immutable private-copy name, without a provider URI");
                File image = new File(c.getFilesDir(), draft.imageName).getCanonicalFile();
                check(image.getParentFile().equals(c.getFilesDir().getCanonicalFile()) && image.isFile(),
                        "SAF image copy exists exclusively in the application private directory");
                BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(image.getAbsolutePath(), bounds);
                check(bounds.outWidth > 0 && bounds.outHeight > 0
                                && Math.max(bounds.outWidth, bounds.outHeight) <= 256,
                        "Actual picked image decodes and retains the existing 256 pixel bound");
                check(draft.imageZoomPercent == 100 && draft.imageXPercent == 0 && draft.imageYPercent == 0,
                        "Actual image selection resets zoom and position to HTML defaults");
            } else {
                FloatingBubbleAppearance.validateSvg(draft.customSvg);
                check(draft.customSvg.getBytes(StandardCharsets.UTF_8).length <= 120 * 1024,
                        "Actual SVG source respects the bounded import size");
                check(draft.customSvgName.toLowerCase(Locale.ROOT).endsWith(".svg")
                                && draft.customSvgName.equals(FloatingBubbleAppearance.safeSvgName(draft.customSvgName)),
                        "Actual SVG keeps a safe display name");
            }
        } finally {
            onMain(activity::finish); waitForIdleSync();
            long finishDeadline = SystemClock.uptimeMillis() + 5000;
            while (!activity.isDestroyed() && SystemClock.uptimeMillis() < finishDeadline) SystemClock.sleep(100);
            check(activity.isDestroyed(), "Picker acceptance waits for settings destruction");
            check(!FloatingBubbleAppearance.isPreviewing(), "Picker acceptance exit clears transient preview");
            FloatingBubbleAppearance.Config restored = FloatingBubbleAppearance.load(c);
            check(committed.matches(restored) && committed.presetId.equals(restored.presetId),
                    "Picker acceptance exit restores the complete committed appearance");
            for (File image : protectedImages) check(image.isFile(), "Picker exit retains each previously referenced image");
        }
    }
    private void restartSave() throws Exception {
        Context c=getTargetContext();
        FloatingBubbleAppearance.Builder b=new FloatingBubbleAppearance.Builder();
        b.shape=FloatingBubbleAppearance.SHAPE_HEXAGON;
        b.backgroundPaint=FloatingBubbleAppearance.defaultPaint(0xffab3456).withMode(2);
        b.iconPaint=FloatingBubbleAppearance.defaultPaint(0xffeeeeee).withMode(1).withAngle(270);
        check(FloatingBubbleAppearance.addSavedStyle(c,b.build())!=null,"Persist complete restart fixture");
        JSONArray styles=new JSONArray();
        for(FloatingBubbleAppearance.Config config:FloatingBubbleAppearance.savedStyles(c))
            styles.put(FloatingBubbleAppearance.toJson(config));
        check(c.getSharedPreferences("v18_restart_expected",0).edit()
                .putString("current",FloatingBubbleAppearance.toJson(FloatingBubbleAppearance.load(c)).toString())
                .putString("styles",styles.toString()).commit(),"Record independent restart expectation");
    }
    private void restartCheck() throws Exception {
        Context c=getTargetContext(); SharedPreferences expected=c.getSharedPreferences("v18_restart_expected",0);
        FloatingBubbleAppearance.Config before=FloatingBubbleAppearance.fromJson(new JSONObject(expected.getString("current","{}")));
        FloatingBubbleAppearance.Config after=FloatingBubbleAppearance.load(c);
        check(before.matches(after) && before.presetId.equals(after.presetId),"Process restart preserves full current appearance and ID");
        JSONArray styles=new JSONArray(expected.getString("styles","[]"));
        List<FloatingBubbleAppearance.Config> actual=FloatingBubbleAppearance.savedStyles(c);
        check(styles.length()==actual.size(),"Process restart preserves history count");
        for(int i=0;i<styles.length();i++) {
            FloatingBubbleAppearance.Config old=FloatingBubbleAppearance.fromJson(styles.getJSONObject(i));
            check(old.presetId.equals(actual.get(i).presetId) && old.matches(actual.get(i)),"Restart keeps each complete ordered preset");
        }
    }
    private void model() throws Exception {
        Context c = getTargetContext();
        SharedPreferences p = c.getSharedPreferences("floating_bubble_appearance", Context.MODE_PRIVATE);
        check(p.edit().clear().commit(), "Isolated preferences ready");
        JSONObject legacy = new JSONObject().put("size_dp", 68).put("opacity_percent", 80)
                .put("style", FloatingBubbleAppearance.STYLE_CUSTOM).put("roundness_percent", 42)
                .put("text", "日本😀").put("color", 0xff123456);
        p.edit().putInt("size_dp", 68).putInt("opacity_percent", 80).putInt("style", 2)
                .putInt("roundness_percent", 42).putString("text", "日本😀").putInt("color", 0xff123456)
                .putString("saved_styles", new JSONArray().put(legacy).put(legacy).toString()).commit();
        List<FloatingBubbleAppearance.Config> old = FloatingBubbleAppearance.savedStyles(c);
        check(old.size() == 2, "Visually equal old cards are both retained");
        check(!old.get(0).presetId.equals(old.get(1).presetId), "Old cards receive distinct stable IDs");
        check(old.get(0).presetId.equals(FloatingBubbleAppearance.savedStyles(c).get(0).presetId), "Legacy IDs remain stable");
        check(old.get(0).legacyVisual && old.get(0).roundnessPercent == 42
                && old.get(0).text.equals("日本😀"), "Continuous corners and Unicode old visual preserved");
        check(FloatingBubbleAppearance.load(c).presetId.equals(old.get(0).presetId), "Committed old selection migrates once");
        check(FloatingBubbleAppearance.save(c, old.get(0)), "Migrated card saves");
        check(p.getInt("data_version", 0) == FloatingBubbleAppearance.DATA_VERSION, "Explicit persisted version");

        FloatingBubbleAppearance.Builder b = new FloatingBubbleAppearance.Builder();
        b.shape = FloatingBubbleAppearance.SHAPE_STAR; b.sizeDp = 100; b.opacityPercent = 63;
        b.icon = FloatingBubbleAppearance.ICON_CUSTOM; b.iconSizePercent = 145;
        b.customSvg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'><g transform='translate(1 1)'><path d='M2 2L20 2L11 20Z' fill='black'/></g></svg>";
        b.customSvgName = "三角.svg"; b.text = "中文日本English";
        b.iconPaint = FloatingBubbleAppearance.defaultPaint(0xff123456).withMode(FloatingBubbleAppearance.COLOR_LINEAR).withAngle(270);
        b.backgroundPaint = FloatingBubbleAppearance.defaultPaint(0xffabcdef).withMode(FloatingBubbleAppearance.COLOR_FREE);
        b.imageZoomPercent = 233; b.imageXPercent = -47; b.imageYPercent = 81;
        FloatingBubbleAppearance.Config full = b.build();
        check(full.matches(FloatingBubbleAppearance.fromJson(FloatingBubbleAppearance.toJson(full))), "Complete JSON round trip");
        check(full.matches(FloatingBubbleAppearance.fromBundle(full.toBundle())), "Complete rotation bundle round trip");
        check(full.iconPaint != full.backgroundPaint && full.iconPaint.mode == 1 && full.backgroundPaint.mode == 2, "Independent paints");
        check(!FloatingBubbleAppearance.save(c, full), "No selection cannot accidentally add through save");
        FloatingBubbleAppearance.validateSvg(full.customSvg);
        check(BubbleSvg.parse("<?xml version='1.0' encoding='UTF-8'?>" + full.customSvg) != null,
                "Ordinary XML declaration preserves a valid drawing-only SVG");
        String[] unsafe = {
            "<!DOCTYPE svg [<!ENTITY a SYSTEM 'file:///etc/passwd'>]><svg/>",
            "<svg><script>alert(1)</script></svg>",
            "<svg><image href='https://example.com/x.png'/></svg>",
            "<svg onload='alert(1)'><path d='M0 0L1 1'/></svg>",
            "<svg><path d='M0 0L1 1' fill='url(https://example.com)'/></svg>",
            "<svg><filter id='a'/></svg>",
            "<svg><text>unsupported</text></svg>",
            "<?xml-stylesheet type='text/css' href='https://example.com/style.css'?><svg><circle r='1'/></svg>",
            "<?unsupported styling?><svg><circle r='1'/></svg>",
            "<svg><![CDATA[unsupported text]]><circle r='1'/></svg>",
            "<svg>&#65;<circle r='1'/></svg>",
            "<svg viewBox='0 0 1e-38 1e-38'><circle r='1'/></svg>",
            "<svg viewBox='100000 100000 .000001 .000001'><circle r='1'/></svg>"};
        for (String source : unsafe) {
            boolean rejected = false;
            try { FloatingBubbleAppearance.validateSvg(source); } catch (IllegalArgumentException expected) { rejected = true; }
            check(rejected, "Unsafe or unsupported SVG explicitly rejected");
        }
        StringBuilder many = new StringBuilder("<svg>");
        for (int i = 0; i < 501; i++) many.append("<circle cx='1' cy='1' r='1'/>");
        many.append("</svg>");
        boolean limited = false;
        try { FloatingBubbleAppearance.validateSvg(many.toString()); } catch (IllegalArgumentException expected) { limited = true; }
        check(limited, "SVG complexity bounded");
        List<String> svgSources=new ArrayList<>(); List<BubbleSvg> compiled=new ArrayList<>();
        for(int i=0;i<11;i++) {
            String source="<svg viewBox='0 0 100 100'><circle cx='50' cy='50' r='"+(i+1)+"'/></svg>";
            svgSources.add(source); compiled.add(BubbleSvg.parse(source));
        }
        for(int i=0;i<11;i++)check(BubbleSvg.parse(svgSources.get(i))==compiled.get(i),
                "Ten SVG cards and the draft reuse compiled drawings");
        for(int i=0;i<25;i++)BubbleSvg.parse("<svg><circle cx='"+(i+70)+"' r='1'/></svg>");
        java.lang.reflect.Field svgCache=BubbleSvg.class.getDeclaredField("CACHE"); svgCache.setAccessible(true);
        check(((java.util.Map<?,?>)svgCache.get(null)).size()<=16,"SVG compiled cache remains bounded");

        FloatingBubbleAppearance.Config a = FloatingBubbleAppearance.addSavedStyle(c, full);
        FloatingBubbleAppearance.Config duplicate = FloatingBubbleAppearance.addSavedStyle(c, full);
        check(a != null && duplicate != null && !a.presetId.equals(duplicate.presetId), "Plus allows identical visuals with unique IDs");
        List<FloatingBubbleAppearance.Config> before = FloatingBubbleAppearance.savedStyles(c);
        b = new FloatingBubbleAppearance.Builder(a); b.sizeDp = 24; b.shape = FloatingBubbleAppearance.SHAPE_DIAMOND;
        FloatingBubbleAppearance.Config changed = b.build();
        for (int i = 0; i < 6; i++) check(FloatingBubbleAppearance.save(c, changed), "Repeated overwrite succeeds");
        List<FloatingBubbleAppearance.Config> after = FloatingBubbleAppearance.savedStyles(c);
        check(before.size() == after.size() && after.get(1).presetId.equals(a.presetId)
                && after.get(1).sizeDp == 24 && after.get(0).presetId.equals(duplicate.presetId), "Overwrite preserves count and position");
        while (FloatingBubbleAppearance.savedStyles(c).size() < 10)
            check(FloatingBubbleAppearance.addSavedStyle(c, full) != null, "Fill history through plus");
        check(FloatingBubbleAppearance.addSavedStyle(c, full) == null, "Ten blocks only plus");
        check(FloatingBubbleAppearance.save(c, changed), "Ten still allows overwriting");
        check(FloatingBubbleAppearance.deleteSavedStyle(c, changed.presetId), "Delete selected ID");
        check(FloatingBubbleAppearance.load(c).presetId.isEmpty(), "Delete committed selection clears ID but retains visual");
        check(FloatingBubbleAppearance.load(c).matches(changed), "Delete keeps current complete appearance");
        check(FloatingBubbleAppearance.savedStyles(c).size() == 9, "Delete decrements once");

        File image = new File(c.getFilesDir(), "floating-bubble-image-v18fixture.png");
        Bitmap fixture = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888); fixture.eraseColor(0xffe44262);
        try (FileOutputStream out = new FileOutputStream(image)) { fixture.compress(Bitmap.CompressFormat.PNG, 100, out); }
        fixture.recycle();
        b = new FloatingBubbleAppearance.Builder(); b.style = FloatingBubbleAppearance.STYLE_IMAGE; b.imageName = image.getName();
        FloatingBubbleAppearance.Config img = FloatingBubbleAppearance.addSavedStyle(c, b.build());
        check(img != null, "Image preset stores private reference");
        check(FloatingBubbleAppearance.save(c, duplicate), "Switch committed appearance away from image");
        FloatingBubbleAppearance.cleanupImages(c, null);
        check(image.isFile(), "Historical image reference prevents deletion");
        check(FloatingBubbleAppearance.deleteSavedStyle(c, img.presetId), "Delete last image preset");
        check(!image.exists(), "Only unreferenced image is cleaned");
        int[] pixels = BubbleColorField.pixels(FloatingBubbleAppearance.defaultFreePoints(), 240, 180);
        int[][] samples = {{0,0,240,120,186},{239,179,68,211,176},{120,90,128,146,216},
                {43,32,233,119,191},{199,145,66,205,182}};
        for (int[] sample : samples) {
            int rgb = pixels[sample[1] * 240 + sample[0]];
            check(Math.abs(((rgb >>> 16) & 255) - sample[2]) <= 1
                    && Math.abs(((rgb >>> 8) & 255) - sample[3]) <= 1
                    && Math.abs((rgb & 255) - sample[4]) <= 1, "Free field matches independently captured HTML pixel");
        }
        render(c);
        // Start UI at one selected, fully opaque style for consistent screenshot comparison.
        p.edit().clear().commit();
        b = new FloatingBubbleAppearance.Builder(); b.opacityPercent = 100;
        check(FloatingBubbleAppearance.addSavedStyle(c, b.build()) != null, "UI selected fixture committed");
    }
    private void render(Context c) {
        onMain(() -> {
            for (int shape = 0; shape < 6; shape++) {
                FloatingBubbleAppearance.Builder b = new FloatingBubbleAppearance.Builder(); b.shape = shape;
                Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
                BubbleAppearanceRenderer.draw(new Canvas(bitmap), c, b.build(), new RectF(0, 0, 100, 100), null);
                check((bitmap.getPixel(50, 50) >>> 24) != 0, "Six shape centers render");
                check((bitmap.getPixel(0, 0) >>> 24) == 0, "Shape corners clip"); bitmap.recycle();
            }
            for (int icon = 0; icon < 6; icon++) {
                FloatingBubbleAppearance.Builder b = new FloatingBubbleAppearance.Builder(); b.icon = icon;
                Bitmap bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
                BubbleAppearanceRenderer.draw(new Canvas(bitmap), c, b.build(), new RectF(0, 0, 100, 100), null);
                int light = 0;
                for (int y = 10; y < 90; y++) for (int x = 10; x < 90; x++) if ((bitmap.getPixel(x, y) & 0xffffff) == 0xffffff) light++;
                check(light > 3, "Every built-in SVG icon paints a centered mask"); bitmap.recycle();
            }
            FloatingBubbleAppearance.Builder legacy = new FloatingBubbleAppearance.Builder(
                    new FloatingBubbleAppearance.Config(44, 100, FloatingBubbleAppearance.STYLE_SEARCH,
                            100, "あ", 0xff008fd1, ""));
            legacy.iconPaint = FloatingBubbleAppearance.defaultPaint(0xffff0000);
            Bitmap oldSearch = Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);
            BubbleAppearanceRenderer.draw(new Canvas(oldSearch),c,legacy.build(),new RectF(0,0,100,100),null);
            int red = 0;
            for(int y=0;y<100;y++)for(int x=0;x<100;x++)if((oldSearch.getPixel(x,y)&0xffffff)==0xff0000)red++;
            check(red > 3, "Legacy search keeps geometry but accepts independent Icon color"); oldSearch.recycle();
        });
    }
    private void ui() throws Exception {
        Activity activity = startActivitySync(new Intent(getTargetContext(), FloatingBubbleSettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        AtomicReference<FloatingBubbleSettingsView> ref = new AtomicReference<>();
        onMain(() -> ref.set(findSettings(activity.getWindow().getDecorView())));
        FloatingBubbleSettingsView editor = ref.get();
        check(editor != null, "Native settings page opens");
        onMain(() -> {
            check(editor.draft().matches(FloatingBubbleAppearance.current(getTargetContext())), "Real service facade receives draft immediately");
            View image = findText(editor, "图片"); check(image != null, "Three content modes present"); image.performClick();
            check(editor.draft().style == FloatingBubbleAppearance.STYLE_IMAGE, "Image mode does not silently launch picker or save");
            View defaults = findText(editor, "默认"); check(defaults != null, "Default mode present"); defaults.performClick();
            View gesture = findText(editor.stickyHeader(), "手势控制"); check(gesture != null, "Gesture tab present"); gesture.performClick();
            findDescription(activity.getWindow().getDecorView(), "返回").performClick();
            check(!activity.isFinishing(), "Toolbar back from gestures returns to appearance");
            check(!editor.handleBack(), "Appearance back delegates to existing route");
            Bundle saved = editor.saveDraftState();
            FloatingBubbleSettingsView rebuilt = new FloatingBubbleSettingsView(getTargetContext(),
                    FloatingBubbleAppearance.fromBundle(saved), config -> {}, () -> {}, () -> {});
            rebuilt.restoreEditorState(saved);
            check(editor.draft().matches(rebuilt.draft()), "Editor reconstruction keeps draft");
            check(editor.getWidth() <= activity.getWindow().getDecorView().getWidth(), "Page has no overall horizontal overflow");
        });
        assertions += BubbleEditorValidation.runMounted(this, activity, editor);
        // SAF cancellation must return to the same draft and never commit it.
        FloatingBubbleAppearance.Config committed = FloatingBubbleAppearance.load(getTargetContext());
        onMain(() -> { findText(editor, "图片").performClick();
            findDescription(editor, "上传按钮图片").performClick(); });
        waitForIdleSync(); SystemClock.sleep(500);
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        waitForIdleSync();
        for(int i=0;i<25 && !activity.hasWindowFocus();i++)SystemClock.sleep(200);
        check(activity.hasWindowFocus(), "SAF cancellation returns foreground focus to editor");
        check(!activity.isDestroyed(), "SAF cancellation keeps the tested editor instance");
        check(!activity.isFinishing(), "SAF cancellation retains editor activity");
        check(editor.draft().style == FloatingBubbleAppearance.STYLE_IMAGE, "SAF cancellation retains image-mode draft");
        check(committed.matches(FloatingBubbleAppearance.load(getTargetContext())), "SAF cancellation never saves draft");
        // Recreate a real activity, including an invalid hidden color field.
        onMain(() -> { findText(editor, "默认").performClick();
            android.widget.EditText hex = (android.widget.EditText)findDescription(editor, "纯色十六进制值");
            check(hex != null, "Rotation test uses actual HEX field"); hex.setText("#GGGGGG");
            Bundle progress=new Bundle(); progress.putFloat("android.view.accessibility.action.ARGUMENT_PROGRESS_VALUE",53);
            check(findDescription(editor,"大小").performAccessibilityAction(16908349,progress),"Platform size action edits real draft");
            check(editor.draft().sizeDp==77,"Recreation fixture is an unsaved size edit"); });
        FloatingBubbleAppearance.Config draft = editor.draft();
        ActivityMonitor monitor = addMonitor(FloatingBubbleSettingsActivity.class.getName(), null, false);
        onMain(activity::recreate);
        Activity rotated = monitor.waitForActivityWithTimeout(5000); removeMonitor(monitor);
        check(rotated != null, "Real activity recreation completes"); waitForIdleSync();
        AtomicReference<FloatingBubbleSettingsView> rotatedEditor = new AtomicReference<>();
        onMain(() -> rotatedEditor.set(findSettings(rotated.getWindow().getDecorView())));
        check(draft.matches(rotatedEditor.get().draft()), "Real recreation preserves draft and selected ID: before="
                +FloatingBubbleAppearance.toJson(draft)+" after="+FloatingBubbleAppearance.toJson(rotatedEditor.get().draft()));
        onMain(() -> check("#GGGGGG".contentEquals(((android.widget.EditText)findDescription(
                rotatedEditor.get(), "纯色十六进制值")).getText()), "Real recreation preserves invalid HEX presentation"));
        check(rotated.getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT,
                "Direction-change fixture starts in portrait");
        ActivityMonitor orientationMonitor = addMonitor(FloatingBubbleSettingsActivity.class.getName(), null, false);
        Activity landscape;
        try {
            onMain(() -> rotated.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
            landscape = orientationMonitor.waitForActivityWithTimeout(5000);
        } finally { removeMonitor(orientationMonitor); }
        check(landscape != null && landscape != rotated, "Landscape request creates a new configuration Activity");
        waitForIdleSync();
        for(int i=0;i<25 && !landscape.hasWindowFocus();i++)SystemClock.sleep(200);
        check(landscape.hasWindowFocus(), "Landscape editor returns foreground focus after configuration change");
        onMain(() -> {
            check(landscape.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE,
                    "Resources reflect an actual landscape configuration");
            check(rotated.isDestroyed(), "Actual direction change destroys the previous Activity");
            FloatingBubbleSettingsView page = findSettings(landscape.getWindow().getDecorView());
            check(page != null && draft.matches(page.draft()) && draft.presetId.equals(page.draft().presetId),
                    "Actual landscape configuration preserves complete draft and selected preset ID");
            android.widget.EditText pending = (android.widget.EditText)findDescription(page, "纯色十六进制值");
            check(pending != null && "#GGGGGG".contentEquals(pending.getText()),
                    "Actual landscape configuration preserves invalid HEX input");
            View shape = findDescription(page, "圆形");
            check(shape != null, "Landscape editor retains the native shape selection");
            ViewGroup sidebar = (ViewGroup)shape.getParent(), dual = (ViewGroup)sidebar.getParent();
            check(dual.getChildCount() == 3 && dual.getChildAt(0) == sidebar
                            && dual.getChildAt(2).getLeft() > sidebar.getRight()
                            && sidebar.getWidth() > 0 && dual.getChildAt(2).getWidth() > 0,
                    "Actual landscape layout keeps the side-by-side shape and editor columns");
            check(dual.getRight() <= page.getWidth() && page.getWidth() > 0
                            && page.getWidth() <= landscape.getWindow().getDecorView().getWidth(),
                    "Actual landscape layout has no overall horizontal overflow");
        });
        onMain(landscape::finish); waitForIdleSync();
        for(int i=0;i<25 && !landscape.isDestroyed();i++)SystemClock.sleep(200);
        check(landscape.isDestroyed(), "Finished landscape editor completes its asynchronous lifecycle");
        check(!FloatingBubbleAppearance.isPreviewing(), "Finishing clears preview owner");
    }
    private void capture() throws Exception {
        Context c = getTargetContext();
        SharedPreferences p = c.getSharedPreferences("floating_bubble_appearance", Context.MODE_PRIVATE);
        p.edit().clear().commit();
        FloatingBubbleAppearance.Builder seed = new FloatingBubbleAppearance.Builder();
        seed.opacityPercent = 25;
        seed.backgroundPaint = FloatingBubbleAppearance.defaultPaint(0xff9da7b1);
        FloatingBubbleAppearance.addSavedStyle(c, seed.build());
        Activity activity = startActivitySync(new Intent(c, FloatingBubbleSettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();
        AtomicReference<FloatingBubbleSettingsView> ref = new AtomicReference<>();
        onMain(() -> ref.set(findSettings(activity.getWindow().getDecorView())));
        FloatingBubbleSettingsView editor = ref.get();
        onMain(() -> { Intent service = new Intent(activity, FloatingService.class); if (android.os.Build.VERSION.SDK_INT >= 26) activity.startForegroundService(service); else activity.startService(service); });
        waitForIdleSync(); screenshot(c, "top");
        onMain(() -> findText(editor, "线性渐变").performClick());
        waitForIdleSync();
        onMain(() -> findScroll(activity.getWindow().getDecorView()).scrollTo(0, Math.round(270 * c.getResources().getDisplayMetrics().density)));
        waitForIdleSync(); screenshot(c, "linear");
        onMain(() -> findText(editor, "自由渐变").performClick());
        waitForIdleSync(); screenshot(c, "free");
        onMain(() -> findScroll(activity.getWindow().getDecorView()).fullScroll(View.FOCUS_DOWN));
        waitForIdleSync(); screenshot(c, "free-detail");
        File image = new File(c.getFilesDir(), "floating-bubble-image-v18screenshot.png");
        Bitmap picture = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888);
        picture.eraseColor(0xffe44262); Canvas canvas = new Canvas(picture);
        android.graphics.Paint paint = new android.graphics.Paint(); paint.setColor(0xff19b6da);
        canvas.drawRect(80, 40, 160, 120, paint);
        try (FileOutputStream out = new FileOutputStream(image)) { picture.compress(Bitmap.CompressFormat.PNG, 100, out); }
        picture.recycle();
        onMain(() -> { editor.setDraftImage(image.getName()); findScroll(activity.getWindow().getDecorView()).scrollTo(0, 0); });
        waitForIdleSync(); screenshot(c, "image");
        onMain(() -> findScroll(activity.getWindow().getDecorView()).fullScroll(View.FOCUS_DOWN));
        waitForIdleSync(); screenshot(c, "image-detail");
        onMain(() -> { findText(editor, "文字").performClick(); View input = findDescription(editor, "按钮显示文字");
            if (input != null) { input.requestFocus(); ((android.view.inputmethod.InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input, 0); } });
        waitForIdleSync(); SystemClock.sleep(800); screenshot(c, "keyboard");
        onMain(activity::finish); waitForIdleSync();
        for(int i=0;i<25 && !activity.isDestroyed();i++)SystemClock.sleep(200);
        check(activity.isDestroyed(),"Screenshot rollback waits for destroyed editor");
        check(!FloatingBubbleAppearance.isPreviewing(),"Screenshot rollback clears draft preview");
        screenshot(c, "rollback");
    }
    private void screenshot(Context c, String name) throws Exception {
        SystemClock.sleep(250); waitForIdleSync();
        Bitmap bitmap = getUiAutomation().takeScreenshot();
        check(bitmap != null, "Screenshot captured");
        File dir = c.getExternalFilesDir("v18-qa"); if (dir != null) dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "native-" + label + "-" + name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        bitmap.recycle();
    }
    private static ScrollView findScroll(View v) {
        if (v instanceof ScrollView) return (ScrollView)v;
        if (v instanceof ViewGroup) for (int i=0;i<((ViewGroup)v).getChildCount();i++) {
            ScrollView found = findScroll(((ViewGroup)v).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private static View findDescription(View v, String text) {
        if (v.getContentDescription() != null && text.contentEquals(v.getContentDescription())) return v;
        if (v instanceof ViewGroup) for (int i=0;i<((ViewGroup)v).getChildCount();i++) {
            View found = findDescription(((ViewGroup)v).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private static FloatingBubbleSettingsView findSettings(View v) {
        if (v instanceof FloatingBubbleSettingsView) return (FloatingBubbleSettingsView) v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)v).getChildCount(); i++) {
            FloatingBubbleSettingsView found = findSettings(((ViewGroup)v).getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private static View findText(View v, String text) {
        if (v instanceof TextView && text.contentEquals(((TextView)v).getText())) return v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)v).getChildCount(); i++) {
            View found = findText(((ViewGroup)v).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
}
