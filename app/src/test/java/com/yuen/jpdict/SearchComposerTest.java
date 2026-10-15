package com.yuen.jpdict;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Standalone composer regression cases without Android or network dependencies. */
public final class SearchComposerTest {
    public static void main(String[] args) {
        clipboardRetainsExactDisplacedDrafts();
        draftIdentityAndRemovalSurviveRepeatedCopies();
        longestPhraseWinsWithoutChangingOriginalText();
        cjkAndSupplementaryCharactersKeepTheirRanges();
        punctuationAndNewlinesCannotCreateInventedPhrases();
        longInputsArePagedWithoutLoss();
        matchedBatchesAccumulateWithoutLosingLaterText();
        System.out.println("SearchComposer: all draft and segmentation checks passed");
    }
    private static void clipboardRetainsExactDisplacedDrafts() {
        SearchDraftState state = new SearchDraftState();
        String original = "  draft\n第二行 😀 ";
        state.displaced(original, "new copy");
        equal(1, state.list().size()); equal(original, state.list().get(0).text);
        state.displaced("same", "same"); state.displaced(" \n", "next"); state.displaced(null, "next");
        equal(1, state.list().size());
        state.clear(); equal(0, state.list().size());
    }
    private static void draftIdentityAndRemovalSurviveRepeatedCopies() {
        SearchDraftState state = new SearchDraftState();
        state.displaced("first", "copy"); SearchDraftState.Draft first = state.list().get(0);
        state.displaced("second", "copy"); state.displaced("first", "another");
        equal(2, state.list().size()); equal(first, state.list().get(0));
        equal(true, state.contains(first)); equal(true, state.remove(first));
        equal(false, state.remove(first)); equal(false, state.contains(first));
        equal("second", state.list().get(0).text);
    }
    private static void longestPhraseWinsWithoutChangingOriginalText() {
        String original = "I hold on to my well-known friend's book.";
        SearchTextSegments.Page page = SearchTextSegments.page(original, 0);
        List<SearchTextSegments.Part> result = SearchTextSegments.match(page,
                new HashSet<>(Arrays.asList("hold", "hold on", "hold on to", "well-known", "friend's")));
        equal(original, concatenate(result));
        equal(Arrays.asList("hold on to", "well-known", "friend's"), matched(result));
        equal(true, page.candidates.contains("hold on to"));
    }
    private static void cjkAndSupplementaryCharactersKeepTheirRanges() {
        String original = "日本語の勉強と𠮷野家 😀 cafe\u0301";
        SearchTextSegments.Page page = SearchTextSegments.page(original, 0);
        List<SearchTextSegments.Part> result = SearchTextSegments.match(page,
                new HashSet<>(Arrays.asList("日本語", "勉強", "𠮷野家", "cafe\u0301")));
        equal(original, concatenate(result));
        equal(Arrays.asList("日本語", "勉強", "𠮷野家", "cafe\u0301"), matched(result));
        for (SearchTextSegments.Part part : result) {
            equal(part.text, original.substring(part.start, part.end));
            if (!part.text.isEmpty() && Character.isLowSurrogate(part.text.charAt(0))) throw new AssertionError("Split supplementary character");
        }
    }
    private static void punctuationAndNewlinesCannotCreateInventedPhrases() {
        String original = "ice, cream\nhold on; café";
        SearchTextSegments.Page page = SearchTextSegments.page(original, 0);
        equal(false, page.candidates.contains("ice, cream"));
        equal(false, page.candidates.contains("cream\nhold"));
        List<SearchTextSegments.Part> result = SearchTextSegments.match(page,
                new HashSet<>(Arrays.asList("ice, cream", "cream\nhold", "hold on", "café")));
        equal(original, concatenate(result)); equal(Arrays.asList("hold on", "café"), matched(result));
    }
    private static void longInputsArePagedWithoutLoss() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 1000; i++) text.append("word").append(i).append(" 日本語 😀\n");
        String input = text.toString(), output = ""; int offset = 0, pages = 0;
        while (offset < input.length()) {
            SearchTextSegments.Page page = SearchTextSegments.page(input, offset);
            if (page.end <= offset) throw new AssertionError("Paging did not advance");
            if (page.candidates.size() > 384) throw new AssertionError("Unbounded candidate batch");
            output += concatenate(SearchTextSegments.match(page, Collections.emptySet()));
            offset = page.end; pages++;
        }
        equal(input, output); equal(true, pages > 1);
        StringBuilder punctuation = new StringBuilder(); for (int i = 0; i < 200; i++) punctuation.append("😀!");
        SearchTextSegments.Page page = SearchTextSegments.page(punctuation.toString(), 0);
        equal(true, page.hasNext()); equal(0, page.candidates.size());
    }
    private static void matchedBatchesAccumulateWithoutLosingLaterText() {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 240; i++) source.append("hold on ").append(i).append(" 日本語\n");
        String input = source.toString();
        List<SearchTextSegments.Part> displayed = new java.util.ArrayList<>();
        SearchTextSegments.Page first = SearchTextSegments.page(input, 0);
        int offset = 0;
        while (offset < input.length()) {
            SearchTextSegments.Page page = SearchTextSegments.page(input, offset);
            SearchTextSegments.replaceRange(displayed, page, SearchTextSegments.match(page,
                    new HashSet<>(Arrays.asList("hold on", "日本語"))));
            offset = page.end;
        }
        equal(input, concatenate(displayed));
        int count = displayed.size();
        SearchTextSegments.Part last = displayed.get(count - 1);
        List<SearchTextSegments.Part> replacement = SearchTextSegments.match(first, Collections.singleton("hold on"));
        SearchTextSegments.replaceRange(displayed, first, replacement);
        equal(input, concatenate(displayed));
        equal(last, displayed.get(displayed.size() - 1));
        equal(true, matched(displayed).contains("hold on"));
        SearchTextSegments.replaceRange(displayed, first, replacement);
        equal(input, concatenate(displayed));
        equal(last, displayed.get(displayed.size() - 1));
    }
    private static String concatenate(List<SearchTextSegments.Part> parts) {
        StringBuilder output = new StringBuilder(); for (SearchTextSegments.Part part : parts) output.append(part.text); return output.toString();
    }
    private static List<String> matched(List<SearchTextSegments.Part> parts) {
        java.util.ArrayList<String> found = new java.util.ArrayList<>();
        for (SearchTextSegments.Part part : parts) if (part.matched) found.add(part.text);
        return found;
    }
    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
