package com.yuen.jpdict;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.widget.FrameLayout;
import java.io.File;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Immutable appearance and atomic history transactions shared by every bubble surface. */
public final class FloatingBubbleAppearance {
    public static final int STYLE_DEFAULT=0, STYLE_SEARCH=1, STYLE_CUSTOM=2, STYLE_IMAGE=3;
    public static final int SHAPE_CIRCLE=0, SHAPE_ROUNDED=1, SHAPE_PILL=2,
            SHAPE_HEXAGON=3, SHAPE_DIAMOND=4, SHAPE_STAR=5, SHAPE_LEGACY=6;
    public static final int ICON_THIN=0, ICON_BOLD=1, ICON_ROUNDED=2, ICON_PLUS=3,
            ICON_FILLED=4, ICON_SCAN=5, ICON_CUSTOM=6;
    public static final int COLOR_SOLID=0, COLOR_LINEAR=1, COLOR_FREE=2;
    public static final int MIN_SIZE_DP=24, MAX_SIZE_DP=100, MIN_OPACITY=0;
    public static final int DEFAULT_COLOR=0xff168af2, MAX_SAVED_STYLES=10, DATA_VERSION=3;
    public static final int MAX_SVG_BYTES=120*1024;
    private static final String PREFS="floating_bubble_appearance";
    private static final String IMAGE_FILE="floating-bubble-image.png";
    private static Object previewOwner;
    private static Config previewConfig;
    private static final Set<Runnable> changeListeners=new HashSet<>();
    private static WeakReference<Bitmap> imageCache=new WeakReference<>(null);
    private static String cachedImagePath;
    private static long cachedImageModified;
    private FloatingBubbleAppearance() { }

    public static final class FreePoint {
        public final float x,y,radius;
        public final int color;
        public FreePoint(float x,float y,int color,float radius) {
            this.x=clamp(x,0,1,.5f); this.y=clamp(y,0,1,.5f);
            this.color=color|0xff000000; this.radius=clamp(radius,10,100,65);
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof FreePoint)) return false;
            FreePoint p=(FreePoint)other;
            return x==p.x && y==p.y && radius==p.radius && color==p.color;
        }
        @Override public int hashCode() { return Objects.hash(x,y,radius,color); }
    }

    public static final class ColorConfig {
        public final int mode,solid,start,end;
        public final float angle;
        public final List<FreePoint> freePoints;
        public ColorConfig(int mode,int solid,int start,int end,float angle,List<FreePoint> points) {
            this.mode=mode>=COLOR_SOLID && mode<=COLOR_FREE?mode:COLOR_SOLID;
            this.solid=solid|0xff000000; this.start=start|0xff000000; this.end=end|0xff000000;
            this.angle=finite(angle)?((angle%360)+360)%360:135;
            List<FreePoint> valid=new ArrayList<>();
            if (points!=null && points.size()>=2 && points.size()<=8)
                for (FreePoint p:points) if (p!=null) valid.add(p);
            this.freePoints=Collections.unmodifiableList(valid.size()>=2?valid:defaultFreePoints());
        }
        public ColorConfig withMode(int value) { return new ColorConfig(value,solid,start,end,angle,freePoints); }
        public ColorConfig withSolid(int value) { return new ColorConfig(mode,value,start,end,angle,freePoints); }
        public ColorConfig withStops(int a,int b) { return new ColorConfig(mode,solid,a,b,angle,freePoints); }
        public ColorConfig withAngle(float value) { return new ColorConfig(mode,solid,start,end,value,freePoints); }
        public ColorConfig withPoints(List<FreePoint> value) { return new ColorConfig(mode,solid,start,end,angle,value); }
        @Override public boolean equals(Object other) {
            if (!(other instanceof ColorConfig)) return false;
            ColorConfig p=(ColorConfig)other;
            return mode==p.mode && solid==p.solid && start==p.start && end==p.end
                    && angle==p.angle && freePoints.equals(p.freePoints);
        }
        @Override public int hashCode() { return Objects.hash(mode,solid,start,end,angle,freePoints); }
    }
    public static ColorConfig defaultPaint(int solid) {
        return new ColorConfig(COLOR_SOLID,solid,0xff168aff,0xffa855f7,135,defaultFreePoints());
    }
    public static List<FreePoint> defaultFreePoints() {
        List<FreePoint> p=new ArrayList<>();
        p.add(new FreePoint(.18f,.18f,0xfffc76b5,65));
        p.add(new FreePoint(.83f,.17f,0xff2195ff,65));
        p.add(new FreePoint(.20f,.82f,0xff9b69ff,65));
        p.add(new FreePoint(.83f,.81f,0xff40daaa,65)); return p;
    }

    public static final class Config {
        public final int sizeDp,opacityPercent,style,roundnessPercent,color,shape,icon,iconSizePercent;
        public final String text,imageName,presetId,customSvg,customSvgName;
        public final ColorConfig iconPaint,backgroundPaint;
        public final float imageZoomPercent,imageXPercent,imageYPercent;
        /** Old kana/mark, continuous corner radius and center-crop remain until explicitly edited. */
        public final boolean legacyVisual;
        public Config(int size,int opacity,int style,int roundness,String text,int color) {
            this(size,opacity,style,roundness,text,color,IMAGE_FILE);
        }
        public Config(int size,int opacity,int style,int roundness,String text,int color,String image) {
            this(new Builder().legacy(size,opacity,style,roundness,text,color,image));
        }
        private Config(Builder b) {
            sizeDp=Math.max(MIN_SIZE_DP,Math.min(MAX_SIZE_DP,b.sizeDp));
            opacityPercent=Math.max(0,Math.min(100,b.opacityPercent));
            style=b.style>=STYLE_DEFAULT && b.style<=STYLE_IMAGE?b.style:STYLE_DEFAULT;
            roundnessPercent=Math.max(0,Math.min(100,b.roundnessPercent));
            shape=b.shape>=SHAPE_CIRCLE && b.shape<=SHAPE_LEGACY?b.shape:SHAPE_CIRCLE;
            icon=b.icon>=ICON_THIN && b.icon<=ICON_CUSTOM?b.icon:ICON_THIN;
            iconSizePercent=Math.max(50,Math.min(150,b.iconSizePercent));
            String label=b.text==null?"":b.text;
            int n=label.codePointCount(0,label.length());
            text=n>8?label.substring(0,label.offsetByCodePoints(0,8)):label;
            imageName="".equals(b.imageName)?"":validImageName(b.imageName)?b.imageName:IMAGE_FILE;
            presetId=b.presetId!=null && b.presetId.matches("[a-zA-Z0-9-]{1,80}")?b.presetId:"";
            customSvg=b.customSvg==null?"":b.customSvg;
            if (customSvg.getBytes(StandardCharsets.UTF_8).length>MAX_SVG_BYTES)
                throw new IllegalArgumentException("SVG 文件请小于 120KB");
            customSvgName=safeSvgName(b.customSvgName);
            iconPaint=b.iconPaint==null?defaultPaint(0xffffffff):b.iconPaint;
            backgroundPaint=b.backgroundPaint==null?defaultPaint(b.color):b.backgroundPaint;
            color=backgroundPaint.solid;
            imageZoomPercent=clamp(b.imageZoomPercent,20,300,100);
            imageXPercent=clamp(b.imageXPercent,-100,100,0);
            imageYPercent=clamp(b.imageYPercent,-100,100,0);
            legacyVisual=b.legacyVisual;
        }
        public float alpha() { return opacityPercent/100f; }
        public int sizePx(Context context) { return dp(context,sizeDp); }
        /** Visual/data equivalence intentionally excludes preset identity. */
        public boolean matches(Config c) {
            return c!=null && sizeDp==c.sizeDp && opacityPercent==c.opacityPercent && style==c.style
                    && roundnessPercent==c.roundnessPercent && shape==c.shape && icon==c.icon
                    && iconSizePercent==c.iconSizePercent && legacyVisual==c.legacyVisual
                    && text.equals(c.text) && imageName.equals(c.imageName)
                    && customSvg.equals(c.customSvg) && customSvgName.equals(c.customSvgName)
                    && iconPaint.equals(c.iconPaint) && backgroundPaint.equals(c.backgroundPaint)
                    && imageZoomPercent==c.imageZoomPercent && imageXPercent==c.imageXPercent
                    && imageYPercent==c.imageYPercent;
        }
        public Bundle toBundle() {
            Bundle state=new Bundle();
            try { state.putString("appearance_v3",toJson(this).toString()); }
            catch (JSONException impossible) { throw new IllegalStateException(impossible); }
            return state;
        }
    }
    public static final class Builder {
        public int sizeDp=44,opacityPercent=25,style=STYLE_SEARCH,roundnessPercent=100,
                color=DEFAULT_COLOR,shape=SHAPE_CIRCLE,icon=ICON_THIN,iconSizePercent=100;
        public String text="搜索",imageName="",presetId="",customSvg="",customSvgName="";
        public ColorConfig iconPaint=defaultPaint(0xffffffff),backgroundPaint=defaultPaint(DEFAULT_COLOR);
        public float imageZoomPercent=100,imageXPercent=0,imageYPercent=0;
        public boolean legacyVisual;
        public Builder() { }
        public Builder(Config c) {
            sizeDp=c.sizeDp; opacityPercent=c.opacityPercent; style=c.style; roundnessPercent=c.roundnessPercent;
            color=c.color; shape=c.shape; icon=c.icon; iconSizePercent=c.iconSizePercent; text=c.text;
            imageName=c.imageName; presetId=c.presetId; customSvg=c.customSvg; customSvgName=c.customSvgName;
            iconPaint=c.iconPaint; backgroundPaint=c.backgroundPaint; imageZoomPercent=c.imageZoomPercent;
            imageXPercent=c.imageXPercent; imageYPercent=c.imageYPercent; legacyVisual=c.legacyVisual;
        }
        private Builder legacy(int size,int opacity,int type,int roundness,String label,int tint,String image) {
            sizeDp=size; opacityPercent=opacity; style=type; roundnessPercent=roundness;
            shape=roundness>=100?SHAPE_CIRCLE:SHAPE_LEGACY; legacyVisual=true;
            text=label==null || label.trim().isEmpty()?"あ":label.trim();
            if (text.codePointCount(0,text.length())>4) text=text.substring(0,text.offsetByCodePoints(0,4));
            color=tint|0xff000000; backgroundPaint=defaultPaint(color); imageName=image; return this;
        }
        public Config build() { return new Config(this); }
    }
    public static Config defaults() { return new Config(44,25,STYLE_DEFAULT,100,"あ",DEFAULT_COLOR); }
    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }
    public static Config load(Context context) {
        SharedPreferences p=preferences(context);
        String raw=p.getString("appearance_v3",null);
        if (raw!=null) try { return fromJson(new JSONObject(raw)); } catch (JSONException | RuntimeException ignored) { }
        Config old=new Config(p.getInt("size_dp",44),p.getInt("opacity_percent",25),
                p.getInt("style",STYLE_DEFAULT),p.getInt("roundness_percent",
                p.getInt("shape",SHAPE_CIRCLE)==SHAPE_ROUNDED?56:100),p.getString("text","あ"),
                p.getInt("color",DEFAULT_COLOR),p.getString("image_name",IMAGE_FILE));
        // Identity is inferred only once for legacy data; normal editing never compares values for selection.
        for (Config item:savedStyles(context)) if (old.matches(item)) {
            Builder b=new Builder(old); b.presetId=item.presetId; return b.build();
        }
        return old;
    }
    public static Config fromBundle(Bundle state) {
        if (state==null) return defaults();
        String raw=state.getString("appearance_v3");
        if (raw!=null) try { return fromJson(new JSONObject(raw)); } catch (JSONException | RuntimeException ignored) { }
        return new Config(state.getInt("size_dp",44),state.getInt("opacity_percent",25),
                state.getInt("style",STYLE_DEFAULT),state.getInt("roundness_percent",100),
                state.getString("text","あ"),state.getInt("color",DEFAULT_COLOR),state.getString("image_name",IMAGE_FILE));
    }
    public static Config current(Context context) { return previewConfig==null?load(context):previewConfig; }
    static boolean isPreviewing() { return previewConfig!=null; }
    static void addChangeListener(Runnable listener) { changeListeners.add(listener); }
    static void removeChangeListener(Runnable listener) { changeListeners.remove(listener); }
    public static void preview(Context context,Object owner,Config config) {
        previewOwner=owner;
        if (config.matches(previewConfig)) { previewConfig=config; return; }
        previewConfig=config; notifyChanged();
    }
    public static void endPreview(Context context,Object owner) {
        if (previewOwner!=owner) return;
        previewOwner=null; previewConfig=null; notifyChanged();
    }
    public static List<Config> savedStyles(Context context) {
        SharedPreferences p=preferences(context); List<Config> result=new ArrayList<>();
        if (!p.contains("saved_styles") && p.contains("size_dp")) {
            Config old=new Config(p.getInt("size_dp",44),p.getInt("opacity_percent",25),
                    p.getInt("style",0),p.getInt("roundness_percent",p.getInt("shape",0)==1?56:100),
                    p.getString("text","あ"),p.getInt("color",DEFAULT_COLOR),p.getString("image_name",IMAGE_FILE));
            Builder b=new Builder(old); b.presetId=legacyId(0,"committed"); result.add(b.build()); return result;
        }
        try {
            JSONArray entries=new JSONArray(p.getString("saved_styles","[]")); Set<String> ids=new HashSet<>();
            for (int i=0;i<entries.length() && result.size()<MAX_SAVED_STYLES;i++) {
                JSONObject entry=entries.optJSONObject(i); if (entry==null) continue;
                try {
                    Config c=fromJson(entry); Builder b=new Builder(c);
                    if (b.presetId.isEmpty() || ids.contains(b.presetId)) b.presetId=legacyId(i,entry.toString());
                    ids.add(b.presetId); result.add(b.build());
                } catch (JSONException | RuntimeException invalid) { /* Preserve other intact entries. */ }
            }
        } catch (JSONException | RuntimeException ignored) { }
        return result;
    }
    private static String legacyId(int index,String source) {
        return UUID.nameUUIDFromBytes((index+":"+source).getBytes(StandardCharsets.UTF_8)).toString();
    }
    static List<Config> replacedStyle(List<Config> previous,String id,Config config) {
        List<Config> list=new ArrayList<>(previous);
        for (int i=0;i<list.size();i++) if (list.get(i).presetId.equals(id)) {
            Builder b=new Builder(config); b.presetId=id; list.set(i,b.build()); return list;
        }
        return null;
    }
    static List<Config> addedStyle(List<Config> previous,Config config) {
        if (previous.size()>=MAX_SAVED_STYLES) return null;
        List<Config> list=new ArrayList<>(previous); Builder b=new Builder(config);
        b.presetId=UUID.randomUUID().toString(); list.add(0,b.build()); return list;
    }
    public static boolean save(Context context,Config config) {
        if (config.presetId.isEmpty() || !validForSave(context,config)) return false;
        List<Config> history=replacedStyle(savedStyles(context),config.presetId,config);
        return history!=null && persist(context,config,history);
    }
    public static Config addSavedStyle(Context context,Config config) {
        if (!validForSave(context,config)) return null;
        List<Config> history=addedStyle(savedStyles(context),config);
        if (history==null) return null;
        Config added=history.get(0); return persist(context,added,history)?added:null;
    }
    public static boolean deleteSavedStyle(Context context,String id) {
        List<Config> history=savedStyles(context); boolean removed=false;
        for (int i=history.size()-1;i>=0;i--) if (history.get(i).presetId.equals(id)) { history.remove(i); removed=true; }
        if (!removed) return false;
        Config committed=load(context);
        if (committed.presetId.equals(id)) { Builder b=new Builder(committed); b.presetId=""; committed=b.build(); }
        return persist(context,committed,history);
    }
    private static boolean validForSave(Context context,Config config) {
        if (config.style==STYLE_IMAGE && !config.imageName.isEmpty() && !hasCustomImage(context,config)) return false;
        if (config.icon==ICON_CUSTOM) try { BubbleSvg.validate(config.customSvg); }
        catch (IllegalArgumentException invalid) { return false; }
        return true;
    }
    private static boolean persist(Context context,Config config,List<Config> history) {
        JSONArray entries=new JSONArray();
        try {
            for (Config c:history) entries.put(toJson(c));
            SharedPreferences store=preferences(context);
            SharedPreferences.Editor update=store.edit().putInt("data_version",DATA_VERSION)
                    .putString("appearance_v3",toJson(config).toString())
                    .putString("saved_styles",entries.toString()).putInt("size_dp",config.sizeDp)
                    .putInt("opacity_percent",config.opacityPercent).putInt("style",config.style)
                    .putInt("roundness_percent",config.roundnessPercent).putString("text",config.text)
                    .putInt("color",config.color).putString("image_name",config.imageName).remove("shape");
            boolean ok=commitWithRollback(store,update);
            if (ok) { cleanupImages(context,config); notifyChanged(); } return ok;
        } catch (JSONException error) { return false; }
    }
    /** commit() mutates Android's memory map even when the disk write returns false. */
    static boolean commitWithRollback(SharedPreferences store,SharedPreferences.Editor update) {
        Map<String,?> previous=new HashMap<>(store.getAll());
        if (update.commit()) return true;
        SharedPreferences.Editor restore=store.edit().clear();
        for (Map.Entry<String,?> entry:previous.entrySet()) {
            String key=entry.getKey(); Object value=entry.getValue();
            if (value instanceof String) restore.putString(key,(String)value);
            else if (value instanceof Integer) restore.putInt(key,(Integer)value);
            else if (value instanceof Long) restore.putLong(key,(Long)value);
            else if (value instanceof Float) restore.putFloat(key,(Float)value);
            else if (value instanceof Boolean) restore.putBoolean(key,(Boolean)value);
            else if (value instanceof Set) {
                Set<String> strings=new HashSet<>();
                for (Object item:(Set<?>)value) if (item instanceof String) strings.add((String)item);
                restore.putStringSet(key,strings);
            }
        }
        // A second storage failure still restores the in-process map before returning.
        // Android's atomic preference writer retains the preceding disk generation.
        restore.commit();
        return false;
    }
    static JSONObject toJson(Config c) throws JSONException {
        return new JSONObject().put("version",DATA_VERSION).put("preset_id",c.presetId)
                .put("size_dp",c.sizeDp).put("opacity_percent",c.opacityPercent).put("style",c.style)
                .put("roundness_percent",c.roundnessPercent).put("text",c.text).put("color",c.color)
                .put("image_name",c.imageName).put("shape",c.shape).put("icon",c.icon)
                .put("icon_size",c.iconSizePercent).put("custom_svg",c.customSvg).put("custom_svg_name",c.customSvgName)
                .put("icon_paint",paintJson(c.iconPaint)).put("background_paint",paintJson(c.backgroundPaint))
                .put("image_zoom",c.imageZoomPercent).put("image_x",c.imageXPercent).put("image_y",c.imageYPercent)
                .put("legacy_visual",c.legacyVisual);
    }
    static Config fromJson(JSONObject j) throws JSONException {
        if (j.optInt("version",0)>DATA_VERSION) throw new JSONException("Unsupported appearance version");
        Config legacy=new Config(j.getInt("size_dp"),j.getInt("opacity_percent"),j.getInt("style"),
                j.optInt("roundness_percent",j.optInt("shape",0)==1?56:100),j.optString("text","あ"),
                j.optInt("color",DEFAULT_COLOR),j.optString("image_name",IMAGE_FILE));
        Builder b=new Builder(legacy); b.presetId=j.optString("preset_id","");
        if (j.optInt("version",0)>=DATA_VERSION) {
            b.shape=j.optInt("shape",legacy.shape); b.icon=j.optInt("icon",0); b.iconSizePercent=j.optInt("icon_size",100);
            b.customSvg=j.optString("custom_svg",""); b.customSvgName=j.optString("custom_svg_name","");
            b.text=j.optString("text",legacy.text); b.legacyVisual=j.optBoolean("legacy_visual",false);
            b.iconPaint=readPaint(j.optJSONObject("icon_paint"),0xffffffff);
            b.backgroundPaint=readPaint(j.optJSONObject("background_paint"),legacy.color);
            b.imageZoomPercent=(float)j.optDouble("image_zoom",100); b.imageXPercent=(float)j.optDouble("image_x",0);
            b.imageYPercent=(float)j.optDouble("image_y",0);
        }
        return b.build();
    }
    private static JSONObject paintJson(ColorConfig p) throws JSONException {
        JSONArray points=new JSONArray();
        for (FreePoint f:p.freePoints) points.put(new JSONObject().put("x",f.x).put("y",f.y).put("color",f.color).put("radius",f.radius));
        return new JSONObject().put("mode",p.mode).put("solid",p.solid).put("start",p.start)
                .put("end",p.end).put("angle",p.angle).put("points",points);
    }
    private static ColorConfig readPaint(JSONObject j,int fallback) {
        if (j==null) return defaultPaint(fallback);
        List<FreePoint> points=new ArrayList<>(); JSONArray a=j.optJSONArray("points");
        if (a!=null && a.length()>=2 && a.length()<=8) for (int i=0;i<a.length();i++) {
            JSONObject p=a.optJSONObject(i); if (p==null) { points.clear(); break; }
            double x=p.optDouble("x",Double.NaN),y=p.optDouble("y",Double.NaN),r=p.optDouble("radius",Double.NaN);
            if (Double.isNaN(x)||Double.isInfinite(x)||Double.isNaN(y)||Double.isInfinite(y)
                    ||Double.isNaN(r)||Double.isInfinite(r)||x<0||x>1||y<0||y>1||r<10||r>100) { points.clear(); break; }
            points.add(new FreePoint((float)x,(float)y,p.optInt("color",fallback),(float)r));
        }
        return new ColorConfig(j.optInt("mode",0),j.optInt("solid",fallback),j.optInt("start",0xff168aff),
                j.optInt("end",0xffa855f7),(float)j.optDouble("angle",135),points);
    }
    private static void notifyChanged() { for (Runnable listener:new ArrayList<>(changeListeners)) listener.run(); }
    public static int sizePx(Context context) { return current(context).sizePx(context); }
    public static FrameLayout createVisual(Context context) {
        FrameLayout visual=new FrameLayout(context); applyVisual(visual,current(context)); return visual;
    }
    /** Touch, window alpha and accessibility stay on the original service host. */
    public static void applyVisual(FrameLayout visual,Config config) {
        visual.removeAllViews(); visual.setClipToOutline(false); visual.setElevation(dp(visual.getContext(),12));
        visual.setBackground(new BubbleAppearanceRenderer.AppearanceDrawable(visual,config));
        visual.setContentDescription("悬浮搜索按钮");
    }
    public static void validateSvg(String source) { BubbleSvg.validate(source); }
    private static boolean validImageName(String name) {
        return IMAGE_FILE.equals(name) || (name!=null && name.matches("floating-bubble-image-[a-zA-Z0-9-]+\\.png"));
    }
    static File imageFile(Context context,Config config) { return new File(context.getFilesDir(),config.imageName); }
    public static boolean hasCustomImage(Context context,Config config) { return !config.imageName.isEmpty() && imageFile(context,config).isFile(); }
    static void cleanupImages(Context context,Config draft) {
        Set<String> keep=new HashSet<>(); keep.add(load(context).imageName);
        if (draft!=null) keep.add(draft.imageName); if (previewConfig!=null) keep.add(previewConfig.imageName);
        for (Config saved:savedStyles(context)) keep.add(saved.imageName);
        File[] files=context.getFilesDir().listFiles(); if (files==null) return;
        for (File file:files) if (validImageName(file.getName()) && !keep.contains(file.getName())) file.delete();
    }
    static synchronized Bitmap loadImage(Context context,Config config) {
        if (config.imageName.isEmpty()) return null;
        File file=imageFile(context,config); if (!file.isFile()) return null;
        Bitmap image=imageCache.get();
        if (image!=null && file.getAbsolutePath().equals(cachedImagePath) && file.lastModified()==cachedImageModified) return image;
        BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(file.getAbsolutePath(),bounds); if (bounds.outWidth<=0||bounds.outHeight<=0) return null;
        BitmapFactory.Options options=new BitmapFactory.Options(); options.inSampleSize=1;
        while (Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>256) options.inSampleSize*=2;
        image=BitmapFactory.decodeFile(file.getAbsolutePath(),options); imageCache=new WeakReference<>(image);
        cachedImagePath=file.getAbsolutePath(); cachedImageModified=file.lastModified(); return image;
    }
    private static boolean finite(float n) { return !Float.isNaN(n)&&!Float.isInfinite(n); }
    static String safeSvgName(String name) {
        if (name==null) return "";
        StringBuilder clean=new StringBuilder(); int count=0;
        int[] tail=new int[4]; int tailCount=0;
        for (int offset=0;offset<name.length();) {
            int cp=name.codePointAt(offset); offset+=Character.charCount(cp); int type=Character.getType(cp);
            if (!Character.isISOControl(cp) && type!=Character.FORMAT
                    && type!=Character.LINE_SEPARATOR && type!=Character.PARAGRAPH_SEPARATOR && type!=Character.SURROGATE) {
                if (count<128) clean.appendCodePoint(cp);
                count++;
                tail[tailCount%4]=cp; tailCount++;
            }
        }
        String result=clean.toString();
        if (count<=128) return result;
        StringBuilder extension=new StringBuilder();
        for (int i=0;i<4;i++) extension.appendCodePoint(tail[(tailCount%4+i)%4]);
        boolean svg=extension.toString().equalsIgnoreCase(".svg");
        return result.substring(0,result.offsetByCodePoints(0,svg?124:128))
                +(svg?extension:"");
    }
    private static float clamp(float n,float min,float max,float fallback) { return finite(n)?Math.max(min,Math.min(max,n)):fallback; }
    private static int dp(Context context,float value) { return Math.round(value*context.getResources().getDisplayMetrics().density); }
}
