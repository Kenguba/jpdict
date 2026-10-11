package com.yuen.jpdict;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Dedicated appearance page for the draggable lookup bubble. */
public final class FloatingBubbleSettingsActivity extends FloatingSearchPageActivity {
    private FloatingBubbleSettingsView settingsView;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = createPage("悬浮按钮", false);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, 0);
        settingsView = new FloatingBubbleSettingsView(
                this, null, editing -> {});
        scroll.addView(settingsView, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        if (settingsView != null) settingsView.reloadSavedSettings();
    }

    @Override public void onBackPressed() {
        FloatingSearchRoutes.finishPage(this);
    }

}
