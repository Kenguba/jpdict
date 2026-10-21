package com.yuen.jpdict;

import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.LocaleList;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Native search surface. The Service retains window ownership and lookup routing. */
final class FloatingSearchView extends FrameLayout {
    interface Callback {
        void onQueryChanged(String query);
        void onLookup(String query, boolean privateMode);
        void onPrivateModeChanged(boolean privateMode);
        void onEditingChanged(boolean editing);
        void onOpenPage(String page, boolean privateMode);
        void onClose();
    }

    private static final int PAPER = AppTheme.PAPER;
    private static final int INK = 0xff151416;
    private static final int MUTED = 0xff949698;
    private static final int SOFT = AppTheme.PAGE;
    private final Callback callback;
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
    private final GridLayout suggestionWords;
    private final FrameLayout suggestionArea;
    private final SearchSuggestionsController suggestions;
    private final SearchDraftChips draftChips;
    private final LinearLayout draftTrash;
    private final SearchTextExplosionView textExplosion;
    private final SearchTextExplosionController explosionController;
    private boolean draftDragging;
    private ClipboardManager clipboard;
    private boolean clipboardListening, clipboardSuspended;
    private final ClipboardManager.OnPrimaryClipChangedListener clipboardChanged = () -> readClipboard(true);
    private List<String> suggestionResults = Collections.emptyList();
    private final Map<String, TextView> suggestionViews = new HashMap<>();
    private ValueAnimator suggestionHeightAnimator;
    private ValueAnimator composerHeightAnimator, keyboardHeightAnimator;
    private int renderedKeyboardHeight = -1, pendingImeHeight;
    private WindowInsetsAnimation activeImeAnimation;
    private final SearchIconView plus;
    private final SearchIconView privateButton;
    private final LinearLayout modelButton;
    private final SearchIconView modelIcon;
    private final TextView languageLabel;
    private final OcrLanguageSettings ocrLanguage;
    private final LookupDestinationSettings destination;
    private final LookupDestinationSettings.Listener destinationChanged = this::refreshDestination;
    private final EnumMap<LookupDestinationSettings.Model, LinearLayout> modelRows =
            new EnumMap<>(LookupDestinationSettings.Model.class);
    private final OcrLanguageSettings.Listener languageChanged = this::refreshLanguage;
    private final LinearLayout[] languageRows = new LinearLayout[OcrLanguageSettings.Language.values().length];
    private OcrLanguageSettings.Language hoveredLanguage;
    private boolean languageDrag;
    private View menuAnchorTouchTarget;
    private final LinearLayout actionButton;
    private final HorizontalScrollView recommendations;
    private final SearchDrawerView drawer;
    private final View drawerDismiss;
    private View emptyView;
    private View menuDismiss;
    private LinearLayout menuCard;
    private final TextView[] tabLabels = new TextView[3];
    private final View[] tabIndicators = new View[3];
    private MenuScroll menuSurface;
    private int drawerWidth;
    private final DrawerStyleSettings drawerSettings;
    private DrawerStyleSettings.Mode drawerMode;
    private final DrawerMotionController drawerMotion;
    private final DrawerMotionController.Geometry drawerGeometry = new DrawerMotionController.Geometry();
    private final GradientDrawable drawerPageBackground = new AppTheme.Shape();
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener drawerStyleChanged =
            (preferences, key) -> refreshDrawerStyle();
    private boolean disposed;
    private boolean compactComposer;
    private int imeBottom;
    private final SearchKeyboardSpace keyboardSpace;
    private int legacyImeCandidate;
    private int safeBottom;
    private int topInset;
    private int systemBackInset;
    private int legacyViewportHeight;
    private ViewTreeObserver.OnGlobalLayoutListener legacyLayoutListener;
    private ViewTreeObserver.OnPreDrawListener legacyPreDrawListener;

    FloatingSearchView(Context context, String query, boolean privateMode, Callback callback) {
        super(context);
        this.callback = callback;
        ocrLanguage = OcrLanguageSettings.get(context);
        destination = LookupDestinationSettings.get(context);
        keyboardSpace = new SearchKeyboardSpace(context);
        state.setPrivateMode(privateMode);
        AppTheme.background(this, PAPER);
        // Own blank-area DOWN events so the drawer receives subsequent MOVE events.
        setClickable(true);
        setFocusableInTouchMode(true);
        setClipChildren(false);
        page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        drawerPageBackground.setColor(PAPER);
        page.setBackground(drawerPageBackground);
        page.setClipChildren(false);
        page.setClipToPadding(false);
        addView(page, new FrameLayout.LayoutParams(-1, -1));

        header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), 0, dp(12), 0);
        SearchIconView menu = button(SearchIconView.Kind.MENU, "打开侧栏", SOFT);
        header.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(44)));
        menu.setOnClickListener(v -> setDrawerOpen(true));
        LinearLayout tabs = new LinearLayout(context);
        tabs.setGravity(Gravity.CENTER);
        addHeaderTab(tabs, "提问", 0, () -> {});
        addHeaderTab(tabs, "图片", 1, () -> unavailable("图片"));
        addHeaderTab(tabs, "历史记录", 2, this::openHistory);
        selectQuestionTab();
        header.addView(tabs, new LinearLayout.LayoutParams(0, dp(44), 1));
        privateButton = button(SearchIconView.Kind.PRIVATE, "切换私密查询", SOFT);
        header.addView(privateButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        privateButton.setOnClickListener(v -> togglePrivateMode());
        page.addView(header, new LinearLayout.LayoutParams(-1, dp(48)));

        body = new FrameLayout(context);
        body.setClipChildren(false);
        page.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        bottom = new LinearLayout(context);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        bottom.setPadding(0, 0, 0, dp(8));
        recommendations = new HorizontalScrollView(context);
        recommendations.setHorizontalScrollBarEnabled(false);
        recommendations.setClipToPadding(false);
        recommendations.setPadding(dp(8), 0, dp(8), 0);
        LinearLayout pills = new LinearLayout(context);
        pills.setGravity(Gravity.CENTER_VERTICAL);
        recommendations.addView(pills, new HorizontalScrollView.LayoutParams(-2, -1));
        addPill(pills, "搜索词库", SearchIconView.Kind.SEARCH, AppTheme.SEARCH_PILL, 0xff2673c5,
                this::submitFromButton);
        addPill(pills, "词库管理", SearchIconView.Kind.BOOK, AppTheme.DICT_PILL, 0xff15996c,
                () -> openPage(FloatingSearchRoutes.PAGE_DICTIONARIES));
        addPill(pills, "悬浮按钮设置", SearchIconView.Kind.SETTINGS, AppTheme.BUBBLE_PILL, 0xff009bdc,
                () -> openPage(FloatingSearchRoutes.PAGE_BUBBLE_SETTINGS));
        addPill(pills, "设置", SearchIconView.Kind.SETTINGS, SOFT, INK,
                () -> openPage(FloatingSearchRoutes.PAGE_SETTINGS));
        suggestionWords = new GridLayout(context);
        suggestionWords.setColumnCount(3);
        suggestionWords.setPadding(dp(16), 0, dp(16), dp(4));
        suggestionWords.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (r - l != oldR - oldL) post(() -> { if (!disposed) renderSuggestions(); });
        });
        suggestionArea = new FrameLayout(context);
        suggestionArea.addView(suggestionWords, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        bottom.addView(suggestionArea, new LinearLayout.LayoutParams(-1, -2));
        composer = new LinearLayout(context);
        composer.setOrientation(LinearLayout.VERTICAL);
        composer.setPadding(dp(8), dp(5), dp(8), dp(7));
        composer.setBackground(shape(Color.WHITE, 26));
        composer.setElevation(dp(4));
        input = new Editor(context);
        AppTheme.text(input, INK);
        AppTheme.hint(input, MUTED);
        input.setTextSize(17);
        AppTheme.background(input, Color.TRANSPARENT);
        input.setPadding(dp(10), dp(5), dp(10), dp(6));
        input.setMinLines(2);
        input.setMaxLines(4);
        input.setMinimumHeight(0);
        input.setGravity(Gravity.TOP);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        applyKeyboardLanguage(false);
        input.setContentDescription("查询输入框");
        input.setText(query == null ? "" : query);
        input.setSelection(input.length());
        composer.addView(input, new LinearLayout.LayoutParams(-1, -2));
        suggestions = new SearchSuggestionsController(context, this::showSuggestions);
        tools = new LinearLayout(context);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        plus = button(SearchIconView.Kind.PLUS, "附件菜单", SOFT);
        tools.addView(plus, new LinearLayout.LayoutParams(dp(36), dp(36)));
        plus.setOnClickListener(v -> toggleMenu(FloatingSearchState.Menu.ATTACHMENT));
        modelButton = new LinearLayout(context);
        modelButton.setGravity(Gravity.CENTER);
        modelButton.setPadding(dp(12), 0, dp(10), 0);
        modelButton.setBackground(ripple(SOFT, 24));
        modelIcon = icon(SearchIconView.Kind.LIGHTNING, INK);
        modelButton.addView(modelIcon, new LinearLayout.LayoutParams(dp(19), dp(23)));
        languageLabel = text(ocrLanguage.language().label, 14, INK, true);
        languageLabel.setPadding(dp(5), 0, dp(5), 0);
        modelButton.addView(languageLabel);
        updateLanguageButton();
        modelButton.addView(icon(SearchIconView.Kind.CHEVRON, 0xff74777a), new LinearLayout.LayoutParams(dp(13), dp(18)));
        LinearLayout.LayoutParams modelLp = new LinearLayout.LayoutParams(-2, dp(36));
        modelLp.leftMargin = dp(7);
        tools.addView(modelButton, modelLp);
        modelButton.setOnClickListener(v -> toggleMenu(FloatingSearchState.Menu.MODEL));
        modelButton.setOnLongClickListener(v -> {
            toggleMenu(FloatingSearchState.Menu.LANGUAGE);
            languageDrag = state.getMenu() == FloatingSearchState.Menu.LANGUAGE;
            excludeDrawerGesture();
            return true;
        });
        modelButton.setOnTouchListener(this::onLanguageTouch);
        draftTrash = new LinearLayout(context);
        draftTrash.setGravity(Gravity.CENTER);
        draftTrash.setBackground(ripple(AppTheme.ROSE, 18));
        draftTrash.setContentDescription("删除暂存文字，拖到此处松手删除");
        draftTrash.addView(icon(SearchIconView.Kind.TRASH, 0xffd5465d), new LinearLayout.LayoutParams(dp(21), dp(24)));
        TextView trashLabel = text("拖到这里删除", 13, 0xffd5465d, true);
        trashLabel.setPadding(dp(8), 0, 0, 0); draftTrash.addView(trashLabel);
        draftTrash.setVisibility(GONE);
        draftChips = new SearchDraftChips(context, input, this, draftTrash, new SearchDraftChips.DragUi() {
            public void editing() { excludeDrawerGesture(); closeMenu(); callback.onEditingChanged(true); }
            public void caret(int offset) { input.setDragCaret(offset); }
            public void dragging(boolean active) {
                draftDragging = active;
                draftTrash.setVisibility(active ? VISIBLE : GONE);
                if (active) { draftTrash.setAlpha(0); draftTrash.setTranslationY(dp(6));
                    draftTrash.animate().alpha(1).translationY(0).setDuration(140).start(); }
                else { draftTrash.animate().cancel(); draftTrash.setScaleX(1); draftTrash.setScaleY(1); }
                updateCompactLayout();
            }
        });
        tools.addView(draftChips, new LinearLayout.LayoutParams(0, dp(36), 1));
        SearchIconView mic = button(SearchIconView.Kind.MIC, "语音转文字，尚未接入", SOFT);
        tools.addView(mic, new LinearLayout.LayoutParams(dp(36), dp(36)));
        mic.setOnClickListener(v -> unavailable("语音转文字"));
        actionButton = new LinearLayout(context);
        actionButton.setGravity(Gravity.CENTER);
        actionButton.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(-2, dp(36));
        actionLp.leftMargin = dp(7);
        tools.addView(actionButton, actionLp);
        actionButton.setOnClickListener(v -> submitFromButton());
        AppTheme.observe(actionButton, this::updateAction);
        composer.addView(tools, new LinearLayout.LayoutParams(-1, dp(44)));
        LinearLayout.LayoutParams composerLp = new LinearLayout.LayoutParams(-1, -2);
        composerLp.setMargins(dp(8), 0, dp(8), 0);
        bottom.addView(composer, composerLp);
        LinearLayout.LayoutParams trashLp = new LinearLayout.LayoutParams(-1, dp(44));
        trashLp.setMargins(dp(16), dp(8), dp(16), 0);
        bottom.addView(draftTrash, trashLp);
        LinearLayout.LayoutParams recommendationsLp = new LinearLayout.LayoutParams(-1, dp(42));
        recommendationsLp.topMargin = dp(8);
        bottom.addView(recommendations, recommendationsLp);
        page.addView(bottom, new LinearLayout.LayoutParams(-1, -2));

        textExplosion = new SearchTextExplosionView(context, new SearchTextExplosionView.Actions() {
            public void lookup(String word) {
                if (disposed) return;
                closeMenu(); hideKeyboard(); callback.onLookup(word, state.isPrivateMode());
            }
        });
        // Use the reserved keyboard region below the shortcuts. IME may cover
        // this content without moving the composer when it opens or closes.
        addView(textExplosion, new FrameLayout.LayoutParams(-1, 0));
        explosionController = new SearchTextExplosionController(context, (textPage, parts, loading) -> {
            textExplosion.show(textPage, parts, loading);
            requestLayout();
        });

        drawerDismiss = new View(context);
        drawerDismiss.setContentDescription("收起侧栏");
        drawerDismiss.setVisibility(GONE);
        drawerDismiss.setOnClickListener(v -> setDrawerOpen(false));
        addView(drawerDismiss, new FrameLayout.LayoutParams(-1, -1));
        drawer = new SearchDrawerView(context, new SearchDrawerView.Callback() {
            public void onNewChat() {
                draftChips.clear();
                input.setText("");
                hideKeyboard();
                drawer.endSearch();
                state.setDrawerOpen(false);
                animateDrawer(0, FloatingSearchView.this::focusInput);
            }
            public void onHistorySelected(String q) { input.setText(q); callback.onLookup(q, false); }
            public void onEditingChanged() { callback.onEditingChanged(true); }
            public void onBack() { handleBack(); }
            public void onFiles(String id) {
                openPage(id == null ? FloatingSearchRoutes.PAGE_FILES
                        : FloatingSearchRoutes.PAGE_FILES + "/" + id);
            }
            public void onSettings() {
                setDrawerOpen(false);
                callback.onOpenPage(FloatingSearchRoutes.PAGE_SETTINGS,
                        state.isPrivateMode());
            }
            public void onBubbleSettings() {
                setDrawerOpen(false);
                callback.onOpenPage(FloatingSearchRoutes.PAGE_BUBBLE_SETTINGS,
                        state.isPrivateMode());
            }
            public void onClose() { setDrawerOpen(false); }
            public void onUnavailable(String feature) { unavailable(feature); }
        });
        drawer.setVisibility(INVISIBLE);
        addView(drawer, new FrameLayout.LayoutParams(dp(340), -1));
        drawerSettings = new DrawerStyleSettings(context);
        drawerMode = drawerSettings.mode();
        drawerMotion = new DrawerMotionController(this, new DrawerMotionController.Listener() {
            public void onProgress(float progress) { applyDrawerProgress(progress); }
            public void onDragStart() { closeMenu(); hideKeyboard(); drawer.refreshHistory(); }
            public void onRelease(boolean open) {
                state.setDrawerOpen(open);
                if (!open) drawer.endSearch();
            }
        });
        drawerSettings.observe(drawerStyleChanged);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                callback.onQueryChanged(s.toString()); updateAction(); suggestions.update(s.toString());
                renderSuggestions();
                explosionController.update(s.toString());
            }
            public void afterTextChanged(Editable s) { animateComposerHeight(); }
        });
        input.setOnEditorActionListener((v, action, event) -> {
            if (event != null) {
                if (event.getKeyCode() != KeyEvent.KEYCODE_ENTER || event.isShiftPressed()) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) submit();
                return true;
            }
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_SEND) {
                submit();
                return true;
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
        // Include the actual hint height in the first layout, before async suggestions arrive.
        renderSuggestions();
        applyPrivateMode(false);
        suggestions.update(input.getText().toString());
        explosionController.update(input.getText().toString());
        installInsets();
    }

    private void showSuggestions(List<String> words) {
        suggestionResults = words;
        renderSuggestions();
    }

    private void renderSuggestions() {
        suggestionArea.setVisibility(compactComposer ? GONE : VISIBLE);
        Set<String> retained = new HashSet<>();
        String current = input.getText().toString();
        boolean hasCurrent = !current.trim().isEmpty();
        List<String> words = new ArrayList<>();
        for (String word : suggestionResults) if (!word.equals(current)) words.add(word);
        int width = suggestionWords.getWidth() > 0 ? suggestionWords.getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        int contentWidth = width - suggestionWords.getPaddingLeft() - suggestionWords.getPaddingRight();
        TextView fullInput = hasCurrent ? suggestion("input", current, retained) : null;
        boolean longInput = fullInput != null && (current.indexOf('\n') >= 0
                || fullInput.getPaint().measureText(current) > (contentWidth - dp(12)) / 3f - dp(16));
        if (hasCurrent && !longInput) words.add(0, current);
        if (longInput) {
            fullInput.setSingleLine(false);
            fullInput.setMaxLines(2);
            fullInput.setMinimumHeight(dp(27));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(0), GridLayout.spec(0, 3));
            lp.width = -1; lp.height = -2; lp.rightMargin = dp(4); lp.bottomMargin = dp(3);
            fullInput.setLayoutParams(lp);
        }
        if (words.isEmpty()) {
            if (longInput) { finishSuggestions(retained, width); return; }
            TextView note = suggestionViews.get("note");
            if (note == null) {
                note = text("本地词库智能联想", 11, MUTED, false);
                suggestionViews.put("note", note); suggestionWords.addView(note);
            }
            retained.add("note");
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(0), GridLayout.spec(0, 3));
            lp.width = -1; note.setLayoutParams(lp);
            finishSuggestions(retained, width);
            return;
        }
        int index = 0;
        for (String word : words) {
            TextView candidate = !longInput && hasCurrent && index == 0 ? fullInput
                    : suggestion("word:" + word, word, retained);
            candidate.setSingleLine(true);
            int rows = (words.size() + 2) / 3;
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec((longInput ? 1 : 0) + rows - 1 - index / 3), GridLayout.spec(index % 3, 1f));
            lp.width = 0; lp.height = dp(27); lp.rightMargin = dp(4); lp.bottomMargin = dp(3); index++;
            candidate.setLayoutParams(lp);
        }
        finishSuggestions(retained, width);
    }

    private void finishSuggestions(Set<String> retained, int width) {
        java.util.Iterator<Map.Entry<String, TextView>> iterator = suggestionViews.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, TextView> entry = iterator.next();
            if (retained.contains(entry.getKey())) continue;
            entry.getValue().animate().cancel(); suggestionWords.removeView(entry.getValue()); iterator.remove();
        }
        updateSuggestionHeight(width);
    }

    private TextView suggestion(String key, String word, Set<String> retained) {
        retained.add(key);
        TextView existing = suggestionViews.get(key);
        if (existing != null) {
            if (!existing.getText().toString().equals(word)) existing.setText(word);
            existing.setContentDescription("查询候选词 " + word);
            return existing;
        }
        TextView candidate = text(word, 13, AppTheme.BLUE, false);
        candidate.setSingleLine(true);
        candidate.setEllipsize(TextUtils.TruncateAt.END);
        candidate.setPadding(dp(8), dp(5), dp(8), dp(5));
        candidate.setBackground(ripple(0xfff0f6ff, 14));
        candidate.setContentDescription("查询候选词 " + word);
        candidate.setOnClickListener(v -> {
            if (disposed) return;
            closeMenu(); hideKeyboard();
            callback.onLookup(candidate.getText().toString().trim(), state.isPrivateMode());
        });
        candidate.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (oldR <= oldL || (l == oldL && t == oldT)) return;
            v.setTranslationX(v.getTranslationX() + oldL - l);
            v.setTranslationY(v.getTranslationY() + oldT - t);
            v.animate().translationX(0).translationY(0).setDuration(160).start();
        });
        suggestionViews.put(key, candidate); suggestionWords.addView(candidate);
        if (isLaidOut()) {
            candidate.setAlpha(.65f); candidate.animate().alpha(1).setDuration(140).start();
        }
        return candidate;
    }

    private void updateSuggestionHeight(int width) {
        suggestionWords.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int height = suggestionWords.getMeasuredHeight();
        if (suggestionHeightAnimator != null) { suggestionHeightAnimator.cancel(); suggestionHeightAnimator = null; }
        if (suggestionArea.getLayoutParams().height != height) {
            if (!isLaidOut()) {
                suggestionArea.getLayoutParams().height = height;
                suggestionArea.requestLayout();
            } else {
                suggestionHeightAnimator = ValueAnimator.ofInt(suggestionArea.getHeight(), height);
                suggestionHeightAnimator.setDuration(160);
                suggestionHeightAnimator.setInterpolator(new DecelerateInterpolator());
                suggestionHeightAnimator.addUpdateListener(a -> {
                    suggestionArea.getLayoutParams().height = (int)a.getAnimatedValue();
                    suggestionArea.requestLayout();
                });
                suggestionHeightAnimator.start();
            }
        }
    }

    private void animateComposerHeight() {
        if (!isLaidOut() || composer.getWidth() == 0) return;
        int from = composer.getHeight();
        if (composerHeightAnimator != null) composerHeightAnimator.cancel();
        composer.measure(MeasureSpec.makeMeasureSpec(composer.getWidth(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int target = composer.getMeasuredHeight();
        if (from == target) { composer.getLayoutParams().height = -2; composer.requestLayout(); return; }
        composerHeightAnimator = ValueAnimator.ofInt(from, target);
        composerHeightAnimator.setDuration(160);
        composerHeightAnimator.setInterpolator(new DecelerateInterpolator());
        composerHeightAnimator.addUpdateListener(animation -> {
            composer.getLayoutParams().height = (int)animation.getAnimatedValue(); composer.requestLayout();
        });
        composerHeightAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator animation) { cancelled = true; }
            @Override public void onAnimationEnd(Animator animation) {
                if (!cancelled) { composer.getLayoutParams().height = -2; composer.requestLayout(); }
            }
        });
        composerHeightAnimator.start();
    }

    void prefillClipboardOnFocus() {
        post(() -> { if (!disposed && hasWindowFocus()) startClipboard(); });
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) {
            updateLanguageButton();
            applyKeyboardLanguage(false);
            post(() -> { if (!disposed && hasWindowFocus()) startClipboard(); });
        }
        else stopClipboard();
    }

    private void startClipboard() {
        if (clipboardSuspended || disposed || !hasWindowFocus()) return;
        if (clipboard == null) clipboard = (ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null && !clipboardListening) {
            clipboard.addPrimaryClipChangedListener(clipboardChanged);
            clipboardListening = true;
        }
        readClipboard(false);
    }

    private void stopClipboard() {
        if (clipboard != null && clipboardListening) clipboard.removePrimaryClipChangedListener(clipboardChanged);
        clipboardListening = false;
    }

    private void readClipboard(boolean changed) {
        if (disposed || clipboardSuspended || !hasWindowFocus()) return;
        String copied = SearchClipboard.recentText(getContext(), changed);
        if (copied != null) draftChips.replaceFromClipboard(copied);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ocrLanguage.observe(languageChanged);
        destination.observe(destinationChanged);
        refreshLanguage();
        refreshDestination();
    }

    @Override protected void onDetachedFromWindow() {
        stopClipboard();
        ocrLanguage.removeObserver(languageChanged);
        destination.removeObserver(destinationChanged);
        super.onDetachedFromWindow();
    }

    private void installInsets() {
        setOnApplyWindowInsetsListener((v, insets) -> {
            // OEMs may redispatch intermediate values here as well as onProgress.
            // Keep the cached reservation until the animation or stable sample settles.
            rememberKeyboardHeight(insets);
            applyInsets(insets);
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) {
            setWindowInsetsAnimationCallback(new WindowInsetsAnimation.Callback(WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                @Override public void onPrepare(WindowInsetsAnimation animation) {
                    if ((animation.getTypeMask() & WindowInsets.Type.ime()) == 0) return;
                    activeImeAnimation = animation; pendingImeHeight = 0;
                    removeCallbacks(confirmImeHeight);
                }
                @Override public WindowInsets onProgress(WindowInsets insets, List<WindowInsetsAnimation> animations) {
                    // Animated/interrupted intermediate heights never replace the reservation.
                    applyInsets(insets); return insets;
                }
                @Override public void onEnd(WindowInsetsAnimation animation) {
                    if (animation != activeImeAnimation || disposed) return;
                    activeImeAnimation = null;
                    WindowInsets current = getRootWindowInsets();
                    if (current != null) {
                        rememberKeyboardHeight(current);
                        applyInsets(current);
                        if (!current.isVisible(WindowInsets.Type.ime()) && current.getInsets(WindowInsets.Type.ime()).bottom == 0) callback.onEditingChanged(false);
                    }
                    removeCallbacks(confirmImeHeight);
                    if (current == null || current.isVisible(WindowInsets.Type.ime())) confirmImeHeight.run();
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
        state.setKeyboardVisible(keyboard > (wasVisible ? Math.max(safeBottom, dp(24)) + dp(4) : dp(120)));
        if (keyboard > dp(120) && keyboard != legacyImeCandidate) {
            legacyImeCandidate = keyboard;
            removeCallbacks(commitLegacyIme); postDelayed(commitLegacyIme, 150);
        } else if (keyboard <= dp(120)) {
            legacyImeCandidate = 0;
            removeCallbacks(commitLegacyIme);
        }

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
        int height = legacyViewportHeight;
        if (child == page) {
            int full = getResources().getDisplayMetrics().heightPixels - legacyVisibleFrame.top;
            // The viewport can shrink/pan on every IME frame. Frame the composer
            // from its retained reservation; only stable height calibration moves it.
            height = Math.max(0, full - reservedKeyboardSpace(full));
        }
        if (lp.topMargin == topInset && lp.height == height) return;
        lp.topMargin = topInset;
        lp.height = height;
        child.setLayoutParams(lp);
    }

    private final Runnable commitLegacyIme = new Runnable() {
        public void run() {
            if (disposed || clipboardSuspended || !state.isKeyboardVisible()) return;
            calibrateKeyboardHeight(legacyImeCandidate);
        }
    };

    private int composerBottomSpace() {
        return composerBottomSpace(getHeight());
    }

    private int composerBottomSpace(int windowHeight) {
        return reservedKeyboardSpace(windowHeight - topInset);
    }

    private int reservedKeyboardSpace(int viewportHeight) {
        int height = renderedKeyboardHeight >= 0 ? renderedKeyboardHeight : keyboardSpace.height();
        return Math.max(safeBottom, Math.min(height, Math.max(0, viewportHeight - dp(220))));
    }

    private void rememberKeyboardHeight(WindowInsets insets) {
        if (Build.VERSION.SDK_INT < 30 || clipboardSuspended) return;
        removeCallbacks(confirmImeHeight);
        pendingImeHeight = insets.isVisible(WindowInsets.Type.ime()) ? insets.getInsets(WindowInsets.Type.ime()).bottom : 0;
        if (activeImeAnimation == null && pendingImeHeight > 0) postDelayed(confirmImeHeight, 150);
    }

    private final Runnable confirmImeHeight = () -> {
        if (!disposed && activeImeAnimation == null && pendingImeHeight > 0) calibrateKeyboardHeight(pendingImeHeight);
    };

    private void calibrateKeyboardHeight(int height) {
        int from = renderedKeyboardHeight >= 0 ? renderedKeyboardHeight : keyboardSpace.height();
        keyboardSpace.measured(height);
        int target = keyboardSpace.height();
        if (keyboardHeightAnimator != null) keyboardHeightAnimator.cancel();
        if (target == from) { renderedKeyboardHeight = target; return; }
        if (!isLaidOut()) { renderedKeyboardHeight = -1; requestLayout(); return; }
        renderedKeyboardHeight = from;
        keyboardHeightAnimator = ValueAnimator.ofInt(from, target);
        keyboardHeightAnimator.setDuration(200);
        keyboardHeightAnimator.setInterpolator(new DecelerateInterpolator());
        keyboardHeightAnimator.addUpdateListener(animation -> {
            renderedKeyboardHeight = (int)animation.getAnimatedValue();
            if (Build.VERSION.SDK_INT < 30) updateLegacyViewport(); else requestLayout();
        });
        keyboardHeightAnimator.start();
    }

    private void applyInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            topInset = bars.top;
            systemBackInset = insets.getInsets(WindowInsets.Type.systemGestures()).left;
            safeBottom = bars.bottom;
            imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
            state.setKeyboardVisible(insets.isVisible(WindowInsets.Type.ime()) || imeBottom > 0);
        } else {
            safeBottom = insets.getStableInsetBottom();
            if (Build.VERSION.SDK_INT >= 29) systemBackInset = insets.getSystemGestureInsets().left;
            updateLegacyViewport();
            return;
        }
        page.setPadding(0, topInset, 0, composerBottomSpace());
        updateCompactLayout();
        drawer.setPadding(drawer.getPaddingLeft(), topInset + dp(6), drawer.getPaddingRight(),
                Math.max(safeBottom, imeBottom) + dp(8));
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
        pill.setContentDescription(label);
        pill.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
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

    private void submitFromButton() {
        if (!input.getText().toString().trim().isEmpty()) submit();
        else if (!state.isPrivateMode()) unavailable("实时语音");
    }

    void prepareForLookup() {
        clipboardSuspended = true;
        removeCallbacks(confirmImeHeight); pendingImeHeight = 0;
        removeCallbacks(commitLegacyIme);
        stopClipboard();
        suggestions.pause();
        explosionController.pause();
        closeMenu();
        drawerMotion.snap(state.isDrawerOpen() ? drawerMotion.limit() : 0);
        hideKeyboard();
    }
    boolean isLookupPaused() { return clipboardSuspended; }
    void resumeAfterLookup() {
        clipboardSuspended = false;
        suggestions.resume();
        explosionController.resume();
        if (hasWindowFocus()) startClipboard();
    }

    private void togglePrivateMode() {
        closeMenu();
        state.togglePrivateMode();
        callback.onPrivateModeChanged(state.isPrivateMode());
        applyPrivateMode(true);
    }

    private void applyPrivateMode(boolean animate) {
        boolean privacy = state.isPrivateMode();
        privateButton.setBackground(ripple(privacy ? 0xffdedede : SOFT, 24));
        privateButton.setContentDescription(privacy ? "退出私密查询" : "进入私密查询");
        input.setHint(privacy ? "临时对话" : "随便问点什么");
        input.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI |
                EditorInfo.IME_FLAG_NO_FULLSCREEN |
                (privacy && Build.VERSION.SDK_INT >= 26 ? EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING : 0));
        if (input.isFocused()) ((InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).restartInput(input);
        recommendations.setVisibility(privacy || draftDragging ? GONE : VISIBLE);
        updateCompactLayout();
        drawer.setPrivateMode(privacy);
        updateAction();
        View previous = emptyView;
        LinearLayout empty = new LinearLayout(getContext());
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(24), 0, dp(24), 0);
        SearchIconView mark = icon(privacy ? SearchIconView.Kind.PRIVATE : SearchIconView.Kind.BOOK, 0xffe7e7e7);
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
        int ink = send && !hasText ? AppTheme.ink(0xffa1a4a5) : AppTheme.isDark() ? AppTheme.color(AppTheme.PAGE) : Color.WHITE;
        actionButton.setBackground(ripple(send && !hasText ? SOFT : INK, 24));
        actionButton.addView(icon(send ? SearchIconView.Kind.SEND : SearchIconView.Kind.VOICE, ink), new LinearLayout.LayoutParams(dp(20), dp(24)));
        if (!send && getResources().getDisplayMetrics().widthPixels / getResources().getDisplayMetrics().density >= 350 && getResources().getConfiguration().fontScale <= 1.2f) {
            TextView title = text("开始说话", 13, ink, true); title.setPadding(dp(5), 0, 0, 0);
            actionButton.addView(title);
        }
        actionButton.setContentDescription(send ? "提交查词" : "开始说话，尚未接入");
        actionButton.setEnabled(hasText || !state.isPrivateMode());
    }

    void handleBack() {
        if (state.getMenu() == FloatingSearchState.Menu.NONE && !state.isKeyboardVisible()
                && !state.isDrawerOpen() && drawerMotion.progress() > 0) {
            setDrawerOpen(false);
            return;
        }
        if (state.isDrawerOpen() && drawer.isSearching() && !state.isKeyboardVisible()) {
            drawer.endSearch();
            return;
        }
        switch (state.onBack()) {
            case CLOSE_MENU: closeMenu(); break;
            case HIDE_KEYBOARD: hideKeyboard(); break;
            case CLOSE_DRAWER: setDrawerOpen(false); break;
            case CLOSE_SEARCH: callback.onClose(); break;
        }
    }

    void focusInput() {
        if (disposed) return;
        callback.onEditingChanged(true);
        input.requestFocus();
        input.post(() -> {
            if (!disposed) ((InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void hideKeyboard() {
        ((InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(getWindowToken(), 0);
        View focused = findFocus();
        if (focused != null) focused.clearFocus();
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
        menuCard.setPadding(dp(requested == FloatingSearchState.Menu.LANGUAGE ? 6 : 10),
                dp(requested == FloatingSearchState.Menu.LANGUAGE ? 6 : 14),
                dp(requested == FloatingSearchState.Menu.LANGUAGE ? 6 : 10),
                dp(requested == FloatingSearchState.Menu.LANGUAGE ? 6 : 12));
        menuCard.setBackground(shape(PAPER, 26));
        menuCard.setElevation(dp(9));
        menuCard.setClickable(true);
        if (requested == FloatingSearchState.Menu.MODEL) buildModelMenu();
        else if (requested == FloatingSearchState.Menu.LANGUAGE) buildLanguageMenu();
        else buildAttachmentMenu();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(menuWidth(), -2);
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

    private void buildLanguageMenu() {
        for (OcrLanguageSettings.Language language : OcrLanguageSettings.Language.values()) {
            LinearLayout row = new LinearLayout(getContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), 0, dp(8), 0);
            row.addView(text(language.label, 15, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
            SearchIconView check = icon(SearchIconView.Kind.CHECK, INK);
            check.setVisibility(language == ocrLanguage.language() ? VISIBLE : INVISIBLE);
            row.addView(check, new LinearLayout.LayoutParams(dp(18), dp(22)));
            row.setContentDescription("OCR " + language.label + (language == ocrLanguage.language() ? "，当前选中" : ""));
            row.setOnClickListener(v -> selectLanguage(language));
            languageRows[language.ordinal()] = row;
            menuCard.addView(row, new LinearLayout.LayoutParams(-1, dp(44)));
        }
        updateLanguageHighlight();
    }

    private boolean onLanguageTouch(View view, MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) languageDrag = false;
        if (!languageDrag) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            OcrLanguageSettings.Language hover = languageAt(event.getRawX(), event.getRawY());
            if (hover != hoveredLanguage) { hoveredLanguage = hover; updateLanguageHighlight(); }
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            OcrLanguageSettings.Language selected = languageAt(event.getRawX(), event.getRawY());
            languageDrag = false;
            hoveredLanguage = null;
            if (selected != null) selectLanguage(selected);
            else {
                if (!containsScreenPoint(modelButton, event.getRawX(), event.getRawY())) closeMenu();
                else updateLanguageHighlight();
            }
            view.setPressed(false);
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            languageDrag = false;
            closeMenu();
            view.setPressed(false);
        }
        return true;
    }

    private OcrLanguageSettings.Language languageAt(float screenX, float screenY) {
        if (state.getMenu() != FloatingSearchState.Menu.LANGUAGE
                || !containsScreenPoint(menuSurface, screenX, screenY)) return null;
        for (OcrLanguageSettings.Language language : OcrLanguageSettings.Language.values()) {
            View row = languageRows[language.ordinal()];
            if (row != null && containsScreenPoint(row, screenX, screenY)) return language;
        }
        return null;
    }

    private boolean containsScreenPoint(View view, float x, float y) {
        if (view == null) return false;
        int[] position = new int[2];
        view.getLocationOnScreen(position);
        return x >= position[0] && x < position[0] + view.getWidth()
                && y >= position[1] && y < position[1] + view.getHeight();
    }

    private void updateLanguageHighlight() {
        for (OcrLanguageSettings.Language language : OcrLanguageSettings.Language.values()) {
            View row = languageRows[language.ordinal()];
            if (row != null) {
                row.setBackground(ripple(language == hoveredLanguage
                        || (hoveredLanguage == null && language == ocrLanguage.language()) ? 0xffe9e9e9 : PAPER, 12));
                ((LinearLayout)row).getChildAt(1).setVisibility(language == ocrLanguage.language() ? VISIBLE : INVISIBLE);
                row.setContentDescription("OCR " + language.label + (language == ocrLanguage.language() ? "，当前选中" : ""));
            }
        }
    }

    private void selectLanguage(OcrLanguageSettings.Language language) {
        ocrLanguage.setLanguage(language);
        if (!isAttachedToWindow()) refreshLanguage();
        closeMenu();
    }

    private void refreshLanguage() {
        if (disposed) return;
        applyKeyboardLanguage(true);
        updateLanguageButton();
        updateLanguageHighlight();
    }

    private void applyKeyboardLanguage(boolean restart) {
        if (Build.VERSION.SDK_INT >= 24) {
            // Request the English layout while preserving manual IME language
            // switching and unrestricted Unicode input.
            input.setImeHintLocales(ocrLanguage.language() == OcrLanguageSettings.Language.ENGLISH
                    ? new LocaleList(Locale.ENGLISH) : null);
        }
        if (restart && input.isFocused())
            ((InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).restartInput(input);
    }

    private void updateLanguageButton() {
        String label = ocrLanguage.language().label;
        languageLabel.setText(label);
        refreshDestination();
    }

    private int menuWidth() {
        return dp(state.getMenu() == FloatingSearchState.Menu.MODEL ? 292
                : state.getMenu() == FloatingSearchState.Menu.LANGUAGE ? 116 : 172);
    }

    private void buildModelMenu() {
        LinearLayout heading = new LinearLayout(getContext());
        heading.setGravity(Gravity.CENTER_VERTICAL); heading.setPadding(dp(6), 0, dp(6), dp(12));
        TextView title = text("Groq AI", 20, INK, true);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = text(GroqKeyStore.hasKey(getContext()) ? "已配置" : "待配置", 11, Color.WHITE, true); badge.setPadding(dp(10), dp(6), dp(10), dp(6)); badge.setBackground(shape(AppTheme.BLUE, 16));
        heading.addView(badge); menuCard.addView(heading);
        modelRow(SearchIconView.Kind.PROJECT, "Build  Beta", "构建应用和网站 · 未接入", false, LookupDestinationSettings.Model.BUILD);
        modelRow(SearchIconView.Kind.CONNECTOR, "重型", "专家团队 · 未接入", false, LookupDestinationSettings.Model.HEAVY);
        modelRow(SearchIconView.Kind.SKILLS, "专家", "深度思考 · 未接入", false, LookupDestinationSettings.Model.EXPERT);
        modelRow(SearchIconView.Kind.LIGHTNING, "快速", "Groq · " + BuildConfig.GROQ_MODEL, true, LookupDestinationSettings.Model.FAST);
        modelRow(SearchIconView.Kind.CONNECTOR, "第三方", "欧路词典 · 悬浮查词", true, LookupDestinationSettings.Model.THIRD_PARTY);
        refreshDestination();
    }

    private void modelRow(SearchIconView.Kind kind, String title, String subtitle, boolean enabled,
                          LookupDestinationSettings.Model model) {
        LinearLayout row = menuRow(kind, title, subtitle, enabled, false);
        SearchIconView check = icon(SearchIconView.Kind.CHECK, INK);
        row.addView(check, new LinearLayout.LayoutParams(dp(22), dp(26)));
        modelRows.put(model, row);
        row.setOnClickListener(v -> {
            if (!enabled) { unavailable(title); return; }
            destination.select(model);
            closeMenu();
        });
    }

    private void refreshDestination() {
        if (disposed) return;
        modelIcon.setKind(destination.isThirdParty() ? SearchIconView.Kind.CONNECTOR : SearchIconView.Kind.LIGHTNING);
        for (java.util.Map.Entry<LookupDestinationSettings.Model, LinearLayout> entry : modelRows.entrySet()) {
            LinearLayout row = entry.getValue();
            boolean selected = destination.model() == entry.getKey();
            row.setSelected(selected);
            row.setBackground(ripple(selected ? 0xffe9e9e9 : PAPER, 16));
            row.getChildAt(row.getChildCount() - 1).setVisibility(selected ? VISIBLE : GONE);
            String title = ((TextView)((LinearLayout)row.getChildAt(1)).getChildAt(0)).getText().toString();
            boolean enabled = entry.getKey() == LookupDestinationSettings.Model.FAST
                    || entry.getKey() == LookupDestinationSettings.Model.THIRD_PARTY;
            row.setContentDescription(title + (selected ? "，当前选中" : enabled ? "，点击选择" : "，尚未接入"));
        }
        modelButton.setContentDescription("查询方式：" + (destination.isThirdParty() ? "第三方，欧路词典" : "系统")
                + "；OCR 语言：" + ocrLanguage.language().label + "，点击选择查询方式，长按选择语言");
    }

    private void buildAttachmentMenu() {
        menuRow(SearchIconView.Kind.CAMERA, "摄像头", "未接入", false, false);
        menuRow(SearchIconView.Kind.IMAGE, "图库", "未接入", false, false);
        menuRow(SearchIconView.Kind.FILE, "文件", "未接入", false, false);
        View line = new View(getContext()); AppTheme.background(line, 0xffe6e7e8);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1)); lp.setMargins(dp(12), dp(10), dp(12), dp(10)); menuCard.addView(line, lp);
        menuRow(SearchIconView.Kind.SKILLS, "技能", "未接入", false, false);
        menuRow(SearchIconView.Kind.CONNECTOR, "连接器", "未接入", false, false);
    }

    private LinearLayout menuRow(SearchIconView.Kind kind, String title, String subtitle, boolean enabled, boolean selected) {
        LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(10), dp(6), dp(8), dp(6));
        row.setBackground(ripple(selected ? 0xffe9e9e9 : PAPER, 16));
        row.addView(icon(kind, enabled ? INK : 0xff909396), new LinearLayout.LayoutParams(dp(25), dp(30)));
        LinearLayout labels = new LinearLayout(getContext()); labels.setOrientation(LinearLayout.VERTICAL); labels.setPadding(dp(12), 0, dp(5), 0);
        labels.addView(text(title, 16, enabled ? INK : 0xff949698, true));
        TextView detail = text(subtitle, 11, enabled ? 0xff66696c : 0xffa3a5a6, false); detail.setMaxLines(2); labels.addView(detail);
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (selected) row.addView(icon(SearchIconView.Kind.CHECK, INK), new LinearLayout.LayoutParams(dp(22), dp(26)));
        row.setOnClickListener(v -> { if (enabled) closeMenu(); else unavailable(title); });
        row.setContentDescription(title + (selected ? "，当前选中" : enabled ? "，点击选择" : "，尚未接入"));
        menuCard.addView(row, new LinearLayout.LayoutParams(-1, dp(58)));
        return row;
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
        boolean languageMenu = state.getMenu() == FloatingSearchState.Menu.LANGUAGE;
        int viewportBottom = Build.VERSION.SDK_INT < 30 && legacyViewportHeight > 0
                ? topInset + legacyViewportHeight : getHeight() - Math.max(safeBottom, imeBottom);
        if (languageMenu) {
            // Keep all four options reachable while holding, including landscape
            // keyboards where the space directly above the anchor is too short.
            int rowHeight = Math.max(dp(32), Math.min(dp(44), (viewportBottom - topInset - dp(28)) / 4));
            for (LinearLayout row : languageRows) {
                if (row != null && row.getLayoutParams().height != rowHeight) {
                    row.getLayoutParams().height = rowHeight;
                    row.requestLayout();
                }
            }
        }
        int maxHeight = Math.max(dp(80), rect.top - topInset - dp(12));
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)menuSurface.getLayoutParams();
        lp.width = Math.min(menuWidth(), getWidth() - dp(24));
        menuCard.measure(MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        if (languageMenu) maxHeight = menuCard.getMeasuredHeight();
        int anchorLeft = state.getMenu() == FloatingSearchState.Menu.LANGUAGE
                ? rect.left + (rect.width() - lp.width) / 2 : rect.left - dp(4);
        lp.leftMargin = state.getMenu() == FloatingSearchState.Menu.MODEL ? dp(12) : Math.max(dp(12), Math.min(anchorLeft, getWidth() - lp.width - dp(12)));
        lp.topMargin = Math.max(topInset + dp(8), rect.top - Math.min(menuCard.getMeasuredHeight(), maxHeight) - dp(12));
        if (languageMenu) lp.topMargin = Math.max(topInset + dp(8), Math.min(lp.topMargin, viewportBottom - maxHeight - dp(8)));
        lp.height = Math.min(menuCard.getMeasuredHeight(), maxHeight);
        if (lp.width != menuSurface.getWidth() || lp.topMargin != menuSurface.getTop() || lp.leftMargin != menuSurface.getLeft() || lp.height != menuSurface.getHeight()) menuSurface.setLayoutParams(lp);
    }

    private void closeMenu() {
        state.setMenu(FloatingSearchState.Menu.NONE);
        hoveredLanguage = null;
        modelRows.clear();
        for (int i = 0; i < languageRows.length; i++) languageRows[i] = null;
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

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            menuAnchorTouchTarget = null;
            if (menuDismiss != null && languageAt(event.getRawX(), event.getRawY()) == null) {
                if (containsPoint(modelButton, event.getX(), event.getY())) menuAnchorTouchTarget = modelButton;
                else if (containsPoint(plus, event.getX(), event.getY())) menuAnchorTouchTarget = plus;
            }
        }
        if (menuAnchorTouchTarget == null) return super.dispatchTouchEvent(event);
        // The dismiss layer covers the composer. Route the complete gesture to
        // its anchor even when opening another menu replaces that dismiss View.
        View target = menuAnchorTouchTarget;
        int[] rootPosition = new int[2], targetPosition = new int[2];
        getLocationOnScreen(rootPosition); target.getLocationOnScreen(targetPosition);
        MotionEvent forwarded = MotionEvent.obtain(event);
        forwarded.offsetLocation(rootPosition[0] - targetPosition[0], rootPosition[1] - targetPosition[1]);
        try { target.dispatchTouchEvent(forwarded); } finally { forwarded.recycle(); }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL)
            menuAnchorTouchTarget = null;
        return true;
    }

    private void addHeaderTab(LinearLayout tabs, String label, int index, Runnable action) {
        FrameLayout tab = new FrameLayout(getContext());
        TextView title = text(label, 17, INK, true);
        title.setSingleLine();
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER);
        tab.addView(title, new FrameLayout.LayoutParams(-1, -1));
        View indicator = new View(getContext());
        indicator.setBackground(shape(0xffb1b1b1, 2));
        FrameLayout.LayoutParams indicatorLayout = new FrameLayout.LayoutParams(
                dp(15), dp(3), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        indicatorLayout.bottomMargin = dp(4);
        tab.addView(indicator, indicatorLayout);
        tabLabels[index] = title;
        tabIndicators[index] = indicator;
        tab.setContentDescription(index == 1 ? "图片，尚未接入" : label);
        tab.setOnClickListener(v -> action.run());
        tabs.addView(tab, new LinearLayout.LayoutParams(0, dp(44), 1));
    }

    private void selectQuestionTab() {
        for (int i = 0; i < tabLabels.length; i++) {
            boolean selected = i == 0;
            AppTheme.text(tabLabels[i], selected ? INK : 0xffb0b0b0);
            tabIndicators[i].setVisibility(selected ? VISIBLE : INVISIBLE);
        }
    }

    private void openPage(String page) {
        hideKeyboard();
        closeMenu();
        callback.onOpenPage(page, state.isPrivateMode());
    }

    private void openHistory() {
        setDrawerOpen(true);
        drawer.showHistory();
    }

    private void setDrawerOpen(boolean open) {
        open = open && drawerMotion.limit() > 0;
        closeMenu();
        if (open) { hideKeyboard(); drawer.refreshHistory(); }
        else { hideKeyboard(); drawer.endSearch(); }
        state.setDrawerOpen(open);
        animateDrawer(open ? 1 : 0);
    }

    private void animateDrawer(float target) {
        animateDrawer(target, null);
    }

    private void animateDrawer(float target, Runnable after) {
        drawerMotion.animate(target > 0, after == null ? null : () -> { if (!disposed) after.run(); });
    }

    private void applyDrawerProgress(float progress) {
        drawerGeometry.update(drawerMode, progress, drawerMotion.limit(), drawerWidth,
                getResources().getDisplayMetrics().density);
        boolean visible = progress > 0 && drawerMotion.limit() > 0;
        boolean push = drawerMode == DrawerStyleSettings.Mode.PUSH;
        drawer.setVisibility(visible ? VISIBLE : INVISIBLE);
        drawer.setTranslationX(drawerGeometry.menuX); drawer.setTranslationY(drawerGeometry.menuY);
        drawer.setPivotX(0); drawer.setPivotY(getHeight() / 2f - drawer.getTop());
        drawer.setScaleX(drawerGeometry.menuScale); drawer.setScaleY(drawerGeometry.menuScale);
        drawer.setElevation(push ? 0 : dp(18) * drawerGeometry.fraction);
        float pageElevation = push ? dp(16) * drawerGeometry.fraction : 0;
        page.setElevation(pageElevation); textExplosion.setTranslationZ(pageElevation);
        page.setPivotX(getWidth()); page.setPivotY(getHeight() / 2f - page.getTop());
        textExplosion.setPivotX(getWidth()); textExplosion.setPivotY(getHeight() / 2f - textExplosion.getTop());
        page.setTranslationX(drawerGeometry.pageX); textExplosion.setTranslationX(drawerGeometry.pageX);
        page.setScaleX(drawerGeometry.pageScale); page.setScaleY(drawerGeometry.pageScale);
        textExplosion.setScaleX(drawerGeometry.pageScale); textExplosion.setScaleY(drawerGeometry.pageScale);
        drawerPageBackground.setCornerRadius(push ? dp(18) * drawerGeometry.fraction : 0);
        page.setClipToOutline(push && visible);
        drawerDismiss.setVisibility(visible ? VISIBLE : GONE);
        drawerDismiss.setTranslationX(drawerMode == DrawerStyleSettings.Mode.OVERLAY ? 0 : Math.max(0, drawerGeometry.pageX));
        drawerDismiss.setTranslationZ(pageElevation + (push && visible ? dp(1) : 0));
        drawerDismiss.setBackgroundColor(((int) (drawerGeometry.shade * 255) << 24) | 0x020813);
        drawer.applyOpeningFraction(drawerGeometry.fraction);
    }

    private void refreshDrawerStyle() {
        if (disposed) return;
        drawerMode = drawerSettings.mode();
        configureDrawerWidth(getWidth());
        if (drawerMotion.limit() == 0) state.setDrawerOpen(false);
        if (clipboardSuspended) drawerMotion.snap(state.isDrawerOpen() ? drawerMotion.limit() : 0);
        else drawerMotion.animate(state.isDrawerOpen(), null);
    }

    private void configureDrawerWidth(int viewportWidth) {
        int percent = drawerSettings.ratio(drawerMode);
        drawerWidth = Math.max(1, Math.round(viewportWidth * percent / 100f));
        ViewGroup.LayoutParams params = drawer.getLayoutParams();
        if (params.width != drawerWidth) { params.width = drawerWidth; drawer.setLayoutParams(params); }
        drawerMotion.configure(drawerWidth, percent == 0 ? 0 : 1);
    }

    void experienceDrawer() { if (!disposed) setDrawerOpen(true); }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (Build.VERSION.SDK_INT >= 30) {
            // onSizeChanged is after measurement: applying the reservation there
            // lets the first frame use full-height children and jump on the next frame.
            int height = MeasureSpec.getSize(heightMeasureSpec);
            int bottomSpace = composerBottomSpace(height);
            page.setPadding(0, topInset, 0, bottomSpace);
            updateCompactLayout(height - topInset - bottomSpace);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (textExplosion.hasText()) {
            FrameLayout.LayoutParams pageLp = (FrameLayout.LayoutParams)page.getLayoutParams();
            int top = pageLp.topMargin + page.getPaddingTop()
                    + (header.getVisibility() == GONE ? 0 : header.getMeasuredHeight())
                    + body.getMeasuredHeight() + bottom.getMeasuredHeight() - bottom.getPaddingBottom();
            int height = Math.max(0, getMeasuredHeight() - safeBottom - top);
            FrameLayout.LayoutParams explosionLp = (FrameLayout.LayoutParams)textExplosion.getLayoutParams();
            explosionLp.topMargin = top; explosionLp.height = height;
            textExplosion.setVisibility(height > 0 ? VISIBLE : GONE);
            textExplosion.measure(MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw && composerHeightAnimator != null) {
            composerHeightAnimator.cancel(); composer.getLayoutParams().height = -2;
        }
        configureDrawerWidth(w);
        if (w != oldw) drawerMotion.snap(state.isDrawerOpen() ? drawerMotion.limit() : 0);
        else applyDrawerProgress(drawerMotion.progress());
        post(this::positionMenu);
        if (Build.VERSION.SDK_INT < 30) updateLegacyViewport();
    }

    private void updateCompactLayout() {
        if (getHeight() == 0) return;
        int available = Build.VERSION.SDK_INT < 30 && legacyViewportHeight > 0
                ? page.getLayoutParams().height : getHeight() - topInset - composerBottomSpace();
        updateCompactLayout(available);
    }

    private void updateCompactLayout(int available) {
        header.setVisibility(available < dp(240) ? GONE : VISIBLE);
        recommendations.setVisibility(!draftDragging && !state.isPrivateMode() && available >= dp(320) ? VISIBLE : GONE);
        boolean tight = available < dp(240);
        suggestionArea.setVisibility(!tight ? VISIBLE : GONE);
        if (tight != compactComposer) {
            if (composerHeightAnimator != null) composerHeightAnimator.cancel();
            composer.getLayoutParams().height = -2;
            compactComposer = tight;
            input.setMinLines(tight ? 1 : 2);
            input.setMaxLines(tight ? 1 : 4);
            input.setPadding(dp(10), tight ? 0 : dp(5), dp(10), tight ? 0 : dp(6));
            composer.setPadding(dp(8), dp(tight ? 2 : 5), dp(8), dp(tight ? 2 : 7));
            ViewGroup.LayoutParams toolsLp = tools.getLayoutParams();
            toolsLp.height = dp(tight ? 36 : 44); tools.setLayoutParams(toolsLp);
            LinearLayout.LayoutParams composerLp = (LinearLayout.LayoutParams)composer.getLayoutParams();
            composerLp.setMargins(dp(8), 0, dp(8), 0);
            composer.setLayoutParams(composerLp);
        }
        // Maintain an 8dp gap above the keyboard and the display's lower edge.
        int bottomPadding = dp(8);
        // Blank space above suggestions belongs to body, so its mark is centered
        // all the way down to the hint instead of excluding invisible padding.
        int topPadding = dp(tight ? 2 : 0);
        if (bottom.getPaddingTop() != topPadding || bottom.getPaddingBottom() != bottomPadding) {
            bottom.setPadding(0, topPadding, 0, bottomPadding);
        }
    }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        return drawerMotion.handle(e, canSwipeDrawer(e), state.isDrawerOpen()) || super.onInterceptTouchEvent(e);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        return drawerMotion.handle(e, canSwipeDrawer(e), state.isDrawerOpen()) || super.onTouchEvent(e);
    }

    private boolean canSwipeDrawer(MotionEvent e) {
        int edge = Math.max(dp(24), systemBackInset + dp(8));
        int bottomEdge = Build.VERSION.SDK_INT < 30 && legacyViewportHeight > 0
                ? topInset + legacyViewportHeight : getHeight() - Math.max(safeBottom, imeBottom);
        return !draftDragging && menuCard == null && !containsPoint(recommendations, e.getX(), e.getY())
                && e.getX() > edge && e.getX() < getWidth() - edge && e.getY() < bottomEdge
                && (drawerMotion.progress() > 0 || e.getY() > topInset + header.getHeight());
    }

    private void excludeDrawerGesture() { drawerMotion.excludeGesture(); }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) handleBack();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (emptyView != null) emptyView.setVisibility(
                body.getHeight() >= dp(state.isPrivateMode() ? 230 : 84) ? VISIBLE : INVISIBLE);
        if (changed) applyDrawerProgress(drawerMotion.progress());
        if (changed && menuCard != null) post(this::positionMenu);
    }

    void dispose() {
        stopClipboard();
        ocrLanguage.removeObserver(languageChanged);
        destination.removeObserver(destinationChanged);
        suggestions.close();
        if (suggestionHeightAnimator != null) suggestionHeightAnimator.cancel();
        if (composerHeightAnimator != null) composerHeightAnimator.cancel();
        if (keyboardHeightAnimator != null) keyboardHeightAnimator.cancel();
        for (TextView candidate : suggestionViews.values()) candidate.animate().cancel();
        explosionController.close();
        removeCallbacks(commitLegacyIme);
        removeCallbacks(confirmImeHeight);
        drawer.dispose();
        disposed = true;
        draftChips.dispose();
        ViewTreeObserver observer = getViewTreeObserver();
        if (observer.isAlive()) {
            if (legacyLayoutListener != null) observer.removeOnGlobalLayoutListener(legacyLayoutListener);
            if (legacyPreDrawListener != null) observer.removeOnPreDrawListener(legacyPreDrawListener);
        }
        legacyLayoutListener = null;
        legacyPreDrawListener = null;
        closeMenu(); drawerMotion.stop();
        drawerSettings.removeObserver(drawerStyleChanged);
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
        private int dragCaret = -1;
        private final Paint dragCaretPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Editor(Context context) { super(context); }
        void setDragCaret(int offset) { dragCaret = offset; invalidate(); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Layout layout = getLayout();
            if (dragCaret < 0 || layout == null) return;
            int offset = Math.min(length(), dragCaret), line = layout.getLineForOffset(offset);
            float x = layout.getPrimaryHorizontal(offset) + getTotalPaddingLeft() - getScrollX();
            float top = layout.getLineTop(line) + getTotalPaddingTop() - getScrollY();
            float bottom = layout.getLineBottom(line) + getTotalPaddingTop() - getScrollY();
            dragCaretPaint.setColor(AppTheme.ink(AppTheme.PURPLE)); dragCaretPaint.setStrokeWidth(dp(2));
            canvas.drawLine(x, top, x, bottom, dragCaretPaint);
            canvas.drawCircle(x, top, dp(3), dragCaretPaint);
        }
        @Override public InputConnection onCreateInputConnection(EditorInfo info) {
            InputConnection connection = super.onCreateInputConnection(info);
            if (Build.VERSION.SDK_INT >= 24) info.hintLocales = ocrLanguage.language() == OcrLanguageSettings.Language.ENGLISH
                    ? new LocaleList(Locale.ENGLISH) : null;
            // TextView adds NO_ENTER_ACTION for multiline editors; retain paste/Shift+Enter
            // support while letting the software keyboard expose the Send action.
            info.imeOptions &= ~EditorInfo.IME_FLAG_NO_ENTER_ACTION;
            return connection;
        }
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
    private GradientDrawable shape(int color, int radius) { GradientDrawable bg = new AppTheme.Shape(); bg.setColor(color); bg.setCornerRadius(dp(radius)); return bg; }
    private RippleDrawable ripple(int color, int radius) { return new RippleDrawable(ColorStateList.valueOf(0x16000000), shape(color, radius), shape(Color.WHITE, radius)); }
    private TextView text(String value, int size, int color, boolean bold) { TextView t = new TextView(getContext()); t.setText(value); t.setTextSize(size); AppTheme.text(t, color); t.setIncludeFontPadding(false); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t; }
    private SearchIconView icon(SearchIconView.Kind kind, int color) { SearchIconView v = new SearchIconView(getContext(), kind); v.setColor(color); return v; }
    private SearchIconView button(SearchIconView.Kind kind, String description, int color) { SearchIconView v = icon(kind, INK); v.setPadding(dp(9), dp(9), dp(9), dp(9)); v.setBackground(ripple(color, 24)); v.setContentDescription(description); v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES); return v; }
    private void toast(String message) { Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show(); }
    private void unavailable(String feature) { toast(feature + "尚未接入；当前支持多语种查词与 Groq AI。"); }
}
