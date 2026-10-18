package com.yuen.jpdict;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Independent Icon/background editing over one immutable appearance draft. */
final class BubblePaintEditorView extends LinearLayout {
    interface Listener { void onChanged(FloatingBubbleAppearance.Config config); }
    private static final int BLUE = AppTheme.BLUE, PALE = AppTheme.PALE, SOFT = AppTheme.SOFT;
    private static final int[] SOLID_PRESETS = {0xff078df4,0xff22c55e,0xfffacc15,0xfff472b6,0xff9254de,0xff2a3547};
    private static final int[][] LINEAR_PRESETS = {{0xff168aff,0xffa855f7},{0xff06b6d4,0xff2563eb},
            {0xfff472b6,0xff9333ea},{0xfffb923c,0xffec4899},{0xfffacc15,0xfff97316},{0xff22c55e,0xff06b6d4}};
    private static final int[] ANGLES = {90,270,0,180,135,225};
    private static final int[][] FREE_COLORS = {{0xfffc76b5,0xff2195ff,0xff9b69ff,0xff40daaa},
            {0xffffaa5d,0xffff5b91,0xff9a6bff,0xff3967f8},{0xff67e8f9,0xff138deb,0xff27d9a2,0xff4863ff},
            {0xfff9d84f,0xffff7d54,0xfffb5ba6,0xff9b54f8},{0xff78dab4,0xffc1f285,0xff188fce,0xff715ae8},
            {0xffff98ce,0xffa98aff,0xffa5e0ff,0xfffee0a5}};
    private static final float[][] FREE_POSITIONS = {{.18f,.18f,.82f,.17f,.20f,.83f,.83f,.81f},
            {.18f,.19f,.83f,.22f,.18f,.82f,.80f,.81f},{.15f,.18f,.86f,.17f,.25f,.80f,.83f,.85f},
            {.18f,.15f,.86f,.22f,.17f,.78f,.85f,.82f},{.12f,.20f,.85f,.20f,.23f,.86f,.85f,.82f},
            {.10f,.25f,.84f,.17f,.21f,.79f,.82f,.80f}};
    private static final FloatingBubbleAppearance.ColorConfig[] SOLID_PAINTS = new FloatingBubbleAppearance.ColorConfig[6];
    private static final FloatingBubbleAppearance.ColorConfig[] LINEAR_PAINTS = new FloatingBubbleAppearance.ColorConfig[6];
    private static final FloatingBubbleAppearance.ColorConfig[] FREE_PAINTS = new FloatingBubbleAppearance.ColorConfig[6];
    static {
        for (int i = 0; i < 6; i++) {
            SOLID_PAINTS[i] = FloatingBubbleAppearance.defaultPaint(SOLID_PRESETS[i]);
            LINEAR_PAINTS[i] = SOLID_PAINTS[i].withMode(FloatingBubbleAppearance.COLOR_LINEAR)
                    .withStops(LINEAR_PRESETS[i][0], LINEAR_PRESETS[i][1]).withAngle(ANGLES[i]);
            List<FloatingBubbleAppearance.FreePoint> points = new ArrayList<>();
            for (int p = 0; p < 4; p++) points.add(new FloatingBubbleAppearance.FreePoint(
                    FREE_POSITIONS[i][p * 2], FREE_POSITIONS[i][p * 2 + 1], FREE_COLORS[i][p], 65));
            FREE_PAINTS[i] = SOLID_PAINTS[i].withMode(FloatingBubbleAppearance.COLOR_FREE).withPoints(points);
        }
    }
    private final TextView[] modes = new TextView[3];
    private final PreviewStage preview;
    private final TextView targetLabel;
    private final LinearLayout solid, gradient, linear;
    private final BubbleColorPickerView picker;
    private final BubbleAngleDialView dial;
    private final BubbleFreeGradientEditorView free;
    private final EditText solidHex, startHex, endHex;
    private final View startSwatch, endSwatch;
    private final LinearLayout solidPresets, gradientPresets;
    private final Map<String, String> invalid = new HashMap<>();
    private final Map<String, Integer> freePresetSelections = new HashMap<>();
    private FloatingBubbleAppearance.Config config = FloatingBubbleAppearance.defaults();
    private String target = "background";
    private Listener listener;
    private boolean binding;
    private AlertDialog activeDialog;

    BubblePaintEditorView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        LinearLayout modeRow = row();
        modeRow.setPadding(dp(3), dp(3), dp(3), dp(3));
        modeRow.setBackground(background(SOFT, 14));
        String[] labels = {"纯色", "线性渐变", "自由渐变"};
        for (int i = 0; i < 3; i++) {
            final int mode = i;
            modes[i] = text(labels[i], 11, AppTheme.MUTED, true);
            modes[i].setSingleLine(true);
            modes[i].setGravity(Gravity.CENTER);
            modes[i].setFocusable(true);
            modes[i].setContentDescription(labels[i] + "颜色模式");
            LayoutParams params = new LayoutParams(0, dp(36), 1);
            if (i > 0) params.leftMargin = dp(2);
            modeRow.addView(modes[i], params);
            modes[i].setOnClickListener(v -> { finishEditing(); publish(paint().withMode(mode)); });
        }
        addView(modeRow, new LayoutParams(-1, -2));
        LinearLayout previewRow = row();
        preview = new PreviewStage(context);
        previewRow.addView(preview, new LayoutParams(dp(56), dp(56)));
        targetLabel = text("", 11, BLUE, true);
        targetLabel.setMaxLines(2);
        LayoutParams labelParams = new LayoutParams(0, -2, 1);
        labelParams.leftMargin = dp(11);
        previewRow.addView(targetLabel, labelParams);
        addView(previewRow, spaced(-1, -2, 11, 13));

        solid = column();
        LinearLayout colorHead = row();
        colorHead.addView(text("当前纯色", 11, AppTheme.MUTED, true), new LayoutParams(0, -2, 1));
        solidHex = hexInput("纯色十六进制值", 11);
        colorHead.addView(solidHex, new LayoutParams(dp(90), dp(34)));
        solid.addView(colorHead, spaced(-1, -2, 0, 9));
        picker = new BubbleColorPickerView(context);
        solid.addView(picker, new LayoutParams(-1, -2));
        solid.addView(text("纯色预设 · 6 种颜色，左右滑动", 10, AppTheme.MUTED, true), spaced(-1, -2, 6, 7));
        solidPresets = row();
        solid.addView(horizontal(solidPresets, "纯色预设，左右滑动选择"));
        addView(solid, new LayoutParams(-1, -2));

        gradient = column();
        gradient.addView(text("渐变预设 · 6 种样式，左右滑动", 10, AppTheme.MUTED, true), spaced(-1, -2, 0, 7));
        gradientPresets = row();
        gradient.addView(horizontal(gradientPresets, "渐变预设，左右滑动选择", true));
        linear = column();
        linear.addView(text("线性渐变颜色", 12, AppTheme.MUTED, true), spaced(-1, -2, 10, 7));
        LinearLayout stops = row();
        startHex = hexInput("起始色色值", 10);
        endHex = hexInput("结束色色值", 10);
        startSwatch = new View(context);
        endSwatch = new View(context);
        addStop(stops, "起始色", startSwatch, startHex, false);
        addStop(stops, "结束色", endSwatch, endHex, true);
        linear.addView(stops, new LayoutParams(-1, -2));
        linear.addView(text("渐变方向", 12, AppTheme.MUTED, true), spaced(-1, -2, 10, 7));
        dial = new BubbleAngleDialView(context);
        linear.addView(dial, spaced(-1, -2, 5, 12));
        gradient.addView(linear, new LayoutParams(-1, -2));
        free = new BubbleFreeGradientEditorView(context, this::chooseColor);
        gradient.addView(free, new LayoutParams(-1, -2));
        addView(gradient, new LayoutParams(-1, -2));

        solidHex.addTextChangedListener(watcher(() -> updateHex(solidHex, "solid")));
        startHex.addTextChangedListener(watcher(() -> updateHex(startHex, "start")));
        endHex.addTextChangedListener(watcher(() -> updateHex(endHex, "end")));
        picker.setListener(color -> {
            if (binding) return;
            finishInput(solidHex); invalid.remove(key("solid")); publish(paint().withSolid(color));
        });
        startSwatch.setOnClickListener(v -> chooseColor("线性渐变起始色", paint().start, startHex, color -> {
            invalid.remove(key("start")); publish(paint().withStops(color, paint().end));
        }));
        endSwatch.setOnClickListener(v -> chooseColor("线性渐变结束色", paint().end, endHex, color -> {
            invalid.remove(key("end")); publish(paint().withStops(paint().start, color));
        }));
        dial.setListener(angle -> publish(paint().withAngle(angle)));
        free.setListener(this::publish);
        bind();
    }

    void setListener(Listener listener) { this.listener = listener; }
    void setConfig(FloatingBubbleAppearance.Config config) {
        if (this.config == config) return;
        boolean samePaints = this.config.iconPaint == config.iconPaint
                && this.config.backgroundPaint == config.backgroundPaint;
        boolean placementEdit = this.config.shape != config.shape
                || !this.config.imageName.equals(config.imageName)
                || this.config.imageZoomPercent != config.imageZoomPercent
                || this.config.imageXPercent != config.imageXPercent
                || this.config.imageYPercent != config.imageYPercent;
        boolean targetChanged = config.style == FloatingBubbleAppearance.STYLE_IMAGE && "icon".equals(target);
        if (targetChanged) { finishEditing(); target = "background"; }
        this.config = config;
        if (placementEdit && paint().mode == FloatingBubbleAppearance.COLOR_FREE) freePresetSelections.remove(target);
        if (samePaints && !targetChanged) {
            preview.invalidate();
            if (placementEdit) renderPresets();
        } else { if (!samePaints) freePresetSelections.remove(target); bind(); }
    }
    void selectIconTarget() { selectTarget("icon"); }
    void resetSession() { invalid.clear(); freePresetSelections.clear(); target = "background"; free.resetSession(); finishEditing(); bind(); }
    boolean isInputValid() { return invalid.isEmpty() && free.isInputValid(); }
    Bundle saveEditorState() {
        Bundle state = new Bundle();
        state.putString("target", target);
        Bundle pending = new Bundle();
        for (Map.Entry<String,String> entry : invalid.entrySet()) pending.putString(entry.getKey(), entry.getValue());
        state.putBundle("invalid", pending);
        state.putBundle("free", free.saveEditorState());
        for (Map.Entry<String,Integer> entry : freePresetSelections.entrySet())
            state.putInt("free_preset_" + entry.getKey(), entry.getValue());
        return state;
    }
    void restoreEditorState(Bundle state) {
        if (state == null) return;
        target = "icon".equals(state.getString("target")) ? "icon" : "background";
        if (config.style == FloatingBubbleAppearance.STYLE_IMAGE) target = "background";
        invalid.clear();
        freePresetSelections.clear();
        Bundle pending = state.getBundle("invalid");
        if (pending != null) for (String key : pending.keySet())
            if (key.matches("(?:background|icon):(?:solid|start|end)")) invalid.put(key, pending.getString(key, ""));
        free.restoreEditorState(state.getBundle("free"));
        for (String paintTarget : new String[]{"background", "icon"}) {
            int index = state.getInt("free_preset_" + paintTarget, -1);
            if (index >= 0 && index < 6) freePresetSelections.put(paintTarget, index);
        }
        bind();
    }
    void finishEditing() {
        solidHex.clearFocus(); startHex.clearFocus(); endHex.clearFocus(); free.finishEditing();
        InputMethodManager manager = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    private FloatingBubbleAppearance.ColorConfig paint() { return "icon".equals(target) ? config.iconPaint : config.backgroundPaint; }
    private String key(String name) { return target + ":" + name; }
    private void selectTarget(String target) {
        if ("icon".equals(target) && config.style == FloatingBubbleAppearance.STYLE_IMAGE) return;
        if (this.target.equals(target)) return;
        finishEditing(); this.target = target; bind();
    }
    private void publish(FloatingBubbleAppearance.ColorConfig paint) {
        freePresetSelections.remove(target);
        publishPaint(paint);
    }
    private void publishPaint(FloatingBubbleAppearance.ColorConfig paint) {
        FloatingBubbleAppearance.Builder builder = new FloatingBubbleAppearance.Builder(config);
        if ("icon".equals(target)) builder.iconPaint = paint; else builder.backgroundPaint = paint;
        config = builder.build();
        bind();
        if (listener != null) listener.onChanged(config);
    }

    private void bind() {
        binding = true;
        FloatingBubbleAppearance.ColorConfig paint = paint();
        for (int i = 0; i < modes.length; i++) {
            boolean active = paint.mode == i;
            modes[i].setSelected(active);
            modes[i].setBackground(background(active ? PALE : SOFT, 11));
            AppTheme.text(modes[i], active ? BLUE : AppTheme.MUTED);
        }
        targetLabel.setText("icon".equals(target) ? "Icon · 点背景切换" : "背景 · 点图标切换");
        preview.setContentDescription("当前编辑" + ("icon".equals(target) ? " Icon，点背景切换" : "背景，点图标切换"));
        preview.invalidate();
        solid.setVisibility(paint.mode == FloatingBubbleAppearance.COLOR_SOLID ? VISIBLE : GONE);
        gradient.setVisibility(paint.mode == FloatingBubbleAppearance.COLOR_SOLID ? GONE : VISIBLE);
        linear.setVisibility(paint.mode == FloatingBubbleAppearance.COLOR_LINEAR ? VISIBLE : GONE);
        free.setVisibility(paint.mode == FloatingBubbleAppearance.COLOR_FREE ? VISIBLE : GONE);
        bindHex(solidHex, "solid", paint.solid);
        bindHex(startHex, "start", paint.start);
        bindHex(endHex, "end", paint.end);
        picker.setColor(paint.solid);
        startSwatch.setBackground(swatch(paint.start));
        endSwatch.setBackground(swatch(paint.end));
        dial.setAngle(paint.angle);
        free.setPaint(paint, target);
        renderPresets();
        binding = false;
    }
    private void bindHex(EditText field, String name, int color) {
        String pending = invalid.get(key(name));
        String value = pending == null ? formatHex(color) : pending;
        if ((!field.hasFocus() || pending != null) && !field.getText().toString().equals(value)) field.setText(value);
        field.setError(pending == null ? null : "使用 3 或 6 位 HEX 颜色");
    }
    private void updateHex(EditText field, String name) {
        if (binding) return;
        Integer color = parseHex(field.getText().toString());
        if (color == null) { invalid.put(key(name), field.getText().toString()); field.setError("使用 3 或 6 位 HEX 颜色"); return; }
        invalid.remove(key(name)); field.setError(null);
        FloatingBubbleAppearance.ColorConfig paint = paint();
        publish("solid".equals(name) ? paint.withSolid(color) : paint.withStops(
                "start".equals(name) ? color : paint.start, "end".equals(name) ? color : paint.end));
    }
    private void renderPresets() {
        // Reuse swatch children on drags; recreating a horizontal list would interrupt its gesture.
        if (solidPresets.getChildCount() == 0) {
            for (int i = 0; i < SOLID_PRESETS.length; i++) {
                final int selected = i;
                PaintSwatch view = new PaintSwatch(getContext());
                view.setContentDescription("使用纯色 " + formatHex(SOLID_PRESETS[i]));
                view.setOnClickListener(v -> { finishEditing(); invalid.remove(key("solid")); publish(paint().withSolid(SOLID_PRESETS[selected])); });
                solidPresets.addView(view, swatchParams(51, 34, i, 6));
            }
            for (int i = 0; i < LINEAR_PRESETS.length; i++) {
                final int selected = i;
                PaintSwatch view = new PaintSwatch(getContext());
                view.setOnClickListener(v -> {
                    finishEditing();
                    if (paint().mode == FloatingBubbleAppearance.COLOR_FREE) {
                        free.clearCurrentInvalid(); freePresetSelections.put(target, selected);
                        publishPaint(paint().withPoints(freePreset(selected)));
                    } else {
                        invalid.remove(key("start")); invalid.remove(key("end"));
                        publish(paint().withStops(LINEAR_PRESETS[selected][0], LINEAR_PRESETS[selected][1]).withAngle(ANGLES[selected]));
                    }
                });
                gradientPresets.addView(view, swatchParams(75, 42, i, 8));
            }
        }
        for (int i = 0; i < SOLID_PRESETS.length; i++) {
            PaintSwatch view = (PaintSwatch) solidPresets.getChildAt(i);
            view.paint = SOLID_PAINTS[i];
            view.setSelected(paint().solid == SOLID_PRESETS[i]); view.invalidate();
            PaintSwatch gradient = (PaintSwatch) gradientPresets.getChildAt(i);
            boolean isFree = paint().mode == FloatingBubbleAppearance.COLOR_FREE;
            gradient.paint = isFree ? FREE_PAINTS[i] : LINEAR_PAINTS[i];
            gradient.setContentDescription((isFree ? "自由" : "线性") + "渐变预设 " + (i + 1));
            Integer selectedPreset = freePresetSelections.get(target);
            gradient.setSelected(isFree ? selectedPreset != null && selectedPreset == i
                    : paint().start == LINEAR_PRESETS[i][0] && paint().end == LINEAR_PRESETS[i][1] && Math.round(paint().angle) == ANGLES[i]);
            gradient.invalidate();
        }
    }

    private void chooseColor(String title, int initial, BubbleFreeGradientEditorView.ColorResult result) {
        chooseColor(title, initial, free.currentHexInput(), result);
    }
    private void chooseColor(String title, int initial, EditText backingHex, BubbleFreeGradientEditorView.ColorResult result) {
        finishEditing();
        Bundle initialEditor = saveEditorState();
        LinearLayout content = column(); content.setPadding(dp(16), dp(12), dp(16), dp(8));
        BubbleColorPickerView dialogPicker = new BubbleColorPickerView(getContext());
        content.addView(dialogPicker, new LayoutParams(-1, -2));
        EditText hex = hexInput(title + " HEX 颜色", 14);
        content.addView(hex, spaced(-1, 44, 8, 0));
        final int[] color = {initial};
        final boolean[] synchronizing = {false};
        hex.setText(formatHex(initial)); dialogPicker.setColor(initial);
        dialogPicker.setListener(value -> {
            synchronizing[0] = true; color[0] = value; hex.setText(formatHex(value)); hex.setError(null);
            // The page field owns valid/invalid draft input, including a dialog interrupted by rotation.
            backingHex.setText(formatHex(value)); synchronizing[0] = false;
        });
        hex.addTextChangedListener(watcher(() -> {
            if (synchronizing[0]) return;
            Integer value = parseHex(hex.getText().toString());
            hex.setError(value == null ? "请输入有效的 HEX 颜色" : null);
            backingHex.setText(hex.getText());
            if (value != null) { color[0] = value; dialogPicker.setColor(value); }
        }));
        Runnable cancel = () -> { result.selected(initial); restoreEditorState(initialEditor); };
        AlertDialog dialog = new AppTheme.DialogBuilder(getContext()).setTitle(title).setView(content)
                .setNegativeButton("取消", (d, which) -> cancel.run()).setPositiveButton("确定", null).create();
        dialog.setOnCancelListener(d -> cancel.run());
        dialog.setOnDismissListener(d -> { if (activeDialog == dialog) activeDialog = null; });
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (parseHex(hex.getText().toString()) == null) { hex.setError("请输入有效的 HEX 颜色"); return; }
            result.selected(color[0]); dialog.dismiss();
        }));
        activeDialog = dialog; dialog.show();
    }
    @Override protected void onDetachedFromWindow() {
        if (activeDialog != null) activeDialog.dismiss();
        super.onDetachedFromWindow();
    }

    private final class PreviewStage extends View {
        private final Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        PreviewStage(Context context) { super(context); setFocusable(true); setClickable(true); }
        @Override protected void onDraw(Canvas canvas) {
            bounds.set(dp(4), dp(4), getWidth() - dp(4), getHeight() - dp(4));
            FloatingBubbleAppearance.Builder builder = new FloatingBubbleAppearance.Builder(config);
            builder.opacityPercent = 100;
            BubbleAppearanceRenderer.draw(canvas, getContext(), builder.build(), bounds, this);
            brush.setStyle(Paint.Style.STROKE); brush.setStrokeWidth(dp(2)); brush.setColor(AppTheme.ink(AppTheme.BLUE));
            if ("icon".equals(target)) {
                brush.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(3), dp(3)}, 0));
                float inset = dp(10);
                canvas.drawRoundRect(new RectF(inset, inset, getWidth() - inset, getHeight() - inset), dp(6), dp(6), brush);
                brush.setPathEffect(null);
            } else {
                int save = canvas.save(); canvas.translate(dp(1), dp(1));
                canvas.drawPath(BubbleAppearanceRenderer.shapePath(config, getWidth() - dp(2), getHeight() - dp(2)), brush);
                canvas.restoreToCount(save);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                boolean center = Math.abs(event.getX() - getWidth() / 2f) <= dp(16)
                        && Math.abs(event.getY() - getHeight() / 2f) <= dp(16);
                selectTarget(center ? "icon" : "background"); performClick(); return true;
            }
            return event.getActionMasked() == MotionEvent.ACTION_DOWN || super.onTouchEvent(event);
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        @Override public boolean onKeyDown(int code, android.view.KeyEvent event) {
            if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) { selectTarget("background"); return true; }
            if (code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) { selectTarget("icon"); return true; }
            return super.onKeyDown(code, event);
        }
    }
    private final class PaintSwatch extends View {
        private final Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF outer = new RectF(), inner = new RectF();
        FloatingBubbleAppearance.ColorConfig paint;
        PaintSwatch(Context context) { super(context); setFocusable(true); setClickable(true); }
        @Override protected void onDraw(Canvas canvas) {
            outer.set(dp(1), dp(1), getWidth() - dp(1), getHeight() - dp(1));
            brush.setStyle(Paint.Style.FILL); brush.setColor(AppTheme.color(SOFT)); canvas.drawRoundRect(outer, dp(10), dp(10), brush);
            inner.set(dp(4), dp(4), getWidth() - dp(4), getHeight() - dp(4));
            android.graphics.Path clip = new android.graphics.Path(); clip.addRoundRect(inner, dp(5), dp(5), android.graphics.Path.Direction.CW);
            int saved = canvas.save(); canvas.clipPath(clip);
            if (paint.mode == FloatingBubbleAppearance.COLOR_FREE) BubblePaintRenderer.drawSampled(canvas, inner, paint, this, 120, 80, true);
            else BubblePaintRenderer.draw(canvas, inner, paint, this);
            canvas.restoreToCount(saved);
            if (isSelected()) { brush.setStyle(Paint.Style.STROKE); brush.setStrokeWidth(dp(1.5f)); brush.setColor(AppTheme.ink(AppTheme.BLUE)); canvas.drawRoundRect(outer, dp(10), dp(10), brush); }
        }
    }

    private static List<FloatingBubbleAppearance.FreePoint> freePreset(int index) {
        return FREE_PAINTS[index].freePoints;
    }
    static String formatHex(int color) { return String.format(Locale.ROOT, "#%06X", color & 0xffffff); }
    static Integer parseHex(String value) {
        String hex = value.trim(); if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.matches("[0-9a-fA-F]{3}")) hex = "" + hex.charAt(0) + hex.charAt(0) + hex.charAt(1) + hex.charAt(1) + hex.charAt(2) + hex.charAt(2);
        if (!hex.matches("[0-9a-fA-F]{6}")) return null;
        return 0xff000000 | Integer.parseInt(hex, 16);
    }
    private void addStop(LinearLayout parent, String label, View swatch, EditText input, boolean margin) {
        LinearLayout cell = column(); cell.addView(text(label, 11, AppTheme.MUTED, false), spaced(-1, -2, 5, 5));
        LinearLayout row = row(); swatch.setFocusable(true); swatch.setContentDescription("选择" + label);
        row.addView(swatch, new LayoutParams(dp(27), dp(27)));
        LayoutParams inputParams = new LayoutParams(0, dp(34), 1); inputParams.leftMargin = dp(4); row.addView(input, inputParams);
        cell.addView(row); LayoutParams params = new LayoutParams(0, -2, 1); if (margin) params.leftMargin = dp(8); parent.addView(cell, params);
    }
    private HorizontalScrollView horizontal(LinearLayout child, String description) {
        return horizontal(child, description, false);
    }
    private HorizontalScrollView horizontal(LinearLayout child, String description, boolean fitThree) {
        HorizontalScrollView scroll = new HorizontalScrollView(getContext()) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, heightSpec);
                if (!fitThree || child.getChildCount() == 0) return;
                int viewport = getMeasuredWidth() - getPaddingLeft() - getPaddingRight()
                        - child.getPaddingLeft() - child.getPaddingRight();
                int swatchWidth = Math.max(1, (viewport - dp(8) * 2) / 3);
                boolean changed = false;
                for (int i = 0; i < child.getChildCount(); i++) {
                    View swatch = child.getChildAt(i);
                    if (swatch.getLayoutParams().width == swatchWidth) continue;
                    swatch.getLayoutParams().width = swatchWidth;
                    swatch.forceLayout(); changed = true;
                }
                if (changed) { child.forceLayout(); super.onMeasure(widthSpec, heightSpec); }
            }
        };
        scroll.setHorizontalScrollBarEnabled(false); scroll.setOverScrollMode(OVER_SCROLL_NEVER); scroll.setFillViewport(false); scroll.setContentDescription(description);
        child.setPadding(dp(1), dp(3), dp(1), dp(9)); scroll.addView(child, new HorizontalScrollView.LayoutParams(-2, -2)); return scroll;
    }
    private LayoutParams swatchParams(int width, int height, int index, int gap) { LayoutParams params = new LayoutParams(dp(width), dp(height)); if (index > 0) params.leftMargin = dp(gap); return params; }
    private EditText hexInput(String description, int size) {
        EditText input = new EditText(getContext()); input.setSingleLine(true); input.setTextSize(size); AppTheme.text(input, AppTheme.MUTED); input.setIncludeFontPadding(false); input.setGravity(Gravity.CENTER); input.setPadding(dp(2), 0, dp(2), 0); input.setBackground(background(SOFT, 10)); input.setContentDescription(description);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)}); input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setOnFocusChangeListener((view, focused) -> { if (!focused && !binding) bind(); }); return input;
    }
    private void finishInput(EditText input) {
        boolean focused = input.hasFocus(); input.clearFocus();
        InputMethodManager manager = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (focused && manager != null) manager.hideSoftInputFromWindow(getWindowToken(), 0);
    }
    private LinearLayout row() { LinearLayout row = new LinearLayout(getContext()); row.setOrientation(HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); return row; }
    private LinearLayout column() { LinearLayout view = new LinearLayout(getContext()); view.setOrientation(VERTICAL); return view; }
    private TextView text(String label, int size, int color, boolean bold) { TextView view = new TextView(getContext()); view.setText(label); view.setTextSize(size); AppTheme.text(view, color); view.setIncludeFontPadding(false); if (bold) view.setTypeface(Typeface.DEFAULT_BOLD); return view; }
    private GradientDrawable swatch(int color) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(100)); return shape; }
    private GradientDrawable background(int color, int radius) { GradientDrawable result = new AppTheme.Shape(); result.setColor(color); result.setCornerRadius(dp(radius)); return result; }
    private LayoutParams spaced(int width, int height, int top, int bottom) { LayoutParams params = new LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height)); params.topMargin = dp(top); params.bottomMargin = dp(bottom); return params; }
    private TextWatcher watcher(Runnable action) { return new TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) { } public void onTextChanged(CharSequence s, int start, int before, int count) { action.run(); } public void afterTextChanged(Editable e) { } }; }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
