# Call Router

Picks the line for every call dialed from the regular Android phone app: **T-Mobile cell**, **Google Voice**, or **Groundwire** (home PBX: AmateurWire, Hams Over IP, Mom & Dad's line, BulkVS hamshack line). It's a modern take on the old Google Voice companion dialer apps, built on Android's call-redirection API (Android 11+).

## Install

Every push to `main` builds an APK through GitHub Actions. Download the newest `CallRouter-N.apk` from **Releases** on your phone and install it. Builds are all signed with the same key (`ci-debug.keystore`), so each new one installs over the last.

## First run

1. **Make Call Router the call-routing app** (Android asks for confirmation).
2. **Grant phone & notification permissions.**
3. **Allow "Display over other apps".** Without it, Google Voice calls show up as a tap-to-call notification instead of opening Google Voice directly.
4. **Groundwire account.** This is picked automatically. Groundwire must have *Enable placing calls through native dialer* turned on, and its active account must be the **home PBX**.
5. **Dry run** is on for a fresh install. Calls go out on the cell line unchanged, and a notification says what would have happened. Turn it off once the routes look right.

## Routing order

| # | Rule | Result |
|---|------|--------|
| 1 | Contains `*` or `#`, or is 3 digits or fewer (911, 611, 988) | Untouched |
| 2 | Matches a Groundwire dial pattern (FreePBX syntax, matched exactly as dialed) | Groundwire, unchanged |
| 3 | International (`+` other than +1, or `011…`) | Cell |
| 4 | Not a full 10-digit US number | Untouched |
| 5 | Contact override | Cell / Google Voice / Hamshack (`9` + number via Groundwire) |
| 6 | Google Voice area codes (default 256, 938) | Google Voice |
| 7 | Cell area codes (default: Virginia) | Cell |
| 8 | Everything else | Default line (Cell) |

Default Groundwire patterns: `05.` AmateurWire, `09.` Mom & Dad's line, `9NXXNXXXXXX` BulkVS, plus the Hams Over IP patterns from the home PBX outbound route.

## Google Voice hand-off

The call is cancelled and its number is passed to the Google Voice app. For 15 seconds after a hand-off, the next outgoing call goes through unrouted, so Google Voice's own carrier call (if it makes one) isn't routed in a loop. The **Try Google Voice hand-off** button in the app shows which call link Google Voice accepts.

Only one app can hold the call-routing role. Once Call Router holds it, turn off Google Voice's own "make calls with Voice" dialer integration so the two don't compete.

## Rules self-test

```
javac -d /tmp/t app/src/main/java/com/kg4bhr/callrouter/{RouteEngine,Defaults}.java tools/RouteEngineTest.java
java -cp /tmp/t com.kg4bhr.callrouter.RouteEngineTest
```
The same test runs in CI before every build.
