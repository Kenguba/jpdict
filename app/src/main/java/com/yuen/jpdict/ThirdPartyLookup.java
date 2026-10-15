package com.yuen.jpdict;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/** The explicit external dictionary route requested by the user. */
final class ThirdPartyLookup {
    private ThirdPartyLookup() {}

    static Intent intent(String query) {
        return new Intent("colordict.intent.action.SEARCH")
                .addCategory(Intent.CATEGORY_DEFAULT)
                .setDataAndType(Uri.parse("eudic:peek"), "text/plain")
                .setComponent(new ComponentName("com.eusoft.trans.en",
                        "com.eusoft.dict.activity.dict.LightpeekActivity"))
                .putExtra("EXTRA_QUERY", query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    static boolean launch(Context context, String query) {
        try {
            context.startActivity(intent(query));
            return true;
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(context, "无法打开第三方词典，请安装或启用欧路词典", Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
