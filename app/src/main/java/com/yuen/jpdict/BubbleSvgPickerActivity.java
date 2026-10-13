package com.yuen.jpdict;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.content.res.AssetFileDescriptor;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** SAF drawing-only SVG import; no provider URI or draft is committed here. */
public final class BubbleSvgPickerActivity extends Activity {
    public static final int REQUEST_PICK_SVG = 42;
    public static final String EXTRA_SVG_SOURCE = "bubble_svg_source";
    public static final String EXTRA_SVG_NAME = "bubble_svg_name";
    private static final int REQUEST_DOCUMENT = 1;
    private static final int MAX_BYTES = 120 * 1024;
    private Uri selectedUri;
    private boolean importing;
    private final CancellationSignal cancellation = new CancellationSignal();
    private volatile InputStream activeInput;
    private volatile boolean closed;

    public static void launch(Activity activity) {
        try {
            activity.startActivityForResult(new Intent(activity, BubbleSvgPickerActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_NO_USER_ACTION),
                    REQUEST_PICK_SVG);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(activity, "无法打开 SVG 选择器", Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        overridePendingTransition(0, 0);
        if (state != null) {
            String uri = state.getString("svg_uri");
            if (uri != null) { selectedUri = Uri.parse(uri); importSelected(); }
            return; // Android restores the outstanding SAF result.
        }
        Intent document = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/svg+xml", "text/xml",
                        "application/xml", "application/octet-stream"})
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivityForResult(document, REQUEST_DOCUMENT); }
        catch (ActivityNotFoundException error) {
            Toast.makeText(this, "此设备没有可用的文件选择器", Toast.LENGTH_LONG).show();
            close();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (selectedUri != null) state.putString("svg_uri", selectedUri.toString());
        super.onSaveInstanceState(state);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQUEST_DOCUMENT) return;
        if (result != RESULT_OK || data == null || data.getData() == null) { close(); return; }
        selectedUri = data.getData();
        importSelected();
    }

    private void importSelected() {
        if (importing) return;
        importing = true;
        Uri uri = selectedUri;
        new Thread(() -> {
            String source = null, name = "自定义图标.svg", message = null;
            try {
                if (!"content".equals(uri.getScheme())) throw new IOException("请选择本机文件");
                try (Cursor cursor = getContentResolver().query(uri,
                        new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null, cancellation)) {
                    if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
                }
                if (!name.toLowerCase(Locale.ROOT).endsWith(".svg"))
                    throw new IOException("请选择 .svg 文件");
                name = FloatingBubbleAppearance.safeSvgName(name);
                try (AssetFileDescriptor descriptor = getContentResolver()
                        .openAssetFileDescriptor(uri, "r", cancellation)) {
                    if (descriptor == null) throw new IOException("SVG 无法读取");
                    try (InputStream input = descriptor.createInputStream()) {
                    activeInput = input;
                    if (closed) throw new IOException("已取消导入");
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (closed) throw new IOException("已取消导入");
                        if (bytes.size() + count > MAX_BYTES) throw new IOException("SVG 文件请小于 120KB");
                        bytes.write(buffer, 0, count);
                    }
                    source = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
                    } finally { activeInput = null; }
                }
                FloatingBubbleAppearance.validateSvg(source);
            } catch (IOException | RuntimeException error) {
                message = error.getMessage();
                source = null;
            }
            final String imported = source, fileName = name, failure = message;
            runOnUiThread(() -> {
                if (closed || isFinishing() || isDestroyed()) return;
                if (imported != null) setResult(RESULT_OK, new Intent()
                        .putExtra(EXTRA_SVG_SOURCE, imported).putExtra(EXTRA_SVG_NAME, fileName));
                else Toast.makeText(this, "导入失败：" + (failure == null ? "无效 SVG" : failure),
                        Toast.LENGTH_LONG).show();
                close();
            });
        }, "BubbleSvgImport").start();
    }

    private void close() { finish(); overridePendingTransition(0, 0); }
    @Override public void onBackPressed() { close(); }
    @Override protected void onDestroy() {
        closed = true;
        cancellation.cancel();
        InputStream input = activeInput;
        if (input != null) try { input.close(); } catch (IOException ignored) { }
        super.onDestroy();
    }
}
