package com.yuen.jpdict;

import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Dedicated appearance page for the draggable lookup bubble. */
public final class FloatingBubbleSettingsActivity extends FloatingSearchPageActivity {
    private FloatingBubbleSettingsView settingsView;
    private boolean previewing;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = createPage("悬浮按钮", false);
        ((LinearLayout) root.getChildAt(0)).getChildAt(0)
                .setOnClickListener(v -> onBackPressed());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, 0);
        Bundle draftState = state == null ? null : state.getBundle("bubble_draft");
        FloatingBubbleAppearance.Config initial = draftState == null
                ? FloatingBubbleAppearance.load(this) : FloatingBubbleAppearance.fromBundle(draftState);
        settingsView = new FloatingBubbleSettingsView(this, initial, config -> {
            if (previewing) FloatingBubbleAppearance.preview(this, this, config);
        }, () -> BubbleImagePickerActivity.launch(this), () -> BubbleSvgPickerActivity.launch(this));
        settingsView.restoreEditorState(draftState);
        root.addView(settingsView.stickyHeader(), new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(settingsView, new ScrollView.LayoutParams(-1, -2));
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> settingsView.onPageScrolled(y));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        previewing = true;
        if (settingsView != null) FloatingBubbleAppearance.preview(this, this, settingsView.draft());
    }

    @Override protected void onPause() {
        previewing = false;
        FloatingBubbleAppearance.endPreview(this, this);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (settingsView != null) state.putBundle("bubble_draft", settingsView.saveDraftState());
        super.onSaveInstanceState(state);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BubbleImagePickerActivity.REQUEST_PICK_IMAGE
                && resultCode == RESULT_OK && data != null && settingsView != null) {
            settingsView.setDraftImage(data.getStringExtra(BubbleImagePickerActivity.EXTRA_IMAGE_NAME));
        }
        if (requestCode == BubbleSvgPickerActivity.REQUEST_PICK_SVG
                && resultCode == RESULT_OK && data != null && settingsView != null) {
            try {
                settingsView.setDraftSvg(data.getStringExtra(BubbleSvgPickerActivity.EXTRA_SVG_SOURCE),
                        data.getStringExtra(BubbleSvgPickerActivity.EXTRA_SVG_NAME));
            } catch (IllegalArgumentException invalid) {
                android.widget.Toast.makeText(this, "SVG 图标无效，已保留原样式",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override protected void onDestroy() {
        FloatingBubbleAppearance.endPreview(this, this);
        if (isFinishing()) FloatingBubbleAppearance.cleanupImages(this, null);
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (settingsView != null && settingsView.handleBack()) return;
        FloatingSearchRoutes.finishPage(this);
    }

}
