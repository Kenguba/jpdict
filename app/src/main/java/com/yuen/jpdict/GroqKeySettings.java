package com.yuen.jpdict;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** One key editor for Activities and focusable search / lookup overlays. */
public final class GroqKeySettings {
    private static final int INK = 0xff202327;
    private static final int BLUE = 0xff008fd1;
    private static final int SOFT = 0xfff5f6f7;

    private GroqKeySettings() {}

    public static LinearLayout createPanel(Context context, Runnable onChanged,
            Runnable onClose, Runnable onEditing) {
        return createPanel(context, onChanged, onClose, onEditing, null);
    }

    public static LinearLayout createPanel(Context context, Runnable onChanged,
            Runnable onClose, Runnable onEditing, Runnable onBack) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 12));
        panel.setBackground(rounded(context, Color.WHITE, 24));
        panel.addView(text(context, "Groq Key", 20, INK, true));

        TextView note = text(context, GroqKeyStore.hasKey(context)
                ? "已全局配置，搜索、OCR 和查词共用。输入新 Key 可替换，留空不会修改。"
                : "保存后在搜索、OCR 和查词中全局生效。Key 使用本机加密保存。",
                13, 0xff6d747b, false);
        note.setPadding(0, dp(context, 10), 0, dp(context, 14));
        note.setLineSpacing(dp(context, 3), 1f);
        panel.addView(note);

        EditText input = new KeyEditor(context, onBack, onEditing != null);
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setTextColor(INK);
        input.setHint("gsk_...");
        input.setHintTextColor(0xff94999e);
        input.setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10));
        input.setBackground(rounded(context, SOFT, 12));
        input.setLongClickable(true);
        input.setSelectAllOnFocus(false);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setTypeface(Typeface.MONOSPACE);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setContentDescription("Groq API Key，支持长按粘贴");
        input.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN && onEditing != null) {
                onEditing.run();
            }
            return false;
        });
        input.setOnFocusChangeListener((view, focused) -> {
            if (focused && onEditing != null) onEditing.run();
        });
        panel.addView(input, new LinearLayout.LayoutParams(-1, dp(context, 48)));

        LinearLayout options = new LinearLayout(context);
        options.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox visible = new CheckBox(context);
        visible.setText("显示 Key");
        visible.setTextSize(13);
        visible.setTextColor(INK);
        visible.setButtonTintList(ColorStateList.valueOf(INK));
        visible.setOnCheckedChangeListener((button, checked) -> {
            int selection = input.getSelectionStart();
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    | (checked ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                            : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            input.setTypeface(Typeface.MONOSPACE);
            if (selection >= 0 && selection <= input.length()) input.setSelection(selection);
        });
        options.addView(visible, new LinearLayout.LayoutParams(0, dp(context, 48), 1f));
        TextView paste = action(context, "粘贴", BLUE);
        paste.setOnClickListener(view -> {
            if (onEditing != null) onEditing.run();
            input.requestFocus();
            pasteWhenFocused(context, input);
        });
        options.addView(paste);
        panel.addView(options);

        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        if (GroqKeyStore.hasKey(context)) {
            TextView clear = action(context, "清除", 0xff6d747b);
            clear.setOnClickListener(view -> {
                if (!GroqKeyStore.clear(context)) {
                    toast(context, "Key 清除失败，请重试");
                    return;
                }
                input.setText("");
                toast(context, "全局 Groq Key 已清除");
                complete(onChanged, onClose);
            });
            actions.addView(clear);
        }
        if (onClose != null) {
            TextView close = action(context, "关闭", INK);
            close.setOnClickListener(view -> onClose.run());
            actions.addView(close);
        }
        TextView save = action(context, "保存", BLUE);
        save.setOnClickListener(view -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) {
                toast(context, "没有输入 Key，未修改");
                return;
            }
            if (!GroqKeyStore.save(context, value)) {
                toast(context, "Key 加密保存失败，请重试");
                return;
            }
            input.setText("");
            toast(context, "Groq Key 已保存，全局生效");
            complete(onChanged, onClose);
        });
        input.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            save.performClick();
            return true;
        });
        actions.addView(save);
        panel.addView(actions);
        return panel;
    }

    public static void showDialog(Activity activity, Runnable onChanged) {
        AlertDialog dialog = new AlertDialog.Builder(activity).create();
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(createPanel(activity, onChanged, dialog::dismiss, null));
        dialog.setView(scroll);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.setOnDismissListener(ignored -> {
            InputMethodManager ime = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (ime != null) ime.hideSoftInputFromWindow(scroll.getWindowToken(), 0);
        });
    }

    public static void bindConfigurationPrompt(TextView body, boolean invalid,
            Runnable openSettings) {
        String message = invalid
                ? "Groq Key 无效或没有权限，请配置 Key 后重试。"
                : "Groq AI 尚未配置，配置 Key 后即可查询。\nKey 在本机加密保存，所有查询入口共用。";
        SpannableString linked = new SpannableString(message);
        int start = message.indexOf("配置 Key");
        linked.setSpan(new ClickableSpan() {
            @Override public void onClick(View widget) {
                if (openSettings != null) openSettings.run();
            }

            @Override public void updateDrawState(TextPaint paint) {
                paint.setColor(BLUE);
                paint.setUnderlineText(true);
            }
        }, start, start + "配置 Key".length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        body.setTextIsSelectable(false);
        body.setText(linked);
        body.setHighlightColor(0xffdceef7);
        body.setMovementMethod(LinkMovementMethod.getInstance());
    }

    public static void setContent(TextView body, String content) {
        body.setMovementMethod(null);
        body.setText(content);
        body.setTextIsSelectable(true);
    }

    private static void pasteKey(Context context, EditText input) {
        if (!input.isAttachedToWindow()) return;
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            CharSequence value = clip == null || clip.getItemCount() == 0
                    ? null : clip.getItemAt(0).getText();
            if (value == null || value.toString().trim().isEmpty()) {
                toast(context, "剪贴板没有文本，请先复制 Key");
                return;
            }
            input.setText(value.toString().trim());
            input.setSelection(input.length());
        } catch (RuntimeException ignored) {
            toast(context, "无法读取剪贴板，请长按输入框粘贴");
        }
    }

    private static void pasteWhenFocused(Context context, EditText input) {
        if (input.hasWindowFocus()) {
            pasteKey(context, input);
            return;
        }
        // Android permits clipboard reads only after this Overlay gains focus.
        input.getViewTreeObserver().addOnWindowFocusChangeListener(
                new ViewTreeObserver.OnWindowFocusChangeListener() {
                    @Override public void onWindowFocusChanged(boolean focused) {
                        if (!focused) return;
                        if (input.getViewTreeObserver().isAlive()) {
                            input.getViewTreeObserver().removeOnWindowFocusChangeListener(this);
                        }
                        input.post(() -> pasteKey(context, input));
                    }
                });
    }

    private static void complete(Runnable onChanged, Runnable onClose) {
        if (onChanged != null) onChanged.run();
        if (onClose != null) onClose.run();
    }

    @android.annotation.SuppressLint("AppCompatCustomView")
    private static final class KeyEditor extends EditText {
        private final Runnable onBack;
        private final boolean overlayInput;

        KeyEditor(Context context, Runnable onBack, boolean overlayInput) {
            super(context);
            this.onBack = onBack;
            this.overlayInput = overlayInput;
        }

        @Override public boolean onKeyPreIme(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK && onBack != null) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) onBack.run();
                return true;
            }
            return super.onKeyPreIme(keyCode, event);
        }

        @Override public void onWindowFocusChanged(boolean focused) {
            super.onWindowFocusChanged(focused);
            if (!overlayInput || !focused || !isFocused()) return;
            post(() -> {
                if (!isAttachedToWindow() || !hasWindowFocus() || !isFocused()) return;
                InputMethodManager ime = (InputMethodManager) getContext()
                        .getSystemService(Context.INPUT_METHOD_SERVICE);
                if (ime != null) ime.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
            });
        }
    }

    private static TextView action(Context context, String label, int color) {
        TextView view = text(context, label, 14, color, true);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(context, 48));
        view.setPadding(dp(context, 14), 0, dp(context, 14), 0);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0xffe6e8ea),
                rounded(context, Color.TRANSPARENT, 12), null));
        return view;
    }

    private static TextView text(Context context, String value, int size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private static GradientDrawable rounded(Context context, int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(context, radius));
        return background;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }
}
