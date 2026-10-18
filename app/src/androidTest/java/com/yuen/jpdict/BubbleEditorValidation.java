package com.yuen.jpdict;

import android.app.Instrumentation;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Real native editor events in the disposable QA application, without production preference writes. */
public final class BubbleEditorValidation {
    private final Instrumentation instrumentation;
    private final Context context;
    private int assertions;
    private BubbleEditorValidation(Instrumentation instrumentation) {
        this.instrumentation=instrumentation; context=instrumentation.getTargetContext();
    }
    public static int run(Instrumentation instrumentation) {
        BubbleEditorValidation test=new BubbleEditorValidation(instrumentation);
        if(!test.context.getPackageName().endsWith(".v18qa"))
            throw new AssertionError("Editor validation requires the disposable .v18qa package");
        test.onMain(test::paintAndState);
        test.onMain(test::freePoints);
        File image=test.createImage();
        try { test.onMain(()->test.imageInput(image.getName())); }
        finally { image.delete(); }
        test.onMain(test::selectionAndLayout);
        return test.assertions;
    }
    /** Called off the main thread after the real Activity is visible and idle. */
    public static int runMounted(Instrumentation instrumentation,Activity activity,
            FloatingBubbleSettingsView page) {
        BubbleEditorValidation test=new BubbleEditorValidation(instrumentation);
        if(!activity.getPackageName().endsWith(".v18qa"))
            throw new AssertionError("Mounted history validation requires the disposable .v18qa package");
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())
            throw new AssertionError("Mounted timer validation must run off the main thread");
        test.mountedHistory(page);
        test.mountedFreePointFocus(page);
        return test.assertions;
    }
    private void mountedFreePointFocus(FloatingBubbleSettingsView page) {
        FloatingBubbleAppearance.Config[] original={null},committed={null};
        Bundle[] editorState={null};
        BubblePaintEditorView[] paint={null};
        BubbleFreeGradientEditorView[] free={null};
        onMain(()->{
            original[0]=page.draft(); committed[0]=FloatingBubbleAppearance.load(context);
            editorState[0]=page.saveDraftState(); paint[0]=field(page,"paintEditor"); free[0]=field(paint[0],"free");
        });
        try {
            for(String inputName:new String[]{"hex","x","y"}) {
                for(String operation:new String[]{"add","delete","reset"}) {
                    onMain(()->setMountedFreeFixture(page,original[0]));
                    instrumentation.waitForIdleSync();
                    onMain(()->{
                        EditText input=field(free[0],inputName);
                        check(input.isAttachedToWindow(),"free focus regression uses a mounted "+inputName+" field");
                        check(input.requestFocus() && input.hasFocus(),"mounted "+inputName+" gains focus before "+operation);
                        input.setText("hex".equals(inputName)?"#112233":"x".equals(inputName)?"61":"62");
                        check(paint[0].isInputValid(),"valid focused "+inputName+" input is accepted before "+operation);
                        if("add".equals(operation))((TextView)field(free[0],"add")).performClick();
                        else if("delete".equals(operation))((TextView)field(free[0],"remove")).performClick();
                        else findText(free[0],"重置").performClick();
                        int expected="add".equals(operation)?5:"delete".equals(operation)?3:4;
                        check(page.draft().backgroundPaint.freePoints.size()==expected,
                                "mounted "+operation+" changes the expected point count after focused "+inputName);
                        checkFreeInputsMatch(page,free[0],operation+" after focused "+inputName);
                        check(paint[0].isInputValid(),"point selection keeps valid input after "+operation);
                        check(page.draft().iconPaint.equals(original[0].iconPaint),"focused free editing keeps independent Icon state");
                    });
                }
            }
            onMain(()->setMountedFreeFixture(page,original[0]));
            instrumentation.waitForIdleSync();
            onMain(()->{
                EditText hex=field(free[0],"hex");
                check(hex.requestFocus() && hex.hasFocus(),"mounted invalid HEX has real input focus");
                int lastValid=page.draft().backgroundPaint.freePoints.get(0).color;
                hex.setText("#GGGGGG"); ((TextView)field(free[0],"add")).performClick();
                check(!paint[0].isInputValid(),"adding another point cannot silently accept focused invalid HEX");
                check(page.draft().backgroundPaint.freePoints.get(0).color==lastValid,
                        "point addition preserves the invalid point's last valid color");
                check("#GGGGGG".equals(free[0].saveEditorState().getString("invalid:background:0:hex")),
                        "point addition retains the old point's invalid input for correction");
                checkFreeInputsMatch(page,free[0],"addition after invalid HEX");
                check(committed[0].matches(FloatingBubbleAppearance.load(context)),
                        "mounted focus regression never commits its fixtures");
            });
        } finally {
            onMain(()->{
                page.finishEditing();
                invoke(page,"applyConfig",new Class<?>[]{FloatingBubbleAppearance.Config.class,boolean.class},original[0],true);
                page.restoreEditorState(editorState[0]);
            });
        }
    }
    private void setMountedFreeFixture(FloatingBubbleSettingsView page,FloatingBubbleAppearance.Config original) {
        FloatingBubbleAppearance.Builder fixture=new FloatingBubbleAppearance.Builder(original);
        fixture.style=FloatingBubbleAppearance.STYLE_SEARCH;
        fixture.backgroundPaint=FloatingBubbleAppearance.defaultPaint(Color.WHITE).withMode(FloatingBubbleAppearance.COLOR_FREE)
                .withPoints(Arrays.asList(new FloatingBubbleAppearance.FreePoint(.14f,.16f,Color.RED,65),
                        new FloatingBubbleAppearance.FreePoint(.84f,.18f,Color.GREEN,65),
                        new FloatingBubbleAppearance.FreePoint(.18f,.83f,Color.BLUE,65),
                        new FloatingBubbleAppearance.FreePoint(.81f,.82f,Color.YELLOW,65)));
        invoke(page,"applyConfig",new Class<?>[]{FloatingBubbleAppearance.Config.class,boolean.class},fixture.build(),true);
    }
    private void checkFreeInputsMatch(FloatingBubbleSettingsView page,BubbleFreeGradientEditorView free,String label) {
        int index=(Integer)invoke(free,"selected",new Class<?>[0]);
        FloatingBubbleAppearance.FreePoint point=page.draft().backgroundPaint.freePoints.get(index);
        EditText hex=field(free,"hex"),x=field(free,"x"),y=field(free,"y");
        check(BubblePaintEditorView.formatHex(point.color).contentEquals(hex.getText()),"selected HEX matches actual point after "+label);
        check(String.valueOf(Math.round(point.x*100)).contentEquals(x.getText()),"selected X matches actual point after "+label);
        check(String.valueOf(Math.round(point.y*100)).contentEquals(y.getText()),"selected Y matches actual point after "+label);
    }
    private void mountedHistory(FloatingBubbleSettingsView page) {
        BubbleStyleHistoryView[] strip={null};
        HorizontalScrollView[] scroll={null};
        ViewGroup[] slots={null};
        List<FloatingBubbleAppearance.Config>[] original=new List[]{null};
        int[] before={0};
        FloatingBubbleAppearance.Config[] draft={null};
        onMain(()->{
            strip[0]=field(page,"savedStyles"); original[0]=field(strip[0],"history");
            check(!original[0].isEmpty(),"mounted history has a committed fixture");
            before[0]=FloatingBubbleAppearance.savedStyles(context).size(); draft[0]=page.draft();
            // Extend only the attached view's fixture, keeping all preference data untouched.
            List<FloatingBubbleAppearance.Config> visible=new ArrayList<>(original[0]);
            while(visible.size()<10) {
                FloatingBubbleAppearance.Builder sample=new FloatingBubbleAppearance.Builder(original[0].get(0));
                sample.presetId="mounted-scroll-fixture-"+visible.size(); visible.add(sample.build());
            }
            setField(strip[0],"history",visible); invoke(strip[0],"render",new Class<?>[0]); strip[0].setDraft(page.draft());
            scroll[0]=field(strip[0],"scroll"); slots[0]=field(strip[0],"slots"); scroll[0].scrollTo(0,0);
        });
        instrumentation.waitForIdleSync();
        try {
            onMain(()->{
                View first=slots[0].getChildAt(0);
                check(first.isAttachedToWindow(),"history timing exercises a mounted native card");
                touch(first,MotionEvent.ACTION_DOWN,first.getWidth()/2f,first.getHeight()/2f);
            });
            SystemClock.sleep(180);
            onMain(()->check(!dialogShowing(page),"long press cannot open delete before 560 milliseconds"));
            SystemClock.sleep(460); instrumentation.waitForIdleSync();
            AlertDialog[] opened={null};
            onMain(()->{
                opened[0]=field(page,"deleteDialog");
                check(opened[0]!=null && opened[0].isShowing(),"560 millisecond long press opens native confirmation");
                View first=slots[0].getChildAt(0);
                touch(first,MotionEvent.ACTION_UP,first.getWidth()/2f,first.getHeight()/2f);
            });
            SystemClock.sleep(120);
            onMain(()->{
                check(opened[0]==field(page,"deleteDialog"),"held press creates only one confirmation");
                View cancel=findText(opened[0].getWindow().getDecorView(),"取消");
                check(cancel!=null,"delete confirmation exposes cancellation"); cancel.performClick();
                check(!opened[0].isShowing(),"cancel dismisses deletion confirmation");
                check(FloatingBubbleAppearance.savedStyles(context).size()==before[0],"cancel cannot delete or add history");
                check(page.draft().presetId.equals(draft[0].presetId),"long press release cannot silently select another card");
            });
            instrumentation.waitForIdleSync();
            onMain(()->{
                View first=slots[0].getChildAt(0);
                float x=first.getLeft()+first.getWidth()/2f,y=first.getTop()+first.getHeight()/2f;
                touch(scroll[0],MotionEvent.ACTION_DOWN,x,y);
                touch(scroll[0],MotionEvent.ACTION_MOVE,x-dp(25),y);
                touch(scroll[0],MotionEvent.ACTION_MOVE,x-dp(90),y);
                touch(scroll[0],MotionEvent.ACTION_UP,x-dp(90),y);
            });
            SystemClock.sleep(640); instrumentation.waitForIdleSync();
            onMain(()->{
                check(!dialogShowing(page),"native horizontal drag cancels the deletion timer");
                check(scroll[0].getScrollX()>0,"horizontal drag actually scrolls the overflow fixture");
                scroll[0].scrollTo(0,0);
                View first=slots[0].getChildAt(0);
                touch(first,MotionEvent.ACTION_DOWN,first.getWidth()/2f,first.getHeight()/2f);
                touch(first,MotionEvent.ACTION_CANCEL,first.getWidth()/2f,first.getHeight()/2f);
            });
            SystemClock.sleep(640); instrumentation.waitForIdleSync();
            onMain(()->{
                check(!dialogShowing(page),"ACTION_CANCEL cannot leave a deletion timer running");
                check(FloatingBubbleAppearance.savedStyles(context).size()==before[0],"all mounted gestures leave preferences intact");
            });
        } finally {
            onMain(()->{
                AlertDialog dialog=field(page,"deleteDialog"); if(dialog!=null)dialog.dismiss();
                setField(strip[0],"history",original[0]); invoke(strip[0],"render",new Class<?>[0]);
                strip[0].setDraft(page.draft()); scroll[0].scrollTo(0,0);
            });
        }
    }
    private static boolean dialogShowing(FloatingBubbleSettingsView page) {
        AlertDialog dialog=field(page,"deleteDialog"); return dialog!=null && dialog.isShowing();
    }
    private int dp(float value) { return Math.round(value*context.getResources().getDisplayMetrics().density); }
    private void onMain(Runnable task) {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        instrumentation.runOnMainSync(()->{ try { task.run(); } catch(Throwable error) { failure.set(error); } });
        if(failure.get()!=null)throw new AssertionError("Native editor validation failed",failure.get());
    }
    private void check(boolean condition,String message) {
        assertions++; if(!condition)throw new AssertionError(message);
    }
    private void close(float actual,float expected,float tolerance,String message) {
        check(Math.abs(actual-expected)<=tolerance,message+" expected="+expected+" actual="+actual);
    }
    private void paintAndState() {
        FloatingBubbleAppearance.Builder base=new FloatingBubbleAppearance.Builder();
        base.backgroundPaint=FloatingBubbleAppearance.defaultPaint(0xff112233);
        base.iconPaint=FloatingBubbleAppearance.defaultPaint(0xffaabbcc);
        FloatingBubbleAppearance.Config[] state={base.build()};
        BubblePaintEditorView editor=new BubblePaintEditorView(context);
        editor.setConfig(state[0]); editor.setListener(c->{ state[0]=c; editor.setConfig(c); });
        measure(editor,180);
        TextView[] modes=field(editor,"modes");
        EditText solid=field(editor,"solidHex"),start=field(editor,"startHex"),end=field(editor,"endHex");
        solid.setText("#102030");
        check(state[0].backgroundPaint.solid==0xff102030,"background HEX updates background");
        check(state[0].iconPaint.solid==0xffaabbcc,"background edit keeps Icon paint");
        View preview=field(editor,"preview");
        touch(preview,MotionEvent.ACTION_DOWN,preview.getWidth()/2f,preview.getHeight()/2f);
        touch(preview,MotionEvent.ACTION_UP,preview.getWidth()/2f,preview.getHeight()/2f);
        check("icon".equals(field(editor,"target")),"center preview tap selects Icon paint");
        modes[1].performClick(); start.setText("#345678"); end.setText("#876543");
        check(state[0].iconPaint.start==0xff345678 && state[0].iconPaint.end==0xff876543,"Icon linear stops are independent");
        check(state[0].backgroundPaint.solid==0xff102030,"Icon gradient keeps background solid");
        modes[2].performClick();
        BubbleFreeGradientEditorView free=field(editor,"free");
        EditText pointHex=field(free,"hex"); pointHex.setText("#435261");
        check(state[0].iconPaint.freePoints.get(0).color==0xff435261,"Icon free point updates only Icon");
        modes[0].performClick();
        check(state[0].iconPaint.solid==0xffaabbcc,"mode switch restores Icon solid parameter");
        modes[1].performClick();
        check(state[0].iconPaint.start==0xff345678,"mode switch preserves linear stops");
        modes[2].performClick();
        check(state[0].iconPaint.freePoints.get(0).color==0xff435261,"mode switch preserves free point");
        touch(preview,MotionEvent.ACTION_DOWN,1,preview.getHeight()/2f);
        touch(preview,MotionEvent.ACTION_UP,1,preview.getHeight()/2f);
        check("background".equals(field(editor,"target")),"preview edge tap selects background paint");
        check(state[0].backgroundPaint.mode==0 && modes[0].isSelected(),"target restores background mode");
        solid.setText("#GGGGGG");
        check(!editor.isInputValid(),"invalid HEX rejects configuration");
        check(state[0].backgroundPaint.solid==0xff102030,"invalid HEX keeps last valid paint");
        modes[2].performClick(); editor.selectIconTarget(); modes[1].performClick();
        check(!editor.isInputValid(),"target and mode changes cannot clear invalid background HEX");
        Bundle pending=editor.saveEditorState();
        BubblePaintEditorView rotated=new BubblePaintEditorView(context); rotated.setConfig(state[0]);
        rotated.restoreEditorState(pending);
        check(!rotated.isInputValid(),"recreation preserves hidden invalid HEX");
        invoke(rotated,"selectTarget",new Class<?>[]{String.class},"background");
        TextView[] restoredModes=field(rotated,"modes"); restoredModes[0].performClick();
        EditText restoredSolid=field(rotated,"solidHex");
        check("#GGGGGG".equals(restoredSolid.getText().toString()),"invalid input text restored for its own target");
        restoredSolid.setText("#ABC");
        check(rotated.isInputValid(),"valid short HEX replaces invalid draft");
        FloatingBubbleAppearance.Config restored=field(rotated,"config");
        check(restored.backgroundPaint.solid==0xffaabbcc,"three-digit HEX expands bidirectionally");
        FloatingBubbleSettingsView page=new FloatingBubbleSettingsView(context,state[0],null,null,null);
        BubblePaintEditorView pagePaint=field(page,"paintEditor");
        EditText pageHex=field(pagePaint,"solidHex"); pageHex.setText("bad!");
        check(!(Boolean)invoke(page,"validateDraft",new Class<?>[0]),"page save validation rejects invalid HEX");
        Bundle pageState=page.saveDraftState();
        FloatingBubbleSettingsView recreated=new FloatingBubbleSettingsView(context,
                FloatingBubbleAppearance.fromBundle(pageState),null,null,null);
        recreated.restoreEditorState(pageState);
        check(!(Boolean)invoke(recreated,"validateDraft",new Class<?>[0]),"page recreation still blocks invalid save");
    }
    private void freePoints() {
        List<FloatingBubbleAppearance.FreePoint> initial=Arrays.asList(
                new FloatingBubbleAppearance.FreePoint(.1f,.1f,Color.RED,10),
                new FloatingBubbleAppearance.FreePoint(.9f,.9f,Color.BLUE,100));
        FloatingBubbleAppearance.ColorConfig[] paint={new FloatingBubbleAppearance.ColorConfig(2,
                Color.WHITE,Color.RED,Color.BLUE,135,initial)};
        BubbleFreeGradientEditorView editor=new BubbleFreeGradientEditorView(context,(title,color,result)->{});
        editor.setPaint(paint[0],"background"); editor.setListener(p->{paint[0]=p;editor.setPaint(p,"background");});
        measure(editor,180);
        TextView add=field(editor,"add"),remove=field(editor,"remove");
        check(!remove.isEnabled(),"free gradient minimum is two points");
        remove.performClick(); check(paint[0].freePoints.size()==2,"minimum deletion is harmless");
        for(int i=0;i<7;i++)add.performClick();
        check(paint[0].freePoints.size()==8 && !add.isEnabled(),"free gradient maximum is eight points");
        add.performClick(); check(paint[0].freePoints.size()==8,"eighth-point boundary blocks another add");
        for(int i=0;i<7;i++)remove.performClick();
        check(paint[0].freePoints.size()==2 && !remove.isEnabled(),"repeated deletion stops at two");
        View board=field(editor,"board");
        FloatingBubbleAppearance.FreePoint selected=paint[0].freePoints.get(1);
        touch(board,MotionEvent.ACTION_DOWN,selected.x*board.getWidth(),selected.y*board.getHeight());
        for(int i=0;i<50;i++)touch(board,MotionEvent.ACTION_MOVE,board.getWidth()*i/49f,board.getHeight()*(49-i)/49f);
        touch(board,MotionEvent.ACTION_MOVE,-10,board.getHeight()+10);
        touch(board,MotionEvent.ACTION_UP,-10,board.getHeight()+10);
        check(paint[0].freePoints.get(1).x==0 && paint[0].freePoints.get(1).y==1,"rapid point drag clamps both edges");
        EditText hex=field(editor,"hex"),x=field(editor,"x"); hex.setText("#nothex");
        check(!editor.isInputValid(),"invalid free HEX prevents submit");
        editor.setPaint(FloatingBubbleAppearance.defaultPaint(Color.GREEN),"icon");
        check(!editor.isInputValid(),"switching target preserves invalid free HEX");
        Bundle pending=editor.saveEditorState();
        BubbleFreeGradientEditorView rotated=new BubbleFreeGradientEditorView(context,(title,color,result)->{});
        rotated.setPaint(paint[0],"background"); rotated.restoreEditorState(pending);
        check(!rotated.isInputValid(),"free recreation retains invalid value");
        TextView title=field(rotated,"title"); check(title.getText().toString().endsWith("2"),"selected free point restored");
        EditText rotatedHex=field(rotated,"hex"); rotatedHex.setText("#12AB34");
        check(rotated.isInputValid(),"correcting pending point HEX permits submit");
        editor.setPaint(paint[0],"background"); hex.setText("#12AB34"); x.setText("NaN");
        check(!editor.isInputValid(),"non-numeric point location rejected");
        x.setText("100"); check(editor.isInputValid(),"valid point location clears location error");
        checkPointDeletionPreservesInvalid();
    }
    private void checkPointDeletionPreservesInvalid() {
        FloatingBubbleAppearance.ColorConfig[] paint={new FloatingBubbleAppearance.ColorConfig(2,
                Color.WHITE,Color.RED,Color.BLUE,135,Arrays.asList(
                new FloatingBubbleAppearance.FreePoint(.1f,.1f,Color.RED,65),
                new FloatingBubbleAppearance.FreePoint(.5f,.5f,Color.GREEN,65),
                new FloatingBubbleAppearance.FreePoint(.9f,.9f,Color.BLUE,65)))};
        BubbleFreeGradientEditorView editor=new BubbleFreeGradientEditorView(context,(title,color,result)->{});
        editor.setPaint(paint[0],"background"); editor.setListener(p->{paint[0]=p;editor.setPaint(p,"background");});
        measure(editor,180); View board=field(editor,"board"); EditText hex=field(editor,"hex");
        hex.setText("#GGGGGG");
        touch(board,MotionEvent.ACTION_DOWN,board.getWidth()*.5f,board.getHeight()*.5f);
        touch(board,MotionEvent.ACTION_UP,board.getWidth()*.5f,board.getHeight()*.5f);
        ((TextView)field(editor,"remove")).performClick();
        check(paint[0].freePoints.size()==2 && !editor.isInputValid(),"deleting another point cannot erase an invalid first point");
        touch(board,MotionEvent.ACTION_DOWN,board.getWidth()*.1f,board.getHeight()*.1f);
        touch(board,MotionEvent.ACTION_UP,board.getWidth()*.1f,board.getHeight()*.1f);
        check("#GGGGGG".equals(hex.getText().toString()),"remaining invalid point restores its own pending input");
        hex.setText("#010203"); check(editor.isInputValid(),"correcting remaining invalid point restores validity");
    }
    private void imageInput(String name) {
        FloatingBubbleAppearance.Builder b=new FloatingBubbleAppearance.Builder(); b.imageName=name;
        b.style=FloatingBubbleAppearance.STYLE_IMAGE; b.shape=FloatingBubbleAppearance.SHAPE_CIRCLE;
        FloatingBubbleAppearance.Config[] state={b.build()};
        BubbleImageEditorView editor=new BubbleImageEditorView(context); editor.setConfig(state[0]);
        editor.setListener(c->{state[0]=c;editor.setConfig(c);}); measure(editor,200);
        View crop=field(editor,"crop"); float w=crop.getWidth(),h=crop.getHeight();
        touch(crop,MotionEvent.ACTION_DOWN,w*.25f,h*.25f);
        touch(crop,MotionEvent.ACTION_MOVE,w*.5f,h*.75f);
        touch(crop,MotionEvent.ACTION_UP,w*.5f,h*.75f);
        close(state[0].imageXPercent,25,1,"single-finger X uses workspace width");
        close(state[0].imageYPercent,50,1,"single-finger Y uses workspace height");
        findText(editor,"居中重置").performClick();
        check(state[0].imageZoomPercent==100 && state[0].imageXPercent==0 && state[0].imageYPercent==0,"reset restores HTML defaults");
        multi(crop,MotionEvent.ACTION_DOWN,new float[]{w*.25f},new float[]{h*.25f});
        multi(crop,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new float[]{w*.25f,w*.55f},new float[]{h*.25f,h*.25f});
        multi(crop,MotionEvent.ACTION_MOVE,new float[]{w*.10f,w*.70f},new float[]{h*.25f,h*.25f});
        close(state[0].imageZoomPercent,200,1,"pinch doubles distance");
        close(state[0].imageXPercent,10,1,"pinch compensates horizontal gesture center");
        close(state[0].imageYPercent,25,1,"pinch compensates vertical gesture center");
        multi(crop,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                new float[]{w*.10f,w*.70f},new float[]{h*.25f,h*.25f});
        multi(crop,MotionEvent.ACTION_MOVE,new float[]{w*.20f},new float[]{h*.25f});
        close(state[0].imageXPercent,20,1,"remaining pointer continues without jumping");
        close(state[0].imageYPercent,25,1,"remaining pointer retains vertical state");
        multi(crop,MotionEvent.ACTION_CANCEL,new float[]{w*.20f},new float[]{h*.25f});
        SeekBar zoom=field(editor,"zoom"),horizontal=field(editor,"horizontal"),vertical=field(editor,"vertical");
        seekEnd(zoom,true); close(state[0].imageZoomPercent,300,1,"slider reaches image 300 percent");
        seekEnd(zoom,false); close(state[0].imageZoomPercent,20,1,"slider reaches image 20 percent");
        seekEnd(horizontal,false); close(state[0].imageXPercent,-100,1,"X slider reaches negative endpoint");
        seekEnd(vertical,true); close(state[0].imageYPercent,100,1,"Y slider reaches positive endpoint");
        findText(editor,"居中重置").performClick();
        check(zoom.getProgress()==80 && horizontal.getProgress()==100 && vertical.getProgress()==100,"reset synchronizes all sliders");
        Bitmap rendered=Bitmap.createBitmap(crop.getWidth(),crop.getHeight(),Bitmap.Config.ARGB_8888);
        crop.draw(new Canvas(rendered));
        check(Color.alpha(rendered.getPixel(0,0))==0,"circle clips image workspace corners");
        check(Color.alpha(rendered.getPixel(rendered.getWidth()/2,rendered.getHeight()/2))>0,"image workspace center remains visible");
        rendered.recycle();
    }
    private void selectionAndLayout() {
        FloatingBubbleAppearance.Builder a=new FloatingBubbleAppearance.Builder(); a.presetId="ui-preset-one";
        FloatingBubbleAppearance.Builder b=new FloatingBubbleAppearance.Builder(a.build()); b.presetId="ui-preset-two";
        List<FloatingBubbleAppearance.Config> history=new ArrayList<>(); history.add(a.build()); history.add(b.build());
        FloatingBubbleAppearance.Config[] selected={null};
        BubbleStyleHistoryView strip=new BubbleStyleHistoryView(context,new BubbleStyleHistoryView.Listener() {
            @Override public void onSelected(FloatingBubbleAppearance.Config config) { selected[0]=config; }
            @Override public void onAdd() { }
            @Override public void onDeleteRequested(String id) { throw new AssertionError("quick movement must cancel deletion"); }
        });
        setField(strip,"history",history); invoke(strip,"render",new Class<?>[0]); measure(strip,320);
        ViewGroup slots=field(strip,"slots"); slots.getChildAt(1).performClick();
        check("ui-preset-two".equals(selected[0].presetId),"equal-looking style selects independent ID");
        strip.setDraft(selected[0]);
        check(!slots.getChildAt(0).isSelected() && slots.getChildAt(1).isSelected(),"equal values do not select two cards");
        FloatingBubbleAppearance.Builder edited=new FloatingBubbleAppearance.Builder(selected[0]);
        edited.backgroundPaint=FloatingBubbleAppearance.defaultPaint(Color.RED); strip.setDraft(edited.build());
        check(slots.getChildAt(1).isSelected(),"editing retains selected identity");
        View addSymbol=((ViewGroup)slots.getChildAt(slots.getChildCount()-1)).getChildAt(0);
        Bitmap plus=Bitmap.createBitmap(addSymbol.getWidth(),addSymbol.getHeight(),Bitmap.Config.ARGB_8888);
        addSymbol.draw(new Canvas(plus));
        int minX=plus.getWidth(),minY=plus.getHeight(),maxX=-1,maxY=-1;
        for(int y=0;y<plus.getHeight();y++)for(int x=0;x<plus.getWidth();x++)
            if((plus.getPixel(x,y)&0xffffff)==(AppTheme.ink(AppTheme.MUTED)&0xffffff) && Color.alpha(plus.getPixel(x,y))>200) {
                minX=Math.min(minX,x); maxX=Math.max(maxX,x); minY=Math.min(minY,y); maxY=Math.max(maxY,y);
            }
        check(maxX>=minX && maxY>=minY,"native plus has a visible Canvas symbol");
        check(Math.abs(minX+maxX-(plus.getWidth()-1))<=1
                && Math.abs(minY+maxY-(plus.getHeight()-1))<=1,
                "native plus stays geometrically centered in its dashed circle");
        plus.recycle();
        FloatingBubbleSettingsView page=new FloatingBubbleSettingsView(context,edited.build(),null,null,null);
        BubblePaintEditorView paintEditor=field(page,"paintEditor");
        TextView[] paintModes=field(paintEditor,"modes");
        for(int width:new int[]{320,393,600}) {
            measure(page,width); measure(page.stickyHeader(),width);
            ViewGroup editor=field(page,"editor");
            check(editor.getRight()<=page.getMeasuredWidth(),"editor outer bounds fit "+width+" dp");
            check(editor.getChildCount()==3 && editor.getChildAt(0).getWidth()>0,"dual-column structure retained at "+width+" dp");
            View sidebar=field(page,"shapeSidebar"); page.onPageScrolled(10000);
            check(sidebar.getTranslationY()<=Math.max(0,editor.getHeight()-sidebar.getHeight()),"sticky shapes stay inside editor at "+width+" dp");
            android.widget.LinearLayout[] shapes=field(page,"shapeButtons");
            shapes[1].performClick(); measure(page,width);
            View firstGlyph=shapes[0].getChildAt(0); TextView firstName=(TextView)shapes[0].getChildAt(1);
            for(int shape=0;shape<shapes.length;shape++) {
                View symbol=shapes[shape].getChildAt(0); TextView name=(TextView)shapes[shape].getChildAt(1);
                close((symbol.getLeft()+name.getRight())/2f,shapes[shape].getWidth()/2f,1,
                        "fixed shape columns share the row center at "+width+" dp shape="+shape);
                check(symbol.getLeft()==firstGlyph.getLeft()
                                && symbol.getLeft()+symbol.getWidth()/2f==firstGlyph.getLeft()+firstGlyph.getWidth()/2f,
                        "all shape glyphs share one horizontal column at "+width+" dp shape="+shape);
                check(name.getLeft()==firstName.getLeft() && name.getWidth()==firstName.getWidth(),
                        "all shape names share one fixed text column at "+width+" dp shape="+shape);
                check(symbol.getLeft()>=shapes[shape].getPaddingLeft()
                                && name.getRight()<=shapes[shape].getWidth()-shapes[shape].getPaddingRight(),
                        "centered shape group stays within the sidebar at "+width+" dp shape="+shape);
                check(name.getLayout()!=null && name.getLineCount()==1
                                && name.getLayout().getLineWidth(0)<=name.getWidth()+1,
                        "complete shape name is visible on one line at "+width+" dp shape="+shape);
                check(Math.abs(name.getLayout().getLineLeft(0))<=1,
                        "shape names align to the start of their fixed column at "+width+" dp shape="+shape);
            }
            for(int mode:new int[]{FloatingBubbleAppearance.COLOR_LINEAR,FloatingBubbleAppearance.COLOR_FREE}) {
                paintModes[mode].performClick();
                FloatingBubbleAppearance.Config beforeMeasure=page.draft(); measure(page,width);
                check(page.draft()==beforeMeasure,"gradient layout cannot change draft state at "+width+" dp");
                ViewGroup row=field(paintEditor,"gradientPresets");
                HorizontalScrollView scroll=(HorizontalScrollView)row.getParent(); scroll.scrollTo(0,0);
                int right=scroll.getWidth()-scroll.getPaddingRight();
                check(row.getChildCount()==6,"all six gradient presets remain available at "+width+" dp mode="+mode);
                check(row.getLeft()+row.getChildAt(2).getRight()<=right,
                        "first three gradient presets fit completely at "+width+" dp mode="+mode);
                check(row.getLeft()+row.getChildAt(3).getRight()>right && row.getWidth()>scroll.getWidth(),
                        "fourth gradient preset uses local horizontal scrolling at "+width+" dp mode="+mode);
                int swatchWidth=row.getChildAt(0).getWidth(),top=row.getChildAt(0).getTop();
                int expected=(scroll.getWidth()-scroll.getPaddingLeft()-scroll.getPaddingRight()
                        -row.getPaddingLeft()-row.getPaddingRight()-2*dp(8))/3;
                check(swatchWidth==expected,"gradient preset width is responsive at "+width+" dp mode="+mode);
                for(int index=1;index<6;index++)
                    check(row.getChildAt(index).getTop()==top && row.getChildAt(index).getWidth()==swatchWidth,
                            "gradient presets share one row and equal widths at "+width+" dp mode="+mode+" index="+index);
            }
            paintModes[FloatingBubbleAppearance.COLOR_SOLID].performClick(); measure(page,width);
            ViewGroup solids=field(paintEditor,"solidPresets");
            check(solids.getChildCount()==6 && solids.getChildAt(0).getWidth()==dp(51),
                    "solid preset sizing remains unchanged at "+width+" dp");
        }
        TextView[] modes=field(page,"modeButtons"); modes[2].performClick();
        View image=field(page,"imageFields"),paint=field(page,"paintEditor"),icons=field(page,"iconStrip");
        check(image.getVisibility()==View.VISIBLE && paint.getVisibility()==View.GONE && icons.getVisibility()==View.GONE,"image mode hides all unrelated paint and icons");
        modes[1].performClick();
        View text=field(page,"textFields"); check(text.getVisibility()==View.VISIBLE && image.getVisibility()==View.GONE,"text mode shows only text editor");
        modes[0].performClick(); check(icons.getVisibility()==View.VISIBLE && text.getVisibility()==View.GONE,"default mode restores icon editor");
        TextView gestures=field(page,"gestureTab"); gestures.performClick();
        check(page.handleBack() && !page.handleBack(),"gesture page returns to appearance before page exits");
    }
    private File createImage() {
        File file=new File(context.getFilesDir(),"floating-bubble-image-editor-validation.png");
        Bitmap image=Bitmap.createBitmap(80,40,Bitmap.Config.ARGB_8888);
        image.eraseColor(Color.RED);
        try(FileOutputStream output=new FileOutputStream(file)) {
            if(!image.compress(Bitmap.CompressFormat.PNG,100,output))throw new AssertionError("test image write failed");
        } catch(Exception error) { throw new AssertionError(error); }
        finally { image.recycle(); }
        return file;
    }
    private void measure(View view,int widthDp) {
        int width=Math.round(widthDp*context.getResources().getDisplayMetrics().density);
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        view.layout(0,0,view.getMeasuredWidth(),view.getMeasuredHeight());
    }
    private void seekEnd(SeekBar bar,boolean maximum) {
        float x=maximum?bar.getWidth()-1:1,y=bar.getHeight()/2f;
        touch(bar,MotionEvent.ACTION_DOWN,x,y); touch(bar,MotionEvent.ACTION_MOVE,x,y); touch(bar,MotionEvent.ACTION_UP,x,y);
    }
    private static void touch(View view,int action,float x,float y) {
        long time=SystemClock.uptimeMillis(); MotionEvent event=MotionEvent.obtain(time,time,action,x,y,0);
        try { view.dispatchTouchEvent(event); } finally { event.recycle(); }
    }
    private static void multi(View view,int action,float[] x,float[] y) {
        MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[x.length];
        MotionEvent.PointerCoords[] coordinates=new MotionEvent.PointerCoords[x.length];
        for(int i=0;i<x.length;i++) {
            properties[i]=new MotionEvent.PointerProperties(); properties[i].id=i; properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i]=new MotionEvent.PointerCoords(); coordinates[i].x=x[i];coordinates[i].y=y[i];coordinates[i].pressure=1;coordinates[i].size=1;
        }
        long time=SystemClock.uptimeMillis();
        MotionEvent event=MotionEvent.obtain(time,time,action,x.length,properties,coordinates,0,0,1,1,0,0,0,0);
        try { view.dispatchTouchEvent(event); } finally { event.recycle(); }
    }
    private static View findText(View view,String text) {
        if(view instanceof TextView && text.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++) {
            View found=findText(((ViewGroup)view).getChildAt(i),text); if(found!=null)return found;
        }
        return null;
    }
    @SuppressWarnings("unchecked") private static <T>T field(Object owner,String name) {
        try { Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return (T)field.get(owner); }
        catch(Exception error) { throw new AssertionError(error); }
    }
    private static void setField(Object owner,String name,Object value) {
        try { Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value); }
        catch(Exception error) { throw new AssertionError(error); }
    }
    private static Object invoke(Object owner,String name,Class<?>[] parameters,Object...arguments) {
        try { Method method=owner.getClass().getDeclaredMethod(name,parameters);method.setAccessible(true);return method.invoke(owner,arguments); }
        catch(Exception error) { throw new AssertionError(error); }
    }
}
