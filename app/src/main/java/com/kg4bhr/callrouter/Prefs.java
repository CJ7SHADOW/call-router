package com.kg4bhr.callrouter;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.telecom.PhoneAccountHandle;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** SharedPreferences wrapper. */
public final class Prefs {
    private static final String FILE = "router";
    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean enabled() { return sp.getBoolean("enabled", true); }
    public void setEnabled(boolean v) { sp.edit().putBoolean("enabled", v).apply(); }

    /** Dry run is ON for a fresh install: calls go out unchanged and a notification shows the decision. */
    public boolean dryRun() { return sp.getBoolean("dryRun", true); }
    public void setDryRun(boolean v) { sp.edit().putBoolean("dryRun", v).apply(); }

    public String groundwirePatterns() { return sp.getString("gwPatterns", Defaults.GROUNDWIRE_PATTERNS); }
    public String gvAreaCodes() { return sp.getString("gvAreas", Defaults.GV_AREA_CODES); }
    public String cellAreaCodes() { return sp.getString("cellAreas", Defaults.CELL_AREA_CODES); }
    public String hamshackPrefix() { return sp.getString("hamPrefix", Defaults.HAMSHACK_PREFIX); }
    public RouteEngine.Line defaultLine() { return line(sp.getString("defaultLine", "CELL")); }

    public void saveRules(String patterns, String gv, String cell, String hamPrefix, RouteEngine.Line def) {
        sp.edit().putString("gwPatterns", patterns)
                .putString("gvAreas", gv)
                .putString("cellAreas", cell)
                .putString("hamPrefix", hamPrefix)
                .putString("defaultLine", def.name())
                .apply();
    }

    // ---- per-contact overrides: number -> {line, name}
    public Map<String, String[]> overrides() {
        Map<String, String[]> out = new LinkedHashMap<>();
        try {
            JSONObject o = new JSONObject(sp.getString("overrides", "{}"));
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject v = o.getJSONObject(k);
                out.put(k, new String[]{v.optString("line", "CELL"), v.optString("name", "")});
            }
        } catch (JSONException ignored) { }
        return out;
    }

    public void putOverride(String ten, RouteEngine.Line line, String name) {
        try {
            JSONObject o = new JSONObject(sp.getString("overrides", "{}"));
            o.put(ten, new JSONObject().put("line", line.name()).put("name", name));
            sp.edit().putString("overrides", o.toString()).apply();
        } catch (JSONException ignored) { }
    }

    public void removeOverride(String ten) {
        try {
            JSONObject o = new JSONObject(sp.getString("overrides", "{}"));
            o.remove(ten);
            sp.edit().putString("overrides", o.toString()).apply();
        } catch (JSONException ignored) { }
    }

    // ---- Groundwire calling account
    public PhoneAccountHandle groundwireAccount() {
        String comp = sp.getString("gwComponent", null);
        String id = sp.getString("gwId", null);
        if (comp == null || id == null) return null;
        ComponentName cn = ComponentName.unflattenFromString(comp);
        return cn == null ? null : new PhoneAccountHandle(cn, id);
    }

    public String groundwireLabel() { return sp.getString("gwLabel", null); }

    public void setGroundwireAccount(PhoneAccountHandle h, String label) {
        sp.edit().putString("gwComponent", h.getComponentName().flattenToString())
                .putString("gwId", h.getId())
                .putString("gwLabel", label)
                .apply();
    }

    // ---- Google Voice hand-off guard (stops GV's own outgoing call from being re-routed)
    public void markHandoff() { sp.edit().putLong("handoffAt", System.currentTimeMillis()).apply(); }
    public boolean inHandoffWindow() {
        return System.currentTimeMillis() - sp.getLong("handoffAt", 0) < 60_000;
    }
    public void clearHandoff() { sp.edit().remove("handoffAt").apply(); }

    public RouteEngine.Config config() {
        RouteEngine.Config c = new RouteEngine.Config();
        try {
            c.groundwire(groundwirePatterns());
        } catch (RuntimeException e) {
            c.groundwire(Defaults.GROUNDWIRE_PATTERNS);
        }
        c.areaCodes(c.gvAreaCodes, gvAreaCodes());
        c.areaCodes(c.cellAreaCodes, cellAreaCodes());
        c.hamshackPrefix = hamshackPrefix();
        c.defaultLine = defaultLine();
        for (Map.Entry<String, String[]> e : overrides().entrySet()) {
            c.overrides.put(e.getKey(), line(e.getValue()[0]));
        }
        return c;
    }

    static RouteEngine.Line line(String s) {
        try { return RouteEngine.Line.valueOf(s); } catch (Exception e) { return RouteEngine.Line.CELL; }
    }
}
