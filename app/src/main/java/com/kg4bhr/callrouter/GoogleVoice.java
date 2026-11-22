package com.kg4bhr.callrouter;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;

/** Hands a number to the Google Voice app. */
public final class GoogleVoice {
    public static final String PKG = "com.google.android.apps.googlevoice";

    private GoogleVoice() {}

    public static boolean installed(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Best intent Google Voice accepts: CALL (places it), then DIAL (number pre-filled), then VIEW. */
    public static Intent intent(Context ctx, String number) {
        Uri uri = Uri.fromParts("tel", number, null);
        for (String action : new String[]{Intent.ACTION_CALL, Intent.ACTION_DIAL, Intent.ACTION_VIEW}) {
            Intent i = new Intent(action, uri).setPackage(PKG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(ctx.getPackageManager()) != null) {
                addAccount(i, new Prefs(ctx).gvAccount());
                return i;
            }
        }
        return null;
    }

    /**
     * Experimental: Google Voice has no documented "use this account" extra, so pass the account
     * under the names Google apps commonly read. Unknown extras are simply ignored.
     */
    static void addAccount(Intent i, String email) {
        if (email == null || email.isEmpty()) return;
        i.putExtra("authAccount", email);          // AccountManager.KEY_ACCOUNT_NAME
        i.putExtra("accountName", email);
        i.putExtra("account_name", email);
        i.putExtra("account", email);
        i.putExtra("com.google.android.apps.googlevoice.ACCOUNT_NAME", email);
        i.putExtra("selected_account", new android.accounts.Account(email, "com.google"));
        i.putExtra("android.accounts.Account", new android.accounts.Account(email, "com.google"));
    }

    public static String describe(Context ctx) {
        if (!installed(ctx)) return "not installed";
        Intent i = intent(ctx, "2565550100");
        if (i == null) return "installed, but it accepts no call links";
        if (Intent.ACTION_CALL.equals(i.getAction())) return "places calls directly";
        if (Intent.ACTION_DIAL.equals(i.getAction())) return "opens with number filled in (tap call)";
        return "opens the number (may need a tap)";
    }

    /**
     * Launch Google Voice. Starting an activity from a background service is only allowed when the
     * app has "Display over other apps"; without it, post a tap-to-call notification instead.
     */
    public static boolean launch(Context ctx, String number) {
        Intent i = intent(ctx, number);
        if (i == null) return false;
        new Prefs(ctx).markHandoff();
        if (Settings.canDrawOverlays(ctx)) {
            try {
                ctx.startActivity(i);
                return true;
            } catch (RuntimeException e) {
                // fall through to notification
            }
        }
        Notifier.tapToCall(ctx, number, i);
        return true;
    }
}
