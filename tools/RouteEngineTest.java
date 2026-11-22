package com.kg4bhr.callrouter;

import static com.kg4bhr.callrouter.RouteEngine.Line.*;

import com.kg4bhr.callrouter.RouteEngine.Decision;
import com.kg4bhr.callrouter.RouteEngine.Line;

/** Plain main() test so it runs with javac/java or from Gradle's test task wrapper below. */
public class RouteEngineTest {
    static int fails = 0;

    static void check(RouteEngine.Config c, String in, Line line, String dial) {
        Decision d = RouteEngine.route(in, c);
        boolean ok = d.line == line && (dial == null || dial.equals(d.dial));
        if (!ok) fails++;
        System.out.println((ok ? "ok   " : "FAIL ") + String.format("%-18s", in) + d
                + (ok ? "" : "   expected " + line + " " + dial));
    }

    public static void main(String[] args) {
        RouteEngine.Config c = Defaults.config();
        c.overrides.put("2565550100", CELL);
        c.overrides.put("7035550199", HAMSHACK);

        // emergency / service codes
        check(c, "911", PASSTHROUGH, "911");
        check(c, "988", PASSTHROUGH, null);
        check(c, "*611", PASSTHROUGH, null);
        check(c, "#31#2565551234", PASSTHROUGH, null);

        // Groundwire prefixes
        check(c, "05203", GROUNDWIRE, "05203");
        check(c, "055001", GROUNDWIRE, "055001");
        check(c, "09 2565551234", GROUNDWIRE, "092565551234");
        check(c, "92565551234", GROUNDWIRE, "92565551234");
        check(c, "9 (703) 555-1234", GROUNDWIRE, "97035551234");

        // Bare AmateurWire extensions get 05
        check(c, "203", GROUNDWIRE, "05203");
        check(c, "947", GROUNDWIRE, "05947");
        check(c, "456", GROUNDWIRE, "05456");
        check(c, "5001", GROUNDWIRE, "055001");
        check(c, "611", PASSTHROUGH, "611");
        check(c, "411", PASSTHROUGH, null);
        check(c, "123", PASSTHROUGH, null);   // NXX can't start with 1

        // Hams Over IP
        check(c, "101234", GROUNDWIRE, null);
        check(c, "10123", PASSTHROUGH, null);  // 5 digits: not 100XX, not [124]0XXXX
        check(c, "10012", GROUNDWIRE, null);
        check(c, "20012", GROUNDWIRE, null);
        check(c, "409999", GROUNDWIRE, null);
        check(c, "1123", GROUNDWIRE, null);
        check(c, "1103", PASSTHROUGH, null);   // 11ZZ: Z can't be 0
        check(c, "15999", GROUNDWIRE, null);
        check(c, "25000", GROUNDWIRE, null);
        check(c, "35012", GROUNDWIRE, null);
        check(c, "35112", PASSTHROUGH, null);
        check(c, "90555", GROUNDWIRE, null);
        check(c, "9101234", GROUNDWIRE, null);
        check(c, "91012345", GROUNDWIRE, null);
        check(c, "30123", PASSTHROUGH, null);

        // Normal numbers
        check(c, "(256) 555-1234", GOOGLE_VOICE, "+12565551234");
        check(c, "+1 256-555-1234", GOOGLE_VOICE, "+12565551234");
        check(c, "12565551234", GOOGLE_VOICE, "+12565551234");
        check(c, "938-555-1234", GOOGLE_VOICE, "+19385551234");
        check(c, "703-555-1234", CELL, "703-555-1234");
        check(c, "5715551234", CELL, null);
        check(c, "9015551234", CELL, null);   // Memphis 10-digit must NOT be BulkVS
        check(c, "2055551234", CELL, null);   // Birmingham -> default
        check(c, "5551234", PASSTHROUGH, null);

        // Overrides
        check(c, "256-555-0100", CELL, null);
        check(c, "+17035550199", HAMSHACK, "97035550199");

        // International
        check(c, "+44 20 7946 0000", CELL, null);
        check(c, "011442079460000", CELL, null);

        System.out.println(fails == 0 ? "\nALL PASSED" : "\n" + fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
