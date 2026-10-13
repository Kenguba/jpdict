package com.yuen.jpdict;

import android.graphics.Color;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Versioned, self-contained v18 appearance payload. Legacy Configs may leave this null. */
final class BubbleDesign {
    static final String[] SHAPES = {"circle","rounded","pill","hexagon","diamond","star"};
    static final String[] ICONS = {"thin","bold","rounded","plus","filled","scan","custom"};
    static final int MAX_POINTS = 8;
    int shape;
    int icon;
    int iconSize = 100;
    int imageZoom = 100, imageX, imageY;
    String svg = "", svgName = "";
    final ColorSpec background = new ColorSpec(0xff168af2);
    final ColorSpec foreground = new ColorSpec(Color.WHITE);

    static class Point {
        float x, y;
        int color, radius;
        Point(float x, float y, int color, int radius) {
            this.x=x; this.y=y; this.color=color | 0xff000000; this.radius=radius;
        }
        JSONObject json() {
            JSONObject o=new JSONObject();
            try { o.put("x",x).put("y",y).put("color",color).put("radius",radius); }
            catch (org.json.JSONException ignored) {}
            return o;
        }
    }

    static final class ColorSpec {
        int mode; // 0 solid, 1 linear, 2 free point-based
        int solid, start=0xff168aff, end=0xffa855f7, angle=135;
        final ArrayList<Point> points = new ArrayList<>();
        ColorSpec(int solid) {
            this.solid = solid | 0xff000000;
            resetPoints();
        }
        void resetPoints() {
            points.clear();
            points.add(new Point(.18f,.18f,0xfffc76b5,65));
            points.add(new Point(.83f,.17f,0xff2195ff,65));
            points.add(new Point(.20f,.82f,0xff9b69ff,65));
            points.add(new Point(.83f,.81f,0xff40daaa,65));
        }
        JSONObject json() {
            JSONObject o=new JSONObject(); JSONArray a=new JSONArray();
            for (Point p:points) a.put(p.json());
            try { o.put("mode",mode).put("solid",solid).put("start",start)
                    .put("end",end).put("angle",angle).put("points",a); }
            catch (org.json.JSONException ignored) {}
            return o;
        }
        void read(JSONObject o) {
            if (o==null) return;
            mode=bounded(o.optInt("mode",mode),0,2);
            solid=o.optInt("solid",solid)|0xff000000;
            start=o.optInt("start",start)|0xff000000;
            end=o.optInt("end",end)|0xff000000;
            angle=((o.optInt("angle",angle)%360)+360)%360;
            JSONArray a=o.optJSONArray("points");
            if (a!=null && a.length()>=2 && a.length()<=MAX_POINTS) {
                ArrayList<Point> validated=new ArrayList<>();
                for (int i=0;i<a.length();i++) {
                    JSONObject p=a.optJSONObject(i);
                    if(p==null)break;
                    double x=p.optDouble("x",-1),y=p.optDouble("y",-1);
                    int r=p.optInt("radius",0);
                    if (!Double.isFinite(x)||!Double.isFinite(y)||x<0||x>1||y<0||y>1||r<10||r>100) break;
                    validated.add(new Point((float)x,(float)y,p.optInt("color",Color.WHITE),r));
                }
                if(validated.size()==a.length()){points.clear();points.addAll(validated);}
            }
        }
    }

    static BubbleDesign fromLegacy(int background) {
        BubbleDesign design=new BubbleDesign();
        design.background.solid=background | 0xff000000;
        return design;
    }
    BubbleDesign copy(){return parse(json().toString());}
    JSONObject json() {
        JSONObject o=new JSONObject();
        try{
            o.put("v",1).put("shape",shape).put("icon",icon)
              .put("iconSize",iconSize).put("imageZoom",imageZoom)
              .put("imageX",imageX).put("imageY",imageY)
              .put("svg",svg).put("svgName",svgName)
              .put("background",background.json()).put("foreground",foreground.json());
        }catch(org.json.JSONException ignored){}
        return o;
    }
    static BubbleDesign parse(String json) {
        if(json==null||json.length()>150000)return new BubbleDesign();
        try{return parse(new JSONObject(json));}
        catch(org.json.JSONException ignored){return new BubbleDesign();}
    }
    static BubbleDesign parse(JSONObject o) {
        BubbleDesign d=new BubbleDesign();
        if(o==null)return d;
        d.shape=bounded(o.optInt("shape",0),0,5);
        d.icon=bounded(o.optInt("icon",0),0,6);
        d.iconSize=bounded(o.optInt("iconSize",100),50,150);
        d.imageZoom=bounded(o.optInt("imageZoom",100),20,300);
        d.imageX=bounded(o.optInt("imageX",0),-100,100);
        d.imageY=bounded(o.optInt("imageY",0),-100,100);
        String svg=o.optString("svg","");
        // An imported vector is carried with the preset. Never store unbounded input.
        d.svg=svg.length()<=120*1024?svg:"";
        String label=o.optString("svgName","");
        d.svgName=label.length()<=120?label:"";
        d.background.read(o.optJSONObject("background"));
        d.foreground.read(o.optJSONObject("foreground"));
        if(d.icon==6 && d.svg.isEmpty())d.icon=0;
        return d;
    }
    static int bounded(int value,int min,int max){return Math.max(min,Math.min(max,value));}
    static int rgb(String hex,int fallback){
        if(hex==null)return fallback;
        try{
            String s=hex.trim();
            if(!s.matches("#[0-9A-Fa-f]{6}"))return fallback;
            return 0xff000000|Integer.parseInt(s.substring(1),16);
        }catch(Exception e){return fallback;}
    }
}