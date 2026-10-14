package com.yuen.jpdict;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Fresh current-clip reads with process-wide duplicate consumption protection. */
final class SearchClipboard {
    private static final Consumption consumed = new Consumption();

    static String recentText(Context context, boolean changed) {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) return null;
            ClipDescription description = clipboard.getPrimaryClipDescription();
            if (!isText(description)) return null;
            long now = System.currentTimeMillis();
            if (Build.VERSION.SDK_INT >= 26 && !isRecent(description.getTimestamp(), now)) return null;
            ClipData clip = clipboard.getPrimaryClip();
            long copiedAt = Build.VERSION.SDK_INT >= 26 && clip != null ? clip.getDescription().getTimestamp() : 0;
            return consumed.take(clip, copiedAt, now, changed);
        } catch (SecurityException unavailable) { return null; }
    }

    private static boolean isText(ClipDescription description) {
        return description != null && (description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                || description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML));
    }

    private static boolean isRecent(long copiedAt, long now) {
        return copiedAt <= 0 || (copiedAt <= now && now - copiedAt <= 120000);
    }

    static final class Consumption {
        private byte[] lastDigest;
        private long lastCopiedAt;

        synchronized String take(ClipData clip, long copiedAt, long now, boolean changed) {
            if (clip == null || !isText(clip.getDescription()) || clip.getItemCount() == 0 || !isRecent(copiedAt, now)) return null;
            CharSequence text = clip.getItemAt(0).getText();
            if (text == null || text.length() > 65536 || text.toString().trim().isEmpty()) return null;
            String value = text.toString();
            final byte[] digest;
            try { digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
            catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
            // With no timestamp, only an actual foreground change event can
            // distinguish copying identical text again from restoring focus.
            if (copiedAt == lastCopiedAt && java.util.Arrays.equals(lastDigest, digest)
                    && (copiedAt > 0 || !changed)) return null;
            lastCopiedAt = copiedAt; lastDigest = digest;
            return value;
        }
    }
    private SearchClipboard() { }
}
