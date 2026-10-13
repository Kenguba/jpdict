package com.yuen.jpdict;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Restricted, drawing-only SVG reader. Rejects external resources and active XML. */
final class BubbleSvgIcon {
    private BubbleSvgIcon(){}
    private static final Pattern NUM=Pattern.compile("[-+]?(?:\\d*\\.\\d+|\\d+\\.?\\d*)(?:[eE][-+]?\\d+)?");
    private static final Pattern TOKEN=Pattern.compile("[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\\d*\\.\\d+|\\d+\\.?\\d*)(?:[eE][-+]?\\d+)?");

    static boolean isSafe(String s) {
        return s!=null&&s.length()>0&&s.length()<=120*1024
            &&!Pattern.compile("<!DOCTYPE|<!ENTITY|<script|<foreignObject|<image|onload|onclick|href\\s*=|url\\s*\\(|https?:|data:|@import",Pattern.CASE_INSENSITIVE).matcher(s).find();
    }
    static boolean draw(Canvas c,String svg,BubbleDesign.ColorSpec color,float cx,float cy,float size) {
        if(!isSafe(svg))return false;
        try {
            XmlPullParser parser=Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,false);
            parser.setInput(new StringReader(svg));
            ArrayList<Path> paths=new ArrayList<>();
            ArrayList<Float> widths=new ArrayList<>();
            ArrayList<Boolean> fills=new ArrayList<>();
            float minX=0,minY=0,vw=24,vh=24;
            int count=0;
            while(parser.next()!=XmlPullParser.END_DOCUMENT){
                if(parser.getEventType()!=XmlPullParser.START_TAG)continue;
                if(++count>500)return false;
                String tag=parser.getName();
                if("svg".equals(tag)){
                    String box=parser.getAttributeValue(null,"viewBox");
                    float[] v=values(box,4);
                    if(v!=null && v[2]>0 && v[3]>0 && v[2]<10000&&v[3]<10000){
                        minX=v[0];minY=v[1];vw=v[2];vh=v[3];
                    }
                    continue;
                }
                Path p=new Path();
                if("path".equals(tag)){
                    String d=parser.getAttributeValue(null,"d");
                    if(d==null||d.length()>40000)continue;
                    p=parsePath(d);
                }else if("circle".equals(tag)){
                    float x=num(parser,"cx",0),y=num(parser,"cy",0),r=num(parser,"r",0);
                    if(r>0)p.addCircle(x,y,r,Path.Direction.CW);
                }else if("ellipse".equals(tag)){
                    float x=num(parser,"cx",0),y=num(parser,"cy",0),rx=num(parser,"rx",0),ry=num(parser,"ry",0);
                    if(rx>0&&ry>0)p.addOval(new RectF(x-rx,y-ry,x+rx,y+ry),Path.Direction.CW);
                }else if("rect".equals(tag)){
                    float x=num(parser,"x",0),y=num(parser,"y",0),w=num(parser,"width",0),h=num(parser,"height",0);
                    float rx=num(parser,"rx",0),ry=num(parser,"ry",rx);
                    if(w>0&&h>0)p.addRoundRect(new RectF(x,y,x+w,y+h),rx,ry,Path.Direction.CW);
                }else if("line".equals(tag)){
                    p.moveTo(num(parser,"x1",0),num(parser,"y1",0));
                    p.lineTo(num(parser,"x2",0),num(parser,"y2",0));
                }else if("polygon".equals(tag)||"polyline".equals(tag)){
                    String coordinates=parser.getAttributeValue(null,"points");
                    if(coordinates!=null){
                        Matcher m=NUM.matcher(coordinates);ArrayList<Float> vs=new ArrayList<>();
                        while(m.find()&&vs.size()<2048)vs.add(Float.parseFloat(m.group()));
                        if(vs.size()>=4){p.moveTo(vs.get(0),vs.get(1));
                            for(int i=2;i+1<vs.size();i+=2)p.lineTo(vs.get(i),vs.get(i+1));
                            if("polygon".equals(tag))p.close();}
                    }
                } else continue;
                if(p==null)continue;
                String stroke=parser.getAttributeValue(null,"stroke"),fill=parser.getAttributeValue(null,"fill");
                float width=Math.max(.25f,Math.min(30f,num(parser,"stroke-width",1.5f)));
                boolean hasStroke=stroke!=null&&!"none".equalsIgnoreCase(stroke);
                boolean hasFill=fill!=null&&!"none".equalsIgnoreCase(fill);
                if(!hasStroke&&!hasFill)hasFill=true;
                paths.add(p);widths.add(hasStroke?width:0f);fills.add(hasFill);
            }
            if(paths.isEmpty())return false;
            c.save();float s=size/Math.max(vw,vh);
            c.translate(cx-vw*s/2f,cy-vh*s/2f);c.scale(s,s);c.translate(-minX,-minY);
            Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
            BubbleCanvasView.applyPaint(paint,color,minX,minY,minX+vw,minY+vh);
            for(int i=0;i<paths.size();i++){
                if(fills.get(i)){paint.setStyle(Paint.Style.FILL);c.drawPath(paths.get(i),paint);}
                if(widths.get(i)>0){paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(widths.get(i));c.drawPath(paths.get(i),paint);}
            }
            c.restore();return true;
        }catch(Exception invalid){return false;}
    }
    private static float num(XmlPullParser p,String key,float fallback){
        String v=p.getAttributeValue(null,key);
        if(v==null)return fallback;
        Matcher m=NUM.matcher(v);
        if(!m.find())return fallback;
        try{return Float.parseFloat(m.group());}catch(NumberFormatException e){return fallback;}
    }
    private static float[] values(String s,int n){
        if(s==null)return null;
        Matcher m=NUM.matcher(s);float[] a=new float[n];int i=0;
        while(m.find()&&i<n)a[i++]=Float.parseFloat(m.group());
        return i==n?a:null;
    }
    private static Path parsePath(String data){
        ArrayList<String> t=new ArrayList<>();Matcher matcher=TOKEN.matcher(data);
        while(matcher.find()&&t.size()<12000)t.add(matcher.group());
        Path path=new Path();
        int i=0;char cmd=' ';float x=0,y=0,sx=0,sy=0,lastCtrlX=0,lastCtrlY=0;
        while(i<t.size()){
            String token=t.get(i);
            if(Character.isLetter(token.charAt(0))){
                cmd=token.charAt(0);i++;
                if(cmd=='Z'||cmd=='z'){path.close();x=sx;y=sy;cmd=' ';continue;}
            }
            if(cmd==' ')break;
            char up=Character.toUpperCase(cmd);boolean relative=Character.isLowerCase(cmd);
            int params=up=='H'||up=='V'?1:up=='M'||up=='L'||up=='T'?2:up=='S'||up=='Q'?4:up=='C'?6:up=='A'?7:0;
            if(params==0||i+params>t.size())break;
            float[] v=new float[params];boolean invalid=false;
            for(int j=0;j<params;j++){
                if(Character.isLetter(t.get(i+j).charAt(0))){invalid=true;break;}
                v[j]=Float.parseFloat(t.get(i+j));
            }
            if(invalid)break;i+=params;
            float ox=x,oy=y;
            if(up=='H'){x=(relative?x:0)+v[0];path.lineTo(x,y);}
            else if(up=='V'){y=(relative?y:0)+v[0];path.lineTo(x,y);}
            else if(up=='M'||up=='L'||up=='T'){
                x=(relative?x:0)+v[0];y=(relative?y:0)+v[1];
                if(up=='M'){path.moveTo(x,y);sx=x;sy=y;cmd=relative?'l':'L';}
                else if(up=='T'){float rx=2*ox-lastCtrlX,ry=2*oy-lastCtrlY;
                    path.quadTo(rx,ry,x,y);lastCtrlX=rx;lastCtrlY=ry;
                }else path.lineTo(x,y);
            }else if(up=='C'){
                float x1=(relative?x:0)+v[0],y1=(relative?y:0)+v[1];
                float x2=(relative?x:0)+v[2],y2=(relative?y:0)+v[3];
                x=(relative?x:0)+v[4];y=(relative?y:0)+v[5];
                path.cubicTo(x1,y1,x2,y2,x,y);lastCtrlX=x2;lastCtrlY=y2;
            }else if(up=='S'){
                float x1=2*x-lastCtrlX,y1=2*y-lastCtrlY;
                float x2=(relative?x:0)+v[0],y2=(relative?y:0)+v[1];
                x=(relative?x:0)+v[2];y=(relative?y:0)+v[3];
                path.cubicTo(x1,y1,x2,y2,x,y);lastCtrlX=x2;lastCtrlY=y2;
            }else if(up=='Q'){
                float x1=(relative?x:0)+v[0],y1=(relative?y:0)+v[1];
                x=(relative?x:0)+v[2];y=(relative?y:0)+v[3];
                path.quadTo(x1,y1,x,y);lastCtrlX=x1;lastCtrlY=y1;
            }else if(up=='A'){
                // Elliptic arcs are approximated by their endpoint instead of executing embedded content.
                x=(relative?x:0)+v[5];y=(relative?y:0)+v[6];path.lineTo(x,y);
            }
        }
        return path;
    }
}