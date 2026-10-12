package com.yuen.jpdict;

import java.util.ArrayList;
import java.util.List;

/** Value and history rules run on the JDK against the app's compiled classes. */
public final class FloatingBubbleAppearanceTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }

    public static void main(String[] args) {
        FloatingBubbleAppearance.Config defaults = FloatingBubbleAppearance.defaults();
        check(defaults.sizeDp == 44 && defaults.opacityPercent == 25
                && defaults.roundnessPercent == 100);
        FloatingBubbleAppearance.Config bounds = new FloatingBubbleAppearance.Config(
                -1, -10, -1, 120, "", 0x168af2);
        check(bounds.sizeDp == 32 && bounds.opacityPercent == 0
                && bounds.roundnessPercent == 100 && bounds.style == 0);
        check(bounds.color == 0xff168af2 && bounds.text.equals("あ"));
        FloatingBubbleAppearance.Config max = new FloatingBubbleAppearance.Config(
                999, 999, 2, -1, "😀😁😂😃😄", 0);
        check(max.sizeDp == 88 && max.opacityPercent == 100 && max.roundnessPercent == 0
                && max.text.codePointCount(0, max.text.length()) == 4);
        check(!max.matches(defaults));
        check(defaults.matches(new FloatingBubbleAppearance.Config(44, 25, 0, 100, "あ", 0xff168af2)));
        FloatingBubbleAppearance.Config imageA = new FloatingBubbleAppearance.Config(
                44, 25, 3, 100, "a", 0, "floating-bubble-image-123.png");
        FloatingBubbleAppearance.Config imageB = new FloatingBubbleAppearance.Config(
                44, 25, 3, 100, "a", 0, "floating-bubble-image-456.png");
        check(!imageA.matches(imageB));
        check(new FloatingBubbleAppearance.Config(44, 25, 3, 100, "a", 0, "../../elsewhere")
                .imageName.equals("floating-bubble-image.png"));

        List<FloatingBubbleAppearance.Config> history = new ArrayList<>();
        for (int opacity = 0; opacity < 12; opacity++) {
            history = FloatingBubbleAppearance.withSavedStyle(history,
                    new FloatingBubbleAppearance.Config(44, opacity, 0, 100, "あ", 0xff168af2));
        }
        check(history.size() == 10 && history.get(0).opacityPercent == 11
                && history.get(9).opacityPercent == 2);
        List<FloatingBubbleAppearance.Config> reordered = FloatingBubbleAppearance.withSavedStyle(
                history, history.get(5));
        check(reordered.size() == 10 && reordered.get(0).opacityPercent == 6
                && reordered.get(1).opacityPercent == 11 && history.get(0).opacityPercent == 11);
        List<FloatingBubbleAppearance.Config> images = FloatingBubbleAppearance.withSavedStyle(
                FloatingBubbleAppearance.withSavedStyle(new ArrayList<>(), imageA), imageB);
        check(images.size() == 2 && images.get(0).matches(imageB) && images.get(1).matches(imageA));
        check(FloatingBubbleAppearance.withSavedStyle(images, imageB).size() == 2);

        // The mounted bubble must receive a draft before preview() returns, without Android
        // broadcast delivery. Recreated editors must not be rolled back by an older owner.
        Object owner = new Object(), replacement = new Object();
        List<FloatingBubbleAppearance.Config> notifications = new ArrayList<>();
        Runnable listener = () -> notifications.add(FloatingBubbleAppearance.isPreviewing()
                ? FloatingBubbleAppearance.current(null) : null);
        FloatingBubbleAppearance.addChangeListener(listener);
        FloatingBubbleAppearance.addChangeListener(listener);
        FloatingBubbleAppearance.preview(null, owner, defaults);
        check(notifications.size() == 1 && notifications.get(0).matches(defaults));
        FloatingBubbleAppearance.preview(null, replacement, defaults);
        FloatingBubbleAppearance.endPreview(null, owner);
        check(notifications.size() == 1 && FloatingBubbleAppearance.isPreviewing());
        FloatingBubbleAppearance.preview(null, replacement, max);
        check(notifications.size() == 2 && notifications.get(1).matches(max));
        FloatingBubbleAppearance.endPreview(null, replacement);
        check(notifications.size() == 3 && notifications.get(2) == null
                && !FloatingBubbleAppearance.isPreviewing());
        FloatingBubbleAppearance.removeChangeListener(listener);
        FloatingBubbleAppearance.preview(null, owner, defaults);
        FloatingBubbleAppearance.endPreview(null, owner);
        check(notifications.size() == 3);
        System.out.println("FloatingBubbleAppearance: defaults, bounds, Unicode, image identity, "
                + "newest-first ordering, deduplication, ten-style limit and synchronous preview passed");
    }
}
