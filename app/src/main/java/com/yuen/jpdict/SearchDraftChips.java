package com.yuen.jpdict;

import android.content.ClipData;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Native draft chips and precise composer/trash drop targets. */
final class SearchDraftChips extends HorizontalScrollView {
    interface DragUi {
        void editing();
        void dragging(boolean active);
        void caret(int offset);
    }
    private final SearchDraftState state = new SearchDraftState();
    private final LinearLayout row;
    private final DragUi ui;
    private final EditText editor;
    private final View trash;
    private boolean dragging;

    SearchDraftChips(Context context, EditText editor, View root, View trash, DragUi ui) {
        super(context);
        this.editor = editor; this.trash = trash; this.ui = ui;
        setHorizontalScrollBarEnabled(false);
        setFillViewport(false);
        setPadding(dp(5), 0, dp(5), 0);
        row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row, new HorizontalScrollView.LayoutParams(-2, -1));
        root.setOnDragListener((v, event) -> {
            SearchDraftState.Draft draft = draft(event);
            if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) {
                if (!state.contains(draft)) return false;
                dragging = true; ui.dragging(true); return true;
            }
            if (!dragging) return false;
            if (event.getAction() == DragEvent.ACTION_DRAG_ENDED) endDrag();
            return true;
        });
        editor.setOnDragListener(this::onEditorDrag);
        trash.setOnDragListener(this::onTrashDrag);
        setContentDescription("被替换的文字，长按拖到输入框插入，拖到红色垃圾桶删除");
    }

    void replaceFromClipboard(String copied) {
        state.displaced(editor.getText().toString(), copied);
        editor.setText(copied);
        editor.setSelection(editor.length());
        render();
    }
    void clear() { state.clear(); render(); }
    void dispose() { endDrag(); state.clear(); }

    private void render() {
        row.removeAllViews();
        for (SearchDraftState.Draft draft : state.list()) {
            TextView chip = new TextView(getContext());
            chip.setText(draft.text.replace('\n', ' ')); AppTheme.text(chip, AppTheme.PURPLE);
            chip.setTextSize(12); chip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            chip.setIncludeFontPadding(false); chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setSingleLine(true); chip.setEllipsize(TextUtils.TruncateAt.END);
            chip.setMaxWidth(dp(164)); chip.setMinWidth(dp(44));
            chip.setPadding(dp(10), 0, dp(10), 0);
            chip.setBackground(background(AppTheme.LAVENDER, AppTheme.LINE));
            chip.setContentDescription("暂存文字 " + draft.text + "，长按拖动");
            chip.setOnClickListener(v -> { ui.editing(); editor.requestFocus();
                int caret = Math.max(0, editor.getSelectionEnd()); insert(draft, caret); });
            chip.setOnLongClickListener(v -> {
                if (!state.contains(draft)) return false;
                ui.editing(); dragging = true; ui.dragging(true);
                ClipData data = ClipData.newPlainText("JPDict 暂存文字", draft.text);
                View.DragShadowBuilder shadow = new View.DragShadowBuilder(chip);
                boolean started = Build.VERSION.SDK_INT >= 24
                        ? chip.startDragAndDrop(data, shadow, draft, 0)
                        : chip.startDrag(data, shadow, draft, 0);
                if (started) chip.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                else endDrag();
                return started;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(28)); lp.rightMargin = dp(5);
            row.addView(chip, lp);
        }
    }

    private boolean onEditorDrag(View view, DragEvent event) {
        SearchDraftState.Draft draft = draft(event);
        if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) return state.contains(draft);
        if (draft == null) return false;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_ENTERED:
            case DragEvent.ACTION_DRAG_LOCATION:
                ui.editing(); editor.requestFocus(); editor.setCursorVisible(true);
                int offset = editor.getOffsetForPosition(event.getX(), event.getY());
                offset = Math.max(0, Math.min(editor.length(), offset));
                editor.setSelection(offset); ui.caret(offset); break;
            case DragEvent.ACTION_DROP:
                insert(draft, editor.getOffsetForPosition(event.getX(), event.getY())); ui.caret(-1); break;
            case DragEvent.ACTION_DRAG_EXITED:
            case DragEvent.ACTION_DRAG_ENDED: ui.caret(-1); break;
        }
        return true;
    }

    private boolean onTrashDrag(View view, DragEvent event) {
        SearchDraftState.Draft draft = draft(event);
        if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) return state.contains(draft);
        if (draft == null) return false;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_ENTERED:
                trash.animate().scaleX(1.04f).scaleY(1.04f).setDuration(100).start();
                trash.setBackground(background(AppTheme.SOFT, AppTheme.RED)); break;
            case DragEvent.ACTION_DROP:
                state.remove(draft); render();
                trash.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); break;
            case DragEvent.ACTION_DRAG_EXITED:
            case DragEvent.ACTION_DRAG_ENDED:
                trash.animate().scaleX(1).scaleY(1).setDuration(100).start();
                trash.setBackground(background(AppTheme.ROSE, AppTheme.SOFT)); break;
        }
        return true;
    }
    private void insert(SearchDraftState.Draft draft, int offset) {
        if (!state.contains(draft)) return;
        int safe = Math.max(0, Math.min(editor.length(), offset));
        // Never split a supplementary character at the preview insertion point.
        if (safe > 0 && safe < editor.length() && Character.isHighSurrogate(editor.getText().charAt(safe - 1))
                && Character.isLowSurrogate(editor.getText().charAt(safe))) safe++;
        editor.getText().insert(safe, draft.text); editor.setSelection(safe + draft.text.length());
        state.remove(draft); render();
    }
    private SearchDraftState.Draft draft(DragEvent event) {
        return event.getLocalState() instanceof SearchDraftState.Draft
                ? (SearchDraftState.Draft)event.getLocalState() : null;
    }
    private void endDrag() {
        if (!dragging) return;
        dragging = false; ui.caret(-1); ui.dragging(false);
    }
    private RippleDrawable background(int color, int stroke) {
        GradientDrawable shape = new AppTheme.Shape(); shape.setColor(color); shape.setCornerRadius(dp(14)); shape.setStroke(dp(1), stroke);
        return new RippleDrawable(ColorStateList.valueOf(0x147945a1), shape, null);
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
