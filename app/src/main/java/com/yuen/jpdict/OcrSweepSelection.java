package com.yuen.jpdict;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

/** Collect nearby touched text boundaries; repeated passes never duplicate text. */
final class OcrSweepSelection {
    final List<MultilingualOcrRecognizer.Region> picked = new ArrayList<>();
    void clear() { picked.clear(); }
    void touch(MultilingualOcrRecognizer.Region region, OcrPickupMode mode) {
        if (region == null) return;
        for (MultilingualOcrRecognizer.Region old : picked) if (old == region) return;
        Rect bounds = bounds();
        int gap = Math.max(region.bounds.height(), bounds.height()) * 2;
        Rect nearby = new Rect(bounds); nearby.inset(-gap, -gap);
        if (mode == OcrPickupMode.ENGLISH || picked.size() >= 64 || (!picked.isEmpty() && !Rect.intersects(nearby, region.bounds))) clear();
        picked.add(region);
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
        // Group by a fixed line anchor before sorting horizontally. A comparator
        // using pairwise line tolerances can be non-transitive for mixed sizes.
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
                else if (!cjk(previous.text.codePointBefore(previous.text.length())) || !cjk(region.text.codePointAt(0)))text.append(' ');
            }
            text.append(region.text); quality += region.quality; previous = region;
        }
        MultilingualOcrRecognizer.Region combined = new MultilingualOcrRecognizer.Region(text.toString(), bounds());
        combined.quality = quality / picked.size(); return combined;
    }
    private static boolean cjk(int codePoint) {
        return (codePoint>=0x3040 && codePoint<=0x30ff) || (codePoint>=0x3400 && codePoint<=0x9fff)
                || (codePoint>=0xac00 && codePoint<=0xd7af) || (codePoint>=0x20000 && codePoint<=0x323af);
    }
}
