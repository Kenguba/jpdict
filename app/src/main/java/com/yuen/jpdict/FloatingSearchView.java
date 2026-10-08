package com.yuen.jpdict;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Native search surface. The Service retains window ownership and lookup routing. */
final class FloatingSearchView extends FrameLayout {
    interface Callback {
        void onQueryChanged(String query);
        void onLookup(String query, boolean privateMode);
        void onPrivateModeChanged(boolean privateMode);
        void onEditingChanged(boolean editing);
        void onComposerBoundsChanged(Rect bounds, boolean keyboardVisible);
        void onClose();
    }

    private static final int PAPER = 0xfffcfcfc;
    private static final int INK = 0xff151416;
    private static final int MUTED = 0xff949698;
    private static final int SOFT = 0xfff2f2f2;
    private final Callback callback;
    private final Rect composerBounds = new Rect();
    private final Rect legacyVisibleFrame = new Rect();
    private final int[] legacyRootPosition = new int[2];
    private final FloatingSearchState state = new FloatingSearchState();
    private final LinearLayout page;
    private final LinearLayout header;
    private final FrameLayout body;
    private final LinearLayout bottom;
    private final LinearLayout composer;
    private final LinearLayout tools;
    private final Editor input;
    private final SearchIconView plus;
    private final SearchIconView privateButton;
    private final LinearLayout modelButton;
    private final LinearLayout actionButton;
    private final HorizontalScrollView recommendations;
    private final SearchDrawerView drawer;
    private final View drawerDismiss;
    private View emptyView;
    private View menuDismiss;
    private LinearLayout menuCard;
    private View settingsCard;
    private MenuScroll menuSurface;
    private int drawerWidth;
    private float drawerProgress;
    private ValueAnimator drawerAnimator;
    private boolean disposed;
    private boolean compactComposer;
    private int imeBottom;
    private int safeBottom;
    private int topInset;
    private int systemBackInset;
    private int legacyViewportHeight;
    private ViewTreeObserver.OnGlobalLayoutListener legacyLayoutListener;
    private ViewTreeObserver.OnPreDrawListener legacyPreDrawListener;
    private float downX, downY, startProgress;
    private boolean swiping, swipeEligible;
    private VelocityTracker velocity;

    FloatingSearchView(Context context, String query, boolean privateMode, Callback callback) {
        super(context);
        this.callback = callback;
        state.setPrivateMode(privateMode);
        setBackgroundColor(PAPER);
        // Own blank-area DOWN events so the drawer receives subsequent MOVE events.
        setClickable(true);
        setFocusableInTouchMode(true);
        setClipChildren(false);
        page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(PAPER);
        page.setClipChildren(false);
        addView(page, new FrameLayout.LayoutParams(-1, -1));

        header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(6), dp(12), dp(6));
        SearchIconView menu = button(SearchIconView.Kind.MENU, "打开侧栏", SOFT);
        header.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(44)));
        menu.setOnClickListener(v -> setDrawerOpen(true));
        LinearLayout tabs = new LinearLayout(context);
        tabs.setGravity(Gravity.CENTER);
        LinearLayout ask = new LinearLayout(context);
        ask.setOrientation(LinearLayout.VERTICAL);
        ask.setGravity(Gravity.CENTER);
        TextView askLabel = text("提问", 17, INK, true);
        askLabel.setSingleLine();
        askLabel.setGravity(Gravity.CENTER);
        ask.addView(askLabel);
        View underline = new View(context);
        underline.setBackground(shape(0xffb1b1b1, 2));
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(dp(15), dp(3));
        line.topMargin = dp(7);
        ask.addView(underline, line);
        tabs.addView(ask, new LinearLayout.LayoutParams(0, dp(52), 65));
        for (String label : new String[]{"Imagine", "构建"}) {
            TextView tab = text(label, 17, 0xffb0b0b0, true);
            tab.setSingleLine();
            tab.setEllipsize(TextUtils.TruncateAt.END);
            tab.setGravity(Gravity.CENTER);
            tab.setContentDescription(label + "，尚未接入");
            tab.setOnClickListener(v -> unavailable(label));
            tabs.addView(tab, new LinearLayout.LayoutParams(0, dp(52), label.equals("Imagine") ? 90 : 56));
        }
        header.addView(tabs, new LinearLayout.LayoutParams(0, dp(56), 1));
        privateButton = button(SearchIconView.Kind.PRIVATE, "切换私密查询", SOFT);
        header.addView(privateButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        privateButton.setOnClickListener(v -> togglePrivateMode());
        page.addView(header, new LinearLayout.LayoutParams(-1, dp(68)));

        body = new FrameLayout(context);
        body.setClipChildren(false);
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setPadding(0, dp(8), 0, dp(8));
        recommendations = new HorizontalScrollView(context);
        recommendations.setHorizontalScrollBarEnabled(false);
        recommendations.setClipToPadding(false);
        recommendations.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout pills = new LinearLayout(context);
        pills.setGravity(Gravity.CENTER_VERTICAL);
        recommendations.addView(pills, new HorizontalScrollView.LayoutParams(-2, -1));
        addPill(pills, "Groq AI 设置", SearchIconView.Kind.MARK, 0xffdef7ff, 0xff009bdc, this::showSettings);
        addPill(pills, "构建应用和网站", SearchIconView.Kind.PROJECT, SOFT, INK, () -> unavailable("构建"));
        addPill(pills, "多语种查词", SearchIconView.Kind.SEARCH, 0xffe7f5ed, 0xff15996c, this::focusInput);
        bottom.addView(recommendations, new LinearLayout.LayoutParams(-1, dp(50)));

        composer = new LinearLayout(context);
        composer.setOrientation(LinearLayout.VERTICAL);
        composer.setPadding(dp(8), dp(5), dp(8), dp(7));
        composer.setBackground(shape(PAPER, 26));
        composer.setElevation(dp(3));
        input = new Editor(context);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setTextSize(17);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(dp(10), dp(5), dp(10), dp(6));
        input.setMinLines(1);
        input.setMaxLines(4);
        input.setMinHeight(0);
        input.setMinimumHeight(0);
        input.setGravity(Gravity.TOP);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        input.setContentDescription("查询输入框");
        input.setText(query == null ? "" : query);
        input.setSelection(input.length());
        composer.addView(input, new LinearLayout.LayoutParams(-1, -2));
        tools = new LinearLayout(context);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        plus = button(SearchIconView.Kind.PLUS, "附件菜单", SOFT);
        tools.addView(plus, new LinearLayout.LayoutParams(dp(36), dp(36)));
        plus.setOnClickListener(v -> toggleMenu(FloatingSearchState.Menu.ATTACHMENT));
        modelButton = new LinearLayout(context);
        modelButton.setGravity(Gravity.CENTER);
        modelButton.setPadding(dp(12), 0, dp(10), 0);
        modelButton.setBackground(ripple(SOFT, 24));
        modelButton.setContentDescription("选择模型，当前快速");
        SearchIconView bolt = icon(SearchIconView.Kind.LIGHTNING, INK);
        modelButton.addView(bolt, new LinearLayout.LayoutParams(dp(19), dp(23)));
        TextView fast = text("快速", 14, INK, true);
        fast.setPadding(dp(5), 0, dp(5), 0);
        modelButton.addView(fast);
        modelButton.addView(icon(SearchIconView.Kind.CHEVRON, 0xff74777a), new LinearLayout.LayoutParams(dp(13), dp(18)));
        LinearLayout.LayoutParams modelLp = new LinearLayout.LayoutParams(-2, dp(36));
        modelLp.leftMargin = dp(7);
        tools.addView(modelButton, modelLp);
        modelButton.setOnClickListener(v -> toggleMenu(FloatingSearchState.Menu.MODEL));
        View spacer = new View(context);
        tools.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        SearchIconView mic = button(SearchIconView.Kind.MIC, "语音转文字，尚未接入", SOFT);
        tools.addView(mic, new LinearLayout.LayoutParams(dp(36), dp(36)));
        mic.setOnClickListener(v -> unavailable("语音转文字"));
        actionButton = new LinearLayout(context);
        actionButton.setGravity(Gravity.CENTER);
        actionButton.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(-2, dp(36));
        actionLp.leftMargin = dp(7);
        tools.addView(actionButton, actionLp);
        actionButton.setOnClickListener(v -> {
            if (!input.getText().toString().trim().isEmpty()) submit();
            else if (!state.isPrivateMode()) unavailable("实时语音");
        });
        composer.addView(tools, new LinearLayout.LayoutParams(-1, dp(44)));
        LinearLayout.LayoutParams composerLp = new LinearLayout.LayoutParams(-1, -2);
        composerLp.setMargins(dp(8), dp(10), dp(8), 0);
        bottom.addView(composer, composerLp);
        page.addView(bottom, new LinearLayout.LayoutParams(-1, -2));

        drawerDismiss = new View(context);
        drawerDismiss.setContentDescription("收起侧栏");
        drawerDismiss.setVisibility(GONE);
        drawerDismiss.setOnClickListener(v -> setDrawerOpen(false));
        addView(drawerDismiss, new FrameLayout.LayoutParams(-1, -1));
        drawer = new SearchDrawerView(context, new SearchDrawerView.Callback() {
            public void onNewChat() { input.setText(""); closeSettings(); setDrawerOpen(false); }
            public void onHistorySelected(String q) { input.setText(q); setDrawerOpen(false); callback.onLookup(q, false); }
            public void onSearch() { closeSettings(); setDrawerOpen(false); postDelayed(FloatingSearchView.this::focusInput, 270); }
            public void onSettings() { setDrawerOpen(false); showSettings(); }
            public void onClose() { setDrawerOpen(false); }
            public void onUnavailable(String feature) { unavailable(feature); }
        });
        drawer.setVisibility(INVISIBLE);
        addView(drawer, new FrameLayout.LayoutParams(dp(340), -1));
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { callback.onQueryChanged(s.toString()); updateAction(); }
            public void afterTextChanged(Editable s) {}
        });
        input.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_SEND ||
                    (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP)) {
                submit(); return true;
            }
            return false;
        });
        input.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                closeMenu(); callback.onEditingChanged(true);
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP) post(this::focusInput);
            return false;
        });
        applyPrivateMode(false);
        installInsets();
    }

    private void installInsets() {
        setOnApplyWindowInsetsListener((v, insets) -> { applyInsets(insets); return insets; });
        if (Build.VERSION.SDK_INT >= 30) {
            setWindowInsetsAnimationCallback(new WindowInsetsAnimation.Callback(WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                @Override public WindowInsets onProgress(WindowInsets insets, List<WindowInsetsAnimation> animations) {
                    applyInsets(insets); return insets;
                }
                @Override public void onEnd(WindowInsetsAnimation animation) {
                    if ((animation.getTypeMask() & WindowInsets.Type.ime()) == 0 || disposed) return;
                    WindowInsets current = getRootWindowInsets();
                    if (current != null) {
                        applyInsets(current);
                        if (!current.isVisible(WindowInsets.Type.ime()) && current.getInsets(WindowInsets.Type.ime()).bottom == 0) callback.onEditingChanged(false);
                    }
                }
            });
        } else {
            legacyLayoutListener = this::updateLegacyViewport;
            legacyPreDrawListener = () -> {
                // ViewRoot's pan can advance during draw without a new layout.
                // Sample once per frame; unchanged viewport frames request no layout.
                updateLegacyViewport();
                // Measure changed viewport frames before ViewRoot draws the old
                // editor bounds and starts its competing auto-pan animation.
                return !isLayoutRequested();
            };
            getViewTreeObserver().addOnGlobalLayoutListener(legacyLayoutListener);
            getViewTreeObserver().addOnPreDrawListener(legacyPreDrawListener);
        }
    }

    private void updateLegacyViewport() {
        if (disposed || !isAttachedToWindow() || getHeight() == 0) return;
        getWindowVisibleDisplayFrame(legacyVisibleFrame);
        if (legacyVisibleFrame.isEmpty()) return;
        getLocationOnScreen(legacyRootPosition);
        boolean wasVisible = state.isKeyboardVisible();
        int keyboard = getResources().getDisplayMetrics().heightPixels - legacyVisibleFrame.bottom;
        state.setKeyboardVisible(keyboard > dp(120));

        // TYPE_PHONE can retain a full-height surface and pan its root to the
        // editor despite ADJUST_RESIZE. Frame all content in the real visible
        // viewport, compensating root pan in the local top margin. A window
        // already resized by its OEM receives no second keyboard/nav inset.
        topInset = Math.max(0, legacyVisibleFrame.top - legacyRootPosition[1]);
        legacyViewportHeight = Math.min(getHeight(), legacyVisibleFrame.height());
        setLegacyViewportFrame(page);
        setLegacyViewportFrame(drawer);
        setLegacyViewportFrame(drawerDismiss);
        if (menuDismiss != null) setLegacyViewportFrame(menuDismiss);
        page.setPadding(0, 0, 0, 0);
        drawer.setPadding(drawer.getPaddingLeft(), dp(6), drawer.getPaddingRight(), dp(12));
        updateCompactLayout();
        positionMenu();
        // Keep editing focus throughout IME startup; release after actual hide.
        if (wasVisible && !state.isKeyboardVisible()) callback.onEditingChanged(false);
    }

    private void setLegacyViewportFrame(View child) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)child.getLayoutParams();
        if (lp.topMargin == topInset && lp.height == legacyViewportHeight) return;
        lp.topMargin = topInset;
        lp.height = legacyViewportHeight;
        child.setLayoutParams(lp);
    }

    private void applyInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            topInset = bars.top;
            systemBackInset = insets.getInsets(WindowInsets.Type.systemGestures()).left;
            safeBottom = bars.bottom;
            imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
            state.setKeyboardVisible(insets.isVisible(WindowInsets.Type.ime()));
        } else {
            safeBottom = insets.getStableInsetBottom();
            if (Build.VERSION.SDK_INT >= 29) systemBackInset = insets.getSystemGestureInsets().left;
            updateLegacyViewport();
            return;
        }
        page.setPadding(0, topInset, 0, Math.max(safeBottom, imeBottom));
        updateCompactLayout();
        drawer.setPadding(drawer.getPaddingLeft(), topInset + dp(6), drawer.getPaddingRight(), safeBottom + dp(12));
        positionMenu();
    }

    private void addPill(LinearLayout parent, String label, SearchIconView.Kind kind, int color, int ink, Runnable action) {
        LinearLayout pill = new LinearLayout(getContext());
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(16), 0, dp(16), 0);
        pill.setBackground(ripple(color, 22));
        pill.addView(icon(kind, ink), new LinearLayout.LayoutParams(dp(21), dp(25)));
        TextView title = text(label, 14, ink, true);
        title.setPadding(dp(7), 0, 0, 0);
        pill.addView(title);
        pill.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(48));
        lp.rightMargin = dp(8);
        parent.addView(pill, lp);
    }

    private void submit() {
        String q = input.getText().toString().trim();
        if (q.isEmpty() || disposed) return;
        closeMenu();
        hideKeyboard();
        callback.onLookup(q, state.isPrivateMode());
    }

    private void togglePrivateMode() {
        closeMenu(); closeSettings();
        state.togglePrivateMode();
        callback.onPrivateModeChanged(state.isPrivateMode());
        applyPrivateMode(true);
    }

    private void applyPrivateMode(boolean animate) {
        boolean privacy = state.isPrivateMode();
        privateButton.setBackground(ripple(privacy ? 0xffdedede : SOFT, 24));
        privateButton.setContentDescription(privacy ? "退出私密查询" : "进入私密查询");
        input.setHint(privacy ? "临时对话" : "随便问点什么");
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI |
                (privacy && Build.VERSION.SDK_INT >= 26 ? EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING : 0));
        if (input.isFocused()) ((InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).restartInput(input);
        recommendations.setVisibility(privacy ? GONE : VISIBLE);
        updateCompactLayout();
        drawer.setPrivateMode(privacy);
        updateAction();
        View previous = emptyView;
        LinearLayout empty = new LinearLayout(getContext());
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(24), 0, dp(24), 0);
        SearchIconView mark = icon(privacy ? SearchIconView.Kind.PRIVATE : SearchIconView.Kind.MARK, 0xffe7e7e7);
        empty.addView(mark, new LinearLayout.LayoutParams(dp(84), dp(84)));
        if (privacy) {
            TextView title = text("私密聊天", 20, INK, true);
            title.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(25); empty.addView(title, lp);
            TextView note = text("本次查询不写入本机历史。\n使用 AI 时，内容仍会发送至 Groq。", 14, 0xff68737a, false);
            note.setGravity(Gravity.CENTER); note.setLineSpacing(dp(4), 1);
            LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
            noteLp.topMargin = dp(18); empty.addView(note, noteLp);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        body.addView(empty, lp); emptyView = empty;
        if (animate) {
            empty.setAlpha(0f); empty.setTranslationY(dp(5));
            empty.animate().alpha(1).translationY(0).setDuration(180).start();
            if (previous != null) previous.animate().alpha(0).setDuration(120).withEndAction(() -> body.removeView(previous)).start();
        } else if (previous != null) body.removeView(previous);
    }

    private void updateAction() {
        boolean hasText = !input.getText().toString().trim().isEmpty();
        boolean send = hasText || state.isPrivateMode();
        actionButton.removeAllViews();
        int ink = send && !hasText ? 0xffa1a4a5 : Color.WHITE;
        actionButton.setBackground(ripple(send && !hasText ? SOFT : INK, 24));
        actionButton.addView(icon(send ? SearchIconView.Kind.SEND : SearchIconView.Kind.VOICE, ink), new LinearLayout.LayoutParams(dp(20), dp(24)));
        if (!send && getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density >= 350 && getResources().getConfiguration().fontScale <= 1.2f) {
            TextView title = text("开始说话", 13, Color.WHITE, true); title.setPadding(dp(5), 0, 0, 0);
            actionButton.addView(title);
        }
        actionButton.setContentDescription(send ? "提交查词" : "开始说话，尚未接入");
        actionButton.setEnabled(hasText || !state.isPrivateMode());
    }

    void handleBack() {
        switch (state.onBack()) {
            case CLOSE_MENU: closeMenu(); break;
            case HIDE_KEYBOARD: hideKeyboard(); break;
            case CLOSE_DRAWER: setDrawerOpen(false); break;
            case CLOSE_SEARCH:
                if (settingsCard != null) closeSettings(); else callback.onClose();
                break;
        }
    }

    private void focusInput() {
        if (disposed) return;
        callback.onEditingChanged(true);
        input.requestFocus();
        input.post(() -> {
            if (!disposed) ((InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void hideKeyboard() {
        ((InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(getWindowToken(), 0);
        input.clearFocus(); requestFocus();
        state.setKeyboardVisible(false);
        callback.onEditingChanged(false);
    }

    private void toggleMenu(FloatingSearchState.Menu requested) {
        boolean same = state.getMenu() == requested;
        closeMenu();
        if (same) return;
        state.setMenu(requested);
        menuDismiss = new View(getContext());
        menuDismiss.setContentDescription("关闭菜单");
        menuDismiss.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float x = event.getX() + v.getLeft(), y = event.getY() + v.getTop();
                if (containsPoint(plus, x, y)) toggleMenu(FloatingSearchState.Menu.ATTACHMENT);
                else if (containsPoint(modelButton, x, y)) toggleMenu(FloatingSearchState.Menu.MODEL);
                else closeMenu();
                v.performClick();
            }
            return true;
        });
        addView(menuDismiss, new FrameLayout.LayoutParams(-1, -1));
        menuCard = new LinearLayout(getContext());
        menuCard.setOrientation(LinearLayout.VERTICAL);
        menuCard.setPadding(dp(10), dp(14), dp(10), dp(12));
        menuCard.setBackground(shape(PAPER, 26));
        menuCard.setElevation(dp(9));
        menuCard.setClickable(true);
        if (requested == FloatingSearchState.Menu.MODEL) buildModelMenu(); else buildAttachmentMenu();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(requested == FloatingSearchState.Menu.MODEL ? dp(292) : dp(172), -2);
        menuSurface = new MenuScroll(getContext());
        menuSurface.setVerticalScrollBarEnabled(false);
        menuSurface.setBackground(shape(PAPER, 26));
        menuSurface.setElevation(dp(5));
        menuSurface.setClipToOutline(true);
        menuCard.setElevation(0);
        menuSurface.addView(menuCard, new ScrollView.LayoutParams(-1, -2));
        addView(menuSurface, lp);
        menuSurface.setAlpha(0); menuSurface.setTranslationY(dp(9));
        menuSurface.animate().alpha(1).translationY(0).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
        plus.animate().rotation(requested == FloatingSearchState.Menu.ATTACHMENT ? 45 : 0).setDuration(160).start();
        plus.setContentDescription(requested == FloatingSearchState.Menu.ATTACHMENT ? "关闭附件菜单" : "附件菜单");
        post(this::positionMenu);
    }

    private void buildModelMenu() {
        LinearLayout heading = new LinearLayout(getContext());
        heading.setGravity(Gravity.CENTER_VERTICAL); heading.setPadding(dp(6), 0, dp(6), dp(12));
        TextView title = text("Groq AI", 20, INK, true);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = text(GroqKeyStore.hasKey(getContext()) ? "已配置" : "待配置", 11, Color.WHITE, true); badge.setPadding(dp(10), dp(6), dp(10), dp(6)); badge.setBackground(shape(INK, 16));
        heading.addView(badge); menuCard.addView(heading);
        menuRow(SearchIconView.Kind.PROJECT, "Build  Beta", "构建应用和网站 · 未接入", false, false);
        menuRow(SearchIconView.Kind.CONNECTOR, "重型", "专家团队 · 未接入", false, false);
        menuRow(SearchIconView.Kind.SKILLS, "专家", "深度思考 · 未接入", false, false);
        menuRow(SearchIconView.Kind.LIGHTNING, "快速", "Groq · " + BuildConfig.GROQ_MODEL, true, true);
        menuRow(SearchIconView.Kind.BOT, "自动", "自动选择模型 · 未接入", false, false);
    }

    private void buildAttachmentMenu() {
        menuRow(SearchIconView.Kind.CAMERA, "摄像头", "未接入", false, false);
        menuRow(SearchIconView.Kind.IMAGE, "图库", "未接入", false, false);
        menuRow(SearchIconView.Kind.FILE, "文件", "未接入", false, false);
        View line = new View(getContext()); line.setBackgroundColor(0xffe6e7e8);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1)); lp.setMargins(dp(12), dp(10), dp(12), dp(10)); menuCard.addView(line, lp);
        menuRow(SearchIconView.Kind.SKILLS, "技能", "未接入", false, false);
        menuRow(SearchIconView.Kind.CONNECTOR, "连接器", "未接入", false, false);
    }

    private void menuRow(SearchIconView.Kind kind, String title, String subtitle, boolean enabled, boolean selected) {
        LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(10), dp(6), dp(8), dp(6));
        row.setBackground(ripple(selected ? 0xffe9e9e9 : PAPER, 16));
        row.addView(icon(kind, enabled ? INK : 0xff909396), new LinearLayout.LayoutParams(dp(25), dp(30)));
        LinearLayout labels = new LinearLayout(getContext()); labels.setOrientation(LinearLayout.VERTICAL); labels.setPadding(dp(12), 0, dp(5), 0);
        labels.addView(text(title, 16, enabled ? INK : 0xff949698, true));
        TextView detail = text(subtitle, 11, enabled ? 0xff66696c : 0xffa3a5a6, false); detail.setMaxLines(2); labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (selected) row.addView(icon(SearchIconView.Kind.CHECK, INK), new LinearLayout.LayoutParams(dp(22), dp(26)));
        row.setOnClickListener(v -> { if (enabled) closeMenu(); else unavailable(title); });
        row.setContentDescription(title + (enabled ? "，当前选中" : "，尚未接入"));
        menuCard.addView(row, new LinearLayout.LayoutParams(-1, dp(58)));
    }

    private boolean containsPoint(View view, float x, float y) {
        Rect rect = new Rect(0, 0, view.getWidth(), view.getHeight());
        offsetDescendantRectToMyCoords(view, rect);
        return rect.contains(Math.round(x), Math.round(y));
    }

    private void positionMenu() {
        if (menuSurface == null || getWidth() == 0) return;
        View anchor = state.getMenu() == FloatingSearchState.Menu.ATTACHMENT ? plus : modelButton;
        Rect rect = new Rect(0, 0, anchor.getWidth(), anchor.getHeight()); offsetDescendantRectToMyCoords(anchor, rect);
        int maxHeight = Math.max(dp(80), rect.top - topInset - dp(12));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)menuSurface.getLayoutParams();
        lp.width = Math.min(state.getMenu() == FloatingSearchState.Menu.MODEL ? dp(292) : dp(172), getWidth() - dp(24));
        menuCard.measure(MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        lp.leftMargin = state.getMenu() == FloatingSearchState.Menu.MODEL ? dp(12) : Math.max(dp(12), Math.min(rect.left - dp(4), getWidth() - lp.width - dp(12)));
        lp.topMargin = Math.max(topInset + dp(8), rect.top - Math.min(menuCard.getMeasuredHeight(), maxHeight) - dp(12));
        lp.height = Math.min(menuCard.getMeasuredHeight(), maxHeight);
        if (lp.width != menuSurface.getWidth() || lp.topMargin != menuSurface.getTop() || lp.leftMargin != menuSurface.getLeft() || lp.height != menuSurface.getHeight()) menuSurface.setLayoutParams(lp);
    }

    private void closeMenu() {
        state.setMenu(FloatingSearchState.Menu.NONE);
        if (menuDismiss != null) removeView(menuDismiss);
        menuDismiss = null;
        MenuScroll old = menuSurface; menuSurface = null; menuCard = null;
        if (old != null) {
            old.animate().cancel();
            old.dismissed = true;
            if (disposed) removeView(old);
            else old.animate().alpha(0).translationY(dp(7)).setDuration(120).withEndAction(() -> removeView(old)).start();
        }
        if (plus != null) {
            plus.animate().rotation(0).setDuration(140).start(); plus.setContentDescription("附件菜单");
        }
    }

    private void showSettings() {
        hideKeyboard(); closeMenu(); closeSettings();
        LinearLayout card = new LinearLayout(getContext()); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(20), dp(22), dp(20)); card.setBackground(shape(SOFT, 24));
        card.addView(text("Groq AI 设置", 21, INK, true));
        TextView note = text(GroqKeyStore.hasKey(getContext()) ? "本机已保存 Key。留空不会修改。" : "输入自己的 Groq API Key，使用本机加密保存。", 13, 0xff6d747b, false);
        note.setPadding(0, dp(10), 0, dp(12)); card.addView(note);
        Editor key = new Editor(getContext()); key.setSingleLine(); key.setTextSize(15); key.setHint("gsk_..."); key.setTextColor(INK);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setOnTouchListener((v, e) -> { if (e.getActionMasked() == MotionEvent.ACTION_DOWN) callback.onEditingChanged(true); return false; });
        card.addView(key, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout actions = new LinearLayout(getContext()); actions.setGravity(Gravity.END);
        TextView cancel = text("关闭", 14, INK, true); cancel.setPadding(dp(12), dp(14), dp(12), dp(14));
        cancel.setOnClickListener(v -> { hideKeyboard(); closeSettings(); }); actions.addView(cancel);
        TextView save = text("保存", 14, 0xff008fd1, true); save.setPadding(dp(12), dp(14), dp(12), dp(14));
        save.setOnClickListener(v -> {
            if (key.length() == 0) { toast("没有输入 Key，未修改"); return; }
            if (GroqKeyStore.save(getContext(), key.getText().toString())) { hideKeyboard(); closeSettings(); drawer.refreshHistory(); toast("Key 已加密保存在本机"); }
            else toast("Key 保存失败，请重试");
        }); actions.addView(save); card.addView(actions);
        bottom.setVisibility(GONE);
        ScrollView settingsScroll = new ScrollView(getContext());
        settingsScroll.setFillViewport(false);
        settingsScroll.setVerticalScrollBarEnabled(false);
        settingsScroll.setPadding(dp(20), dp(20), dp(20), dp(20));
        settingsScroll.addView(card, new ScrollView.LayoutParams(-1, -2));
        settingsCard = settingsScroll;
        body.addView(settingsScroll, new FrameLayout.LayoutParams(-1, -1));
        settingsScroll.setAlpha(0); settingsScroll.animate().alpha(1).setDuration(180).start();
    }

    private void closeSettings() {
        if (settingsCard != null) { hideKeyboard(); body.removeView(settingsCard); }
        settingsCard = null; bottom.setVisibility(VISIBLE);
    }

    private void setDrawerOpen(boolean open) {
        closeMenu();
        if (open) { hideKeyboard(); drawer.refreshHistory(); }
        state.setDrawerOpen(open);
        animateDrawer(open ? 1 : 0);
    }

    private void animateDrawer(float target) {
        if (drawerAnimator != null) drawerAnimator.cancel();
        drawerAnimator = ValueAnimator.ofFloat(drawerProgress, target);
        drawerAnimator.setDuration(Math.max(90, (long)(260 * Math.abs(target - drawerProgress))));
        drawerAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
        drawerAnimator.addUpdateListener(a -> applyDrawerProgress((float)a.getAnimatedValue()));
        drawerAnimator.start();
    }

    private void applyDrawerProgress(float progress) {
        drawerProgress = Math.max(0, Math.min(1, progress));
        drawer.setVisibility(drawerProgress > 0 ? VISIBLE : INVISIBLE);
        drawer.setTranslationX(-drawerWidth * (1 - drawerProgress));
        page.setTranslationX(drawerWidth * drawerProgress);
        drawerDismiss.setVisibility(drawerProgress > 0 ? VISIBLE : GONE);
        drawerDismiss.setTranslationX(drawerWidth * drawerProgress);
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        drawerWidth = Math.min(dp(380), Math.round(w * .89f));
        ViewGroup.LayoutParams lp = drawer.getLayoutParams(); lp.width = drawerWidth; drawer.setLayoutParams(lp);
        applyDrawerProgress(drawerProgress); post(this::positionMenu);
        if (Build.VERSION.SDK_INT < 30) updateLegacyViewport(); else updateCompactLayout();
    }

    private void updateCompactLayout() {
        if (getHeight() == 0) return;
        int available = Build.VERSION.SDK_INT < 30 && legacyViewportHeight > 0
                ? legacyViewportHeight : getHeight() - topInset - Math.max(safeBottom, imeBottom);
        header.setVisibility(state.isKeyboardVisible() && available < dp(220) ? GONE : VISIBLE);
        recommendations.setVisibility(!state.isPrivateMode() && available >= dp(320) ? VISIBLE : GONE);
        boolean tight = state.isKeyboardVisible() && available < dp(220);
        if (tight != compactComposer) {
            compactComposer = tight;
            input.setMaxLines(tight ? 1 : 4);
            input.setPadding(dp(10), tight ? 0 : dp(5), dp(10), tight ? 0 : dp(6));
            composer.setPadding(dp(8), dp(tight ? 2 : 5), dp(8), dp(tight ? 2 : 7));
            bottom.setPadding(0, dp(tight ? 2 : 8), 0, dp(tight ? 2 : 8));
            ViewGroup.LayoutParams toolsLp = tools.getLayoutParams();
            toolsLp.height = dp(tight ? 36 : 44); tools.setLayoutParams(toolsLp);
            LinearLayout.LayoutParams composerLp = (LinearLayout.LayoutParams)composer.getLayoutParams();
            composerLp.setMargins(dp(8), dp(tight ? 2 : 10), dp(8), 0);
            composer.setLayoutParams(composerLp);
        }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = e.getX(); downY = e.getY(); startProgress = drawerProgress; swiping = false;
            // Reserve the outer system-Back edge and the horizontally scrolling pills.
            int swipeStart = Math.max(dp(24), systemBackInset + dp(8));
            int viewportBottom = Build.VERSION.SDK_INT < 30 && legacyViewportHeight > 0
                    ? topInset + legacyViewportHeight : getHeight() - Math.max(safeBottom, imeBottom);
            swipeEligible = menuCard == null && downX > swipeStart &&
                    (drawerProgress > 0 || (downX < swipeStart + dp(40) && downY > topInset + header.getHeight() && downY < viewportBottom - bottom.getHeight()));
            recycleVelocity(); velocity = VelocityTracker.obtain(); velocity.addMovement(e);
        } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE && beginSwipe(e)) {
            return true;
        } else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) recycleVelocity();
        return super.onInterceptTouchEvent(e);
    }

    private boolean beginSwipe(MotionEvent e) {
        if (!swipeEligible) return false;
        float dx = e.getX() - downX, dy = e.getY() - downY;
        if (Math.abs(dx) <= ViewConfiguration.get(getContext()).getScaledTouchSlop() || Math.abs(dx) <= Math.abs(dy) * 1.3f) return false;
        if (drawerAnimator != null) drawerAnimator.cancel();
        if (!swiping && startProgress == 0 && dx > 0) { hideKeyboard(); drawer.refreshHistory(); }
        swiping = true;
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        // Blank content has no child touch target, so ViewGroup skips interception
        // after DOWN. Recognize that stream here as well as child-owned streams.
        if (!swiping && e.getActionMasked() == MotionEvent.ACTION_MOVE) beginSwipe(e);
        if (!swiping) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) recycleVelocity();
            return super.onTouchEvent(e);
        }
        if (velocity != null) velocity.addMovement(e);
        if (e.getActionMasked() == MotionEvent.ACTION_MOVE) applyDrawerProgress(startProgress + (e.getX() - downX) / drawerWidth);
        if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            float speed = 0;
            if (velocity != null) { velocity.computeCurrentVelocity(1000); speed = velocity.getXVelocity(); }
            boolean open = e.getActionMasked() == MotionEvent.ACTION_CANCEL ? state.isDrawerOpen() : Math.abs(speed) > dp(500) ? speed > 0 : drawerProgress > .5f;
            state.setDrawerOpen(open); animateDrawer(open ? 1 : 0); swiping = false; recycleVelocity();
        }
        return true;
    }

    private void recycleVelocity() { if (velocity != null) velocity.recycle(); velocity = null; }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) handleBack();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (emptyView != null) emptyView.setVisibility(body.getHeight() >= dp(state.isPrivateMode() ? 230 : 84) ? VISIBLE : INVISIBLE);
        if (!disposed) {
            composer.getGlobalVisibleRect(composerBounds);
            callback.onComposerBoundsChanged(composerBounds, state.isKeyboardVisible());
        }
        if (changed && menuCard != null) post(this::positionMenu);
    }

    void dispose() {
        disposed = true;
        ViewTreeObserver observer = getViewTreeObserver();
        if (observer.isAlive()) {
            if (legacyLayoutListener != null) observer.removeOnGlobalLayoutListener(legacyLayoutListener);
            if (legacyPreDrawListener != null) observer.removeOnPreDrawListener(legacyPreDrawListener);
        }
        legacyLayoutListener = null;
        legacyPreDrawListener = null;
        closeMenu(); recycleVelocity();
        if (drawerAnimator != null) drawerAnimator.cancel();
        page.animate().cancel(); plus.animate().cancel();
        if (emptyView != null) emptyView.animate().cancel();
    }

    private static final class MenuScroll extends ScrollView {
        boolean dismissed;
        MenuScroll(Context context) { super(context); }
        @Override public boolean onInterceptTouchEvent(MotionEvent event) { return dismissed || super.onInterceptTouchEvent(event); }
        @Override public boolean onTouchEvent(MotionEvent event) { return dismissed || super.onTouchEvent(event); }
    }

    @android.annotation.SuppressLint("AppCompatCustomView")
    private final class Editor extends EditText {
        Editor(Context context) { super(context); }
        @Override public boolean onKeyPreIme(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) handleBack();
                return true;
            }
            return super.onKeyPreIme(keyCode, event);
        }
        @Override public void onWindowFocusChanged(boolean focused) {
            super.onWindowFocusChanged(focused);
            if (focused && isFocused()) post(() -> {
                if (!disposed && hasWindowFocus() && isFocused()) ((InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT);
            });
        }
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color, int radius) { GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(radius)); return bg; }
    private RippleDrawable ripple(int color, int radius) { return new RippleDrawable(ColorStateList.valueOf(0x16000000), shape(color, radius), shape(Color.WHITE, radius)); }
    private TextView text(String value, int size, int color, boolean bold) { TextView t = new TextView(getContext()); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setIncludeFontPadding(false); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t; }
    private SearchIconView icon(SearchIconView.Kind kind, int color) { SearchIconView v = new SearchIconView(getContext(), kind); v.setColor(color); return v; }
    private SearchIconView button(SearchIconView.Kind kind, String description, int color) { SearchIconView v = icon(kind, INK); v.setPadding(dp(9), dp(9), dp(9), dp(9)); v.setBackground(ripple(color, 24)); v.setContentDescription(description); v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES); return v; }
    private void toast(String message) { Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show(); }
    private void unavailable(String feature) { toast(feature + "尚未接入；当前支持多语种查词与 Groq AI。"); }
}
