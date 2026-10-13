package com.yuen.jpdict;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/** Native v18 layout, with JPDict cards and one immutable draft as the appearance source. */
public final class FloatingBubbleSettingsView extends LinearLayout {
    public interface PreviewListener { void onPreview(FloatingBubbleAppearance.Config config); }
    private static final int INK=0xff171819, MUTED=0xff6d747b, SOFT=0xfff4f5f6,
            BLUE=0xff008fd1, PALE_BLUE=0xffdef7ff, PAGE=0xfff2f2f2;
    private final PreviewListener onPreview;
    private final Runnable onChooseImage, onChooseSvg;
    private final LinearLayout header, savedHeader, appearance, gesture, editor, shapeSidebar;
    private final TextView appearanceTab, gestureTab, styleCount, sizeValue, opacityValue;
    private final BubbleStyleHistoryView savedStyles;
    private final TextView[] modeButtons=new TextView[3];
    private final LinearLayout[] shapeButtons=new LinearLayout[7];
    private final IconChoice[] iconButtons=new IconChoice[7];
    private final SeekBar size, opacity, iconSize;
    private final BubbleSliderStyle.ValueThumb iconSizeThumb;
    private final IconStrip iconStrip;
    private final LinearLayout textFields, imageFields, svgTools;
    private final LinearLayout iconSizeRow;
    private final EditText customText;
    private final TextView imageLabel, svgName;
    private final View colorSeparator;
    private final BubblePaintEditorView paintEditor;
    private final BubbleImageEditorView imageEditor;
    private FloatingBubbleAppearance.Config config;
    private boolean applyingConfig, gesturePage;
    private AlertDialog deleteDialog;
    private int pageScroll;

    public FloatingBubbleSettingsView(Context context, FloatingBubbleAppearance.Config initial,
            PreviewListener onPreview, Runnable onChooseImage) {
        this(context, initial, onPreview, onChooseImage, null);
    }

    public FloatingBubbleSettingsView(Context context, FloatingBubbleAppearance.Config initial,
            PreviewListener onPreview, Runnable onChooseImage, Runnable onChooseSvg) {
        super(context);
        config=initial;
        this.onPreview=onPreview; this.onChooseImage=onChooseImage; this.onChooseSvg=onChooseSvg;
        setOrientation(VERTICAL); setBackgroundColor(PAGE);
        boolean narrow=getResources().getConfiguration().screenWidthDp<=355;
        int edge=narrow?8:16;
        setPadding(dp(edge), dp(8), dp(edge), dp(16));
        header=column(); header.setBackgroundColor(PAGE);
        header.setPadding(dp(edge),0,dp(edge),dp(8));
        LinearLayout tabs=row();
        appearanceTab=tab("外观"); gestureTab=tab("手势控制");
        tabs.addView(appearanceTab,new LayoutParams(0,dp(58),1));
        tabs.addView(gestureTab,new LayoutParams(0,dp(58),1));
        header.addView(tabs);
        View tabLine=new View(context); tabLine.setBackgroundColor(0xffe6e8eb);
        header.addView(tabLine,new LayoutParams(-1,dp(1)));
        appearanceTab.setOnClickListener(v->setGesturePage(false));
        gestureTab.setOnClickListener(v->setGesturePage(true));
        savedHeader=column();
        LinearLayout savedLabel=row(); savedLabel.setPadding(dp(6),dp(10),dp(6),dp(7));
        savedLabel.addView(text("已保存样式 · 最新在左",12,MUTED,false),new LayoutParams(0,-2,1));
        styleCount=text("",12,MUTED,false); savedLabel.addView(styleCount);
        TextView save=button("保存",12); save.setTextColor(0xffffffff);
        save.setBackground(background(0xff007aff,14)); save.setPadding(dp(12),0,dp(12),0);
        LayoutParams saveParams=new LayoutParams(-2,dp(32)); saveParams.leftMargin=dp(9);
        savedLabel.addView(save,saveParams); save.setOnClickListener(v->saveSelected());
        savedHeader.addView(savedLabel);
        savedStyles=new BubbleStyleHistoryView(context,new BubbleStyleHistoryView.Listener() {
            @Override public void onSelected(FloatingBubbleAppearance.Config selected) {
                finishEditing(); applyConfig(selected,true);
            }
            @Override public void onAdd() { addStyle(); }
            @Override public void onDeleteRequested(String id) { confirmDeletion(id); }
        });
        savedHeader.addView(savedStyles,new LayoutParams(-1,-2)); header.addView(savedHeader);
        appearance=column(); addView(appearance,new LayoutParams(-1,-2));
        LinearLayout sliders=column(); sliders.setPadding(dp(14),dp(6),dp(14),dp(6));
        sliders.setBackground(background(0xffffffff,18));
        sizeValue=text("",13,MUTED,false); opacityValue=text("",13,MUTED,false);
        size=settingSlider(sliders,"大小",sizeValue,76,47,43,44);
        opacity=settingSlider(sliders,"透明度",opacityValue,100,47,43,44);
        appearance.addView(sliders,new LayoutParams(-1,-2));
        editor=row(); editor.setGravity(Gravity.TOP); editor.setClipChildren(false);
        editor.setPadding(dp(9),dp(11),dp(9),dp(14));
        editor.setBackground(background(0xffffffff,18));
        LayoutParams editorParams=new LayoutParams(-1,-2); editorParams.topMargin=dp(8);
        appearance.addView(editor,editorParams);
        shapeSidebar=column(); shapeSidebar.setPadding(dp(3),dp(3),dp(3),dp(3));
        shapeSidebar.setBackground(background(SOFT,18));
        editor.addView(shapeSidebar,new LayoutParams(dp(narrow?85:94),-2));
        String[] shapeNames={"圆形","圆角方形","胶囊形","六边形","菱形","星形","爱心形"};
        Paint shapeLabelMeasure=new Paint(Paint.ANTI_ALIAS_FLAG);
        shapeLabelMeasure.setTypeface(Typeface.DEFAULT_BOLD);
        shapeLabelMeasure.setTextSize((narrow?10:11)*getResources().getDisplayMetrics().scaledDensity);
        int shapeLabelWidth=(int)Math.ceil(shapeLabelMeasure.measureText("圆角方形"));
        for(int i=0;i<shapeNames.length;i++) {
            final int shape=shapeValueForIndex(i);
            LinearLayout choice=row(); choice.setGravity(Gravity.CENTER);
            choice.setPadding(dp(narrow?3:5),0,dp(narrow?3:5),0);
            ShapeSymbol symbol=new ShapeSymbol(context,i); choice.addView(symbol,new LayoutParams(dp(narrow?21:23),dp(25)));
            TextView name=text(shapeNames[i],narrow?10:11,MUTED,false);
            name.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
            LayoutParams label=new LayoutParams(shapeLabelWidth,-1); label.leftMargin=dp(narrow?4:6);
            choice.addView(name,label); choice.setFocusable(true); choice.setContentDescription(shapeNames[i]);
            LayoutParams shapeParams=new LayoutParams(-1,dp(53)); if(i>0)shapeParams.topMargin=dp(4);
            shapeSidebar.addView(choice,shapeParams); shapeButtons[i]=choice;
            choice.setOnClickListener(v->{
                FloatingBubbleAppearance.Builder b=new FloatingBubbleAppearance.Builder(config);
                // A shape edit does not also replace an old glyph or its image crop policy.
                b.shape=shape;
                change(b.build());
            });
        }
        View divider=new View(context); divider.setBackgroundColor(0xffe6e8eb);
        LayoutParams dividerParams=new LayoutParams(dp(1),-1);
        dividerParams.leftMargin=dp(narrow?5:8); dividerParams.rightMargin=dp(narrow?5:8);
        editor.addView(divider,dividerParams);
        LinearLayout settings=column(); settings.setPadding(dp(narrow?2:4),dp(7),dp(narrow?2:4),dp(6));
        editor.addView(settings,new LayoutParams(0,-2,1));
        LinearLayout modes=row(); modes.setPadding(dp(4),dp(4),dp(4),dp(4));
        modes.setBackground(background(SOFT,18));
        String[] modeNames={"默认","文字","图片"};
        for(int i=0;i<3;i++) {
            final int index=i;
            TextView item=button(modeNames[i],13); modeButtons[i]=item;
            LayoutParams itemParams=new LayoutParams(0,dp(37),1); if(i>0)itemParams.leftMargin=dp(3);
            modes.addView(item,itemParams);
            item.setOnClickListener(v->{
                finishEditing();
                int active=config.style==FloatingBubbleAppearance.STYLE_IMAGE?2:
                        config.style==FloatingBubbleAppearance.STYLE_CUSTOM?1:0;
                if(index==active)return;
                FloatingBubbleAppearance.Builder b=new FloatingBubbleAppearance.Builder(config);
                b.style=index==0?FloatingBubbleAppearance.STYLE_SEARCH:index==1?
                        FloatingBubbleAppearance.STYLE_CUSTOM:FloatingBubbleAppearance.STYLE_IMAGE;
                if(index==2 && config.style!=FloatingBubbleAppearance.STYLE_IMAGE
                        && !FloatingBubbleAppearance.hasCustomImage(getContext(),config))b.imageName="";
                b.legacyVisual=false; change(b.build());
            });
        }
        settings.addView(modes,new LayoutParams(-1,-2));
        iconStrip=new IconStrip(context); LayoutParams iconsLayout=new LayoutParams(-1,dp(47)); iconsLayout.topMargin=dp(11);
        settings.addView(iconStrip,iconsLayout);
        String[] iconNames={"细线搜索","粗线搜索","圆润搜索","放大搜索","实心搜索","扫描搜索","导入 SVG"};
        for(int i=0;i<7;i++) {
            final int index=i; IconChoice icon=new IconChoice(context,i);
            icon.setContentDescription(iconNames[i]); icon.setFocusable(true); iconButtons[i]=icon;
            iconStrip.addIcon(icon);
            icon.setOnClickListener(v->selectIcon(index));
        }
        iconSizeRow=row();
        iconSize=settingLabeledSlider(iconSizeRow,"Icon 大小",100,59,32);
        iconSizeThumb=(BubbleSliderStyle.ValueThumb)iconSize.getThumb();
        LayoutParams iconSizeParams=new LayoutParams(-1,-2); iconSizeParams.topMargin=dp(7);
        settings.addView(iconSizeRow,iconSizeParams);
        svgTools=column(); svgName=text("",10,MUTED,false); svgName.setSingleLine(true);
        svgTools.addView(svgName,new LayoutParams(-1,dp(22)));
        LinearLayout svgActions=row();
        TextView replace=button("更换 SVG",10); replace.setOnClickListener(v->chooseSvg());
        TextView remove=button("移除",10); remove.setOnClickListener(v->{
            FloatingBubbleAppearance.Builder b=new FloatingBubbleAppearance.Builder(config);
            b.customSvg=""; b.customSvgName=""; if(b.icon==6)b.icon=0; change(b.build());
        });
        addWeighted(svgActions,replace,30,0); addWeighted(svgActions,remove,30,6);
        svgTools.addView(svgActions); settings.addView(svgTools);
        textFields=column();
        customText=new EditText(context); customText.setSingleLine(true); customText.setTextSize(13);
        customText.setTextColor(INK); customText.setHintTextColor(MUTED); customText.setHint("输入文字");
        customText.setContentDescription("按钮显示文字");
        customText.setPadding(dp(10),0,dp(10),0); customText.setBackground(background(SOFT,14));
        customText.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                |android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                |android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN);
        customText.setFilters(new InputFilter[]{(source,start,end,destination,replaceStart,replaceEnd)->{
            String remaining=destination.subSequence(0,replaceStart).toString()
                    +destination.subSequence(replaceEnd,destination.length());
            int available=8-remaining.codePointCount(0,remaining.length());
            String inserted=source.subSequence(start,end).toString();
            if(inserted.codePointCount(0,inserted.length())<=available)return null;
            return available<=0?"":inserted.substring(0,inserted.offsetByCodePoints(0,available));
        }});
        customText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) { }
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {
                if(applyingConfig)return;
                FloatingBubbleAppearance.Builder b=new FloatingBubbleAppearance.Builder(config);
                b.text=s.toString(); b.legacyVisual=false; change(b.build());
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        LayoutParams textInputParams=new LayoutParams(-1,dp(42)); textInputParams.topMargin=dp(8);
        textFields.addView(customText,textInputParams); settings.addView(textFields);
        imageFields=column(); LayoutParams imageParams=new LayoutParams(-1,-2); imageParams.topMargin=dp(12);
        settings.addView(imageFields,imageParams);
        LinearLayout imageDrop=column(); imageDrop.setGravity(Gravity.CENTER);
        imageDrop.setPadding(dp(8),dp(13),dp(8),dp(13));
        SearchIconView imageSymbol=new SearchIconView(context,SearchIconView.Kind.IMAGE);
        imageSymbol.setColor(0xff8394a8); imageDrop.addView(imageSymbol,new LayoutParams(dp(24),dp(24)));
        imageLabel=button("点此上传按钮图片",12); imageLabel.setBackgroundColor(0x00000000);
        GradientDrawable drop=background(0xfff7fbff,14); drop.setStroke(dp(1),0xffcbd8e6,dp(4),dp(3));
        imageDrop.setBackground(drop); imageLabel.setTextColor(0xff71809a);
        LayoutParams imageTextParams=new LayoutParams(-1,-2); imageTextParams.topMargin=dp(5);
        imageDrop.addView(imageLabel,imageTextParams); imageDrop.setFocusable(true);
        imageDrop.setContentDescription("上传按钮图片");
        imageDrop.setOnClickListener(v->chooseImage()); imageLabel.setOnClickListener(v->chooseImage());
        imageFields.addView(imageDrop,new LayoutParams(-1,-2));
        imageEditor=new BubbleImageEditorView(context); imageEditor.setListener(this::change);
        imageFields.addView(imageEditor,new LayoutParams(-1,-2));
        colorSeparator=new View(context); colorSeparator.setBackgroundColor(0xffe9edf3);
        LayoutParams separator=new LayoutParams(-1,dp(1)); separator.topMargin=dp(16); separator.bottomMargin=dp(16);
        settings.addView(colorSeparator,separator);
        paintEditor=new BubblePaintEditorView(context); paintEditor.setListener(this::change);
        settings.addView(paintEditor,new LayoutParams(-1,-2));
        TextView reset=button("恢复默认",12); reset.setOnClickListener(v->{
            FloatingBubbleAppearance.Builder b=new FloatingBubbleAppearance.Builder(FloatingBubbleAppearance.defaults());
            b.presetId=config.presetId; finishEditing(); applyConfig(b.build(),true);
            toast("默认样式已预览，保存后采用");
        });
        LayoutParams resetParams=new LayoutParams(-1,dp(44)); resetParams.topMargin=dp(12);
        appearance.addView(reset,resetParams);
        gesture=column(); gesture.setPadding(dp(16),dp(16),dp(16),dp(16));
        gesture.setBackground(background(0xffffffff,18));
        gesture.addView(text("悬浮按钮手势",16,INK,true),new LayoutParams(-1,dp(36)));
        addGesture("轻点","打开桌面全局搜索");
        addGesture("长按 700ms 后拖动","振动后将准星移到文字，高亮后松手查词；未授权时先请求屏幕共享");
        addGesture("短滑动","保留向上、向下、向左、向右的方向提示");
        TextView returnAppearance=button("返回外观设置",13);
        returnAppearance.setOnClickListener(v->setGesturePage(false)); gesture.addView(returnAppearance,new LayoutParams(-1,dp(44)));
        addView(gesture,new LayoutParams(-1,-2));
        size.setOnSeekBarChangeListener(sliderListener(0));
        opacity.setOnSeekBarChangeListener(sliderListener(1));
        iconSize.setOnSeekBarChangeListener(sliderListener(2));
        refresh(); setGesturePage(false);
        editor.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->onPageScrolled(pageScroll));
    }

    /** Activity puts this above its scroll view; no duplicate editor state is created. */
    public View stickyHeader() { return header; }
    public FloatingBubbleAppearance.Config draft() { return config; }
    public String selectedPresetId() { return config.presetId; }
    public boolean handleBack() { if(!gesturePage)return false; setGesturePage(false); return true; }
    public void onPageScrolled(int scrollY) {
        pageScroll=scrollY;
        float distance=Math.max(0,scrollY-editor.getTop()-dp(11));
        float limit=Math.max(0,editor.getHeight()-shapeSidebar.getHeight()-dp(25));
        shapeSidebar.setTranslationY(Math.min(distance,limit));
    }
    public Bundle saveDraftState() {
        Bundle state=config.toBundle();
        state.putString("editor_text",customText.getText().toString());
        state.putBundle("paint_editor",paintEditor.saveEditorState());
        state.putBoolean("gesture_page",gesturePage);
        return state;
    }
    public void restoreEditorState(Bundle state) {
        if(state==null)return;
        applyingConfig=true;
        customText.setText(state.getString("editor_text",config.text));
        applyingConfig=false;
        paintEditor.restoreEditorState(state.getBundle("paint_editor"));
        setGesturePage(state.getBoolean("gesture_page",false)); refresh();
    }
    public void setDraftImage(String name) {
        FloatingBubbleAppearance.Builder b=new FloatingBubbleAppearance.Builder(config);
        b.style=FloatingBubbleAppearance.STYLE_IMAGE; b.imageName=name;
        b.imageZoomPercent=100; b.imageXPercent=0; b.imageYPercent=0; b.legacyVisual=false;
        FloatingBubbleAppearance.Config image=b.build();
        if(!FloatingBubbleAppearance.hasCustomImage(getContext(),image))return;
        change(image); FloatingBubbleAppearance.cleanupImages(getContext(),image);
    }
    public void setDraftSvg(String source,String name) {
        FloatingBubbleAppearance.validateSvg(source);
        FloatingBubbleAppearance.Builder b=new FloatingBubbleAppearance.Builder(config);
        b.customSvg=source; b.customSvgName=name; b.icon=FloatingBubbleAppearance.ICON_CUSTOM;
        b.style=FloatingBubbleAppearance.STYLE_SEARCH; b.legacyVisual=false;
        change(b.build()); paintEditor.selectIconTarget();
        iconStrip.post(()->iconStrip.fullScroll(HorizontalScrollView.FOCUS_RIGHT));
    }
    public void finishEditing() {
        customText.clearFocus(); paintEditor.finishEditing(); imageEditor.finishEditing();
        InputMethodManager manager=(InputMethodManager)getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if(manager!=null)manager.hideSoftInputFromWindow(getWindowToken(),0);
    }
    private void change(FloatingBubbleAppearance.Config next) {
        if(applyingConfig)return; config=next; refresh();
    }
    private void applyConfig(FloatingBubbleAppearance.Config next,boolean resetSession) {
        config=next; if(resetSession)paintEditor.resetSession(); refresh();
    }
    private void refresh() {
        applyingConfig=true;
        size.setProgress(config.sizeDp-24); opacity.setProgress(config.opacityPercent);
        iconSize.setProgress(config.iconSizePercent-50);
        sizeValue.setText(config.sizeDp+" dp"); opacityValue.setText(config.opacityPercent+"%");
        iconSizeThumb.setValue(config.iconSizePercent+"%");
        if(!customText.getText().toString().equals(config.text))customText.setText(config.text);
        int mode=config.style==FloatingBubbleAppearance.STYLE_IMAGE?2:config.style==FloatingBubbleAppearance.STYLE_CUSTOM?1:0;
        for(int i=0;i<3;i++)selected(modeButtons[i],i==mode);
        for(int i=0;i<shapeButtons.length;i++) {
            boolean active=config.shape==shapeValueForIndex(i)
                    || (i==1 && config.shape==FloatingBubbleAppearance.SHAPE_LEGACY);
            shapeButtons[i].setSelected(active); shapeButtons[i].setBackground(new ShapeBackground(active));
            ((TextView)shapeButtons[i].getChildAt(1)).setTextColor(active?BLUE:0xff667185);
            ((TextView)shapeButtons[i].getChildAt(1)).setTypeface(active?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);
            shapeButtons[i].getChildAt(0).invalidate();
        }
        iconStrip.setVisibility(mode==0?VISIBLE:GONE); iconSizeRow.setVisibility(mode==0?VISIBLE:GONE);
        svgTools.setVisibility(mode==0 && !config.customSvg.isEmpty()?VISIBLE:GONE);
        svgName.setText(config.customSvgName.isEmpty()?"已导入图标.svg":config.customSvgName);
        for(int i=0;i<7;i++) { iconButtons[i].setSelected(config.icon==i); iconButtons[i].invalidate(); }
        textFields.setVisibility(mode==1?VISIBLE:GONE); imageFields.setVisibility(mode==2?VISIBLE:GONE);
        colorSeparator.setVisibility(mode==2?GONE:VISIBLE); paintEditor.setVisibility(mode==2?GONE:VISIBLE);
        boolean hasImage=FloatingBubbleAppearance.hasCustomImage(getContext(),config);
        imageLabel.setText(hasImage?"已加载按钮图片 · 点此更换":"点此上传按钮图片");
        imageEditor.setVisibility(hasImage?VISIBLE:GONE);
        imageEditor.setConfig(config); paintEditor.setConfig(config);
        savedStyles.setDraft(config); styleCount.setText(savedStyles.count()+"/10");
        applyingConfig=false;
        if(onPreview!=null)onPreview.onPreview(config);
    }
    private boolean validateDraft() {
        if(config.style==FloatingBubbleAppearance.STYLE_CUSTOM && customText.getText().toString().trim().isEmpty()) {
            customText.setError("请输入图标文字"); return false;
        }
        if(!paintEditor.isInputValid()) { toast("请输入有效的十六进制颜色"); return false; }
        if(config.icon==FloatingBubbleAppearance.ICON_CUSTOM) {
            try { FloatingBubbleAppearance.validateSvg(config.customSvg); }
            catch(IllegalArgumentException invalid) {
                toast("SVG 图标无法保存："+invalid.getMessage()); return false;
            }
        }
        if(config.style==FloatingBubbleAppearance.STYLE_IMAGE && !config.imageName.isEmpty()
                && !FloatingBubbleAppearance.hasCustomImage(getContext(),config)) {
            toast("请先选择按钮图片"); return false;
        }
        return true;
    }
    private void saveSelected() {
        if(!validateDraft())return;
        boolean found=false;
        for(FloatingBubbleAppearance.Config item:FloatingBubbleAppearance.savedStyles(getContext()))
            if(item.presetId.equals(config.presetId))found=true;
        if(!found) { toast("请先选择要修改的样式，或点＋新增"); return; }
        if(!FloatingBubbleAppearance.save(getContext(),config)) { toast("设置保存失败，请重试"); return; }
        finishEditing(); savedStyles.reload(); refresh(); toast("已更新选中的样式");
    }
    private void addStyle() {
        if(savedStyles.count()>=10) { toast("最多保存 10 个样式"); return; }
        if(!validateDraft())return;
        FloatingBubbleAppearance.Config added=FloatingBubbleAppearance.addSavedStyle(getContext(),config);
        if(added==null) { toast("设置保存失败，请重试"); return; }
        finishEditing(); config=added; savedStyles.reload(); savedStyles.scrollToStart(); refresh(); toast("已新增样式");
    }
    private void confirmDeletion(String id) {
        if(deleteDialog!=null && deleteDialog.isShowing())return;
        finishEditing();
        LinearLayout content=column(); content.setPadding(dp(22),dp(16),dp(22),dp(16));
        FrameLayout symbol=new FrameLayout(getContext()); symbol.setBackground(background(0xfffff0f3,18));
        SearchIconView trash=new SearchIconView(getContext(),SearchIconView.Kind.TRASH); trash.setColor(0xffdf5366);
        symbol.addView(trash,new FrameLayout.LayoutParams(dp(23),dp(23),Gravity.CENTER));
        LayoutParams symbolParams=new LayoutParams(dp(48),dp(48)); symbolParams.gravity=Gravity.CENTER_HORIZONTAL;
        symbolParams.bottomMargin=dp(8); content.addView(symbol,symbolParams);
        TextView title=text("删除已保存样式？",18,INK,true); title.setGravity(Gravity.CENTER);
        content.addView(title,new LayoutParams(-1,dp(36)));
        TextView detail=text("删除后无法恢复，当前编辑内容不受影响",14,MUTED,false);
        detail.setGravity(Gravity.CENTER); detail.setLineSpacing(dp(2),1); content.addView(detail,new LayoutParams(-1,-2));
        LinearLayout actions=row(); LayoutParams actionsParams=new LayoutParams(-1,dp(44)); actionsParams.topMargin=dp(16);
        TextView cancel=button("取消",14), remove=button("删除样式",14);
        cancel.setTextColor(0xff55657a); remove.setTextColor(0xffcb4355); remove.setBackground(background(0xfffce8ec,14));
        addWeighted(actions,cancel,44,0); addWeighted(actions,remove,44,10); content.addView(actions,actionsParams);
        deleteDialog=new AlertDialog.Builder(getContext()).setView(content).create();
        cancel.setOnClickListener(v->deleteDialog.dismiss());
        remove.setOnClickListener(v->{
            if(!FloatingBubbleAppearance.deleteSavedStyle(getContext(),id)) { toast("删除失败，请重试"); return; }
            if(config.presetId.equals(id)) { FloatingBubbleAppearance.Builder b=new FloatingBubbleAppearance.Builder(config); b.presetId=""; config=b.build(); }
            deleteDialog.dismiss(); savedStyles.reload(); refresh(); toast("已删除保存的样式");
        });
        deleteDialog.show();
        if(deleteDialog.getWindow()!=null) {
            deleteDialog.getWindow().setBackgroundDrawable(background(0xffffffff,18));
            deleteDialog.getWindow().setLayout(standardDialogWidth(),WindowManager.LayoutParams.WRAP_CONTENT);
        }
        cancel.requestFocus();
    }
    private void setGesturePage(boolean value) {
        gesturePage=value; appearance.setVisibility(value?GONE:VISIBLE); gesture.setVisibility(value?VISIBLE:GONE);
        savedHeader.setVisibility(value?GONE:VISIBLE); selectedTab(appearanceTab,!value); selectedTab(gestureTab,value);
        if(value)shapeSidebar.setTranslationY(0);
    }
    private void chooseImage() { finishEditing(); if(onChooseImage!=null)onChooseImage.run(); }
    private void chooseSvg() { finishEditing(); if(onChooseSvg!=null)onChooseSvg.run(); else toast("SVG 选择器不可用"); }
    private void selectIcon(int index) {
        if(index==6 && config.customSvg.isEmpty()) { chooseSvg(); return; }
        finishEditing(); FloatingBubbleAppearance.Builder b=new FloatingBubbleAppearance.Builder(config);
        b.icon=index; b.style=FloatingBubbleAppearance.STYLE_SEARCH; b.legacyVisual=false;
        change(b.build()); paintEditor.selectIconTarget();
    }
    private SeekBar.OnSeekBarChangeListener sliderListener(int kind) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser) {
                if(applyingConfig || !fromUser)return;
                FloatingBubbleAppearance.Builder b=new FloatingBubbleAppearance.Builder(config);
                if(kind==0)b.sizeDp=progress+24; else if(kind==1)b.opacityPercent=progress;
                else b.iconSizePercent=progress+50;
                change(b.build());
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        };
    }
    private SeekBar settingSlider(LinearLayout parent,String label,TextView output,int maximum,
            int labelWidth,int outputWidth,int height) {
        LinearLayout line=row();
        line.addView(text(label,labelWidth==47?14:11,labelWidth==47?INK:0xff60718a,true),new LayoutParams(dp(labelWidth),-1));
        SeekBar slider=new SeekBar(getContext()); slider.setMax(maximum); slider.setContentDescription(label);
        BubbleSliderStyle.apply(slider);
        LayoutParams sliderParams=new LayoutParams(0,dp(height),1); sliderParams.leftMargin=dp(6);
        sliderParams.rightMargin=dp(6); line.addView(slider,sliderParams);
        output.setGravity(Gravity.END|Gravity.CENTER_VERTICAL); line.addView(output,new LayoutParams(dp(outputWidth),-1));
        parent.addView(line,new LayoutParams(-1,dp(height))); return slider;
    }
    private SeekBar settingLabeledSlider(LinearLayout parent,String label,int maximum,
            int labelWidth,int height) {
        LinearLayout line=row();
        line.addView(text(label,11,0xff60718a,true),new LayoutParams(dp(labelWidth),-1));
        SeekBar slider=new SeekBar(getContext()); slider.setMax(maximum); slider.setContentDescription(label);
        BubbleSliderStyle.applyLabeled(slider,"100%",0);
        LayoutParams sliderParams=new LayoutParams(0,dp(height),1); sliderParams.leftMargin=dp(6);
        sliderParams.rightMargin=dp(6); line.addView(slider,sliderParams);
        parent.addView(line,new LayoutParams(-1,dp(height))); return slider;
    }
    private int standardDialogWidth() {
        int screenWidth=getResources().getDisplayMetrics().widthPixels;
        int widthAttribute=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE
                ? android.R.attr.windowMinWidthMajor : android.R.attr.windowMinWidthMinor;
        TypedArray values=getContext().obtainStyledAttributes(new int[]{widthAttribute});
        float themedWidth=values.getFraction(0,screenWidth,screenWidth,0);
        values.recycle();
        if(themedWidth<=0 || themedWidth>screenWidth) themedWidth=screenWidth*.88f;
        return Math.min(Math.round(themedWidth),Math.max(0,screenWidth-dp(32)));
    }
    private static int shapeValueForIndex(int index) {
        switch(index) {
            case 0: return FloatingBubbleAppearance.SHAPE_CIRCLE;
            case 1: return FloatingBubbleAppearance.SHAPE_ROUNDED;
            case 2: return FloatingBubbleAppearance.SHAPE_PILL;
            case 3: return FloatingBubbleAppearance.SHAPE_HEXAGON;
            case 4: return FloatingBubbleAppearance.SHAPE_DIAMOND;
            case 5: return FloatingBubbleAppearance.SHAPE_STAR;
            default: return FloatingBubbleAppearance.SHAPE_HEART;
        }
    }
    private void addGesture(String title,String description) {
        TextView label=text(title,13,INK,true); LayoutParams labelParams=new LayoutParams(-1,dp(36));
        labelParams.topMargin=dp(8); gesture.addView(label,labelParams);
        TextView detail=text(description,12,MUTED,false); detail.setLineSpacing(dp(4),1);
        gesture.addView(detail,new LayoutParams(-1,-2));
    }
    private LinearLayout column() { LinearLayout v=new LinearLayout(getContext()); v.setOrientation(VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v=new LinearLayout(getContext()); v.setOrientation(HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private TextView text(String value,float size,int color,boolean bold) {
        TextView v=new TextView(getContext()); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setIncludeFontPadding(false); v.setGravity(Gravity.CENTER_VERTICAL); if(bold)v.setTypeface(Typeface.DEFAULT_BOLD); return v;
    }
    private TextView button(String value,float size) {
        TextView v=text(value,size,INK,true); v.setGravity(Gravity.CENTER); v.setBackground(background(SOFT,14)); v.setFocusable(true); return v;
    }
    private TextView tab(String value) { TextView v=button(value,16); v.setBackgroundColor(PAGE); return v; }
    private void selected(TextView v,boolean active) { v.setSelected(active); v.setTextColor(active?BLUE:INK); v.setBackground(background(active?PALE_BLUE:0x00000000,14)); }
    private void selectedTab(TextView v,boolean active) { v.setSelected(active); v.setTextColor(active?0xff007aff:0xff5c687d); v.setBackground(new TabBackground(active)); }
    private void addWeighted(LinearLayout parent,View child,int height,int gap) { LayoutParams p=new LayoutParams(0,dp(height),1); p.leftMargin=dp(gap); parent.addView(child,p); }
    private GradientDrawable background(int color,int radius) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private void toast(String value) { Toast.makeText(getContext(),value,Toast.LENGTH_SHORT).show(); }
    private int dp(float value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    @Override protected void onDetachedFromWindow() { if(deleteDialog!=null)deleteDialog.dismiss(); super.onDetachedFromWindow(); }

    private final class TabBackground extends android.graphics.drawable.Drawable {
        private final boolean active; private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        TabBackground(boolean active) { this.active=active; }
        @Override public void draw(Canvas c) { if(active) { paint.setColor(0xff007aff); float center=getBounds().exactCenterX(); c.drawRoundRect(center-dp(29),getBounds().bottom-dp(4),center+dp(29),getBounds().bottom,dp(3),dp(3),paint); } }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
    private final class ShapeBackground extends GradientDrawable {
        ShapeBackground(boolean active) { setColor(active?PALE_BLUE:0x00000000); setCornerRadius(dp(14)); }
    }
    private final class IconStrip extends HorizontalScrollView {
        private final LinearLayout icons;
        IconStrip(Context context) { super(context); setHorizontalScrollBarEnabled(false); icons=row(); addView(icons,new LayoutParams(-2,-1)); }
        void addIcon(View icon) { icons.addView(icon,new LayoutParams(dp(30),-1)); }
        @Override protected void onMeasure(int widthSpec,int heightSpec) {
            int width=Math.max(dp(30),(MeasureSpec.getSize(widthSpec)-dp(10))/6);
            for(int i=0;i<icons.getChildCount();i++) { android.view.ViewGroup.LayoutParams p=icons.getChildAt(i).getLayoutParams(); if(p.width!=width) { p.width=width; icons.getChildAt(i).setLayoutParams(p); } }
            super.onMeasure(widthSpec,heightSpec);
        }
    }
    private final class IconChoice extends View {
        private final int kind; private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private FloatingBubbleAppearance.Config glyphConfig;
        private String glyphSvg;
        private boolean glyphSelected;
        IconChoice(Context context,int kind) { super(context); this.kind=kind; }
        @Override protected void onDraw(Canvas canvas) {
            int color=isSelected()?0xff0b8af7:0xff8994a5;
            float x=getWidth()/2f,y=dp(17);
            if(kind==6 && config.customSvg.isEmpty()) {
                paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1.7f));
                canvas.drawLine(x-dp(9),y,x+dp(9),y,paint); canvas.drawLine(x,y-dp(9),x,y+dp(9),paint);
                paint.setStyle(Paint.Style.FILL); paint.setTextSize(dp(8)); paint.setTextAlign(Paint.Align.CENTER); canvas.drawText("SVG",x,dp(34),paint);
            } else {
                if(glyphConfig==null || glyphSelected!=isSelected() || !config.customSvg.equals(glyphSvg)) {
                    FloatingBubbleAppearance.Builder b=new FloatingBubbleAppearance.Builder(config);
                    b.icon=kind; b.iconPaint=FloatingBubbleAppearance.defaultPaint(color); b.iconSizePercent=100;
                    b.style=FloatingBubbleAppearance.STYLE_SEARCH; b.legacyVisual=false;
                    glyphConfig=b.build(); glyphSvg=config.customSvg; glyphSelected=isSelected();
                }
                float side=dp(kind==5?18:23);
                BubbleAppearanceRenderer.drawIcon(canvas,glyphConfig,new RectF(x-side/2,y-side/2,x+side/2,y+side/2),this);
            }
            if(isSelected()) { paint.setStyle(Paint.Style.FILL); paint.setColor(0xff007aff); canvas.drawRoundRect(x-dp(7),dp(37),x+dp(7),dp(39),dp(2),dp(2),paint); }
        }
    }
    private final class ShapeSymbol extends View {
        private final int kind; private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG); private final Path path=new Path();
        ShapeSymbol(Context context,int kind) { super(context); this.kind=kind; }
        @Override protected void onDraw(Canvas c) {
            c.save(); float scale=Math.min(getWidth(),getHeight())/24f;
            c.translate((getWidth()-24*scale)/2,(getHeight()-24*scale)/2); c.scale(scale,scale);
            paint.setColor(((View)getParent()).isSelected()?BLUE:0xff667185); paint.setStyle(Paint.Style.FILL);
            switch(kind) {
                case 0: c.drawCircle(12,12,9,paint); break;
                case 1: c.drawRoundRect(3,3,21,21,5,5,paint); break;
                case 2: c.drawRoundRect(2,6,22,18,6,6,paint); break;
                case 3: path.reset(); path.moveTo(12,2); path.lineTo(21,7); path.lineTo(21,17); path.lineTo(12,22); path.lineTo(3,17); path.lineTo(3,7); path.close(); c.drawPath(path,paint); break;
                case 4: c.rotate(45,12,12); c.drawRoundRect(4.5f,4.5f,19.5f,19.5f,2,2,paint); break;
                case 5: path.reset(); path.moveTo(12,2.5f); path.lineTo(15,8.6f); path.lineTo(21.7f,9.6f); path.lineTo(16.85f,14.3f); path.lineTo(18,21); path.lineTo(12,17.85f); path.lineTo(6,21); path.lineTo(7.15f,14.3f); path.lineTo(2.3f,9.6f); path.lineTo(9,8.6f); path.close(); c.drawPath(path,paint); break;
                case 6:
                    path.reset(); path.moveTo(12,22);
                    path.cubicTo(10.3f,20.4f,2.5f,14.8f,2.5f,8.4f);
                    path.cubicTo(2.5f,4.3f,5.6f,2f,8.6f,2f);
                    path.cubicTo(10.2f,2f,11.5f,3.1f,12,4.5f);
                    path.cubicTo(12.5f,3.1f,13.8f,2f,15.4f,2f);
                    path.cubicTo(18.4f,2f,21.5f,4.3f,21.5f,8.4f);
                    path.cubicTo(21.5f,14.8f,13.7f,20.4f,12,22);
                    path.close(); c.drawPath(path,paint); break;
                default: break;
            }
            c.restore();
        }
    }
}
