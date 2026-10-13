package com.yuen.jpdict;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Real compiled value classes tested on the JDK; Android persistence/rendering has device tests. */
public final class FloatingBubbleAppearanceTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check "+checks); }
    public static void main(String[] args) {
        FloatingBubbleAppearance.Config defaults=FloatingBubbleAppearance.defaults();
        check(defaults.sizeDp==44 && defaults.opacityPercent==25 && defaults.roundnessPercent==100 && defaults.legacyVisual);
        FloatingBubbleAppearance.Config bounds=new FloatingBubbleAppearance.Config(-1,-10,-1,120,"",0x168af2);
        check(bounds.sizeDp==24 && bounds.opacityPercent==0 && bounds.roundnessPercent==100 && bounds.style==0);
        check(bounds.color==0xff168af2 && bounds.text.equals("あ"));
        FloatingBubbleAppearance.Config max=new FloatingBubbleAppearance.Config(999,999,2,-1,"😀😁😂😃😄",0);
        check(max.sizeDp==100 && max.opacityPercent==100 && max.roundnessPercent==0 && max.text.codePointCount(0,max.text.length())==4);
        check(defaults.matches(new FloatingBubbleAppearance.Config(44,25,0,100,"あ",0xff168af2)));
        FloatingBubbleAppearance.Builder unicode=new FloatingBubbleAppearance.Builder(); unicode.text="😀😁😂😃😄😅😆😉😊";
        check(unicode.build().text.codePointCount(0,unicode.build().text.length())==8);
        FloatingBubbleAppearance.Builder filename=new FloatingBubbleAppearance.Builder();
        filename.customSvgName="日本\n中文\t\u0000\u0085\u202e\u2028图标.SVG";
        check(filename.build().customSvgName.equals("日本中文图标.SVG"));
        StringBuilder longName=new StringBuilder(); for (int i=0;i<140;i++) longName.append("😀");
        filename.customSvgName=longName+".svg";
        String limitedName=filename.build().customSvgName;
        check(limitedName.codePointCount(0,limitedName.length())==128 && limitedName.endsWith(".svg"));
        check(limitedName.substring(0,limitedName.length()-4).codePointCount(0,limitedName.length()-4)==124);
        filename.customSvgName=longName.toString();
        check(filename.build().customSvgName.codePointCount(0,filename.build().customSvgName.length())==128);
        filename.customSvgName=null; check(filename.build().customSvgName.isEmpty());
        filename.customSvgName="合法名称😀.svg"; check(filename.build().customSvgName.equals(filename.customSvgName));
        for (String label:new String[]{"中文追加","日本語追加","Search","😀😁更多"}) {
            FloatingBubbleAppearance.Builder textStyle=new FloatingBubbleAppearance.Builder(); textStyle.style=FloatingBubbleAppearance.STYLE_CUSTOM; textStyle.text=label;
            FloatingBubbleAppearance.Config textConfig=textStyle.build();
            check(textConfig.text.equals(label));
            check(BubbleAppearanceRenderer.displayText(textConfig).equals(label.substring(0,label.offsetByCodePoints(0,2))));
            textStyle.legacyVisual=true; check(BubbleAppearanceRenderer.displayText(textStyle.build()).equals(label));
        }
        FloatingBubbleAppearance.Config imageA=new FloatingBubbleAppearance.Config(44,25,3,100,"a",0,"floating-bubble-image-123.png");
        FloatingBubbleAppearance.Config imageB=new FloatingBubbleAppearance.Config(44,25,3,100,"a",0,"floating-bubble-image-456.png");
        check(!imageA.matches(imageB));
        check(new FloatingBubbleAppearance.Config(44,25,3,100,"a",0,"../../elsewhere").imageName.equals("floating-bubble-image.png"));
        FloatingBubbleAppearance.Builder noImage=new FloatingBubbleAppearance.Builder(imageA); noImage.imageName="";
        check(noImage.build().imageName.isEmpty());
        FloatingBubbleAppearance.Builder transform=new FloatingBubbleAppearance.Builder(imageA);
        transform.imageZoomPercent=Float.NaN; transform.imageXPercent=1000; transform.imageYPercent=-1000;
        FloatingBubbleAppearance.Config transformed=transform.build();
        check(transformed.imageZoomPercent==100 && transformed.imageXPercent==100 && transformed.imageYPercent==-100);
        for (int shape=0;shape<=6;shape++) {
            FloatingBubbleAppearance.Builder b=new FloatingBubbleAppearance.Builder(); b.shape=shape;
            check(b.build().shape==shape);
        }
        for (int icon=0;icon<=6;icon++) {
            FloatingBubbleAppearance.Builder b=new FloatingBubbleAppearance.Builder(); b.icon=icon;
            check(b.build().icon==icon);
        }
        List<FloatingBubbleAppearance.Config> history=new ArrayList<>();
        for (int i=0;i<10;i++) history=FloatingBubbleAppearance.addedStyle(history,defaults);
        check(history.size()==10);
        check(FloatingBubbleAppearance.addedStyle(history,defaults)==null);
        for (int i=0;i<history.size();i++) for (int j=0;j<i;j++) check(!history.get(i).presetId.equals(history.get(j).presetId));
        String id=history.get(5).presetId; FloatingBubbleAppearance.Builder edited=new FloatingBubbleAppearance.Builder(history.get(5));
        edited.shape=FloatingBubbleAppearance.SHAPE_STAR; edited.style=FloatingBubbleAppearance.STYLE_SEARCH;
        List<FloatingBubbleAppearance.Config> updated=FloatingBubbleAppearance.replacedStyle(history,id,edited.build());
        check(updated.size()==10 && updated.get(5).presetId.equals(id) && updated.get(5).shape==FloatingBubbleAppearance.SHAPE_STAR);
        for (int i=0;i<updated.size();i++) check(history.get(i).presetId.equals(updated.get(i).presetId));
        for (int i=0;i<25;i++) updated=FloatingBubbleAppearance.replacedStyle(updated,id,edited.build());
        check(updated.size()==10 && updated.get(5).presetId.equals(id));
        check(history.get(5).shape==FloatingBubbleAppearance.SHAPE_CIRCLE);
        check(FloatingBubbleAppearance.replacedStyle(history,"missing",defaults)==null);
        FloatingBubbleAppearance.ColorConfig background=FloatingBubbleAppearance.defaultPaint(0xff123456);
        FloatingBubbleAppearance.ColorConfig icon=FloatingBubbleAppearance.defaultPaint(0xffffffff);
        List<FloatingBubbleAppearance.FreePoint> mutable=FloatingBubbleAppearance.defaultFreePoints();
        FloatingBubbleAppearance.ColorConfig copied=new FloatingBubbleAppearance.ColorConfig(2,0,1,2,-45,mutable);
        mutable.clear(); check(copied.freePoints.size()==4 && copied.angle==315);
        try { copied.freePoints.clear(); throw new AssertionError("mutable points"); }
        catch (UnsupportedOperationException expected) { checks++; }
        FloatingBubbleAppearance.Builder paintDraft=new FloatingBubbleAppearance.Builder(); paintDraft.backgroundPaint=background; paintDraft.iconPaint=icon;
        paintDraft.iconPaint=icon.withMode(2).withAngle(213).withSolid(0xffddeeff);
        FloatingBubbleAppearance.Config paints=paintDraft.build();
        check(paints.backgroundPaint.equals(background) && paints.iconPaint.mode==2 && paints.iconPaint.solid==0xffddeeff);
        check(paints.iconPaint.withMode(0).withMode(1).withMode(2).freePoints.equals(paints.iconPaint.freePoints));
        check(paints.iconPaint.withMode(1).angle==213 && paints.backgroundPaint.mode==0);
        FloatingBubbleAppearance.ColorConfig invalid=new FloatingBubbleAppearance.ColorConfig(99,0,0,0,Float.POSITIVE_INFINITY,new ArrayList<>());
        check(invalid.mode==0 && invalid.angle==135 && invalid.freePoints.size()==4);
        FloatingBubbleAppearance.FreePoint constrained=new FloatingBubbleAppearance.FreePoint(Float.NaN,2,0,-5);
        check(constrained.x==.5f && constrained.y==1 && constrained.radius==10 && constrained.color==0xff000000);
        List<FloatingBubbleAppearance.FreePoint> pair=new ArrayList<>();
        pair.add(new FloatingBubbleAppearance.FreePoint(0,.5f,0xffff0000,65)); pair.add(new FloatingBubbleAppearance.FreePoint(1,.5f,0xff0000ff,65));
        int[] field=BubbleColorField.pixels(pair,3,3);
        check(field[4]==0xff800080 && field[3]!=field[5] && field[0]==field[6] && field[2]==field[8]);
        int[] rectangle=BubbleColorField.pixels(pair,5,3); check(rectangle[7]==0xff800080);
        try { BubbleColorField.pixels(pair,300,3); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { checks++; }
        failedCommits();
        Object owner=new Object(),replacement=new Object(); List<FloatingBubbleAppearance.Config> notifications=new ArrayList<>();
        Runnable listener=()->notifications.add(FloatingBubbleAppearance.isPreviewing()?FloatingBubbleAppearance.current(null):null);
        FloatingBubbleAppearance.addChangeListener(listener); FloatingBubbleAppearance.addChangeListener(listener);
        FloatingBubbleAppearance.preview(null,owner,defaults); check(notifications.size()==1 && notifications.get(0).matches(defaults));
        FloatingBubbleAppearance.preview(null,replacement,defaults); FloatingBubbleAppearance.endPreview(null,owner);
        check(notifications.size()==1 && FloatingBubbleAppearance.isPreviewing());
        FloatingBubbleAppearance.preview(null,replacement,max); check(notifications.size()==2 && notifications.get(1).matches(max));
        FloatingBubbleAppearance.endPreview(null,replacement); check(notifications.size()==3 && notifications.get(2)==null && !FloatingBubbleAppearance.isPreviewing());
        FloatingBubbleAppearance.removeChangeListener(listener); FloatingBubbleAppearance.preview(null,owner,defaults); FloatingBubbleAppearance.endPreview(null,owner);
        check(notifications.size()==3);
        System.out.println("FloatingBubbleAppearance: "+checks+" checks passed (bounds, Unicode, stable IDs, overwrite order, capacity, independent paints, transforms, Gaussian field, failed commit rollback, preview ownership)");
    }
    private static void failedCommits() {
        MemoryPreferences prefs=new MemoryPreferences(); Set<String> tags=new HashSet<>(); tags.add("old");
        prefs.edit().putString("saved_styles","[stable-old-id]").putString("appearance_v3","committed-old-id")
                .putInt("data_version",2).putInt("shape",1).putLong("long",7L).putFloat("float",.5f)
                .putBoolean("boolean",true).putStringSet("set",tags).commit();
        Map<String,?> original=prefs.getAll(); prefs.failures=1;
        check(!FloatingBubbleAppearance.commitWithRollback(prefs,prefs.edit().putString("saved_styles","[new-id,stable-old-id]")
                .putString("appearance_v3","new-id").putInt("data_version",3).remove("shape").putString("unexpected","new")));
        check(prefs.getAll().equals(original) && prefs.disk.equals(original));
        check(prefs.getString("saved_styles","").equals("[stable-old-id]") && !prefs.contains("unexpected"));
        // Like Android, this fake applies its memory edits even when both disk attempts fail.
        prefs.failures=2;
        check(!FloatingBubbleAppearance.commitWithRollback(prefs,prefs.edit().remove("saved_styles").putString("appearance_v3","deleted-id")));
        check(prefs.getAll().equals(original) && prefs.disk.equals(original));
        check(FloatingBubbleAppearance.commitWithRollback(prefs,prefs.edit().putString("appearance_v3","successful-retry")));
        check(prefs.getString("appearance_v3","").equals("successful-retry") && prefs.disk.equals(prefs.getAll()));
        check(prefs.getString("saved_styles","").equals("[stable-old-id]") && prefs.getStringSet("set",null).equals(tags));
    }
    /** Models the Android commit ordering without a filesystem or Android runtime. */
    private static final class MemoryPreferences implements SharedPreferences {
        final Map<String,Object> memory=new HashMap<>(),disk=new HashMap<>(); int failures;
        @Override public Map<String,?> getAll() { return new HashMap<>(memory); }
        @Override public String getString(String key,String fallback) { Object v=memory.get(key); return v==null?fallback:(String)v; }
        @Override public Set<String> getStringSet(String key,Set<String> fallback) {
            Object v=memory.get(key); if (v==null) return fallback;
            Set<String> copy=new HashSet<>(); for (Object item:(Set<?>)v) copy.add((String)item); return copy;
        }
        @Override public int getInt(String key,int fallback) { Object v=memory.get(key); return v==null?fallback:(Integer)v; }
        @Override public long getLong(String key,long fallback) { Object v=memory.get(key); return v==null?fallback:(Long)v; }
        @Override public float getFloat(String key,float fallback) { Object v=memory.get(key); return v==null?fallback:(Float)v; }
        @Override public boolean getBoolean(String key,boolean fallback) { Object v=memory.get(key); return v==null?fallback:(Boolean)v; }
        @Override public boolean contains(String key) { return memory.containsKey(key); }
        @Override public Editor edit() { return new MemoryEditor(); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        private final class MemoryEditor implements Editor {
            final Map<String,Object> updates=new HashMap<>(); boolean clear;
            @Override public Editor putString(String key,String value) { updates.put(key,value); return this; }
            @Override public Editor putStringSet(String key,Set<String> value) { updates.put(key,value==null?null:new HashSet<>(value)); return this; }
            @Override public Editor putInt(String key,int value) { updates.put(key,value); return this; }
            @Override public Editor putLong(String key,long value) { updates.put(key,value); return this; }
            @Override public Editor putFloat(String key,float value) { updates.put(key,value); return this; }
            @Override public Editor putBoolean(String key,boolean value) { updates.put(key,value); return this; }
            @Override public Editor remove(String key) { updates.put(key,null); return this; }
            @Override public Editor clear() { clear=true; return this; }
            @Override public boolean commit() {
                if (clear) memory.clear();
                for (Map.Entry<String,Object> e:updates.entrySet()) if (e.getValue()==null) memory.remove(e.getKey()); else memory.put(e.getKey(),e.getValue());
                if (failures>0) { failures--; return false; }
                disk.clear(); disk.putAll(memory); return true;
            }
            @Override public void apply() { commit(); }
        }
    }
}
