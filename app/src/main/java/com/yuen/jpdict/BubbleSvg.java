package com.yuen.jpdict;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.Xml;
import androidx.core.graphics.PathParser;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.xmlpull.v1.XmlPullParser;

/** Drawing-only SVG mask. Unsupported features fail explicitly; no URI or resource loading. */
public final class BubbleSvg {
    public static final int MAX_BYTES=FloatingBubbleAppearance.MAX_SVG_BYTES;
    private static final Pattern NUMBER=Pattern.compile("[-+]?(?:\\d*\\.\\d+|\\d+\\.?\\d*)(?:[eE][-+]?\\d+)?");
    private static final Pattern TRANSFORM=Pattern.compile("(matrix|translate|scale|rotate|skewX|skewY)\\s*\\(([^)]*)\\)");
    // Covers all ten saved styles and the draft without reparsing every wide-screen redraw.
    private static final int MAX_CACHED_SVGS=16;
    private static final Map<String,BubbleSvg> CACHE=new LinkedHashMap<>(MAX_CACHED_SVGS,.75f,true);
    private final List<Part> parts;
    private final RectF viewport;
    private BubbleSvg(List<Part> parts,RectF viewport) { this.parts=parts; this.viewport=viewport; }
    public static void validate(String source) { parse(source); }
    public static synchronized BubbleSvg parse(String source) {
        if (source==null || source.isEmpty()) throw invalid("SVG 没有可用图形");
        if (source.length()>MAX_BYTES) throw invalid("SVG 文件请小于 120KB");
        BubbleSvg cached=CACHE.get(source); if (cached!=null) return cached;
        if (source.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES) throw invalid("SVG 文件请小于 120KB");
        String lower=source.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("<!doctype")||lower.contains("<!entity")) throw invalid("SVG 不支持 DTD 或实体定义");
        try {
            XmlPullParser parser=Xml.newPullParser(); parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);
            parser.setInput(new StringReader(source)); List<Part> parts=new ArrayList<>();
            List<State> stack=new ArrayList<>(); RectF viewport=null; int nodes=0,geometry=0,commandBudget=0;
            int event;
            while ((event=parser.nextToken())!=XmlPullParser.END_DOCUMENT) {
                // The XML declaration is consumed by the parser; SVG processing
                // instructions must not silently import unsupported styling.
                if (event==XmlPullParser.PROCESSING_INSTRUCTION || event==XmlPullParser.DOCDECL) {
                    throw invalid("SVG 不支持处理指令或文档声明");
                } else if (event==XmlPullParser.START_TAG) {
                    if (++nodes>500 || parser.getDepth()>32) throw invalid("SVG 图形复杂度超过限制");
                    String tag=parser.getName(),namespace=parser.getNamespace();
                    if (!namespace.isEmpty()&&!namespace.equals("http://www.w3.org/2000/svg")) throw invalid("SVG 不支持外部命名空间");
                    if (!tag.matches("svg|g|path|circle|rect|ellipse|line|polygon|polyline")) throw invalid("SVG 不支持元素："+tag);
                    if (tag.equals("svg") && parser.getDepth()!=1) throw invalid("SVG 不支持嵌套 svg");
                    if (parser.getDepth()==1 && !tag.equals("svg")) throw invalid("SVG 根元素必须是 svg");
                    Map<String,String> a=new LinkedHashMap<>();
                    for (int i=0;i<parser.getAttributeCount();i++) {
                        String name=parser.getAttributeName(i),value=parser.getAttributeValue(i).trim();
                        String ns=parser.getAttributeNamespace(i);
                        if (!ns.isEmpty()) throw invalid("SVG 不支持命名空间属性："+name);
                        if (!name.matches("viewBox|width|height|preserveAspectRatio|d|x|y|x1|y1|x2|y2|cx|cy|r|rx|ry|points|fill|stroke|stroke-width|stroke-linecap|stroke-linejoin|stroke-miterlimit|opacity|fill-opacity|stroke-opacity|fill-rule|transform|id"))
                            throw invalid("SVG 不支持属性："+name);
                        if (value.length()>40000 || value.contains("<") || value.contains(">") || value.toLowerCase(java.util.Locale.ROOT).matches(".*(?:url\\(|javascript:|data:|https?:|@import).*"))
                            throw invalid("SVG 禁止资源引用或无效属性");
                        a.put(name,value);
                    }
                    State state=new State(stack.isEmpty()?new State():stack.get(stack.size()-1));
                    if (a.containsKey("transform")) state.matrix.preConcat(transform(a.get("transform")));
                    validateMatrix(state.matrix);
                    if (a.containsKey("fill")) state.fill=alphaColor(a.get("fill"));
                    if (a.containsKey("stroke")) state.stroke=alphaColor(a.get("stroke"));
                    state.width=value(a,"stroke-width",state.width,0,10000);
                    float opacity=value(a,"opacity",1,0,1);
                    if ((tag.equals("g")||tag.equals("svg"))&&opacity!=1)
                        throw invalid("SVG 暂不支持分组透明度，请将透明度设置在图形上");
                    state.opacity*=opacity;
                    state.fillOpacity=value(a,"fill-opacity",state.fillOpacity,0,1);
                    state.strokeOpacity=value(a,"stroke-opacity",state.strokeOpacity,0,1);
                    state.miter=value(a,"stroke-miterlimit",state.miter,1,1000);
                    if (a.containsKey("stroke-linecap")) {
                        String cap=a.get("stroke-linecap");
                        if (cap.equals("round")) state.cap=Paint.Cap.ROUND; else if (cap.equals("square")) state.cap=Paint.Cap.SQUARE;
                        else if (cap.equals("butt")) state.cap=Paint.Cap.BUTT; else throw invalid("SVG 线端无效");
                    }
                    if (a.containsKey("stroke-linejoin")) {
                        String join=a.get("stroke-linejoin");
                        if (join.equals("round")) state.join=Paint.Join.ROUND; else if (join.equals("bevel")) state.join=Paint.Join.BEVEL;
                        else if (join.equals("miter")) state.join=Paint.Join.MITER; else throw invalid("SVG 连接无效");
                    }
                    if (a.containsKey("fill-rule")) {
                        if (!a.get("fill-rule").matches("evenodd|nonzero")) throw invalid("SVG 填充规则无效");
                        state.evenOdd=a.get("fill-rule").equals("evenodd");
                    }
                    if (tag.equals("svg")) {
                        if (a.containsKey("preserveAspectRatio") && !a.get("preserveAspectRatio").matches("(?:xMidYMid(?: meet)?|none)"))
                            throw invalid("SVG 仅支持居中等比或 none 缩放");
                        if ("none".equals(a.get("preserveAspectRatio"))) state.stretch=true;
                        float[] v=a.containsKey("viewBox")?numbers(a.get("viewBox"),4):new float[]{0,0,value(a,"width",24,1,9999),value(a,"height",24,1,9999)};
                        if (v.length!=4 || v[2]<.001f||v[3]<.001f||v[2]>=10000||v[3]>=10000) throw invalid("SVG viewBox 数值超出范围");
                        viewport=new RectF(v[0],v[1],v[0]+v[2],v[1]+v[3]);
                        if (!finite(viewport.width())||!finite(viewport.height())
                                ||viewport.width()<.001f||viewport.height()<.001f)
                            throw invalid("SVG viewBox 数值超出范围");
                    } else if (!tag.equals("g")) {
                        Path path=new Path();
                        switch (tag) {
                            case "path":
                                String d=a.get("d"); if (d==null||d.isEmpty()) throw invalid("SVG 路径为空");
                                if (!d.matches("[MmZzLlHhVvCcSsQqTtAa0-9eE+.,\\s-]+")) throw invalid("SVG 路径含不支持指令");
                                int commands=0;
                                for (int i=0;i<d.length();i++) if ("MmZzLlHhVvCcSsQqTtAa".indexOf(d.charAt(i))>=0) commands++;
                                commandBudget+=commands;
                                if (commandBudget>4000) throw invalid("SVG 路径复杂度超过限制");
                                float[] coordinates=numbers(d.replaceAll("[MmZzLlHhVvCcSsQqTtAa]"," "),4000);
                                geometry+=coordinates.length; path=PathParser.createPathFromPathData(d);
                                if (path==null) throw invalid("SVG 路径无效"); break;
                            case "circle":
                                float r=value(a,"r",0,0,10000); path.addCircle(value(a,"cx",0,-100000,100000),value(a,"cy",0,-100000,100000),r,Path.Direction.CW); geometry+=4; break;
                            case "ellipse":
                                float cx=value(a,"cx",0,-100000,100000),cy=value(a,"cy",0,-100000,100000),rx=value(a,"rx",0,0,10000),ry=value(a,"ry",0,0,10000);
                                path.addOval(cx-rx,cy-ry,cx+rx,cy+ry,Path.Direction.CW); geometry+=6; break;
                            case "rect":
                                float x=value(a,"x",0,-100000,100000),y=value(a,"y",0,-100000,100000),w=value(a,"width",0,0,10000),h=value(a,"height",0,0,10000);
                                float cornerX=value(a,"rx",a.containsKey("ry")?value(a,"ry",0,0,10000):0,0,10000),cornerY=value(a,"ry",cornerX,0,10000);
                                path.addRoundRect(x,y,x+w,y+h,Math.min(w/2,cornerX),Math.min(h/2,cornerY),Path.Direction.CW); geometry+=8; break;
                            case "line":
                                path.moveTo(value(a,"x1",0,-100000,100000),value(a,"y1",0,-100000,100000));
                                path.lineTo(value(a,"x2",0,-100000,100000),value(a,"y2",0,-100000,100000)); geometry+=4; break;
                            default:
                                float[] p=numbers(a.containsKey("points")?a.get("points"):"",4000);
                                if (p.length<4 || p.length%2!=0) throw invalid("SVG 顶点无效");
                                path.moveTo(p[0],p[1]); for (int i=2;i<p.length;i+=2) path.lineTo(p[i],p[i+1]);
                                if (tag.equals("polygon")) path.close(); geometry+=p.length;
                        }
                        if (geometry>4000) throw invalid("SVG 路径复杂度超过限制");
                        RectF bounds=new RectF(); path.computeBounds(bounds,true); state.matrix.mapRect(bounds);
                        for (float coordinate:new float[]{bounds.left,bounds.top,bounds.right,bounds.bottom})
                            if (Float.isNaN(coordinate)||Float.isInfinite(coordinate)||Math.abs(coordinate)>1000000)
                                throw invalid("SVG 变换后的图形超出范围");
                        path.setFillType(state.evenOdd?Path.FillType.EVEN_ODD:Path.FillType.WINDING);
                        parts.add(new Part(path,state));
                    }
                    stack.add(state);
                } else if (event==XmlPullParser.END_TAG) stack.remove(stack.size()-1);
                else if (event==XmlPullParser.TEXT || event==XmlPullParser.CDSECT || event==XmlPullParser.ENTITY_REF) {
                    String content=parser.getText();
                    if (content==null || !content.trim().isEmpty()) throw invalid("SVG 不支持文字内容");
                }
            }
            boolean drawable=false;
            for (Part part:parts) if (!part.path.isEmpty() && part.state.opacity>0
                    && (part.state.fill*part.state.fillOpacity>0
                    ||part.state.stroke*part.state.strokeOpacity>0&&part.state.width>0)) drawable=true;
            if (viewport==null||!drawable) throw invalid("SVG 没有可用图形");
            BubbleSvg result=new BubbleSvg(parts,viewport); CACHE.put(source,result);
            while (CACHE.size()>MAX_CACHED_SVGS) CACHE.remove(CACHE.keySet().iterator().next()); return result;
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw invalid("SVG 文件格式或路径错误"); }
    }
    public void drawMask(Canvas canvas,RectF area) {
        boolean stretch=parts.get(0).state.stretch;
        float sx=area.width()/viewport.width(),sy=area.height()/viewport.height();
        if (!stretch) sx=sy=Math.min(sx,sy);
        if (!finite(sx)||!finite(sy)||sx<=0||sy<=0) throw invalid("SVG 绘制尺寸超出范围");
        canvas.save(); canvas.clipRect(area);
        canvas.translate(area.centerX()-viewport.width()*sx/2,area.centerY()-viewport.height()*sy/2);
        canvas.scale(sx,sy); canvas.translate(-viewport.left,-viewport.top);
        for (Part part:parts) {
            State s=part.state; canvas.save(); canvas.concat(s.matrix);
            Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(Color.BLACK);
            if (s.fill>0) { p.setStyle(Paint.Style.FILL); p.setAlpha(Math.round(255*s.fill*s.opacity*s.fillOpacity)); canvas.drawPath(part.path,p); }
            if (s.stroke>0 && s.width>0) {
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(s.width); p.setStrokeCap(s.cap); p.setStrokeJoin(s.join); p.setStrokeMiter(s.miter);
                p.setAlpha(Math.round(255*s.stroke*s.opacity*s.strokeOpacity)); canvas.drawPath(part.path,p);
            }
            canvas.restore();
        }
        canvas.restore();
    }
    private static float value(Map<String,String> a,String name,float fallback,float min,float max) {
        if (!a.containsKey(name)) return fallback;
        String raw=a.get(name); if (!NUMBER.matcher(raw).matches()) throw invalid("SVG 数值属性不支持单位或表达式："+name);
        float result=Float.parseFloat(raw); if (Float.isNaN(result)||Float.isInfinite(result)||result<min||result>max) throw invalid("SVG 数值超出范围："+name); return result;
    }
    private static float[] numbers(String source,int max) {
        List<Float> list=new ArrayList<>(); Matcher m=NUMBER.matcher(source); int end=0;
        while (m.find()) {
            if (!source.substring(end,m.start()).matches("[,\\s]*")) throw invalid("SVG 数字格式无效");
            float value=Float.parseFloat(m.group()); if (Float.isNaN(value)||Float.isInfinite(value)||Math.abs(value)>100000) throw invalid("SVG 数值超出范围");
            list.add(value); if (list.size()>max) throw invalid("SVG 路径复杂度超过限制"); end=m.end();
        }
        if (!source.substring(end).matches("[,\\s]*")) throw invalid("SVG 数字格式无效");
        float[] result=new float[list.size()]; for (int i=0;i<result.length;i++) result[i]=list.get(i); return result;
    }
    private static Matrix transform(String source) {
        Matrix result=new Matrix(); Matcher m=TRANSFORM.matcher(source); int end=0;
        while (m.find()) {
            if (!source.substring(end,m.start()).trim().isEmpty()) throw invalid("SVG transform 无效");
            float[] n=numbers(m.group(2),6); Matrix step=new Matrix();
            switch (m.group(1)) {
                case "matrix": if (n.length!=6) throw invalid("SVG matrix 无效"); step.setValues(new float[]{n[0],n[2],n[4],n[1],n[3],n[5],0,0,1}); break;
                case "translate": if (n.length<1||n.length>2) throw invalid("SVG translate 无效"); step.setTranslate(n[0],n.length==2?n[1]:0); break;
                case "scale": if (n.length<1||n.length>2) throw invalid("SVG scale 无效"); step.setScale(n[0],n.length==2?n[1]:n[0]); break;
                case "rotate": if (n.length!=1&&n.length!=3) throw invalid("SVG rotate 无效"); step.setRotate(n[0],n.length==3?n[1]:0,n.length==3?n[2]:0); break;
                default: if (n.length!=1||Math.abs(n[0])>=89) throw invalid("SVG skew 超出范围"); float tangent=(float)Math.tan(Math.toRadians(n[0])); step.setSkew(m.group(1).equals("skewX")?tangent:0,m.group(1).equals("skewY")?tangent:0);
            }
            result.preConcat(step); end=m.end();
            validateMatrix(result);
        }
        if (!source.substring(end).trim().isEmpty()) throw invalid("SVG transform 无效"); return result;
    }
    private static float alphaColor(String value) {
        if (value.equals("none")||value.equals("transparent")) return 0;
        if (value.equals("currentColor")) return 1;
        if (value.matches("#[0-9a-fA-F]{3}|#[0-9a-fA-F]{6}")) return 1;
        if (value.matches("#[0-9a-fA-F]{4}")) return Integer.parseInt(value.substring(4),16)/15f;
        if (value.matches("#[0-9a-fA-F]{8}")) return Integer.parseInt(value.substring(7),16)/255f;
        try { return Color.alpha(Color.parseColor(value))/255f; }
        catch (IllegalArgumentException unsupported) { throw invalid("SVG 不支持颜色表达式："+value); }
    }
    private static void validateMatrix(Matrix matrix) {
        float[] values=new float[9]; matrix.getValues(values);
        for (float value:values) if (Float.isNaN(value)||Float.isInfinite(value)||Math.abs(value)>100000)
            throw invalid("SVG 变换复杂度或数值超出范围");
    }
    private static boolean finite(float value) { return !Float.isNaN(value)&&!Float.isInfinite(value); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
    private static final class Part {
        final Path path; final State state; Part(Path path,State state) { this.path=path; this.state=state; }
    }
    private static final class State {
        Matrix matrix=new Matrix(); float fill=1,stroke=0,width=1,opacity=1,fillOpacity=1,strokeOpacity=1,miter=4;
        Paint.Cap cap=Paint.Cap.BUTT; Paint.Join join=Paint.Join.MITER; boolean evenOdd,stretch;
        State() { }
        State(State parent) {
            matrix=new Matrix(parent.matrix); fill=parent.fill; stroke=parent.stroke; width=parent.width;
            opacity=parent.opacity; fillOpacity=parent.fillOpacity; strokeOpacity=parent.strokeOpacity;
            miter=parent.miter; cap=parent.cap; join=parent.join; evenOdd=parent.evenOdd; stretch=parent.stretch;
        }
    }
}
