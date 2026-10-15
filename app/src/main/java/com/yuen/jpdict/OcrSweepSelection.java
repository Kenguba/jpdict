package com.yuen.jpdict;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.IdentityHashMap;

/** Selects actual glyph bounds along the pointer path; a return pass keeps its extrema. */
final class OcrSweepSelection {
    final List<MultilingualOcrRecognizer.Region> picked = new ArrayList<>();
    private final IdentityHashMap<MultilingualOcrRecognizer.Region, int[]> ranges = new IdentityHashMap<>();
    private final List<MultilingualOcrRecognizer.Region> sources = new ArrayList<>();
    private boolean bounded, hasPoint;
    private float lastX, lastY;

    void clear() { picked.clear(); sources.clear(); ranges.clear(); bounded = hasPoint = false; }

    // Element-level fallback for model versions without symbol geometry.
    void touch(MultilingualOcrRecognizer.Region region, OcrPickupMode mode) {
        if (region == null || sources.contains(region)) return;
        if (distant(region) || sources.size() >= 64) clear();
        sources.add(region); ranges.put(region, new int[]{-1, -1}); rebuild();
    }

    void move(List<MultilingualOcrRecognizer.Region> regions, float x, float y, float tolerance) {
        MultilingualOcrRecognizer.Region touched = MultilingualOcrRecognizer.regionAt(regions, x, y, tolerance);
        if (touched != null && (distant(touched) || sources.size() >= 64)) clear();
        if (touched == null && !picked.isEmpty()) {
            Rect nearby = bounds(); int gap = Math.max(nearby.height() * 2, Math.round(tolerance * 2));
            nearby.inset(-gap, -gap);
            if (!nearby.contains(Math.round(x), Math.round(y))) clear();
        }
        // Include every crossed symbol, even when Android coalesces a fast sweep
        // into just an endpoint. Returning across it never removes an endpoint.
        if (hasPoint && Math.hypot(x-lastX, y-lastY) > 0.5f) {
            for (MultilingualOcrRecognizer.Region region : regions) {
                if (region.symbols.isEmpty()) {
                    if (intersects(region.bounds, lastX, lastY, x, y)
                            && MultilingualOcrRecognizer.regionAt(regions,region.bounds.exactCenterX(),region.bounds.exactCenterY(),0)==region) add(region, -1);
                } else for (int i=0; i<region.symbols.size(); i++) {
                    Rect b=region.symbols.get(i).bounds;
                    if (intersects(b, lastX, lastY, x, y)
                            && MultilingualOcrRecognizer.regionAt(regions,b.exactCenterX(),b.exactCenterY(),0)==region) add(region, i);
                }
            }
        }
        if (touched != null) add(touched, symbolAt(touched, x, y));
        lastX = x; lastY = y; hasPoint = true; rebuild();
    }

    private boolean distant(MultilingualOcrRecognizer.Region region) {
        if (picked.isEmpty()) return false;
        Rect nearby = bounds(); int gap = Math.max(region.bounds.height(), nearby.height()) * 2;
        nearby.inset(-gap, -gap);
        return !Rect.intersects(nearby, region.bounds);
    }

    private void add(MultilingualOcrRecognizer.Region region, int symbol) {
        int[] range = ranges.get(region);
        if (range == null) {
            if (sources.size() >= 64) return;
            if (!sources.isEmpty()) bounded = true;
            sources.add(region); ranges.put(region, new int[]{symbol, symbol});
        } else if (symbol >= 0 && range[0] >= 0) {
            if (symbol < range[0] || symbol > range[1]) bounded = true;
            range[0] = Math.min(range[0], symbol); range[1] = Math.max(range[1], symbol);
        }
    }

    private void rebuild() {
        picked.clear();
        for (MultilingualOcrRecognizer.Region source : sources) {
            int[] range = ranges.get(source);
            picked.add(bounded && range[0] >= 0 ? source.slice(range[0], range[1]) : source);
        }
    }

    private static int symbolAt(MultilingualOcrRecognizer.Region region, float x, float y) {
        double best = Double.MAX_VALUE; int index = -1;
        for (int i=0; i<region.symbols.size(); i++) {
            Rect b = region.symbols.get(i).bounds;
            double dx = Math.max(Math.max(b.left-x, x-b.right), 0);
            double dy = Math.max(Math.max(b.top-y, y-b.bottom), 0);
            double distance = dx*dx+dy*dy;
            // Resolve shared boundaries by nearest glyph centre, independent of
            // model iteration order and without approximating character widths.
            double rank = distance + Math.abs(b.exactCenterX()-x)*0.001;
            if (rank < best) { best = rank; index = i; }
        }
        return index;
    }

    private static boolean intersects(Rect b, float x0, float y0, float x1, float y1) {
        float low = 0, high = 1;
        float[] start = {x0, y0}, delta = {x1-x0, y1-y0};
        int[] min = {b.left, b.top}, max = {b.right, b.bottom};
        for (int axis=0; axis<2; axis++) {
            if (Math.abs(delta[axis]) < 0.0001f) {
                if (start[axis] < min[axis] || start[axis] > max[axis]) return false;
            } else {
                float a = (min[axis]-start[axis])/delta[axis], z = (max[axis]-start[axis])/delta[axis];
                low = Math.max(low, Math.min(a,z)); high = Math.min(high, Math.max(a,z));
                if (high < low) return false;
            }
        }
        return true;
    }

    Rect bounds() {
        Rect bounds = new Rect();
        for (MultilingualOcrRecognizer.Region region : picked) bounds.union(region.bounds);
        return bounds;
    }

    MultilingualOcrRecognizer.Region combined() {
        if (picked.isEmpty()) return null;
        if (picked.size() == 1) return picked.get(0);
        List<MultilingualOcrRecognizer.Region> sorted = new ArrayList<>(picked);
        Collections.sort(sorted, (first, second) -> {
            int vertical = Integer.compare(first.bounds.centerY(), second.bounds.centerY());
            return vertical != 0 ? vertical : Integer.compare(first.bounds.left, second.bounds.left);
        });
        List<MultilingualOcrRecognizer.Region> ordered = new ArrayList<>();
        for (int index=0;index<sorted.size();) {
            MultilingualOcrRecognizer.Region anchor=sorted.get(index);
            List<MultilingualOcrRecognizer.Region> line=new ArrayList<>();line.add(anchor);index++;
            while(index<sorted.size() && Math.abs(sorted.get(index).bounds.centerY()-anchor.bounds.centerY())
                    <=Math.min(sorted.get(index).bounds.height(),anchor.bounds.height())/2)line.add(sorted.get(index++));
            Collections.sort(line, (first, second) -> Integer.compare(first.bounds.left, second.bounds.left));ordered.addAll(line);
        }
        StringBuilder text = new StringBuilder(); float quality = 0;
        MultilingualOcrRecognizer.Region previous = null;
        for (MultilingualOcrRecognizer.Region region : ordered) {
            if (previous != null) {
                if (Math.abs(previous.bounds.centerY()-region.bounds.centerY()) > Math.min(previous.bounds.height(), region.bounds.height())/2)text.append('\n');
                else if (!cjk(previous.text.codePointBefore(previous.text.length())) || !cjk(region.text.codePointAt(0))) {
                    float unit = Math.min(previous.bounds.height(), region.bounds.height());
                    if (region.bounds.left-previous.bounds.right >= Math.max(1, unit/6)) text.append(' ');
                }
            }
            text.append(region.text); quality += region.quality; previous = region;
        }
        MultilingualOcrRecognizer.Region combined = new MultilingualOcrRecognizer.Region(text.toString(), bounds());
        combined.quality = quality / picked.size(); combined.boundedRange = true; return combined;
    }
    private static boolean cjk(int codePoint) {
        return (codePoint>=0x3040 && codePoint<=0x30ff) || (codePoint>=0x3400 && codePoint<=0x9fff)
                || (codePoint>=0xac00 && codePoint<=0xd7af) || (codePoint>=0x20000 && codePoint<=0x323af);
    }
}
