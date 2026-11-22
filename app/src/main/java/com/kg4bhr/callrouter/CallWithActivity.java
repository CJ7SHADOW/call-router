package com.kg4bhr.callrouter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.telecom.PhoneAccountHandle;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Call with…": pick the line by hand for one call. Opened from
 *  - a tel: link (choose Call Router in the "Open with" sheet),
 *  - sharing a contact to Call Router,
 *  - the launcher shortcut (long-press the Call Router icon), which opens the contact picker.
 */
public class CallWithActivity extends Activity {
    private static final int REQ_PICK = 1;
    private static final int THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert;

    private String contactName = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        List<String> numbers = numbersFrom(getIntent());
        if (numbers.isEmpty()) {
            startActivityForResult(new Intent(Intent.ACTION_PICK,
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI), REQ_PICK);
        } else {
            chooseNumber(numbers);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) {
            finish();
            return;
        }
        try (Cursor c = getContentResolver().query(data.getData(), new String[]{
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                contactName = c.getString(1) == null ? "" : c.getString(1);
                chooseLine(c.getString(0));
                return;
            }
        } catch (RuntimeException ignored) { }
        toast("Couldn't read that contact");
        finish();
    }

    // ------------------------------------------------------------------ input

    private List<String> numbersFrom(Intent in) {
        Set<String> out = new LinkedHashSet<>();
        if (in == null) return new ArrayList<>();
        Uri data = in.getData();
        if (data != null && "tel".equals(data.getScheme())) {
            out.add(data.getSchemeSpecificPart());
        } else if (Intent.ACTION_SEND.equals(in.getAction())) {
            @SuppressWarnings("deprecation") Uri stream = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) readVcard(stream, out);
            CharSequence text = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                if (text.toString().contains("BEGIN:VCARD")) parseVcard(text.toString(), out);
                else {
                    Matcher m = Pattern.compile("\\+?[0-9][0-9 ().\\-]{6,}[0-9]").matcher(text);
                    while (m.find()) out.add(m.group());
                }
            }
        }
        return new ArrayList<>(out);
    }

    private void readVcard(Uri uri, Set<String> out) {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedReader r = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
        } catch (Exception ignored) { }
        parseVcard(sb.toString(), out);
    }

    private void parseVcard(String v, Set<String> out) {
        for (String line : v.split("\\r?\\n")) {
            String u = line.toUpperCase();
            if (u.startsWith("FN:") || u.startsWith("FN;")) {
                if (contactName.isEmpty()) contactName = line.substring(line.indexOf(':') + 1).trim();
            } else if (u.startsWith("TEL")) {
                String n = line.substring(line.lastIndexOf(':') + 1).trim();
                if (!n.isEmpty()) out.add(n);
            }
        }
    }

    // ------------------------------------------------------------------ dialogs

    private void chooseNumber(List<String> numbers) {
        if (numbers.size() == 1) { chooseLine(numbers.get(0)); return; }
        String[] items = numbers.toArray(new String[0]);
        new AlertDialog.Builder(this, THEME)
                .setTitle(contactName.isEmpty() ? "Which number?" : contactName)
                .setItems(items, (d, w) -> chooseLine(items[w]))
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void chooseLine(String number) {
        Prefs p = new Prefs(this);
        RouteEngine.Config cfg = p.config();
        RouteEngine.Decision auto = RouteEngine.route(number, cfg);
        String[] items = {
                "Automatic: " + RouteEngine.label(auto.line == RouteEngine.Line.PASSTHROUGH ? RouteEngine.Line.CELL : auto.line),
                "Cell (T-Mobile)",
                "Google Voice",
                "Groundwire, as dialed",
                "Hamshack (BulkVS)"};
        String title = (contactName.isEmpty() ? "" : contactName + "  ") + number;
        new AlertDialog.Builder(this, THEME)
                .setTitle(title)
                .setItems(items, (d, w) -> {
                    place(number, w, cfg);
                    finish();
                })
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void place(String number, int which, RouteEngine.Config cfg) {
        if (which == 0) {
            // Let the routing rules decide, exactly like dialing it.
            try {
                getSystemService(android.telecom.TelecomManager.class)
                        .placeCall(Uri.fromParts("tel", number, null), new Bundle());
            } catch (SecurityException e) {
                toast("Call Router needs the phone permission");
            }
            return;
        }
        RouteEngine.Line line = new RouteEngine.Line[]{null, RouteEngine.Line.CELL, RouteEngine.Line.GOOGLE_VOICE,
                RouteEngine.Line.GROUNDWIRE, RouteEngine.Line.HAMSHACK}[which];
        RouteEngine.Decision d = RouteEngine.force(line, number, cfg, "chosen by hand");
        Prefs p = new Prefs(this);
        switch (d.line) {
            case CELL: {
                if (!Lines.placeOn(this, d.dial, Lines.cellAccount(this, null))) toast("Couldn't find the T-Mobile account");
                break;
            }
            case GOOGLE_VOICE: {
                Intent i = GoogleVoice.intent(this, d.dial);
                if (i == null) { toast("Google Voice isn't available"); break; }
                p.markHandoff();
                startActivity(i);
                break;
            }
            case GROUNDWIRE:
            case HAMSHACK: {
                PhoneAccountHandle gw = p.groundwireAccount();
                if (!Lines.placeOn(this, d.dial, gw)) { toast("No Groundwire account selected in Call Router"); break; }
                if (p.showGroundwire()) Lines.bringToFront(this, gw.getComponentName().getPackageName());
                break;
            }
            default:
                toast(d.reason);
        }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
