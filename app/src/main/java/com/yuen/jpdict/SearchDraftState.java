package com.yuen.jpdict;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Displaced composer text lives only in the current search session. */
final class SearchDraftState {
    static final class Draft {
        final long id;
        final String text;
        Draft(long id, String text) { this.id = id; this.text = text; }
    }
    private final List<Draft> drafts = new ArrayList<>();
    private long nextId;

    void displaced(String previous, String replacement) {
        if (previous == null || previous.trim().isEmpty() || previous.equals(replacement)) return;
        // One chip per distinct draft; repeatedly copying does not accumulate duplicates.
        for (int i = 0; i < drafts.size(); i++) if (drafts.get(i).text.equals(previous)) {
            Draft existing = drafts.remove(i); drafts.add(0, existing); return;
        }
        drafts.add(0, new Draft(++nextId, previous));
    }
    List<Draft> list() { return Collections.unmodifiableList(new ArrayList<>(drafts)); }
    boolean contains(Draft draft) { return draft != null && drafts.contains(draft); }
    boolean remove(Draft draft) { return drafts.remove(draft); }
    void clear() { drafts.clear(); }
}
