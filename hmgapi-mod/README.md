# Battery Watchdog as an HMG-API mod (work in progress)

A second way to install Battery Watchdog, for cars running
[HMG-API](https://github.com/VysseCodes/HMG-API). The NEMESIS package in
`package/` stays as it is. Both are built from this repo.

With HMG-API, the mod reads the car through named readings
(`battery12v.soc`, `hv.soc`, `hv.health`, `hv.ready`, `charging`,
`outside_temp`), mapped per car by HMG-API. It needs no transaction numbers, no
firmware list, no temperature hunting and no root plumbing.

## Files

- `mod.json`: the mod's declaration. It started from HMG-API's
  `tools/mod-draft.py`, then was corrected: version, description, licence,
  readings, USB folder, service user and start.
- `DRAFT-NOTES.txt`: the draft tool's notes on every line of the NEMESIS package
  that needs changing for HMG-API.

## Porting checklist

1. [x] Draft `mod.json` (`tools/mod-draft.py package --out …`).
2. [x] **Service:** one shared `package/svc/watchdog.sh` with an HMG-API mode
       (on when `$HMGAPI_MOD` is set and `hmgapid` is on the PATH; the mod
       package copies it to `svc/watchdog.sh`). In that mode it:
   - [x] reads `hmgapid vehicle battery12v.soc hv.soc hv.health hv.ready charging outside_temp`
         (one call per sample) instead of `service call tm_hscan_service …`
   - [x] skips the firmware check, `known_firmware.txt`, the telematics-service
         wait and the temperature hunting; it counts as verified once
         `battery12v.soc` gives a number (`verify=hmgapi`), otherwise it stays
         unverified (`hmgapi_no_reading`) and retries every minute
   - [x] detects drives by `hv.ready` (car on); when HMG-API doesn't know it,
         falls back to the screen check
   - [x] keeps state in `$HMGAPI_DATA`; output goes to HMG-API's service log
   - [x] writes the same `log.csv` columns (`outside_temp` rounded toward zero
         to whole °C), so `tools/analyze.py`, export and share zips keep working
   - [x] tests HA1–HA4 in `tests/test_service.sh` with a mocked `hmgapid`
   - [ ] check on the car: `hv.ready` is 1 while the car is on (READY), and
         `battery12v.soc` matches the NEMESIS version's reading
3. [x] **App** (same APK, two modes, `MainActivity.detectMode()`):
   - [x] detects HMG-API with `hmgapid whoami` (it answers the mod's id only to
         a mod's own app) and then works on the service's folder,
         `/data/hmgapi/run/<mod>/watch`, as itself: shell commands run as the
         app and files are written directly, with no NEMESIS root channel
   - [x] hides the firmware verification (and its override button) and the
         test run; "Paused" says HMG-API has no 12V reading for this car yet
   - [x] Read sensor now, Restart monitor and Diagnostics go through `hmgapid`
         (`vehicle`, `service-restart watch`, `services`, `service-log watch`, `usb`)
   - [x] Export tries HMG-API's `BatteryWatchdog` folder on a stick first, then the
         drive's top level
   - [ ] check on the car: whether an ordinary app may write to the stick at
         all (Android 4.4 lets only system apps write to removable drives; the
         NEMESIS version writes as root). If not, ask VysseCodes for a way to
         copy into a mod's USB folder.
   - [ ] check on the car: whether the service's toasts (`am broadcast`) and its
         screen fallback (`dumpsys power`) work as the app's user
   - [ ] later: react to `hmgapi.intent.action.USB_FOLDER` (offer Export when a
         stick with the folder goes in), once writing is confirmed
4. [x] **Extras HMG-API makes possible:**
   - [x] app-menu tile in the car's own style: `icons/` (day, night and the two
         greyed-out pictures), made with HMG-API's `tools/mkicon.sh` from the
         battery glyph (`python3 tools/make_icon.py --glyph glyph.png`), listed
         under `provides.launcher` in `mod.json`
   - [x] split-screen panel page "12V battery" (`provides.panel`,
         `PanelWidget`): the 12V %, main battery, outside temperature, the
         parked drop before this drive and any warning, from the service's status
         file. The service refreshes it after each reading; a tap opens the app.
   - [x] the service passes HMG-API's install-time script check
         (`tools/check-scripts.sh`); a few lines were reworded for its
         line-by-line reading, with no change in behaviour
   - [ ] check on the car: the tile pictures in the app menu (day and night),
         and the page in the split-screen editor and panel
5. [ ] **Upstream (HMG-API PR):** readings for the 12V battery sensor's flags
       (`getBATSNSRState`/`Invalid`/`Error`) and the Auxiliary Battery Saver option
       (`get_BAT_AuxBattOpt`). Until then, the NEMESIS version logs them.
       Also worth reporting: `ScriptCheck` reads line by line, so it flags
       backslash-continued lines, `a|b)` case patterns, a bare `-` pattern and
       nested parentheses inside `$(( ))` as missing commands.
6. [ ] **Package:** a mod-signing key (kept private and backed up), then
       `tools/mkmodpkg.sh` (HMG-API mod) and `tools/mknemesismod.sh` (NEMESIS
       package that installs it through HMG-API); CI for both; catalogue PR.
7. [ ] **Test** on a car with HMG-API (the maintainer's Ioniq 5 US 260507, which
       HMG-API lists as "waiting for a check on a car").

## Open questions

- **`"user": "app"`: answered.** HMG-API runs the service as the first app in
  `"apps"` (`ModRuntime.java`: `app` → `app:<package>`). The service and the
  app share one Android user, so the app reads the service's files directly,
  with no root.
- **Licensing:** HMG-API's client library (`hmgapi-client.jar`) is AGPL-3.0.
  Battery Watchdog is MIT, so the mod talks to HMG-API only through the
  `hmgapid` command (from the service and through a shell), not by bundling
  the jar. Ask VysseCodes whether the client is meant to carry a linking
  exception.
- **Version:** the mod keeps the app's version (`VERSION`). Bump `mod.json`
  with each release.
