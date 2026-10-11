package com.yuen.jpdict;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Dedicated appearance page for the draggable lookup bubble. */
public final class FloatingBubbleSettingsActivity extends FloatingSearchPageActivity {
    private FloatingBubbleSettingsView settingsView;
    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        return view;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xfff4f5f6);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff4f5f6);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), 0, dp(16), 0);
        SearchIconView back = new SearchIconView(this, SearchIconView.Kind.BACK);
        back.setColor(0xff171819);
        back.setContentDescription("返回");
        back.setOnClickListener(v -> FloatingSearchRoutes.finishPage(this));
        header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(48)));
        TextView title = text("悬浮按钮", 19, 0xff171819);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(12), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(16), dp(4), dp(16), dp(18));
        settingsView = new FloatingBubbleSettingsView(
                this, null, editing -> {});
        scroll.addView(settingsView, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
        overridePendingTransition(0, 0);
    }

    @Override protected void onResume() {
        super.onResume();
        if (settingsView != null) settingsView.reloadSavedSettings();
    }

    @Override public void onBackPressed() {
        FloatingSearchRoutes.finishPage(this);
    }

}
