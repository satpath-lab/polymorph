package dev.volt.player;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class VoltAlarm extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        post(c, i.getStringExtra("title"), i.getStringExtra("text"), i.getStringExtra("tag"));
    }

    static int reqCode() { return (int) (System.nanoTime() & 0x7fffffff); }

    static void schedule(Context c, String title, String text, String tag, long delayMs) {
        Intent i = new Intent(c, VoltAlarm.class).putExtra("title", title).putExtra("text", text).putExtra("tag", tag);
        PendingIntent pi = PendingIntent.getBroadcast(c, reqCode(), i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMs, pi);
    }

    static void post(Context c, String title, String text, String tag) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel("volt", "Notifications", NotificationManager.IMPORTANCE_HIGH));
        Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        open.putExtra("volt_tap", tag == null ? "" : tag);
        open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int req = reqCode();
        PendingIntent pi = PendingIntent.getActivity(c, req, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, "volt") : new Notification.Builder(c);
        int icon = c.getResources().getIdentifier("ic_stat", "drawable", c.getPackageName());
        b.setSmallIcon(icon).setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true).setContentIntent(pi).setColor(0xFF6C3BFF);
        nm.notify(req, b.build());
    }
}
