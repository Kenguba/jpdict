package com.yuen.jpdict;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Selects actual glyph bounds along the pointer path; a return pass keeps its extrema. */
final class OcrSweepSelection {
    private static final Pattern LATIN_WORD = Pattern.compile("[\\p{IsLatin}\\p{M}]+(?:[-'’][\\p{IsLatin}\\p{M}]+)*");
    final List<MultilingualOcrRecognizer.Region> picked = new ArrayList<>();
    private final IdentityHashMap<MultilingualOcrRecognizer.Region, int[]> ranges = new IdentityHashMap<>();
    private final List<MultilingualOcrRecognizer.Region> sources = new ArrayList<>();
    private boolean bounded, hasPoint, precise, hasDirection;
    private MultilingualOcrRecognizer.Region automatic;
    private float lastX, lastY, originX, originY, directionX, directionY, furthest;

    void clear() {
        picked.clear(); sources.clear(); ranges.clear(); automatic = null;
        bounded = hasPoint = precise = hasDirection = false; furthest = 0;
    }

    // Element-level fallback for model versions without symbol geometry.
    void touch(MultilingualOcrRecognizer.Region region, OcrPickupMode mode) {
        if (region == null || sources.contains(region)) return;
        if (distant(region) || sources.size() >= 64) clear();
        sources.add(region); ranges.put(region, new int[]{-1, -1}); rebuild();
    }

    void move(List<MultilingualOcrRecognizer.Region> regions, float x, float y, float tolerance) {
        move(regions, x, y, tolerance, false);
    }

    void move(List<MultilingualOcrRecognizer.Region> regions, float x, float y, float tolerance, boolean singleEnglishWord) {
        if (singleEnglishWord) {
            // Hit-test individual tokens, never the union of a swept line.
            MultilingualOcrRecognizer.Region word = englishWordAt(regions, x, y, tolerance);
            MultilingualOcrRecognizer.Region previous = picked.isEmpty() ? null : picked.get(0);
            if (previous != null && withinWordTolerance(previous.bounds, x, y, tolerance)
                    && (word == null || (!contains(word.bounds, x, y)
                    && distance(previous.bounds, x, y) <= distance(word.bounds, x, y)))) word = previous;
            picked.clear();
            if (word != null) picked.add(word);
            return;
        }
        MultilingualOcrRecognizer.Region touched = MultilingualOcrRecognizer.regionAt(regions, x, y, tolerance);
        if (touched != null && (distant(touched) || sources.size() >= 64)) clear();
        if (touched == null && !picked.isEmpty()) {
            Rect nearby = bounds(); int gap = Math.max(nearby.height() * 2, Math.round(tolerance * 2));
            nearby.inset(-gap, -gap);
            if (!nearby.contains(Math.round(x), Math.round(y))) clear();
        }
        if (touched != null) automatic = touched;
        if (hasPoint && touched != null && !precise) {
            float threshold = Math.max(2, Math.min(touched.bounds.height()/4f,
                    tolerance > 0 ? tolerance/2 : Float.MAX_VALUE));
            if (!hasDirection && Math.hypot(x-originX, y-originY) >= threshold) {
                float distance = (float)Math.hypot(x-originX, y-originY);
                directionX = (x-originX)/distance; directionY = (y-originY)/distance;
                hasDirection = true;
            }
            if (hasDirection) {
                float distance = (x-originX)*directionX+(y-originY)*directionY;
                // Initial placement and one-way travel select a complete word.
                // A deliberate return pass switches to the saved glyph range;
                // merely crossing a glyph edge must not turn it into a fragment.
                if (furthest-distance >= threshold) precise = true;
                furthest = Math.max(furthest, distance);
            }
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
        if (!hasPoint) { originX = x; originY = y; }
        lastX = x; lastY = y; hasPoint = true; rebuild();
    }

    static MultilingualOcrRecognizer.Region englishWordAt(List<MultilingualOcrRecognizer.Region> regions,
            float x, float y, float tolerance) {
        List<MultilingualOcrRecognizer.Region> words = new ArrayList<>();
        for (MultilingualOcrRecognizer.Region region : regions) {
            MultilingualOcrRecognizer.Region word = englishWordAt(region, x, y, tolerance);
            if (word != null) words.add(word);
        }
        return MultilingualOcrRecognizer.regionAt(words, x, y, tolerance);
    }

    static MultilingualOcrRecognizer.Region englishWordAt(MultilingualOcrRecognizer.Region region, float x, float y) {
        return englishWordAt(region, x, y, 0);
    }

    private static MultilingualOcrRecognizer.Region englishWordAt(MultilingualOcrRecognizer.Region region,
            float x, float y, float tolerance) {
        if (region == null || !withinWordTolerance(region.bounds, x, y, tolerance)) return null;
        List<MultilingualOcrRecognizer.Region> words = new ArrayList<>();
        Matcher match = LATIN_WORD.matcher(region.text);
        while (match.find()) {
            Rect wordBounds = new Rect();
            int covered = match.start();
            for (MultilingualOcrRecognizer.Symbol symbol : region.symbols) {
                if (symbol.start < match.start() || symbol.end > match.end()) continue;
                wordBounds.union(symbol.bounds);
                if (symbol.start <= covered) covered = Math.max(covered, symbol.end);
            }
            if (covered < match.end()) {
                // Missing/partial glyph geometry is safe only for an element
                // that already represents one complete word. Never guess a
                // word's horizontal offset within a geometry-less whole line.
                if (!OcrPickupMode.ENGLISH.clean(region.text).equals(match.group())) continue;
                wordBounds.set(region.bounds);
            }
            if (wordBounds.isEmpty() || !withinWordTolerance(wordBounds, x, y, tolerance)) continue;
            MultilingualOcrRecognizer.Region word = new MultilingualOcrRecognizer.Region(match.group(), wordBounds);
            word.quality = region.quality;
            word.boundedRange = true;
            word.selectedBounds.add(new Rect(wordBounds));
            words.add(word);
        }
        return MultilingualOcrRecognizer.regionAt(words, x, y, tolerance);
    }

    private static boolean withinWordTolerance(Rect bounds, float x, float y, float tolerance) {
        if (contains(bounds, x, y)) return true;
        // Limit snapping by text height, so a large screen-density tolerance
        // cannot reach a distant row or an unrelated word.
        float limit = Math.min(Math.max(0, tolerance), Math.max(2, bounds.height()/3f));
        return limit > 0 && distance(bounds, x, y) <= limit*limit;
    }

    private static boolean contains(Rect bounds, float x, float y) {
        return x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom;
    }

    private static double distance(Rect bounds, float x, float y) {
        double dx = Math.max(Math.max(bounds.left-x, x-bounds.right), 0);
        double dy = Math.max(Math.max(bounds.top-y, y-bounds.bottom), 0);
        return dx*dx+dy*dy;
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
        if (!precise && automatic != null) { picked.add(automatic); return; }
        for (MultilingualOcrRecognizer.Region source : sources) {
            int[] range = ranges.get(source);
            picked.add(bounded && range[0] >= 0 ? source.slice(range[0], range[1]) : source);
        }
    }

    private static int symbolAt(MultilingualOcrRecognizer.Region region, float x, float y) {
        double best = Double.MAX_VALUE; int index = -1; boolean bestInside = false;
        for (int i=0; i<region.symbols.size(); i++) {
            Rect b = region.symbols.get(i).bounds;
            boolean inside = x >= b.left && x < b.right && y >= b.top && y < b.bottom;
            double dx = Math.max(Math.max(b.left-x, x-b.right), 0);
            double dy = Math.max(Math.max(b.top-y, y-b.bottom), 0);
            double distance = dx*dx+dy*dy;
            // Rect uses inclusive left/top and exclusive right/bottom. At a
            // shared edge the actual containing glyph wins over its neighbour.
            double rank = distance + Math.abs(b.exactCenterX()-x)*0.001;
            if ((inside && !bestInside) || (inside == bestInside && rank < best)) {
                best = rank; index = i; bestInside = inside;
            }
        }
        return index;
    }

    private static boolean intersects(Rect b, float x0, float y0, float x1, float y1) {
        float low = 0, high = 1;
        float[] start = {x0, y0}, delta = {x1-x0, y1-y0};
        int[] min = {b.left, b.top}, max = {b.right, b.bottom};
        for (int axis=0; axis<2; axis++) {
            if (Math.abs(delta[axis]) < 0.0001f) {
                if (start[axis] < min[axis] || start[axis] >= max[axis]) return false;
            } else {
                float a = (min[axis]-start[axis])/delta[axis], z = (max[axis]-start[axis])/delta[axis];
                low = Math.max(low, Math.min(a,z)); high = Math.min(high, Math.max(a,z));
                // Merely touching a neighbour's edge/corner is not a sweep
                // through that glyph. The endpoint itself is added by symbolAt.
                if (high <= low) return false;
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
        for (MultilingualOcrRecognizer.Region region : ordered) combined.selectedBounds.add(new Rect(region.bounds));
        combined.quality = quality / picked.size(); combined.boundedRange = true; return combined;
    }
    private static boolean cjk(int codePoint) {
        return (codePoint>=0x3040 && codePoint<=0x30ff) || (codePoint>=0x3400 && codePoint<=0x9fff)
                || (codePoint>=0xac00 && codePoint<=0xd7af) || (codePoint>=0x20000 && codePoint<=0x323af);
    }
}
