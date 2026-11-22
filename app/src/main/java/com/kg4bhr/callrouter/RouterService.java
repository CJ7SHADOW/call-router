package com.kg4bhr.callrouter;

import android.net.Uri;
import android.telecom.CallRedirectionService;
import android.telecom.PhoneAccountHandle;
import android.util.Log;

/**
 * Android hands every outgoing call to this service before it is placed (once the app holds the
 * Call Redirection role). We must answer with exactly one of placeCallUnmodified(), redirectCall()
 * or cancelCall(), within a few seconds.
 */
public class RouterService extends CallRedirectionService {
    private static final String TAG = "CallRouter";

    @Override
    public void onPlaceCall(Uri handle, PhoneAccountHandle initialPhoneAccount, boolean allowInteractiveResponse) {
        Action a;
        try {
            a = decide(handle, initialPhoneAccount);
        } catch (RuntimeException e) {
            Log.e(TAG, "routing failed, placing unchanged", e);
            a = Action.UNCHANGED;
        }
        respond(a);
    }

    private enum Kind { UNCHANGED, REDIRECT, CANCEL }

    private static final class Action {
        static final Action UNCHANGED = new Action(Kind.UNCHANGED, null, null, null, false);
        final Kind kind; final Uri uri; final PhoneAccountHandle account; final String gvNumber;
        final boolean toGroundwire;
        Action(Kind k, Uri u, PhoneAccountHandle acct, String gv, boolean gw) {
            kind = k; uri = u; account = acct; gvNumber = gv; toGroundwire = gw;
        }
    }

    private Action decide(Uri handle, PhoneAccountHandle initial) {
        Prefs p = new Prefs(this);
        if (!p.enabled() || handle == null || !"tel".equals(handle.getScheme())) return Action.UNCHANGED;

        // A call we (or Google Voice right after a hand-off) just placed on purpose: don't route it again.
        if (p.inHandoffWindow()) {
            p.clearHandoff();
            Log.i(TAG, "bypass window: passing " + handle + " through");
            return Action.UNCHANGED;
        }

        String dialed = handle.getSchemeSpecificPart();
        RouteEngine.Decision d = RouteEngine.route(dialed, p.config());
        Log.i(TAG, dialed + " -> " + d);

        if (p.dryRun()) {
            if (d.line != RouteEngine.Line.PASSTHROUGH) Notifier.dryRun(this, dialed, d);
            return Action.UNCHANGED;
        }

        switch (d.line) {
            case GROUNDWIRE:
            case HAMSHACK: {
                PhoneAccountHandle gw = p.groundwireAccount();
                if (gw == null) {
                    Notifier.error(this, "Call to " + dialed + " cancelled",
                            "It should go through Groundwire, but no Groundwire calling account is selected. Open Call Router to pick it.");
                    return new Action(Kind.CANCEL, null, null, null, false);
                }
                return new Action(Kind.REDIRECT, Uri.fromParts("tel", d.dial, null), gw, null, true);
            }
            case GOOGLE_VOICE:
                if (GoogleVoice.intent(this, d.dial) == null) {
                    Notifier.error(this, "Google Voice unavailable",
                            "Called " + dialed + " on your cell instead. Google Voice isn't installed or won't accept call links.");
                    return Action.UNCHANGED;
                }
                return new Action(Kind.CANCEL, null, null, d.dial, false);
            case CELL: {
                // Normally untouched; a force prefix (*0) means the number was rewritten.
                if (d.dial.equals(dialed.trim())) return Action.UNCHANGED;
                PhoneAccountHandle sim = Lines.cellAccount(this, initial);
                if (sim == null) {
                    Notifier.error(this, "Call to " + dialed + " cancelled", "Couldn't find the T-Mobile calling account.");
                    return new Action(Kind.CANCEL, null, null, null, false);
                }
                return new Action(Kind.REDIRECT, Uri.fromParts("tel", d.dial, null), sim, null, false);
            }
            default:
                return Action.UNCHANGED;
        }
    }

    private void respond(Action a) {
        switch (a.kind) {
            case REDIRECT:
                redirectCall(a.uri, a.account, false);
                if (a.toGroundwire && new Prefs(this).showGroundwire()) {
                    Lines.bringToFront(this, a.account.getComponentName().getPackageName());
                }
                break;
            case CANCEL:
                cancelCall();
                if (a.gvNumber != null) GoogleVoice.launch(this, a.gvNumber);
                break;
            default:
                placeCallUnmodified();
        }
    }
}
