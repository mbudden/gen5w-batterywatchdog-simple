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
   - [x] tests H1–H4 in `tests/test_service.sh` with a mocked `hmgapid`
   - [ ] check on the car: `hv.ready` is 1 while the car is on (READY), and
         `battery12v.soc` matches the NEMESIS version's reading
3. [ ] **App** (same APK, two modes):
   - [ ] detect HMG-API (`hmgapid whoami`) and read state from `$HMGAPI_DATA`
         instead of through NEMESIS root
   - [ ] hide the firmware verification UI in that mode
   - [ ] USB: also react to `hmgapi.intent.action.USB_FOLDER` (a drive with a
         `BatteryWatchdog` folder) to offer or run Export
4. [ ] **Extras HMG-API makes possible:** tile icons in the car's style
       (`tools/mkicon.sh`); a side-panel page with the 12V % in the split screen
       (`"provides": {"panel": …}`).
5. [ ] **Upstream (HMG-API PR):** readings for the 12V battery sensor's flags
       (`getBATSNSRState`/`Invalid`/`Error`) and the Auxiliary Battery Saver option
       (`get_BAT_AuxBattOpt`). Until then, the NEMESIS version logs them.
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
