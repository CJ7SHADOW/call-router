package com.kg4bhr.callrouter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure-Java routing rules (no Android imports) so it can be unit tested anywhere.
 *
 * Order of evaluation:
 *   0. Force prefixes (** Google Voice, *0 Cell, *1 Hamshack) + number -> that line, prefix removed
 *   1. Service codes (* or #), letters, N11 and 988 -> pass through untouched
 *   2. Groundwire dial patterns (FreePBX syntax, matched against the raw digits, optional
 *      "prepend+pattern" adds digits in front) -> Groundwire
 *   2b. Any other number of 3 digits or fewer -> pass through untouched
 *   3. International (+ other than +1, or 011...) -> international line
 *   4. Anything that isn't a full 10-digit NANP number -> pass through untouched
 *   5. Per-contact overrides
 *   6. Google Voice area codes
 *   7. Cell area codes
 *   8. Default line
 */
public final class RouteEngine {

    public enum Line { PASSTHROUGH, CELL, GOOGLE_VOICE, GROUNDWIRE, HAMSHACK }

    public static final class Config {
        public final List<Pattern> groundwirePatterns = new ArrayList<>();
        /** Digits to add in front of a match, same index as groundwirePatterns ("" = none). */
        public final List<String> groundwirePrepends = new ArrayList<>();
        public final Set<String> gvAreaCodes = new HashSet<>();
        public final Set<String> cellAreaCodes = new HashSet<>();
        /** key = 10-digit number, value = CELL, GOOGLE_VOICE or HAMSHACK */
        public final Map<String, Line> overrides = new HashMap<>();
        public Line defaultLine = Line.CELL;
        public Line internationalLine = Line.CELL;
        /** Prefix the home PBX uses to send a call out the BulkVS hamshack line. */
        public String hamshackPrefix = "9";
        /** Dial these in front of a number to force a line for that one call. */
        public String forceGoogleVoice = "**";
        public String forceCell = "*0";
        public String forceHamshack = "*1";

        public Config groundwire(String patternsText) {
            groundwirePatterns.clear();
            groundwirePrepends.clear();
            for (String p : patternLines(patternsText)) {
                String[] pp = splitPrepend(p);
                groundwirePrepends.add(pp[0]);
                groundwirePatterns.add(compileFreePbxPattern(pp[1]));
            }
            return this;
        }

        public Config areaCodes(Set<String> target, String text) {
            target.clear();
            for (String t : text.split("[^0-9]+")) if (t.length() == 3) target.add(t);
            return this;
        }
    }

    public static final class Decision {
        public final Line line;
        /** Number to hand to the chosen line (unchanged for CELL / PASSTHROUGH). */
        public final String dial;
        public final String reason;

        Decision(Line line, String dial, String reason) {
            this.line = line;
            this.dial = dial;
            this.reason = reason;
        }

        @Override public String toString() {
            return label(line) + " → " + dial + "  (" + reason + ")";
        }
    }

    public static String label(Line l) {
        switch (l) {
            case CELL: return "Cell (T-Mobile)";
            case GOOGLE_VOICE: return "Google Voice";
            case GROUNDWIRE: return "Groundwire";
            case HAMSHACK: return "Hamshack (BulkVS via Groundwire)";
            default: return "Unchanged";
        }
    }

    private static final Pattern PROTECTED = Pattern.compile("[2-9]11|988");

    private RouteEngine() {}

    /** One pattern per line (or comma-separated); "#" starts a comment anywhere on a line. */
    static List<String> patternLines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            for (String part : line.split("[,;]")) {
                String p = part.trim();
                if (!p.isEmpty()) out.add(p);
            }
        }
        return out;
    }

    /** "05+NXX" -> {"05", "NXX"}; "NXX" -> {"", "NXX"}. */
    static String[] splitPrepend(String p) {
        int plus = p.indexOf('+');
        if (plus < 0) return new String[]{"", p};
        String pre = p.substring(0, plus).trim();
        if (!pre.matches("[0-9]*")) throw new IllegalArgumentException("Prefix before + must be digits: " + p);
        return new String[]{pre, p.substring(plus + 1).trim()};
    }

    public static Decision route(String raw, Config c) {
        if (raw == null) raw = "";
        String s = raw.trim();

        Decision forced = forced(s, c);
        if (forced != null) return forced;

        // Service codes, vanity letters, anything odd: leave it alone.
        if (s.indexOf('*') >= 0 || s.indexOf('#') >= 0 || s.indexOf(',') >= 0 || s.indexOf(';') >= 0) {
            return new Decision(Line.PASSTHROUGH, s, "service code");
        }
        boolean plus = s.startsWith("+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') sb.append(ch);
            else if (Character.isLetter(ch)) return new Decision(Line.PASSTHROUGH, s, "contains letters");
            // spaces, dashes, dots, parentheses, slashes, the leading + are formatting
        }
        String d = sb.toString();
        if (d.isEmpty()) return new Decision(Line.PASSTHROUGH, s, "no digits");
        // N11 (211-911) and 988 are never routed or prefixed, whatever the patterns say.
        if (!plus && PROTECTED.matcher(d).matches()) return new Decision(Line.PASSTHROUGH, s, "emergency/service code");

        // Groundwire patterns are matched against exactly what was dialed.
        if (!plus) {
            for (int i = 0; i < c.groundwirePatterns.size(); i++) {
                Pattern p = c.groundwirePatterns.get(i);
                if (p.matcher(d).matches()) {
                    String pre = i < c.groundwirePrepends.size() ? c.groundwirePrepends.get(i) : "";
                    return new Decision(Line.GROUNDWIRE, pre + d, "matches PBX pattern "
                            + (pre.isEmpty() ? "" : pre + "+") + p.pattern());
                }
            }
        }
        if (!plus && d.length() <= 3) return new Decision(Line.PASSTHROUGH, s, "short code");

        // Normalize to 10-digit NANP.
        String ten = null;
        if (plus) {
            if (d.length() == 11 && d.startsWith("1")) ten = d.substring(1);
            else return international(c, s, "international number");
        } else if (d.startsWith("011")) {
            return international(c, s, "international number");
        } else if (d.length() == 11 && d.startsWith("1")) {
            ten = d.substring(1);
        } else if (d.length() == 10) {
            ten = d;
        }
        if (ten == null || ten.charAt(0) < '2' || ten.charAt(3) < '2') {
            return new Decision(Line.PASSTHROUGH, s, "not a full US number");
        }

        Line o = c.overrides.get(ten);
        if (o != null) return forLine(c, o, s, ten, "contact override");

        String npa = ten.substring(0, 3);
        if (c.gvAreaCodes.contains(npa)) return forLine(c, Line.GOOGLE_VOICE, s, ten, "area code " + npa);
        if (c.cellAreaCodes.contains(npa)) return forLine(c, Line.CELL, s, ten, "area code " + npa);
        return forLine(c, c.defaultLine, s, ten, "default");
    }

    /** Handles "**2565551234" style one-call overrides. Returns null if no force prefix applies. */
    static Decision forced(String s, Config c) {
        String compact = s.replaceAll("[\\s\\-().]", "");
        String[][] table = {
                {c.forceGoogleVoice, Line.GOOGLE_VOICE.name()},
                {c.forceCell, Line.CELL.name()},
                {c.forceHamshack, Line.HAMSHACK.name()}};
        // longest prefix first so "**" isn't shadowed by a shorter one
        java.util.Arrays.sort(table, (a, b) -> b[0].length() - a[0].length());
        for (String[] t : table) {
            String pre = t[0];
            if (pre == null || pre.isEmpty() || !compact.startsWith(pre)) continue;
            String rest = compact.substring(pre.length());
            if (!rest.matches("\\+?[0-9]{7,}")) return null;
            return force(Line.valueOf(t[1]), rest, c, "forced with " + pre);
        }
        return null;
    }

    /** Build a decision for an explicitly chosen line (used by force prefixes and "Call with…"). */
    public static Decision force(Line line, String number, Config c, String why) {
        String n = number.replaceAll("[^0-9+]", "");
        String ten = toTen(n);
        switch (line) {
            case GOOGLE_VOICE:
                if (ten != null) return new Decision(Line.GOOGLE_VOICE, "+1" + ten, why);
                if (n.startsWith("+")) return new Decision(Line.GOOGLE_VOICE, n, why);
                if (n.startsWith("011")) return new Decision(Line.GOOGLE_VOICE, "+" + n.substring(3), why);
                return new Decision(Line.GOOGLE_VOICE, n, why);
            case HAMSHACK:
                if (ten == null) return new Decision(Line.PASSTHROUGH, number, "hamshack needs a 10-digit US number");
                return new Decision(Line.HAMSHACK, c.hamshackPrefix + ten, why);
            case GROUNDWIRE:
                return new Decision(Line.GROUNDWIRE, n.replace("+", ""), why);
            case CELL:
                return new Decision(Line.CELL, ten != null ? ten : n, why);
            default:
                return new Decision(Line.PASSTHROUGH, number, why);
        }
    }

    static String toTen(String n) {
        String d = n.replace("+", "");
        boolean plus = n.startsWith("+");
        if (d.length() == 11 && d.startsWith("1")) d = d.substring(1);
        else if (plus || d.length() != 10) return null;
        if (d.charAt(0) < '2' || d.charAt(3) < '2') return null;
        return d;
    }

    private static Decision international(Config c, String raw, String why) {
        if (c.internationalLine == Line.CELL) return new Decision(Line.CELL, raw, why);
        return new Decision(Line.PASSTHROUGH, raw, why);
    }

    private static Decision forLine(Config c, Line l, String raw, String ten, String why) {
        switch (l) {
            case GOOGLE_VOICE: return new Decision(Line.GOOGLE_VOICE, "+1" + ten, why);
            case HAMSHACK: return new Decision(Line.HAMSHACK, c.hamshackPrefix + ten, why);
            case CELL: return new Decision(Line.CELL, raw, why);
            default: return new Decision(Line.PASSTHROUGH, raw, why);
        }
    }

    /**
     * FreePBX/Asterisk dial pattern to regex:
     *   X = 0-9, Z = 1-9, N = 2-9, [..] = class, . = one or more, ! = zero or more,
     *   "prefix|match" = the bar is ignored for matching.
     * (A leading "digits+" prepend is split off before this is called.)
     */
    public static Pattern compileFreePbxPattern(String pat) {
        StringBuilder re = new StringBuilder();
        String p = pat.trim().toUpperCase(Locale.US);
        if (p.startsWith("_")) p = p.substring(1); // Asterisk-style leading underscore
        for (int i = 0; i < p.length(); i++) {
            char ch = p.charAt(i);
            switch (ch) {
                case 'X': re.append("[0-9]"); break;
                case 'Z': re.append("[1-9]"); break;
                case 'N': re.append("[2-9]"); break;
                case '.': re.append("[0-9]+"); break;
                case '!': re.append("[0-9]*"); break;
                case '|': case ' ': case '"': break;
                case '[': {
                    int end = p.indexOf(']', i);
                    if (end < 0) throw new IllegalArgumentException("Unclosed [ in " + pat);
                    String body = p.substring(i + 1, end).replaceAll("[^0-9\\-]", "");
                    re.append('[').append(body).append(']');
                    i = end;
                    break;
                }
                default:
                    if (ch >= '0' && ch <= '9') re.append(ch);
                    else throw new IllegalArgumentException("Unsupported character '" + ch + "' in " + pat);
            }
        }
        return Pattern.compile(re.toString());
    }

    public static List<String> validatePatterns(String text) {
        List<String> errors = new ArrayList<>();
        for (String p : patternLines(text)) {
            try { compileFreePbxPattern(splitPrepend(p)[1]); } catch (RuntimeException e) { errors.add(e.getMessage()); }
        }
        return Collections.unmodifiableList(errors);
    }
}
