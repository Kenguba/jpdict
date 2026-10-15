package com.yuen.jpdict;

import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.Collections;

/** Measures real search views frame by frame; isolated QA package, no keyboard/model dependency. */
public final class SearchLayoutInstrumentation extends Instrumentation {
    private int checks;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context context = getTargetContext();
            if ("com.yuen.jpdict".equals(context.getPackageName())) {
                throw new AssertionError("Use an independent disposable QA applicationId");
            }
            Throwable[] problem = new Throwable[1];
            runOnMainSync(() -> { try {
                check(android.os.Build.VERSION.SDK_INT >= 30, "IME animation tests require API 30+");
                Configuration config = new Configuration(context.getResources().getConfiguration());
                for (float fontScale : new float[]{1f, 1.3f}) {
                    config.fontScale = fontScale;
                    config.orientation = Configuration.ORIENTATION_PORTRAIT;
                    verify(context.createConfigurationContext(config));
                }
            } catch (Throwable failure) { problem[0] = failure; } });
            if (problem[0] != null) throw new AssertionError(problem[0]);
            result.putString("stream", "Search layout: " + checks + " assertions passed\n");
            finish(android.app.Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            StringWriter trace = new StringWriter(); failure.printStackTrace(new PrintWriter(trace));
            result.putString("stream", trace.toString());
            finish(android.app.Activity.RESULT_CANCELED, result);
        }
    }

    private void verify(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        int width = Math.round(393 * density), height = Math.round(852 * density);
        int keyboard = Math.round(340 * density);
        context.getSharedPreferences("search_keyboard", Context.MODE_PRIVATE).edit()
                .putFloat("height_1", 340).putFloat("height_2", 180).commit();
        FloatingSearchView view = new FloatingSearchView(context, "", false, new FloatingSearchView.Callback() {
            public void onQueryChanged(String value) { }
            public void onLookup(String value, boolean privacy) { }
            public void onPrivateModeChanged(boolean value) { }
            public void onEditingChanged(boolean value) { }
            public void onOpenPage(String page, boolean privacy) { }
            public void onClose() { }
        });
        try {
            view.dispatchApplyWindowInsets(insets(0, density));
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            check(field(view, "page").getPaddingBottom() == keyboard,
                    "Cached keyboard space is applied before the first layout, while root height is zero");
            layout(view, width, height);
            View composer = field(view, "composer"), body = field(view, "body");
            View note = field(view, "suggestionArea"), empty = field(view, "emptyView");
            LinearLayout page = (LinearLayout)field(view, "page");
            int firstComposer = top(composer), firstMark = top(empty);
            layout(view, width, height);
            check(top(composer) == firstComposer && top(empty) == firstMark,
                    "First frame already uses cached keyboard height; the second frame cannot jump");
            check(page.getPaddingBottom() == keyboard, "Portrait cache is reserved before IME visibility");
            check(note.getHeight() < Math.round(60 * density), "An empty hint does not reserve four blank rows");
            check(Math.abs((empty.getTop() + empty.getBottom()) - body.getHeight()) <= 1,
                    "Book is centered in the region between header and actual suggestions");
            View header = field(view, "header");
            check(Math.abs((top(empty) + empty.getHeight() / 2f)
                    - (top(header) + header.getHeight() + top(note)) / 2f) <= 5 * density,
                    "Visual center includes the full blank region above the hint");

            int changed = Math.round(380 * density);
            WindowInsetsAnimation show = new WindowInsetsAnimation(WindowInsets.Type.ime(), new LinearInterpolator(), 200);
            view.dispatchWindowInsetsAnimationPrepare(show);
            view.dispatchApplyWindowInsets(insets(changed, density));
            layout(view, width, height);
            int target = top(composer), markTarget = top(empty);
            check(page.getPaddingBottom() == changed, "New IME destination is known before animation frames");
            view.dispatchWindowInsetsAnimationStart(show,
                    new WindowInsetsAnimation.Bounds(Insets.NONE, Insets.of(0, 0, 0, changed)));
            for (int current : new int[]{0, changed / 3, changed / 2, changed}) {
                view.dispatchWindowInsetsAnimationProgress(insets(current, density), Collections.singletonList(show));
                layout(view, width, height);
                check(top(composer) == target && top(empty) == markTarget,
                        "Keyboard animation progress never moves reserved search content");
            }
            view.dispatchWindowInsetsAnimationEnd(show);
            view.dispatchApplyWindowInsets(insets(changed, density));
            layout(view, width, height);
            check(top(composer) == target, "Animation end introduces no delayed height correction");
            for (int cycle = 0; cycle < 3; cycle++) {
                view.dispatchApplyWindowInsets(insets(0, density));
                layout(view, width, height);
                check(top(composer) == target && top(empty) == markTarget, "Hiding preserves input and book positions");
                view.dispatchApplyWindowInsets(insets(changed, density));
                layout(view, width, height);
                check(top(composer) == target, "Repeated showing preserves input position");
            }
            // OEM progress without onPrepare must also never poison the saved final height.
            view.dispatchWindowInsetsAnimationProgress(insets(changed / 2, density), Collections.emptyList());
            check(new SearchKeyboardSpace(context).reserved(height) == changed,
                    "A partial IME frame cannot replace the persisted height");
            view.dispatchApplyWindowInsets(insets(0, density));
            layout(view, height, width);
            int narrowFirst = top(composer);
            layout(view, height, width);
            check(top(composer) == narrowFirst && header.getVisibility() == View.GONE,
                    "A short viewport uses compact layout from its first measurement");
            layout(view, width, height);
            check(top(composer) == target && header.getVisibility() == View.VISIBLE,
                    "Restoring available height restores normal layout without a trailing frame");

            Configuration landscape = new Configuration(context.getResources().getConfiguration());
            landscape.orientation = Configuration.ORIENTATION_LANDSCAPE;
            check(new SearchKeyboardSpace(context.createConfigurationContext(landscape)).reserved(height)
                    == Math.round(180 * density), "Keyboard heights remain independent by orientation");
        } finally { view.dispose(); }
    }

    private static WindowInsets insets(int keyboard, float density) {
        return new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, Math.round(24 * density), 0, Math.round(24 * density)))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, keyboard))
                .setVisible(WindowInsets.Type.ime(), keyboard > 0).build();
    }
    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }
    private static int top(View view) {
        int top = view.getTop();
        while (view.getParent() instanceof View) { view = (View)view.getParent(); top += view.getTop(); }
        return top;
    }
    private static View field(FloatingSearchView view, String name) {
        try {
            Field field = FloatingSearchView.class.getDeclaredField(name); field.setAccessible(true);
            return (View)field.get(view);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
}
