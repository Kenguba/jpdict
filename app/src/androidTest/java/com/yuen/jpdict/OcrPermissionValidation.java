package com.yuen.jpdict;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.PackageManager;
import java.util.concurrent.atomic.AtomicReference;

/** Notification decisions must still lead to the real system capture consent intent. */
final class OcrPermissionValidation {
    static String run(Instrumentation test) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        test.runOnMainSync(() -> {
            try {
                int[] captures = {0}, finishes = {0};
                MainActivity.OcrActivity activity = new MainActivity.OcrActivity() {
                    { attachBaseContext(test.getTargetContext()); }
                    @Override public int checkSelfPermission(String permission) { return PackageManager.PERMISSION_DENIED; }
                    @Override public void startActivityForResult(Intent intent, int request) {
                        if (request != REQ_CAPTURE || intent.getComponent() == null)
                            throw new AssertionError("OCR must request the system screen-sharing dialog");
                        captures[0]++;
                    }
                    @Override public void finish() { finishes[0]++; }
                };
                for (int[] result : new int[][]{{PackageManager.PERMISSION_DENIED}, {PackageManager.PERMISSION_GRANTED}, {}}) {
                    int before = captures[0];
                    activity.onRequestPermissionsResult(MainActivity.OcrActivity.REQ_NOTIFY,
                            new String[]{Manifest.permission.POST_NOTIFICATIONS}, result);
                    if (captures[0] != before + 1 || finishes[0] != 0)
                        throw new AssertionError("Every notification decision must request capture consent exactly once");
                }
                activity.onActivityResult(MainActivity.OcrActivity.REQ_CAPTURE, Activity.RESULT_CANCELED, null);
                if (finishes[0] != 1 || captures[0] != 3)
                    throw new AssertionError("Canceling screen-sharing consent must finish without retrying");
                activity.onRequestPermissionsResult(-1, new String[0], new int[0]);
                if (captures[0] != 3) throw new AssertionError("Unrelated permission results cannot start OCR");
            } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
        return "OCR permission: denial, grant, empty notification result, capture cancellation and unrelated results passed\n";
    }
}
