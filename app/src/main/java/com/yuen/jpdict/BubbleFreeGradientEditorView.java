package com.yuen.jpdict;

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
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Point editing only; rendering and the immutable color field belong to the shared renderer/model. */
final class BubbleFreeGradientEditorView extends LinearLayout {
    interface Listener { void onChanged(FloatingBubbleAppearance.ColorConfig paint); }
    interface ColorChooser { void choose(String title, int color, ColorResult result); }
    interface ColorResult { void selected(int color); }
    private final FieldBoard board;
    private final TextView count, title, add, remove, radiusValue;
    private final View swatch;
    private final EditText hex, x, y;
    private final SeekBar radius;
    private final ColorChooser chooser;
    private final Map<String, Integer> selections = new HashMap<>();
    private final Map<String, String> invalid = new HashMap<>();
    private FloatingBubbleAppearance.ColorConfig field = FloatingBubbleAppearance.defaultPaint(0xffffffff);
    private String target = "background";
    private boolean binding;
    private Listener listener;

    BubbleFreeGradientEditorView(Context context, ColorChooser chooser) {
        super(context);
        this.chooser = chooser;
        setOrientation(VERTICAL);
        TextView caption = text("自由渐变编辑器 · 拖动颜色点", 11, 0xff40516b, true);
        addView(caption, spaced(-1, -2, 10, 7));
        board = new FieldBoard(context);
        int height = getResources().getConfiguration().screenWidthDp <= 355 ? 167 : 186;
        addView(board, new LayoutParams(-1, dp(height)));
        TextView help = text("轻触色点可选中，拖动可改变位置。颜色自由融合，不受固定方向限制。", 10, 0xff718098, false);
        help.setLineSpacing(dp(2), 1);
        addView(help, spaced(-1, -2, 7, 0));
        LinearLayout toolbar = row();
        count = text("", 11, 0xff526179, true);
        toolbar.addView(count, new LayoutParams(0, -2, 1));
        add = action("＋ 添加", 0xffe6f3ff, 0xff0789f5);
        toolbar.addView(add, new LayoutParams(-2, dp(34)));
        TextView reset = action("重置", 0xfff4f6f9, 0xff637188);
        LayoutParams resetParams = new LayoutParams(-2, dp(34));
        resetParams.leftMargin = dp(4);
        toolbar.addView(reset, resetParams);
        addView(toolbar, spaced(-1, -2, 10, 11));

        LinearLayout details = new LinearLayout(context);
        details.setOrientation(VERTICAL);
        details.setPadding(dp(8), dp(10), dp(8), dp(10));
        details.setBackground(background(0xfff4f5f6, 12));
        title = text("", 11, 0xff46566e, true);
        details.addView(title);
        LinearLayout colorRow = row();
        swatch = new View(context);
        swatch.setContentDescription("选择当前颜色点的颜色");
        swatch.setFocusable(true);
        colorRow.addView(swatch, new LayoutParams(dp(32), dp(32)));
        hex = input("颜色点十六进制色值", false);
        LayoutParams hexParams = new LayoutParams(0, dp(36), 1);
        hexParams.leftMargin = dp(7);
        colorRow.addView(hex, hexParams);
        details.addView(colorRow, spaced(-1, -2, 9, 0));
        LinearLayout coordinates = row();
        x = input("颜色点水平位置百分比", true);
        y = input("颜色点垂直位置百分比", true);
        addCoordinate(coordinates, "X", x, false);
        addCoordinate(coordinates, "Y", y, true);
        details.addView(coordinates, spaced(-1, -2, 10, 0));
        LinearLayout rangeRow = row();
        rangeRow.addView(text("影响范围", 10, 0xff617087, false));
        radius = new SeekBar(context);
        radius.setMax(90);
        radius.setContentDescription("颜色点影响范围，10 至 100 百分比");
        BubbleSliderStyle.apply(radius);
        rangeRow.addView(radius, new LayoutParams(0, dp(36), 1));
        radiusValue = text("", 10, 0xff35475e, false);
        radiusValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        rangeRow.addView(radiusValue, new LayoutParams(dp(30), -2));
        details.addView(rangeRow, spaced(-1, -2, 6, 0));
        remove = action("删除当前色点", 0xfffff1f3, 0xffc44656);
        details.addView(remove, spaced(-1, 34, 8, 0));
        addView(details, new LayoutParams(-1, -2));

        hex.addTextChangedListener(watcher(() -> updateHex()));
        x.addTextChangedListener(watcher(() -> coordinateChanged(x, true)));
        y.addTextChangedListener(watcher(() -> coordinateChanged(y, false)));
        swatch.setOnClickListener(v -> chooser.choose("颜色点 " + (selected() + 1), point().color,
                color -> { invalid.remove(key("hex")); replacePoint(new FloatingBubbleAppearance.FreePoint(
                        point().x, point().y, color, point().radius)); }));
        radius.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser) replacePoint(new FloatingBubbleAppearance.FreePoint(
                        point().x, point().y, point().color, value + 10));
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        add.setOnClickListener(v -> addPoint());
        remove.setOnClickListener(v -> {
            if (field.freePoints.size() <= 2) return;
            finishEditing();
            List<FloatingBubbleAppearance.FreePoint> points = new ArrayList<>(field.freePoints);
            int deleted = selected();
            points.remove(deleted);
            removeInvalidPoint(deleted);
            selections.put(target, Math.min(selected(), points.size() - 1));
            publish(field.withPoints(points));
        });
        reset.setOnClickListener(v -> {
            finishEditing();
            clearInvalidTarget();
            selections.put(target, 0);
            publish(field.withPoints(FloatingBubbleAppearance.defaultFreePoints()));
        });
        bind();
    }

    void setListener(Listener listener) { this.listener = listener; }
    void setPaint(FloatingBubbleAppearance.ColorConfig field, String target) {
        if (this.field == field && this.target.equals(target)) return;
        this.field = field;
        this.target = target;
        bind();
    }
    boolean isInputValid() {
        if (invalid.isEmpty()) return true;
        bind();
        return false;
    }
    void resetSession() { invalid.clear(); selections.clear(); bind(); }
    void finishEditing() {
        boolean editing = hex.hasFocus() || x.hasFocus() || y.hasFocus();
        hex.clearFocus(); x.clearFocus(); y.clearFocus();
        InputMethodManager manager = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (editing && manager != null) manager.hideSoftInputFromWindow(getWindowToken(), 0);
    }
    Bundle saveEditorState() {
        Bundle state = new Bundle();
        for (Map.Entry<String, String> entry : invalid.entrySet()) state.putString("invalid:" + entry.getKey(), entry.getValue());
        for (Map.Entry<String, Integer> entry : selections.entrySet()) state.putInt("selected:" + entry.getKey(), entry.getValue());
        return state;
    }
    void restoreEditorState(Bundle state) {
        if (state == null) return;
        invalid.clear(); selections.clear();
        for (String key : state.keySet()) {
            if (key.matches("invalid:(?:background|icon):[0-7]:(?:hex|x|y)"))
                invalid.put(key.substring(8), state.getString(key, ""));
            if (key.matches("selected:(?:background|icon)"))
                selections.put(key.substring(9), Math.max(0, Math.min(7, state.getInt(key))));
        }
        bind();
    }
    void clearCurrentInvalid() { clearInvalidTarget(); bind(); }
    EditText currentHexInput() { return hex; }

    private void bind() {
        binding = true;
        FloatingBubbleAppearance.FreePoint p = point();
        count.setText("色点 " + field.freePoints.size() + "/8");
        title.setText("选中颜色点 " + (selected() + 1));
        bindInput(hex, "hex", BubblePaintEditorView.formatHex(p.color));
        bindInput(x, "x", String.valueOf(Math.round(p.x * 100)));
        bindInput(y, "y", String.valueOf(Math.round(p.y * 100)));
        swatch.setBackground(background(p.color, 100));
        radius.setProgress(Math.round(p.radius) - 10);
        radiusValue.setText(Math.round(p.radius) + "%");
        add.setEnabled(field.freePoints.size() < 8);
        add.setAlpha(add.isEnabled() ? 1 : .45f);
        remove.setEnabled(field.freePoints.size() > 2);
        remove.setAlpha(remove.isEnabled() ? 1 : .45f);
        board.setContentDescription("自由渐变画布，颜色点 " + (selected() + 1) + "，X "
                + Math.round(p.x * 100) + "%，Y " + Math.round(p.y * 100) + "%，拖动或方向键调整");
        board.invalidate();
        binding = false;
    }

    private void bindInput(EditText input, String key, String value) {
        String pending = invalid.get(key(key));
        String next = pending == null ? value : pending;
        if (!input.hasFocus() || pending != null) {
            if (!input.getText().toString().equals(next)) input.setText(next);
        }
        input.setError(pending == null ? null : "请输入有效值");
    }
    private void updateHex() {
        if (binding) return;
        Integer color = BubblePaintEditorView.parseHex(hex.getText().toString());
        if (color == null) { invalid.put(key("hex"), hex.getText().toString()); hex.setError("使用 3 或 6 位 HEX 颜色"); return; }
        invalid.remove(key("hex")); hex.setError(null);
        replacePoint(new FloatingBubbleAppearance.FreePoint(point().x, point().y, color, point().radius));
    }
    private void coordinateChanged(EditText input, boolean horizontal) {
        if (binding) return;
        String coordinate = horizontal ? "x" : "y";
        float value;
        try { value = Float.parseFloat(input.getText().toString()); }
        catch (NumberFormatException error) { invalid.put(key(coordinate), input.getText().toString()); return; }
        if (Float.isNaN(value) || Float.isInfinite(value)) { invalid.put(key(coordinate), input.getText().toString()); return; }
        value = Math.max(0, Math.min(100, value)) / 100;
        invalid.remove(key(coordinate)); input.setError(null);
        replacePoint(new FloatingBubbleAppearance.FreePoint(horizontal ? value : point().x,
                horizontal ? point().y : value, point().color, point().radius));
    }
    private int selected() { Integer index = selections.get(target); return Math.max(0, Math.min(field.freePoints.size() - 1, index == null ? 0 : index)); }
    private FloatingBubbleAppearance.FreePoint point() { return field.freePoints.get(selected()); }
    private String key(String fieldKey) { return target + ":" + selected() + ":" + fieldKey; }
    private void clearInvalidCoordinates() {
        invalid.remove(key("x")); invalid.remove(key("y"));
    }
    private void clearInvalidTarget() {
        for (String key : new ArrayList<>(invalid.keySet())) if (key.startsWith(target + ":")) invalid.remove(key);
    }
    private void removeInvalidPoint(int deleted) {
        Map<String, String> moved = new HashMap<>();
        String prefix = target + ":";
        for (String key : new ArrayList<>(invalid.keySet())) {
            if (!key.startsWith(prefix)) continue;
            String suffix = key.substring(prefix.length());
            int separator = suffix.indexOf(':');
            if (separator < 0) continue;
            int index;
            try { index = Integer.parseInt(suffix.substring(0, separator)); }
            catch (NumberFormatException damagedState) { invalid.remove(key); continue; }
            if (index < deleted) continue;
            String value = invalid.remove(key);
            if (index > deleted) moved.put(prefix + (index - 1) + suffix.substring(separator), value);
        }
        invalid.putAll(moved);
    }
    private void replacePoint(FloatingBubbleAppearance.FreePoint p) {
        List<FloatingBubbleAppearance.FreePoint> points = new ArrayList<>(field.freePoints);
        points.set(selected(), p);
        publish(field.withPoints(points));
    }
    private void publish(FloatingBubbleAppearance.ColorConfig next) {
        field = next;
        bind();
        if (listener != null) listener.onChanged(next);
    }
    private void addPoint() {
        if (field.freePoints.size() >= 8) return;
        finishEditing();
        float[][] candidates = {{.50f,.50f},{.30f,.50f},{.70f,.50f},{.50f,.25f},{.50f,.75f},{.24f,.28f},{.75f,.73f}};
        float[] best = candidates[0];
        float bestDistance = -1;
        for (float[] candidate : candidates) {
            float distance = Float.MAX_VALUE;
            for (FloatingBubbleAppearance.FreePoint p : field.freePoints) {
                float dx = p.x - candidate[0], dy = p.y - candidate[1];
                distance = Math.min(distance, dx * dx + dy * dy);
            }
            if (distance > bestDistance) { bestDistance = distance; best = candidate; }
        }
        int count = field.freePoints.size();
        int[] colors = {0xffffd166, 0xfff973b5, 0xff4ee4d1, 0xff5b82ff};
        List<FloatingBubbleAppearance.FreePoint> points = new ArrayList<>(field.freePoints);
        points.add(new FloatingBubbleAppearance.FreePoint(best[0], best[1], colors[count % 4], 60));
        selections.put(target, count);
        publish(field.withPoints(points));
    }

    private final class FieldBoard extends View {
        private final Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF area = new RectF();
        private boolean dragging;
        private int pointer = -1;
        FieldBoard(Context context) { super(context); setFocusable(true); setClickable(true); }
        @Override protected void onDraw(Canvas canvas) {
            area.set(0, 0, getWidth(), getHeight());
            int saved = canvas.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(area, dp(11), dp(11), android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            BubblePaintRenderer.drawSampled(canvas, area, field.mode == FloatingBubbleAppearance.COLOR_FREE
                    ? field : field.withMode(FloatingBubbleAppearance.COLOR_FREE), this, 240, 180, false, target);
            canvas.restoreToCount(saved);
            for (int i = 0; i < field.freePoints.size(); i++) {
                FloatingBubbleAppearance.FreePoint p = field.freePoints.get(i);
                float cx = p.x * getWidth(), cy = p.y * getHeight();
                boolean active = i == selected();
                float radius = dp(active ? 12.5f : 10.5f);
                brush.setStyle(Paint.Style.FILL);
                if (active) { brush.setColor(0x8affffff); canvas.drawCircle(cx, cy, radius + dp(5), brush); }
                brush.setColor(active ? 0xff178af2 : 0xff60728d);
                canvas.drawCircle(cx, cy, radius + dp(active ? 2 : 1), brush);
                brush.setColor(0xffffffff); canvas.drawCircle(cx, cy, radius, brush);
                brush.setColor(p.color); canvas.drawCircle(cx, cy, radius - dp(2), brush);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    int hit = -1;
                    double distance = dp(20);
                    for (int i = field.freePoints.size() - 1; i >= 0; i--) {
                        FloatingBubbleAppearance.FreePoint p = field.freePoints.get(i);
                        double next = Math.hypot(event.getX() - p.x * getWidth(), event.getY() - p.y * getHeight());
                        if (next < distance) { hit = i; distance = next; }
                    }
                    if (hit < 0) return false;
                    finishEditing(); selections.put(target, hit); dragging = true; pointer = event.getPointerId(0);
                    getParent().requestDisallowInterceptTouchEvent(true); bind(); return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) return false;
                    int index = event.findPointerIndex(pointer);
                    if (index < 0) { stopDrag(); return true; }
                    clearInvalidCoordinates();
                    replacePoint(new FloatingBubbleAppearance.FreePoint(
                            Math.max(0, Math.min(1, event.getX(index) / Math.max(1, getWidth()))),
                            Math.max(0, Math.min(1, event.getY(index) / Math.max(1, getHeight()))), point().color, point().radius));
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN: return dragging;
                case MotionEvent.ACTION_POINTER_UP:
                    if (dragging && event.getPointerId(event.getActionIndex()) == pointer) stopDrag();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!dragging) return false;
                    stopDrag();
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick(); return true;
                default: return super.onTouchEvent(event);
            }
        }
        private void stopDrag() {
            dragging = false; pointer = -1;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        }
        @Override protected void onDetachedFromWindow() { stopDrag(); super.onDetachedFromWindow(); }
        @Override public boolean onKeyDown(int code, KeyEvent event) {
            float dx = 0, dy = 0, step = event.isShiftPressed() ? .10f : .02f;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) dx = -step;
            else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) dx = step;
            else if (code == KeyEvent.KEYCODE_DPAD_UP) dy = -step;
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN) dy = step;
            else return super.onKeyDown(code, event);
            clearInvalidCoordinates();
            replacePoint(new FloatingBubbleAppearance.FreePoint(Math.max(0, Math.min(1, point().x + dx)),
                    Math.max(0, Math.min(1, point().y + dy)), point().color, point().radius));
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }

    private void addCoordinate(LinearLayout parent, String label, EditText editor, boolean margin) {
        LinearLayout cell = row();
        cell.addView(text(label, 11, 0xff68768c, false), new LayoutParams(dp(15), -2));
        cell.addView(editor, new LayoutParams(0, dp(36), 1));
        LayoutParams params = new LayoutParams(0, -2, 1);
        if (margin) params.leftMargin = dp(8);
        parent.addView(cell, params);
    }
    private EditText input(String description, boolean numeric) {
        EditText input = new EditText(getContext());
        input.setTextSize(11); input.setTextColor(0xff253348); input.setSingleLine(true);
        input.setIncludeFontPadding(false); input.setGravity(Gravity.CENTER);
        input.setPadding(dp(3), 0, dp(3), 0); input.setBackground(background(0xffffffff, numeric ? 7 : 10));
        input.setContentDescription(description);
        input.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(numeric ? 6 : 7)});
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setOnFocusChangeListener((view, focused) -> { if (!focused && !binding) bind(); });
        return input;
    }
    private TextView action(String label, int color, int ink) {
        TextView view = text(label, 10, ink, true); view.setGravity(Gravity.CENTER);
        view.setPadding(dp(6), dp(5), dp(6), dp(5)); view.setFocusable(true);
        view.setBackground(background(color, 8)); return view;
    }
    private LinearLayout row() { LinearLayout row = new LinearLayout(getContext()); row.setOrientation(HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); return row; }
    private TextView text(String label, int size, int color, boolean bold) {
        TextView view = new TextView(getContext()); view.setText(label); view.setTextSize(size); view.setTextColor(color);
        view.setIncludeFontPadding(false); if (bold) view.setTypeface(Typeface.DEFAULT_BOLD); return view;
    }
    private GradientDrawable background(int color, int radius) { GradientDrawable result = new GradientDrawable(); result.setColor(color); result.setCornerRadius(dp(radius)); return result; }
    private LayoutParams spaced(int width, int height, int top, int bottom) { LayoutParams params = new LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height)); params.topMargin = dp(top); params.bottomMargin = dp(bottom); return params; }
    private TextWatcher watcher(Runnable action) { return new TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) { } public void onTextChanged(CharSequence s, int start, int before, int count) { action.run(); } public void afterTextChanged(Editable e) { } }; }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
