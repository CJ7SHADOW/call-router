package com.kg4bhr.callrouter;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;

/** Helpers shared by the routing service and the "Call with…" picker. */
public final class Lines {
    private static final String TAG = "CallRouter";

    private Lines() {}

    private static boolean sameApp(PhoneAccountHandle a, PhoneAccountHandle b) {
        return a != null && b != null && a.getComponentName().equals(b.getComponentName());
    }

    /** The SIM (T-Mobile) calling account. */
    public static PhoneAccountHandle cellAccount(Context ctx, PhoneAccountHandle initial) {
        PhoneAccountHandle gw = new Prefs(ctx).groundwireAccount();
        if (initial != null && !sameApp(initial, gw)) return initial;
        TelecomManager tm = ctx.getSystemService(TelecomManager.class);
        try {
            PhoneAccountHandle def = tm.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL);
            if (def != null && !sameApp(def, gw)) return def;
            for (PhoneAccountHandle h : tm.getCallCapablePhoneAccounts()) {
                if (sameApp(h, gw)) continue;
                PhoneAccount a = null;
                try { a = tm.getPhoneAccount(h); } catch (RuntimeException ignored) { }
                String pkg = h.getComponentName().getPackageName();
                if ((a != null && a.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION))
                        || pkg.equals("com.android.phone") || pkg.startsWith("com.android.services.telephony")) {
                    return h;
                }
            }
        } catch (SecurityException e) {
            Log.w(TAG, "no permission to list accounts", e);
        }
        return null;
    }

    /** Place a call on a specific account, skipping routing for it. */
    public static boolean placeOn(Context ctx, String number, PhoneAccountHandle account) {
        if (account == null) return false;
        Bundle extras = new Bundle();
        extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account);
        new Prefs(ctx).markHandoff();
        try {
            ctx.getSystemService(TelecomManager.class).placeCall(Uri.fromParts("tel", number, null), extras);
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "placeCall denied", e);
            new Prefs(ctx).clearHandoff();
            return false;
        }
    }

    /** Give a call a moment to start, then open the app carrying it (needs "Display over other apps"). */
    public static void bringToFront(Context ctx, String pkg) {
        final Context app = ctx.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (!Settings.canDrawOverlays(app)) return;
            Intent i = app.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try { app.startActivity(i); } catch (RuntimeException e) { Log.w(TAG, "couldn't open " + pkg, e); }
        }, 1200);
    }
}
