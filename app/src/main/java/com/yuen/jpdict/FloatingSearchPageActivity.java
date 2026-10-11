package com.yuen.jpdict;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.os.Bundle;

/** Shared lifecycle handling for pages opened from the floating search host. */
abstract class FloatingSearchPageActivity extends Activity {
    private BroadcastReceiver pageSessionReceiver;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        pageSessionReceiver = FloatingSearchRoutes.pageSessionReceiver(this);
        FloatingSearchRoutes.registerPageSessionReceiver(this, pageSessionReceiver);
    }

    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        FloatingSearchRoutes.onPageBackground(this);
    }

    @Override protected void onDestroy() {
        if (pageSessionReceiver != null) {
            try { unregisterReceiver(pageSessionReceiver); }
            catch (RuntimeException ignored) {}
            pageSessionReceiver = null;
        }
        super.onDestroy();
    }
}
