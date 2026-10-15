package com.yuen.jpdict;

import android.app.Instrumentation;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.TextView;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

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
        AtomicReference<String> lookup = new AtomicReference<>();
        FloatingSearchView view = new FloatingSearchView(context, "", false, new FloatingSearchView.Callback() {
            public void onQueryChanged(String value) { }
            public void onLookup(String value, boolean privacy) { lookup.set(value); }
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
            check(page.getPaddingBottom() == keyboard && top(composer) == firstComposer,
                    "A newly reported keyboard height cannot abruptly move the cached composer");
            view.dispatchWindowInsetsAnimationStart(show,
                    new WindowInsetsAnimation.Bounds(Insets.NONE, Insets.of(0, 0, 0, changed)));
            for (int current : new int[]{0, changed / 3, changed / 2, changed}) {
                view.dispatchWindowInsetsAnimationProgress(insets(current, density), Collections.singletonList(show));
                view.dispatchApplyWindowInsets(insets(current, density));
                layout(view, width, height);
                check(top(composer) == target && top(empty) == markTarget,
                        "Progress and repeated OEM onApply frames never move or overwrite cached content");
                check(new SearchKeyboardSpace(context).height() == keyboard,
                        "An intermediate onApply inset is never persisted");
            }
            view.dispatchWindowInsetsAnimationEnd(show);
            check(new SearchKeyboardSpace(context).height() == changed,
                    "The completed keyboard height is persisted after animation");
            ValueAnimator calibration = animation(view, "keyboardHeightAnimator");
            check(calibration != null && page.getPaddingBottom() == keyboard,
                    "A genuine keyboard height change begins a smooth calibration from the old position");
            int previousReservation = keyboard;
            for (float progress : new float[]{.25f, .5f, .75f, 1f}) {
                calibration.setCurrentFraction(progress); layout(view, width, height);
                check(page.getPaddingBottom() >= previousReservation && page.getPaddingBottom() <= changed,
                        "Stable height calibration advances monotonically without a jump back");
                previousReservation = page.getPaddingBottom();
            }
            calibration.end();
            layout(view, width, height);
            target = top(composer); markTarget = top(empty);
            check(page.getPaddingBottom() == changed, "Stable height calibration reaches the exact measured target");
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
            WindowInsetsAnimation cancelled = new WindowInsetsAnimation(WindowInsets.Type.ime(), new LinearInterpolator(), 200);
            WindowInsetsAnimation hiding = new WindowInsetsAnimation(WindowInsets.Type.ime(), new LinearInterpolator(), 200);
            view.dispatchWindowInsetsAnimationPrepare(cancelled);
            view.dispatchApplyWindowInsets(insets(changed / 2, density));
            view.dispatchWindowInsetsAnimationPrepare(hiding);
            view.dispatchApplyWindowInsets(insets(0, density));
            view.dispatchWindowInsetsAnimationEnd(cancelled); view.dispatchWindowInsetsAnimationEnd(hiding);
            check(new SearchKeyboardSpace(context).height() == changed,
                    "An interrupted old animation cannot persist a half-height keyboard after hiding");
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

            EditText input = (EditText)field(view, "input");
            input.setText("hold");
            showSuggestions(view, "hold on", "holder");
            layout(view, width, height);
            GridLayout candidates = (GridLayout)field(view, "suggestionWords");
            TextView phrase = candidate(candidates, "hold on");
            TextView typed = candidate(candidates, "hold");
            check(phrase != null && typed != null, "Input and local matches are visible together");
            input.setText("hol");
            check(candidate(candidates, "hold on") == phrase && candidate(candidates, "hol") == typed,
                    "Typing retains existing candidate views and updates the input chip in place");
            phrase.performClick();
            check("hold on".equals(lookup.get()) && input.getText().toString().equals("hol"),
                    "Candidate lookup preserves the complete search draft");
            view.prepareForLookup(); view.resumeAfterLookup();
            check(candidate(candidates, "hold on") == phrase,
                    "Returning from lookup immediately preserves visible candidate objects");
            SearchTextExplosionView explosion = (SearchTextExplosionView)field(view, "textExplosion");
            SearchTextSegments.Page text = SearchTextSegments.page("Please hold on now.", 0);
            explosion.show(text, SearchTextSegments.match(text, Collections.singleton("hold on")), false);
            layout(view, width, height);
            View shortcuts = field(view, "recommendations");
            check(explosion.getVisibility() == View.VISIBLE && top(explosion) >= top(shortcuts) + shortcuts.getHeight(),
                    "Explosion occupies the region below shortcuts");
            check(explosion.getHeight() > 0 && top(explosion) < height - Math.round(24 * density),
                    "Explosion has a scrollable region when the keyboard is hidden");
            int explosionTop = top(explosion), composerTop = top(composer);
            view.dispatchApplyWindowInsets(insets(changed, density)); layout(view, width, height);
            check(top(explosion) == explosionTop && top(composer) == composerTop,
                    "Keyboard may cover explosion without shifting input or explosion");
            view.dispatchApplyWindowInsets(insets(0, density)); layout(view, width, height);
            check(explosion.getVisibility() == View.VISIBLE && top(explosion) == explosionTop,
                    "Hiding the keyboard reveals the retained explosion region");

            int originalHeight = composer.getHeight();
            input.setText("first line\nsecond line\nthird line\nfourth line");
            layout(view, width, height);
            check(composer.getHeight() == originalHeight,
                    "Typing across a line boundary begins at the existing composer height");
            ValueAnimator growth = animation(view, "composerHeightAnimator");
            check(growth != null, "A multiline height change uses a smooth transition");
            growth.setCurrentFraction(.5f); layout(view, width, height);
            int middleHeight = composer.getHeight();
            growth.end(); layout(view, width, height);
            check(middleHeight > originalHeight && composer.getHeight() >= middleHeight,
                    "Input card grows continuously when typing adds visible lines");

            LookupDestinationSettings destination = LookupDestinationSettings.get(context);
            LookupDestinationSettings.Model previous = destination.model();
            try {
                destination.select(LookupDestinationSettings.Model.THIRD_PARTY); refreshDestination(view);
                check(iconKind(field(view, "modelIcon")) == SearchIconView.Kind.CONNECTOR,
                        "Third-party destination uses the matching connector icon");
                destination.select(LookupDestinationSettings.Model.FAST); refreshDestination(view);
                check(iconKind(field(view, "modelIcon")) == SearchIconView.Kind.LIGHTNING,
                        "Fast destination restores the lightning icon");
                destination.toggleSystem(); refreshDestination(view);
                check(iconKind(field(view, "modelIcon")) == SearchIconView.Kind.CONNECTOR,
                        "Joystick destination changes share the same icon source");
            } finally { destination.select(previous); }
        } finally { view.dispose(); }
    }

    private static TextView candidate(GridLayout grid, String value) {
        for (int i = 0; i < grid.getChildCount(); i++) {
            TextView child = (TextView)grid.getChildAt(i);
            if (value.equals(child.getText().toString())) return child;
        }
        return null;
    }
    private static void showSuggestions(FloatingSearchView view, String... words) {
        try {
            java.lang.reflect.Method method = FloatingSearchView.class.getDeclaredMethod("showSuggestions", java.util.List.class);
            method.setAccessible(true); method.invoke(view, Arrays.asList(words));
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static void refreshDestination(FloatingSearchView view) {
        try {
            java.lang.reflect.Method method = FloatingSearchView.class.getDeclaredMethod("refreshDestination");
            method.setAccessible(true); method.invoke(view);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static SearchIconView.Kind iconKind(View icon) {
        try {
            Field field = SearchIconView.class.getDeclaredField("kind"); field.setAccessible(true);
            return (SearchIconView.Kind)field.get(icon);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static ValueAnimator animation(FloatingSearchView view, String name) {
        try {
            Field field = FloatingSearchView.class.getDeclaredField(name); field.setAccessible(true);
            return (ValueAnimator)field.get(view);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
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
