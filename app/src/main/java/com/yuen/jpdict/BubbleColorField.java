package com.yuen.jpdict;

import java.util.List;

/** HTML v18's radial basis blend; independent of Android and shared with rendering tests. */
final class BubbleColorField {
    private BubbleColorField() { }
    static int[] pixels(List<FloatingBubbleAppearance.FreePoint> points,int width,int height) {
        if (width<2 || height<2 || width>256 || height>256) throw new IllegalArgumentException("Invalid field dimensions");
        int[] pixels=new int[width*height]; double aspect=(double)width/height;
        double[] spread=new double[points.size()];
        for (int i=0;i<spread.length;i++) {
            double s=.085+.0035*points.get(i).radius; spread[i]=2*s*s;
        }
        for (int y=0;y<height;y++) for (int x=0;x<width;x++) {
            double nx=(double)x/(width-1),ny=(double)y/(height-1),total=0,r=0,g=0,b=0;
            for (int i=0;i<points.size();i++) {
                FloatingBubbleAppearance.FreePoint p=points.get(i);
                double dx=(nx-p.x)*aspect,dy=ny-p.y;
                double weight=Math.exp(-(dx*dx+dy*dy)/spread[i])+.012;
                total+=weight; r+=((p.color>>16)&255)*weight; g+=((p.color>>8)&255)*weight; b+=(p.color&255)*weight;
            }
            // Canvas ImageData uses Uint8ClampedArray (round to nearest, ties to even).
            pixels[y*width+x]=0xff000000|((int)Math.rint(r/total)<<16)|((int)Math.rint(g/total)<<8)|(int)Math.rint(b/total);
        }
        return pixels;
    }
}
