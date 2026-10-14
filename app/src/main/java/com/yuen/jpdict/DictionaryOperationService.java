package com.yuen.jpdict;

import android.app.*;
import android.content.*;
import android.os.*;

/** Keeps user-started dictionary I/O alive while its progress is minimized. */
public final class DictionaryOperationService extends Service {
    private static final String CHANNEL = "dictionary-operations";
    private static final int NOTICE = 7304;
    private static DictionaryOperationService current;
    static void refresh(Context context) { if (current != null) current.notifyProgress(); }
    @Override public void onCreate() {
        super.onCreate(); current = this;
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(new NotificationChannel(CHANNEL, "词库后台加载", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTICE, notification());
    }
    private Notification notification() {
        Intent open = new Intent(this, DictionaryOperationService.class).setAction("restore");
        PendingIntent restore;
        if (android.provider.Settings.canDrawOverlays(this) && !DictionaryOperation.get(this).requiresActivity()) {
            restore = PendingIntent.getService(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else {
            Intent page = new Intent(this, DictionaryActivity.class).putExtra("show_dictionary_progress", true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            restore = PendingIntent.getActivity(this, 0, page, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle("JPDict · 词库操作")
                .setContentText(DictionaryOperation.get(this).notificationText()).setContentIntent(restore)
                .setOngoing(DictionaryOperation.get(this).running()).setOnlyAlertOnce(true).build();
    }
    private void notifyProgress() { ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTICE, notification()); }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "restore".equals(intent.getAction())) DictionaryOperation.get(this).restore();
        return START_NOT_STICKY;
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration); DictionaryOperation.get(this).configurationChanged();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { current = null; stopForeground(true); super.onDestroy(); }
}
