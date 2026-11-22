package com.kg4bhr.callrouter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

public final class Notifier {
    private static final String CH_INFO = "routing";
    private static final String CH_ACTION = "action";
    private static final int ID_DRY = 1, ID_ERR = 2, ID_TAP = 3;

    private Notifier() {}

    private static NotificationManager nm(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_INFO, "Routing decisions",
                NotificationManager.IMPORTANCE_DEFAULT));
        nm.createNotificationChannel(new NotificationChannel(CH_ACTION, "Tap to call",
                NotificationManager.IMPORTANCE_HIGH));
        return nm;
    }

    private static PendingIntent openApp(Context ctx) {
        return PendingIntent.getActivity(ctx, 0, new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static void dryRun(Context ctx, String dialed, RouteEngine.Decision d) {
        nm(ctx).notify(ID_DRY, new Notification.Builder(ctx, CH_INFO)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("Dry run: " + dialed + " → " + RouteEngine.label(d.line))
                .setContentText("Would dial " + d.dial + " (" + d.reason + "). Placed on cell unchanged.")
                .setStyle(new Notification.BigTextStyle().bigText(
                        "Would dial " + d.dial + " via " + RouteEngine.label(d.line)
                                + "\nReason: " + d.reason + "\nDry run is on, so the call went out unchanged."))
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .build());
    }

    public static void error(Context ctx, String title, String text) {
        nm(ctx).notify(ID_ERR, new Notification.Builder(ctx, CH_INFO)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .build());
    }

    public static void tapToCall(Context ctx, String number, Intent gvIntent) {
        PendingIntent pi = PendingIntent.getActivity(ctx, 1, gvIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm(ctx).notify(ID_TAP, new Notification.Builder(ctx, CH_ACTION)
                .setSmallIcon(android.R.drawable.sym_action_call)
                .setContentTitle("Tap to call " + number + " with Google Voice")
                .setContentText("Grant \"Display over other apps\" in Call Router to skip this step.")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_CALL)
                .build());
    }
}
