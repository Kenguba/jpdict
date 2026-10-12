package com.yuen.jpdict;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;

/** One foreground read per explicit search opening; never observes the background clipboard. */
final class SearchClipboard {
    static String recentText(Context context) {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return null;
            ClipDescription description = clipboard.getPrimaryClipDescription();
            if (description == null || (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                    && !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML))) return null;
            if (Build.VERSION.SDK_INT >= 26) {
                long copiedAt = description.getTimestamp();
                if (copiedAt > 0 && (System.currentTimeMillis() - copiedAt > 120000
                        || copiedAt > System.currentTimeMillis())) return null;
            }
            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence text = clip.getItemAt(0).getText();
            if (text == null || text.length() > 65536) return null;
            String value = text.toString().trim();
            return value.isEmpty() ? null : value;
        } catch (SecurityException unavailable) { return null; }
    }
    private SearchClipboard() { }
}
