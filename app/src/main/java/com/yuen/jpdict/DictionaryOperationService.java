package com.yuen.jpdict;

import android.app.*;
import android.content.*;
import android.os.*;

/** Keeps user-started dictionary I/O alive while its progress is minimized. */
public final class DictionaryOperationService extends Service {
    private static final String CHANNEL = "dictionary-operations";
    private static final String RESULT_CHANNEL = "dictionary-operation-results";
    private static final int NOTICE = 7304, RESULT_NOTICE = 7305;
    private static DictionaryOperationService current;
    private boolean foreground, resultPosted;
    static void refresh(Context context) { if (current != null) current.notifyProgress(); }
    static void clearResult(Context context) {
        ((NotificationManager)context.getSystemService(NOTIFICATION_SERVICE)).cancel(RESULT_NOTICE);
        if (current != null) current.resultPosted = false;
    }
    @Override public void onCreate() {
        super.onCreate(); current = this;
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "词库后台加载", NotificationManager.IMPORTANCE_LOW));
            manager.createNotificationChannel(new NotificationChannel(RESULT_CHANNEL, "词库操作结果", NotificationManager.IMPORTANCE_DEFAULT));
        }
        startForeground(NOTICE, notification(false)); foreground = true;
    }
    private Notification notification(boolean result) {
        // Open the page directly, including after the process has been reclaimed.
        // A service PendingIntent cannot reliably launch a chooser on Android 12+.
        Intent page = new Intent(this, DictionaryActivity.class).putExtra("show_dictionary_progress", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent restore = PendingIntent.getActivity(this, 0, page, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, result ? RESULT_CHANNEL : CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_search).setContentTitle(result ? "JPDict · 词库操作结果" : "JPDict · 词库操作")
                .setContentText(DictionaryOperation.get(this).notificationText()).setContentIntent(restore)
                .setOngoing(!result).setAutoCancel(result).setOnlyAlertOnce(!result)
                .setPriority(result ? Notification.PRIORITY_DEFAULT : Notification.PRIORITY_LOW).build();
    }
    private void notifyProgress() {
        DictionaryOperation operation = DictionaryOperation.get(this);
        NotificationManager manager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (operation.running()) {
            if (foreground) manager.notify(NOTICE, notification(false));
            else { startForeground(NOTICE, notification(false)); foreground = true; }
        } else if (operation.hasTask()) {
            if (!resultPosted) { manager.notify(RESULT_NOTICE, notification(true)); resultPosted = true; }
            if (foreground) { stopForeground(true); foreground = false; }
            stopSelf();
        }
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "restore".equals(intent.getAction())) DictionaryOperation.get(this).restore();
        notifyProgress();
        if (!DictionaryOperation.get(this).hasTask()) stopSelf();
        return START_NOT_STICKY;
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration); DictionaryOperation.get(this).configurationChanged();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { if (current == this) current = null; stopForeground(true); foreground = false; super.onDestroy(); }
}
