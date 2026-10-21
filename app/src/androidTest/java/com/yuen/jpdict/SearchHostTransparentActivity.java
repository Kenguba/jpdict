package com.yuen.jpdict;

import android.app.Activity;
import android.content.*;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

/** A different-package translucent foreground window; it does not emulate Eudic's protocol. */
public final class SearchHostTransparentActivity extends Activity {
    private String replyPackage, nonce;
    private final BroadcastReceiver close = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (nonce.equals(intent.getStringExtra("nonce"))) finish();
        }
    };
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        replyPackage = getIntent().getStringExtra("reply_package");
        nonce = getIntent().getStringExtra("nonce");
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setDimAmount(0);
        FrameLayout root = new FrameLayout(this);
        TextView card = new TextView(this);
        card.setText("External window fixture"); card.setTextColor(Color.WHITE); card.setBackgroundColor(0xff6551a0);
        int unit = Math.round(getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(190*unit,44*unit,Gravity.TOP|Gravity.LEFT);
        params.topMargin = 24*unit; root.addView(card,params); setContentView(root);
        IntentFilter filter = new IntentFilter(getPackageName()+".SEARCH_HOST_QA_CLOSE");
        if (Build.VERSION.SDK_INT>=33) registerReceiver(close,filter,Context.RECEIVER_EXPORTED);
        else registerReceiver(close,filter);
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused); report(focused ? "focused" : "unfocused");
    }
    @Override protected void onDestroy() {
        unregisterReceiver(close); report("closed"); super.onDestroy();
    }
    private void report(String state) {
        sendBroadcast(new Intent(replyPackage+".SEARCH_HOST_QA_STATUS").setPackage(replyPackage)
                .putExtra("nonce",nonce).putExtra("state",state));
    }
}
