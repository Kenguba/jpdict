package com.yuen.jpdict;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Native routed settings, using the same motion rules as the real search drawer. */
public final class DrawerStyleSettingsActivity extends FloatingSearchPageActivity {
    private static final int BLUE = AppTheme.ACCENT, INK = AppTheme.INK, MUTED = AppTheme.MUTED;
    private DrawerStyleSettings settings;
    private DrawerStyleSettings.Mode selected;
    private DrawerPreviewView preview;
    private DrawerWaterfallPreview waterfall;
    private RatioSlider slider;
    private final LinearLayout[] cards = new LinearLayout[3];
    private final TextView[] checks = new TextView[3];
    private final LinearLayout[] tagRows = new LinearLayout[3];
    private final DrawerPreviewView[] thumbnails = new DrawerPreviewView[3];

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new DrawerStyleSettings(this); selected = settings.mode();
        int sw = getResources().getConfiguration().screenWidthDp, sh = getResources().getConfiguration().screenHeightDp;
        boolean narrow = sw <= 365;
        LinearLayout root = createPage("侧边栏展开方式", false);
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false); scroll.setFillViewport(true);
        LinearLayout content = column(); content.setPadding(dp(12), dp(8), dp(12), dp(16));
        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text("动态交互预览", 14, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(heading, margins(-1, -2, 0, 0, 0, 12));
        FrameLayout stage = new FrameLayout(this);
        stage.setBackground(shape(AppTheme.STAGE, 24, AppTheme.LINE));
        stage.setClipToOutline(true);
        preview = new DrawerPreviewView(this, true);
        waterfall = new DrawerWaterfallPreview(this, preview); preview.setWaterfallPreview(waterfall);
        stage.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        slider = new RatioSlider();
        FrameLayout.LayoutParams sliderLayout = new FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM);
        stage.addView(slider, sliderLayout);
        preview.setProgressListener(progress -> slider.invalidate());
        content.addView(stage, new LinearLayout.LayoutParams(-1, dp(sh <= 740 ? 242 : narrow ? 250 : 258)));
        TextView hint = text("↔ 左右滑动预览 · 长按滑块保存比例", 12, MUTED, false);
        SpannableString hintText = new SpannableString(hint.getText());
        hintText.setSpan(new RelativeSizeSpan(19f / 12), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        hintText.setSpan(new ForegroundColorSpan(0xfff9b748), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        hint.setText(hintText); hint.setGravity(Gravity.CENTER);
        content.addView(hint, margins(-1, -2, 0, 10, 0, 18));
        LinearLayout choicesHeading = new LinearLayout(this); choicesHeading.setGravity(Gravity.CENTER_VERTICAL);
        choicesHeading.addView(text("选择展开方式", 14, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        choicesHeading.addView(text("点击卡片即时切换", 12, MUTED, false));
        content.addView(choicesHeading, margins(-1, -2, 0, 0, 0, 12));
        LinearLayout choices = new LinearLayout(this);
        boolean stacked = sw
                / getResources().getConfiguration().fontScale < 330;
        choices.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        for (DrawerStyleSettings.Mode mode : DrawerStyleSettings.Mode.values()) {
            LinearLayout card = makeCard(mode);
            LinearLayout.LayoutParams params = stacked ? margins(-1, -2, 0, 0, 0, 10)
                    : new LinearLayout.LayoutParams(0, -1, 1);
            if (!stacked && mode.ordinal() > 0) params.leftMargin = dp(8);
            choices.addView(card, params);
        }
        content.addView(choices, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView experience = text("立即体验当前方式", 15, AppTheme.ACCENT_INK, true);
        experience.setGravity(Gravity.CENTER); experience.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x26000000), new GradientPanel(true, true), null));
        experience.setCompoundDrawablesWithIntrinsicBounds(new ActionGlyph(), null, null, null);
        experience.setCompoundDrawablePadding(dp(12));
        experience.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int padding = Math.max(dp(16), Math.round((v.getWidth()
                    - experience.getPaint().measureText(experience.getText().toString()) - dp(48)) / 2));
            if (experience.getPaddingLeft() != padding) experience.setPadding(padding, 0, padding, 0);
        });
        experience.setElevation(dp(6));
        if (android.os.Build.VERSION.SDK_INT >= 28) AppTheme.observe(experience, () -> {
            experience.setOutlineAmbientShadowColor(AppTheme.color(BLUE));
            experience.setOutlineSpotShadowColor(AppTheme.color(BLUE));
        });
        experience.setOnClickListener(v -> {
            slider.cancel();
            FloatingSearchRoutes.experienceDrawer(this);
        });
        root.addView(experience, margins(-1, dp(52), 12, 10, 12, 10));
        TextView saved = text("●  展开方式与侧栏宽度自动保存 · 重启后仍然有效", 11, MUTED, false);
        android.text.SpannableString note = new android.text.SpannableString(saved.getText());
        note.setSpan(new android.text.style.ForegroundColorSpan(0xff22c3a1), 0, 1, 0);
        saved.setText(note);
        saved.setGravity(Gravity.CENTER);
        root.addView(saved, margins(-1, -2, 12, 0, 12, 12));
        setContentView(root);
        select(selected, false);
    }

    private LinearLayout makeCard(DrawerStyleSettings.Mode mode) {
        int i = mode.ordinal();
        LinearLayout card = column(); cards[i] = card;
        card.setPadding(dp(9), dp(10), dp(9), dp(10));
        card.setClickable(true); card.setFocusable(true);
        card.setOnClickListener(v -> select(mode, true));
        card.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.RadioButton"); info.setCheckable(true); info.setChecked(selected == mode);
            }
        });
        DrawerPreviewView miniature = new DrawerPreviewView(this, false); thumbnails[i] = miniature;
        card.addView(miniature, new LinearLayout.LayoutParams(-1,
                dp(getResources().getConfiguration().screenWidthDp <= 365 ? 59 : 64)));
        TextView title = text(mode.title, 13, INK, true);
        LinearLayout titleLine = new LinearLayout(this); titleLine.setGravity(Gravity.CENTER_VERTICAL);
        titleLine.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView check = text("✓", 12, BLUE, true); checks[i] = check;
        check.setGravity(Gravity.CENTER); check.setBackground(shape(AppTheme.ACCENT_PALE, 100, BLUE));
        check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        titleLine.addView(check, new LinearLayout.LayoutParams(dp(19), dp(19)));
        card.addView(titleLine, margins(-1, -2, 0, 10, 0, 4));
        LinearLayout caption = new LinearLayout(this);
        caption.addView(text(mode.english, 11, MUTED, false), new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(caption);
        TextView description = text(mode.description, 12, MUTED, false);
        description.setMinLines(3); description.setLineSpacing(dp(2), 1);
        LinearLayout.LayoutParams descriptionParams = margins(-1, -2, 0, 12, 0, 12);
        descriptionParams.weight = 1; card.addView(description, descriptionParams);
        HorizontalScrollView tags = new HorizontalScrollView(this);
        tags.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this); tagRows[i] = row;
        for (String label : new String[] {mode.firstTag, mode.secondTag}) {
            TextView tag = text(label, 9, MUTED, false); tag.setSingleLine();
            tag.setPadding(dp(5), dp(3), dp(5), dp(3));
            row.addView(tag, margins(-2, -2, row.getChildCount() == 0 ? 0 : 3, 0, 0, 0));
        }
        tags.addView(row); card.addView(tags);
        return card;
    }

    private void select(DrawerStyleSettings.Mode mode, boolean animate) {
        slider.cancel(); selected = mode;
        if (animate) settings.select(mode);
        for (DrawerStyleSettings.Mode option : DrawerStyleSettings.Mode.values()) {
            int i = option.ordinal(); boolean checked = option == selected;
            cards[i].setBackground(new RippleDrawable(ColorStateList.valueOf(0x18006bff),
                    new GradientPanel(checked, false), null));
            cards[i].setSelected(checked); checks[i].setVisibility(checked ? View.VISIBLE : View.INVISIBLE);
            for (int j = 0; j < tagRows[i].getChildCount(); j++) {
                TextView tag = (TextView)tagRows[i].getChildAt(j);
                AppTheme.text(tag, checked ? BLUE : MUTED);
                tag.setBackground(shape(checked ? AppTheme.ACCENT_PALE : AppTheme.STAGE,
                        7, checked ? (BLUE & 0xffffff) | 0x80000000 : AppTheme.LINE));
            }
            cards[i].setContentDescription(option.title + "，" + option.english + "，" + option.description);
            thumbnails[i].show(option, settings.ratio(option), false);
        }
        preview.show(mode, settings.ratio(mode), animate);
        if (!animate) preview.seek(0);
        slider.refresh();
    }

    @Override public void onBackPressed() { slider.cancel(); FloatingSearchRoutes.finishPage(this); }
    @Override protected void onPause() {
        slider.cancel(); preview.pause(); waterfall.pause(); super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        if (preview != null) { select(settings.mode(), false); waterfall.resume(); }
    }

    private final class RatioSlider extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int value, original, pointer = -1;
        private float startX, startY;
        private boolean thumb;

        RatioSlider() { super(DrawerStyleSettingsActivity.this); setFocusable(true); setClickable(true); }
        void refresh() { value = settings.ratio(selected); setContentDescription(value + "%，长按调整"); invalidate(); }
        private float left() { return dp(28); }
        private float right() { return Math.max(left() + 1, getWidth() - dp(76)); }
        private float thumbX() { return left() + (right() - left()) * value / 100f; }
        private int percent(float x) { return Math.round(DrawerMotionController.clamp((x - left()) / (right() - left()), 0, 1) * 100); }

        @Override protected void onDraw(Canvas canvas) {
            float y = getHeight() / 2f, x = thumbX(); int progress = Math.round(preview.progress() * 100);
            paint.setColor(AppTheme.color(AppTheme.LINE)); paint.setStrokeWidth(dp(5)); paint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(left(), y, right(), y, paint);
            paint.setColor(AppTheme.color(BLUE));
            canvas.drawLine(left(), y, left() + (right() - left()) * progress / 100f, y, paint);
            paint.setColor((AppTheme.color(BLUE) & 0xffffff) | 0x18000000);
            canvas.drawCircle(x, y, dp(20), paint);
            paint.setShadowLayer(dp(3), 0, dp(2), 0x48000000);
            paint.setColor(AppTheme.isDark() ? 0xffffeab5 : 0xfff9fcff); canvas.drawCircle(x, y, dp(16), paint);
            paint.clearShadowLayer();
            paint.setColor(AppTheme.color(BLUE)); canvas.drawCircle(x, y, dp(14.5f), paint);
            paint.setTextSize(dp(10)); paint.setTextAlign(Paint.Align.CENTER); paint.setColor(AppTheme.ink(AppTheme.ACCENT_INK));
            canvas.drawText(String.valueOf(value), x, y - (paint.ascent() + paint.descent()) / 2, paint);
            paint.setColor(AppTheme.color(BLUE)); paint.setTextSize(dp(16)); paint.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText(progress + "%",
                    getWidth() - dp(35), y - (paint.ascent() + paint.descent()) / 2, paint);
        }

        private void update(float x, boolean width) {
            int next = percent(x); if (width) value = next;
            preview.show(selected, value, false); preview.seek(Math.min(value, next)); waterfall.pause();
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                cancel(); pointer = e.getPointerId(0); startX = e.getX(); startY = e.getY(); thumb = Math.abs(startX - thumbX()) <= dp(20);
                preview.pause(); waterfall.pause(); original = Math.round(preview.progress() * 100);
                if (!thumb) update(startX, false);
                return true;
            }
            if (pointer < 0) return false;
            int index = e.findPointerIndex(pointer);
            if (action == MotionEvent.ACTION_CANCEL || action >= MotionEvent.ACTION_POINTER_DOWN || index < 0) { cancel(); return true; }
            if (action == MotionEvent.ACTION_MOVE) {
                float x = e.getX(index), y = e.getY(index);
                if (Math.abs(x - startX) > dp(9) && Math.abs(x - startX) > Math.abs(y - startY) * 1.3f)
                    getParent().requestDisallowInterceptTouchEvent(true);
                if (thumb && startX >= 0 && e.getEventTime() - e.getDownTime() < 700) {
                    if (Math.hypot(x - startX, y - startY) <= dp(9)) return true;
                    startX = -1;
                }
                if (!thumb || startX >= 0) update(x, thumb);
            } else if (action == MotionEvent.ACTION_UP) {
                if (!thumb || startX >= 0) update(e.getX(index), thumb);
                if (thumb && startX >= 0) {
                    if (e.getEventTime() - e.getDownTime() < 700) { cancel(); return true; }
                    settings.saveRatio(selected, value); thumbnails[selected.ordinal()].show(selected, value, false); refresh();
                }
                pointer = -1; waterfall.resume(); performClick();
            }
            return true;
        }

        void cancel() {
            if (pointer >= 0) { value = settings.ratio(selected); preview.show(selected, value, false); preview.seek(original); }
            pointer = -1;
        }
        @Override public boolean performClick() { super.performClick(); return true; }

        private void saveAccessible(int percent) {
            cancel(); value = Math.max(0, Math.min(100, percent)); settings.saveRatio(selected, value);
            int progress = Math.round(preview.progress() * 100);
            preview.show(selected, value, false); preview.seek(progress); thumbnails[selected.ordinal()].show(selected, value, false); refresh();
        }
        @Override public boolean onKeyDown(int key, KeyEvent event) {
            int step = key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_DOWN ? -1
                    : key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_UP ? 1
                    : key == KeyEvent.KEYCODE_PAGE_DOWN ? -10 : key == KeyEvent.KEYCODE_PAGE_UP ? 10 : 0;
            if (step != 0 || key == KeyEvent.KEYCODE_MOVE_HOME || key == KeyEvent.KEYCODE_MOVE_END) {
                saveAccessible(key == KeyEvent.KEYCODE_MOVE_HOME ? 0 : key == KeyEvent.KEYCODE_MOVE_END ? 100 : value + step);
                return true;
            }
            return super.onKeyDown(key, event);
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info); info.setClassName("android.widget.SeekBar");
            info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0, 100, value));
            info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD); info.addAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
            if (android.os.Build.VERSION.SDK_INT >= 24) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        }
        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                saveAccessible(value + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1)); return true;
            }
            if (android.os.Build.VERSION.SDK_INT >= 24 && action == android.R.id.accessibilityActionSetProgress && arguments != null) {
                saveAccessible(Math.round(arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE))); return true;
            }
            return super.performAccessibilityAction(action, arguments);
        }
    }

    private final class GradientPanel extends android.graphics.drawable.Drawable {
        private final boolean selected, button;
        private final Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
        private android.graphics.Shader gradient;
        private boolean dark;
        GradientPanel(boolean selected, boolean button) { this.selected = selected; this.button = button; }
        private void colors() {
            dark = AppTheme.isDark();
            int from = button ? (dark ? 0xffffce4b : 0xff408fff)
                    : dark ? (selected ? 0xff35455b : 0xff2c3b52) : (selected ? 0xffeaf3ff : 0xffffffff);
            int to = button ? (dark ? 0xffffa500 : 0xff006bff)
                    : dark ? (selected ? 0xff273449 : 0xff253148) : (selected ? 0xfff5f9ff : 0xfff3f7fc);
            gradient = new android.graphics.LinearGradient(0, 0, button ? Math.max(1, getBounds().width()) : 0,
                    Math.max(1, getBounds().height()), from, to, android.graphics.Shader.TileMode.CLAMP);
        }
        @Override protected void onBoundsChange(android.graphics.Rect bounds) { colors(); }
        @Override public void getOutline(android.graphics.Outline outline) {
            outline.setRoundRect(getBounds(), dp(button ? 20 : 22));
        }
        @Override public void draw(Canvas canvas) {
            if (gradient == null || dark != AppTheme.isDark()) colors();
            android.graphics.Rect b = getBounds(); float inset = dp(1), radius = dp(button ? 20 : 22);
            brush.setStyle(Paint.Style.FILL); brush.setShader(gradient);
            canvas.drawRoundRect(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset, radius, radius, brush);
            brush.setShader(null); brush.setStyle(Paint.Style.STROKE); brush.setStrokeWidth(dp(1));
            brush.setColor(AppTheme.color(selected ? BLUE : AppTheme.LINE));
            if (selected && !button) brush.setShadowLayer(dp(3), 0, 0, (AppTheme.color(BLUE) & 0xffffff) | 0x65000000);
            canvas.drawRoundRect(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset, radius, radius, brush);
            brush.clearShadowLayer();
        }
        @Override public void setAlpha(int alpha) { brush.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { brush.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private final class ActionGlyph extends android.graphics.drawable.Drawable {
        private final Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas canvas) {
            brush.setColor(AppTheme.ink(AppTheme.ACCENT_INK)); brush.setStyle(Paint.Style.STROKE);
            brush.setStrokeWidth(dp(1.5f)); brush.setStrokeJoin(Paint.Join.ROUND); brush.setStrokeCap(Paint.Cap.ROUND);
            canvas.save(); canvas.translate(getBounds().left, getBounds().top);
            canvas.drawLine(dp(5), dp(3), dp(17), dp(10), brush);
            canvas.drawLine(dp(17), dp(10), dp(5), dp(17), brush);
            canvas.drawLine(dp(5), dp(17), dp(5), dp(3), brush);
            canvas.restore();
        }
        @Override public int getIntrinsicWidth() { return dp(20); }
        @Override public int getIntrinsicHeight() { return dp(20); }
        @Override public void setAlpha(int alpha) { brush.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { brush.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout result = new LinearLayout(this); result.setOrientation(LinearLayout.VERTICAL); return result; }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView result = new TextView(this); result.setText(value); result.setTextSize(size); AppTheme.text(result, color);
        result.setIncludeFontPadding(false); if (bold) result.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return result;
    }
    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable result = new AppTheme.Shape(); result.setColor(color); result.setCornerRadius(dp(radius));
        if (stroke != 0) result.setStroke(dp(1), stroke); return result;
    }
    private RippleDrawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x26000000), shape(color, radius, 0), null);
    }
    private LinearLayout.LayoutParams margins(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(width, height);
        result.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return result;
    }
}
