package com.yuen.jpdict;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/** Compact appearance editor matching the search page's gray cards and blue pills. */
public final class FloatingBubbleSettingsView extends LinearLayout {
    public interface EditingListener { void onEditingChanged(boolean editing); }
    private static final int INK = 0xff171819;
    private static final int MUTED = 0xff6d747b;
    private static final int SOFT = 0xfff4f5f6;
    private static final int BLUE = 0xff008fd1;
    private static final int PALE_BLUE = 0xffdef7ff;
    private final Runnable onChanged;
    private final EditingListener onEditing;
    private final FrameLayout preview;
    private final TextView sizeValue;
    private final TextView opacityValue;
    private final SeekBar size;
    private final SeekBar opacity;
    private final TextView[] styleButtons = new TextView[4];
    private final TextView[] shapeButtons = new TextView[2];
    private final LinearLayout customFields;
    private final LinearLayout imageFields;
    private final EditText customText;
    private final EditText customColor;
    private int style;
    private int shape;
    private int lastValidColor;

    public FloatingBubbleSettingsView(Context context, Runnable onChanged,
            EditingListener onEditing) {
        super(context);
        this.onChanged = onChanged;
        this.onEditing = onEditing;
        FloatingBubbleAppearance.Config config = FloatingBubbleAppearance.load(context);
        style = config.style;
        shape = config.shape;
        lastValidColor = config.color;
        setOrientation(VERTICAL);
        setPadding(dp(22), dp(20), dp(22), dp(20));
        setBackground(background(SOFT, 24));
        addView(text("悬浮按钮", 21, INK, true));
        TextView note = text("调整大小、样式和透明度", 13, MUTED, false);
        note.setPadding(0, dp(8), 0, dp(16));
        addView(note);

        FrameLayout previewArea = new FrameLayout(context);
        previewArea.setBackground(background(0xffffffff, 20));
        preview = new FrameLayout(context);
        previewArea.addView(preview, new FrameLayout.LayoutParams(config.sizePx(context),
                config.sizePx(context), Gravity.CENTER));
        addView(previewArea, new LayoutParams(-1, dp(132)));

        sizeValue = addSettingLabel("大小");
        size = slider(FloatingBubbleAppearance.MAX_SIZE_DP - FloatingBubbleAppearance.MIN_SIZE_DP,
                config.sizeDp - FloatingBubbleAppearance.MIN_SIZE_DP);
        opacityValue = addSettingLabel("透明度");
        opacity = slider(100 - FloatingBubbleAppearance.MIN_OPACITY,
                config.opacityPercent - FloatingBubbleAppearance.MIN_OPACITY);

        addSectionLabel("图标样式");
        LinearLayout styles = row();
        String[] labels = {"当前样式", "简洁搜索", "自定义", "图片"};
        for (int i = 0; i < labels.length; i++) {
            final int selected = i;
            TextView button = choice(labels[i]);
            styleButtons[i] = button;
            addChoice(styles, button, i);
            button.setOnClickListener(v -> {
                if (selected == FloatingBubbleAppearance.STYLE_IMAGE
                        && !FloatingBubbleAppearance.hasCustomImage(context)) {
                    chooseImage();
                    return;
                }
                style = selected;
                finishEditing();
                refreshPreview();
            });
        }
        addView(styles);

        customFields = new LinearLayout(context);
        customFields.setOrientation(VERTICAL);
        customText = editor("图标文字（最多 4 个字符）", config.text, 8);
        customText.setFilters(new InputFilter[]{(source, start, end, destination, replaceStart, replaceEnd) -> {
            String remaining = destination.subSequence(0, replaceStart).toString()
                    + destination.subSequence(replaceEnd, destination.length());
            int available = 4 - remaining.codePointCount(0, remaining.length());
            String inserted = source.subSequence(start, end).toString();
            if (inserted.codePointCount(0, inserted.length()) <= available) return null;
            return available <= 0 ? "" : inserted.substring(0, inserted.offsetByCodePoints(0, available));
        }});
        customFields.addView(customText, fieldLayout());
        customColor = editor("颜色，例如 #168AF2", formatColor(config.color), 7);
        customColor.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        customFields.addView(customColor, fieldLayout());
        LinearLayout colors = row();
        int[] palette = {0xff168af2, 0xff171819, 0xff745be8, 0xffd75b97,
                0xffdf704b, 0xff429580};
        for (int color : palette) {
            TextView swatch = choice("");
            swatch.setBackground(background(color, 14));
            swatch.setContentDescription("颜色 " + formatColor(color));
            LayoutParams colorLayout = new LayoutParams(0, dp(36), 1);
            colorLayout.setMargins(0, dp(10), dp(7), 0);
            colors.addView(swatch, colorLayout);
            swatch.setOnClickListener(v -> customColor.setText(formatColor(color)));
        }
        customFields.addView(colors);
        addView(customFields);

        imageFields = new LinearLayout(context);
        imageFields.setOrientation(VERTICAL);
        TextView imageNote = text("从相册或文件中选择一张图片", 12, MUTED, false);
        imageNote.setPadding(0, dp(12), 0, dp(10));
        imageFields.addView(imageNote);
        TextView choose = choice("选择图片");
        choose.setTextColor(BLUE);
        choose.setBackground(background(PALE_BLUE, 16));
        choose.setOnClickListener(v -> chooseImage());
        imageFields.addView(choose, new LayoutParams(-1, dp(44)));
        addView(imageFields);

        addSectionLabel("按钮形状");
        LinearLayout shapes = row();
        String[] shapeLabels = {"圆形", "圆角方形"};
        for (int i = 0; i < shapeLabels.length; i++) {
            final int selected = i;
            shapeButtons[i] = choice(shapeLabels[i]);
            addChoice(shapes, shapeButtons[i], i);
            shapeButtons[i].setOnClickListener(v -> { shape = selected; refreshPreview(); });
        }
        addView(shapes);

        LinearLayout actions = row();
        LayoutParams actionsLayout = new LayoutParams(-1, dp(48));
        actionsLayout.topMargin = dp(20);
        TextView reset = choice("恢复默认");
        reset.setOnClickListener(v -> resetDefaults());
        addChoice(actions, reset, 0);
        TextView save = choice("保存设置");
        save.setTextColor(BLUE);
        save.setBackground(background(PALE_BLUE, 16));
        save.setOnClickListener(v -> {
            if (!saveDraft()) return;
            finishEditing();
            Toast.makeText(context, "悬浮按钮设置已保存", Toast.LENGTH_SHORT).show();
        });
        addChoice(actions, save, 1);
        addView(actions, actionsLayout);

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                refreshPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        };
        size.setOnSeekBarChangeListener(listener);
        opacity.setOnSeekBarChangeListener(listener);
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                refreshPreview();
            }
            @Override public void afterTextChanged(Editable value) { }
        };
        customText.addTextChangedListener(watcher);
        customColor.addTextChangedListener(watcher);
        refreshPreview();
    }

    private FloatingBubbleAppearance.Config draft() {
        Integer color = parseColor(customColor.getText().toString());
        if (color != null) lastValidColor = color;
        return new FloatingBubbleAppearance.Config(
                size.getProgress() + FloatingBubbleAppearance.MIN_SIZE_DP,
                opacity.getProgress() + FloatingBubbleAppearance.MIN_OPACITY,
                style, shape, customText.getText().toString(), lastValidColor);
    }

    private void refreshPreview() {
        FloatingBubbleAppearance.Config config = draft();
        sizeValue.setText(config.sizeDp + " dp");
        opacityValue.setText(config.opacityPercent + "%");
        preview.setLayoutParams(new FrameLayout.LayoutParams(config.sizePx(getContext()),
                config.sizePx(getContext()), Gravity.CENTER));
        FloatingBubbleAppearance.applyVisual(preview, config);
        preview.setAlpha(config.alpha());
        customFields.setVisibility(style == FloatingBubbleAppearance.STYLE_CUSTOM ? VISIBLE : GONE);
        imageFields.setVisibility(style == FloatingBubbleAppearance.STYLE_IMAGE ? VISIBLE : GONE);
        for (int i = 0; i < styleButtons.length; i++) setSelected(styleButtons[i], i == style);
        for (int i = 0; i < shapeButtons.length; i++) setSelected(shapeButtons[i], i == shape);
    }

    private boolean saveDraft() {
        if (style == FloatingBubbleAppearance.STYLE_CUSTOM) {
            if (customText.getText().toString().trim().isEmpty()) {
                customText.setError("请输入图标文字");
                return false;
            }
            if (parseColor(customColor.getText().toString()) == null) {
                customColor.setError("使用 # 加 6 位颜色，例如 #168AF2");
                return false;
            }
        }
        if (!FloatingBubbleAppearance.save(getContext(), draft())) {
            Toast.makeText(getContext(), "设置保存失败，请重试", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (onChanged != null) onChanged.run();
        return true;
    }

    private void chooseImage() {
        if (!saveDraft()) return;
        finishEditing();
        BubbleImagePickerActivity.launch(getContext());
    }

    private void resetDefaults() {
        FloatingBubbleAppearance.Config config = FloatingBubbleAppearance.defaults();
        style = config.style;
        shape = config.shape;
        size.setProgress(config.sizeDp - FloatingBubbleAppearance.MIN_SIZE_DP);
        opacity.setProgress(config.opacityPercent - FloatingBubbleAppearance.MIN_OPACITY);
        customText.setText(config.text);
        customColor.setText(formatColor(config.color));
        refreshPreview();
        if (saveDraft()) {
            finishEditing();
            Toast.makeText(getContext(), "已恢复默认悬浮按钮", Toast.LENGTH_SHORT).show();
        }
    }

    private void finishEditing() {
        customText.clearFocus();
        customColor.clearFocus();
        InputMethodManager manager = (InputMethodManager) getContext().getSystemService(
                Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(getWindowToken(), 0);
        if (onEditing != null) onEditing.onEditingChanged(false);
    }

    private EditText editor(String hint, String value, int maxLength) {
        EditText field = new EditText(getContext());
        field.setSingleLine(true);
        field.setTextSize(14);
        field.setTextColor(INK);
        field.setHintTextColor(MUTED);
        field.setHint(hint);
        field.setText(value);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        field.setPadding(dp(12), 0, dp(12), 0);
        field.setBackground(background(0xffffffff, 14));
        field.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        field.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && onEditing != null) {
                onEditing.onEditingChanged(true);
            }
            return false;
        });
        field.setOnFocusChangeListener((v, focused) -> {
            if (focused && onEditing != null) onEditing.onEditingChanged(true);
        });
        return field;
    }

    private TextView addSettingLabel(String label) {
        LinearLayout line = row();
        LayoutParams lineLayout = new LayoutParams(-1, -2);
        lineLayout.topMargin = dp(16);
        line.addView(text(label, 14, INK, true), new LayoutParams(0, -2, 1));
        TextView value = text("", 13, MUTED, false);
        line.addView(value);
        addView(line, lineLayout);
        return value;
    }

    private SeekBar slider(int max, int progress) {
        SeekBar slider = new SeekBar(getContext());
        slider.setMax(max);
        slider.setProgress(progress);
        slider.setProgressTintList(ColorStateList.valueOf(BLUE));
        slider.setThumbTintList(ColorStateList.valueOf(BLUE));
        slider.setPadding(dp(4), 0, dp(4), 0);
        addView(slider, new LayoutParams(-1, dp(40)));
        return slider;
    }

    private void addSectionLabel(String label) {
        TextView section = text(label, 14, INK, true);
        section.setPadding(0, dp(16), 0, dp(10));
        addView(section);
    }

    private LinearLayout row() {
        LinearLayout line = new LinearLayout(getContext());
        line.setOrientation(HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        return line;
    }

    private TextView choice(String label) {
        TextView view = text(label, 12, INK, true);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(44));
        view.setBackground(background(0xffffffff, 14));
        view.setFocusable(true);
        return view;
    }

    private void addChoice(LinearLayout row, TextView view, int index) {
        LayoutParams layout = new LayoutParams(0, dp(44), 1);
        if (index > 0) layout.leftMargin = dp(6);
        row.addView(view, layout);
    }

    private void setSelected(TextView view, boolean selected) {
        view.setSelected(selected);
        view.setTextColor(selected ? BLUE : INK);
        view.setBackground(background(selected ? PALE_BLUE : 0xffffffff, 14));
    }

    private LayoutParams fieldLayout() {
        LayoutParams layout = new LayoutParams(-1, dp(48));
        layout.topMargin = dp(12);
        return layout;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView text = new TextView(getContext());
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setIncludeFontPadding(false);
        text.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) text.setTypeface(Typeface.DEFAULT_BOLD);
        return text;
    }

    private GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private static String formatColor(int color) {
        return String.format(java.util.Locale.ROOT, "#%06X", color & 0xffffff);
    }

    private static Integer parseColor(String value) {
        String normalized = value.trim();
        if (!normalized.matches("#[0-9a-fA-F]{6}")) return null;
        return 0xff000000 | Integer.parseInt(normalized.substring(1), 16);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
