package com.kg4bhr.callrouter;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQ_ROLE = 1, REQ_PICK_CONTACT = 2, REQ_PERMS = 3;

    private Prefs prefs;
    private LinearLayout root;
    private TextView roleStatus, overlayStatus, permStatus, gwStatus, gvStatus, testResult;
    private Button roleBtn, overlayBtn, permBtn;
    private EditText patternsEdit, gvEdit, cellEdit, hamEdit, testEdit;
    private RadioGroup defaultGroup;
    private LinearLayout overridesList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);

        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(scroll);

        title("Call Router");
        note("Picks Cell, Google Voice or Groundwire for every call you dial from the regular phone app.");

        // ---- Setup
        header("Setup");
        roleStatus = note("");
        roleBtn = button("Make Call Router the call-routing app", v -> requestRole());
        permStatus = note("");
        permBtn = button("Grant phone & notification permissions", v -> requestPerms());
        overlayStatus = note("");
        overlayBtn = button("Allow \"Display over other apps\"", v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        gwStatus = note("");
        button("Choose Groundwire calling account", v -> chooseGroundwire());
        gvStatus = note("");

        // ---- Switches
        header("Routing");
        Switch enabled = new Switch(this);
        enabled.setText("Routing on");
        enabled.setChecked(prefs.enabled());
        enabled.setOnCheckedChangeListener((CompoundButton b, boolean c) -> prefs.setEnabled(c));
        root.addView(enabled);
        Switch dry = new Switch(this);
        dry.setText("Dry run (call normally, notify what would happen)");
        dry.setChecked(prefs.dryRun());
        dry.setOnCheckedChangeListener((CompoundButton b, boolean c) -> prefs.setDryRun(c));
        root.addView(dry);

        Switch showGw = new Switch(this);
        showGw.setText("Bring Groundwire to the front on its calls");
        showGw.setChecked(prefs.showGroundwire());
        showGw.setOnCheckedChangeListener((CompoundButton b, boolean c) -> prefs.setShowGroundwire(c));
        root.addView(showGw);

        // ---- Tester
        header("Test a number");
        testEdit = edit("Number to test", false);
        testEdit.setInputType(InputType.TYPE_CLASS_PHONE);
        LinearLayout row = hrow();
        row.addView(smallButton("Check route", v -> runTest()));
        row.addView(smallButton("Try Google Voice hand-off", v -> testGv()));
        root.addView(row);
        testResult = note("");
        testResult.setTypeface(Typeface.MONOSPACE);

        // ---- Rules
        header("Groundwire dial patterns");
        note("FreePBX syntax, one per line. X=0-9, Z=1-9, N=2-9, [..]=set, .=one or more. Lines starting with # are comments. Matched against exactly what you dial. Put digits and + in front to have them added, e.g. 05+NXX turns 203 into 05203. 911/N11 and 988 are never touched.");
        patternsEdit = edit("", true);
        patternsEdit.setText(prefs.groundwirePatterns());
        patternsEdit.setTypeface(Typeface.MONOSPACE);

        header("Area codes");
        note("Google Voice area codes:");
        gvEdit = edit("", false);
        gvEdit.setText(prefs.gvAreaCodes());
        note("Cell area codes:");
        cellEdit = edit("", false);
        cellEdit.setText(prefs.cellAreaCodes());
        note("Hamshack (BulkVS) prefix on your home PBX, used by contact overrides:");
        hamEdit = edit("", false);
        hamEdit.setText(prefs.hamshackPrefix());
        hamEdit.setInputType(InputType.TYPE_CLASS_PHONE);

        note("Every other US number goes to:");
        defaultGroup = new RadioGroup(this);
        defaultGroup.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton rCell = new RadioButton(this); rCell.setText("Cell"); rCell.setId(View.generateViewId());
        RadioButton rGv = new RadioButton(this); rGv.setText("Google Voice"); rGv.setId(View.generateViewId());
        defaultGroup.addView(rCell); defaultGroup.addView(rGv);
        defaultGroup.check(prefs.defaultLine() == RouteEngine.Line.GOOGLE_VOICE ? rGv.getId() : rCell.getId());
        rGv.setTag(RouteEngine.Line.GOOGLE_VOICE); rCell.setTag(RouteEngine.Line.CELL);
        root.addView(defaultGroup);
        button("Save rules", v -> saveRules());

        // ---- Overrides
        header("Contact overrides");
        note("Always use a specific line for a contact, whatever its area code.");
        overridesList = new LinearLayout(this);
        overridesList.setOrientation(LinearLayout.VERTICAL);
        root.addView(overridesList);
        button("Add contact", v -> pickContact());

        note("\nEmergency numbers, 3-digit codes and anything with * or # are never touched.");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshOverrides();
    }

    // ------------------------------------------------------------------ status

    private void refreshStatus() {
        RoleManager rm = getSystemService(RoleManager.class);
        boolean role = rm.isRoleHeld(RoleManager.ROLE_CALL_REDIRECTION);
        roleStatus.setText(mark(role) + "Call-routing role " + (role ? "granted" : "not granted"));
        roleBtn.setVisibility(role ? View.GONE : View.VISIBLE);

        boolean perms = missingPerms().isEmpty();
        permStatus.setText(mark(perms) + "Phone & notification permissions" + (perms ? "" : " needed"));
        permBtn.setVisibility(perms ? View.GONE : View.VISIBLE);

        boolean overlay = Settings.canDrawOverlays(this);
        overlayStatus.setText(mark(overlay) + "Display over other apps "
                + (overlay ? "allowed" : "off (Google Voice calls will need a tap on a notification)"));
        overlayBtn.setVisibility(overlay ? View.GONE : View.VISIBLE);

        if (prefs.groundwireAccount() == null && perms) autoPickGroundwire();
        String gw = prefs.groundwireLabel();
        gwStatus.setText(mark(gw != null) + "Groundwire account: " + (gw == null ? "not chosen" : gw));

        gvStatus.setText(mark(GoogleVoice.intent(this, "2565550100") != null)
                + "Google Voice: " + GoogleVoice.describe(this));
    }

    private static String mark(boolean ok) { return ok ? "✅ " : "⚠️ "; }

    private void requestRole() {
        RoleManager rm = getSystemService(RoleManager.class);
        startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_REDIRECTION), REQ_ROLE);
    }

    private List<String> missingPerms() {
        List<String> out = new ArrayList<>();
        for (String p : new String[]{Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.CALL_PHONE,
                Manifest.permission.POST_NOTIFICATIONS}) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) out.add(p);
        }
        return out;
    }

    private void requestPerms() {
        List<String> m = missingPerms();
        if (!m.isEmpty()) requestPermissions(m.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        refreshStatus();
    }

    // ------------------------------------------------------------------ Groundwire account

    private static final class Acct {
        final PhoneAccountHandle h; final String label;
        Acct(PhoneAccountHandle h, String label) { this.h = h; this.label = label; }
    }

    private List<Acct> thirdPartyAccounts() {
        List<Acct> out = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return out;
        TelecomManager tm = getSystemService(TelecomManager.class);
        lastScan.setLength(0);
        try {
            List<PhoneAccountHandle> handles = tm.getCallCapablePhoneAccounts();
            lastScan.append(handles.size()).append(" calling account(s): ");
            for (PhoneAccountHandle h : handles) {
                String pkg = h.getComponentName().getPackageName();
                lastScan.append(pkg).append(' ');
                PhoneAccount a = null;
                try { a = tm.getPhoneAccount(h); } catch (RuntimeException ignored) { }
                // Android may hide another app's account details from us, so a null here is not a reason to skip it.
                if (a != null && a.hasCapabilities(PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION)) continue;
                if (a == null && (pkg.equals("com.android.phone") || pkg.startsWith("com.android.services.telephony"))) continue;
                String label = (a == null || a.getLabel() == null) ? pkg : a.getLabel().toString();
                out.add(new Acct(h, label + "  (" + pkg + ")"));
            }
        } catch (SecurityException e) {
            lastScan.append("permission denied: ").append(e.getMessage());
        }
        return out;
    }

    private final StringBuilder lastScan = new StringBuilder();

    private void autoPickGroundwire() {
        for (Acct a : thirdPartyAccounts()) {
            String s = a.label.toLowerCase();
            if (s.contains("groundwire") || s.contains("acrobits")) {
                prefs.setGroundwireAccount(a.h, a.label);
                return;
            }
        }
    }

    private void chooseGroundwire() {
        List<Acct> accts = thirdPartyAccounts();
        if (accts.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("No Groundwire account found")
                    .setMessage("Android reported " + lastScan + "\n\nCheck Phone settings → Calling accounts → All calling accounts and make sure Groundwire is switched on, then try again.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] labels = new String[accts.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = accts.get(i).label;
        new AlertDialog.Builder(this).setTitle("Groundwire calling account")
                .setItems(labels, (dlg, which) -> {
                    prefs.setGroundwireAccount(accts.get(which).h, accts.get(which).label);
                    refreshStatus();
                }).show();
    }

    // ------------------------------------------------------------------ tester

    private void runTest() {
        String n = testEdit.getText().toString();
        RouteEngine.Decision d = RouteEngine.route(n, currentConfigFromScreen());
        testResult.setText(RouteEngine.label(d.line) + "\n  dials: " + d.dial + "\n  why:   " + d.reason
                + (prefs.dryRun() ? "\n  (dry run is on: real calls still go out on cell)" : ""));
    }

    private void testGv() {
        String n = testEdit.getText().toString().trim();
        if (n.isEmpty()) n = "2565550100";
        RouteEngine.Decision d = RouteEngine.route(n, currentConfigFromScreen());
        String num = d.line == RouteEngine.Line.GOOGLE_VOICE ? d.dial : n;
        android.content.Intent i = GoogleVoice.intent(this, num);
        if (i == null) { toast("Google Voice isn't installed or won't accept call links."); return; }
        new AlertDialog.Builder(this).setTitle("Google Voice hand-off")
                .setMessage("This opens Google Voice with " + num + " using " + i.getAction()
                        + ".\nIf the action is CALL, it will place a real call.")
                .setPositiveButton("Open", (dlg, w) -> { prefs.markHandoff(); startActivity(i); })
                .setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------------ rules

    private RouteEngine.Line selectedDefault() {
        View v = defaultGroup.findViewById(defaultGroup.getCheckedRadioButtonId());
        return v != null && v.getTag() instanceof RouteEngine.Line ? (RouteEngine.Line) v.getTag() : RouteEngine.Line.CELL;
    }

    private RouteEngine.Config currentConfigFromScreen() {
        RouteEngine.Config c = prefs.config();
        try { c.groundwire(patternsEdit.getText().toString()); } catch (RuntimeException ignored) { }
        c.areaCodes(c.gvAreaCodes, gvEdit.getText().toString());
        c.areaCodes(c.cellAreaCodes, cellEdit.getText().toString());
        c.hamshackPrefix = hamEdit.getText().toString().trim();
        c.defaultLine = selectedDefault();
        return c;
    }

    private void saveRules() {
        String pats = patternsEdit.getText().toString();
        List<String> errs = RouteEngine.validatePatterns(pats);
        if (!errs.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Pattern problem")
                    .setMessage(String.join("\n", errs)).setPositiveButton("OK", null).show();
            return;
        }
        prefs.saveRules(pats, gvEdit.getText().toString(), cellEdit.getText().toString(),
                hamEdit.getText().toString().trim(), selectedDefault());
        toast("Rules saved");
    }

    // ------------------------------------------------------------------ overrides

    private void refreshOverrides() {
        overridesList.removeAllViews();
        Map<String, String[]> o = prefs.overrides();
        if (o.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("None yet.");
            overridesList.addView(t);
        }
        for (Map.Entry<String, String[]> e : o.entrySet()) {
            String ten = e.getKey();
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            TextView t = new TextView(this);
            String name = e.getValue()[1];
            t.setText((name.isEmpty() ? "" : name + "\n") + ten + " → " + RouteEngine.label(Prefs.line(e.getValue()[0])));
            r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button del = new Button(this);
            del.setText("Remove");
            del.setOnClickListener(v -> { prefs.removeOverride(ten); refreshOverrides(); });
            r.addView(del);
            overridesList.addView(r);
        }
    }

    private void pickContact() {
        Intent i = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        startActivityForResult(i, REQ_PICK_CONTACT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_CONTACT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            String number = null, name = "";
            try (Cursor c = getContentResolver().query(data.getData(), new String[]{
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    number = c.getString(0);
                    name = c.getString(1) == null ? "" : c.getString(1);
                }
            } catch (RuntimeException e) {
                toast("Couldn't read that contact");
            }
            if (number != null) chooseOverrideLine(number, name);
        }
        refreshStatus();
    }

    private void chooseOverrideLine(String number, String name) {
        String digits = number.replaceAll("[^0-9]", "");
        if (digits.length() == 11 && digits.startsWith("1")) digits = digits.substring(1);
        if (digits.length() != 10) { toast("Overrides work on 10-digit US numbers only"); return; }
        final String ten = digits;
        RouteEngine.Line[] lines = {RouteEngine.Line.CELL, RouteEngine.Line.GOOGLE_VOICE, RouteEngine.Line.HAMSHACK};
        String[] labels = new String[lines.length];
        for (int i = 0; i < lines.length; i++) labels[i] = RouteEngine.label(lines[i]);
        new AlertDialog.Builder(this).setTitle((name.isEmpty() ? ten : name) + " always uses…")
                .setItems(labels, (dlg, which) -> {
                    prefs.putOverride(ten, lines[which], name);
                    refreshOverrides();
                }).show();
    }

    // ------------------------------------------------------------------ tiny view helpers

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void title(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(t);
    }

    private void header(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(20), 0, dp(4));
        root.addView(t);
    }

    private TextView note(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(4), 0, dp(4));
        root.addView(t);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        root.addView(b);
        return b;
    }

    private Button smallButton(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        return b;
    }

    private LinearLayout hrow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private EditText edit(String hint, boolean multi) {
        EditText e = new EditText(this);
        e.setHint(hint);
        if (multi) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            e.setMinLines(6);
            e.setGravity(Gravity.TOP);
        }
        root.addView(e);
        return e;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
