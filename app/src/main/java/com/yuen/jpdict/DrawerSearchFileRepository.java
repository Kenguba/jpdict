package com.yuen.jpdict;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** App-private text-file index used by the drawer's local search page. */
final class DrawerSearchFileRepository {
    private static final String PREFS = "drawer_search_files";
    private static final String INDEX = "index";
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_FILES = 24;

    static final class Document {
        final String id;
        final String name;
        final String content;

        Document(String id, String name, String content) {
            this.id = id;
            this.name = name;
            this.content = content;
        }

    }

    static final class SearchResult {
        final Document document;
        final String preview;

        SearchResult(Document document, String preview) {
            this.document = document;
            this.preview = preview;
        }
    }

    private DrawerSearchFileRepository() {}

    static List<Document> load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = prefs.getString(INDEX, "[]");
        if (raw == null) return Collections.emptyList();
        File directory = directory(context);
        List<Document> result = new ArrayList<>();
        try {
            JSONArray index = new JSONArray(raw);
            for (int i = 0; i < index.length(); i++) {
                JSONObject item = index.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id", "");
                if (!id.matches("[a-f0-9-]{36}")) continue;
                File file = new File(directory, id + ".txt");
                if (!file.isFile() || file.length() > MAX_FILE_BYTES) continue;
                String content = read(file);
                result.add(new Document(id, item.optString("name", "文本文件"), content));
            }
        } catch (Exception ignored) {}
        return result;
    }

    static String importDocument(Context context, Uri uri) throws IOException {
        if (uri == null) throw new IOException("没有选择文件");
        List<Document> current = load(context);
        if (current.size() >= MAX_FILES) throw new IOException("最多保存 " + MAX_FILES + " 个文件");

        ContentResolver resolver = context.getContentResolver();
        String name = displayName(resolver, uri);
        byte[] bytes;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("无法读取所选文件");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_FILE_BYTES) {
                    throw new IOException("文件超过 1 MB，请选择较小的文本文件");
                }
                output.write(buffer, 0, count);
            }
            bytes = output.toByteArray();
        }

        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new IOException("目前只支持 UTF-8 文本文件", error);
        }
        if (content.indexOf('\u0000') >= 0) throw new IOException("目前只支持 UTF-8 文本文件");

        String id = UUID.randomUUID().toString();
        File directory = directory(context);
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("无法创建本地文件索引");
        File target = new File(directory, id + ".txt");
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(bytes);
            output.getFD().sync();
        }

        JSONArray index = readIndex(context);
        JSONObject entry = new JSONObject();
        try {
            entry.put("id", id);
            entry.put("name", name);
            index.put(entry);
        } catch (Exception error) {
            target.delete();
            throw new IOException("无法保存文件索引", error);
        }

        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(INDEX, index.toString()).commit()) {
            target.delete();
            throw new IOException("无法保存文件索引");
        }
        return name;
    }

    static List<SearchResult> search(List<Document> documents, String query) {
        if (documents == null || documents.isEmpty()) return Collections.emptyList();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<SearchResult> matches = new ArrayList<>();
        for (Document document : documents) {
            if (Thread.currentThread().isInterrupted()) return Collections.emptyList();
            boolean nameMatch = document.name.toLowerCase(Locale.ROOT).contains(needle);
            String lowerContent = needle.isEmpty() || nameMatch
                    ? "" : document.content.toLowerCase(Locale.ROOT);
            int hit = needle.isEmpty() ? 0 : lowerContent.indexOf(needle);
            if (!needle.isEmpty() && !nameMatch && hit < 0) continue;

            String preview;
            if (document.content.isEmpty()) {
                preview = "空文件";
            } else {
                int start = hit < 0 ? 0 : Math.max(0, hit - 42);
                int end = Math.min(document.content.length(), start + 110);
                String excerpt = document.content.substring(start, end)
                        .replace('\n', ' ').replace('\r', ' ').trim();
                preview = (start > 0 ? "…" : "") + excerpt
                        + (end < document.content.length() ? "…" : "");
            }
            matches.add(new SearchResult(document, preview));
        }
        return matches;
    }

    private static JSONArray readIndex(Context context) {
        String raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(INDEX, "[]");
        try { return new JSONArray(raw == null ? "[]" : raw); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private static File directory(Context context) {
        return new File(context.getFilesDir(), "drawer_search_files");
    }

    private static String read(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String value = cursor.getString(column);
                    if (value != null && !value.trim().isEmpty()) return value;
                }
            }
        } catch (RuntimeException ignored) {}
        String segment = uri.getLastPathSegment();
        return segment == null || segment.trim().isEmpty() ? "文本文件" : segment;
    }
}
