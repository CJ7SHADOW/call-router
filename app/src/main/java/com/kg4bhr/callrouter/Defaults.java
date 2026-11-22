package com.kg4bhr.callrouter;

/** Starting values; everything here is editable in the app. */
public final class Defaults {
    private Defaults() {}

    /** Home PBX outbound routes reached through Groundwire (FreePBX pattern syntax). */
    public static final String GROUNDWIRE_PATTERNS =
            "# AmateurWire (05 + extension)\n" +
            "05.\n" +
            "# AmateurWire extensions dialed bare: 05 added automatically\n" +
            "# (3-digit Poseidon, 5xxx Triton; 911/611/988 are always protected)\n" +
            "05+NXX\n" +
            "05+5XXX\n" +
            "# Mom & Dad's line (09 + number)\n" +
            "09.\n" +
            "# BulkVS hamshack line (9 + 10 digits)\n" +
            "9NXXNXXXXXX\n" +
            "# Hams Over IP (no prefix)\n" +
            "[124]0XXXX\n" +
            "100XX\n" +
            "11ZZ\n" +
            "15XXX\n" +
            "200XX\n" +
            "25XXX\n" +
            "350XX\n" +
            "90XXX\n" +
            "910XXXX\n" +
            "910XXXXX\n";

    public static final String GV_AREA_CODES = "256 938";

    /** Virginia area codes. */
    public static final String CELL_AREA_CODES = "703 571 276 434 540 826 757 948 804";

    public static final String HAMSHACK_PREFIX = "9";

    public static RouteEngine.Config config() {
        RouteEngine.Config c = new RouteEngine.Config().groundwire(GROUNDWIRE_PATTERNS);
        c.areaCodes(c.gvAreaCodes, GV_AREA_CODES);
        c.areaCodes(c.cellAreaCodes, CELL_AREA_CODES);
        c.hamshackPrefix = HAMSHACK_PREFIX;
        return c;
    }
}
