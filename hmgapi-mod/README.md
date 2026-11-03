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
2. [ ] **Service** (`svc/watchdog.sh`, a copy adapted for HMG-API; the NEMESIS
       one stays):
   - [ ] reads: `hmgapid vehicle battery12v.soc hv.soc hv.health charging outside_temp`
         instead of `service call tm_hscan_service …`
   - [ ] remove `verify()`, `known_firmware.txt`, `read_temp` and the climate dump
         and logcat parsing (HMG-API's map and `outside_temp` replace them)
   - [ ] drive detection: `hv.ready` (car on) instead of `dumpsys power`. Keep
         the screen check as a fallback until HMG-API offers a screen-on event.
   - [ ] state in `$HMGAPI_DATA`; output already goes to HMG-API's service log
   - [ ] same `log.csv` format, so `tools/analyze.py`, export and share zips
         keep working
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

- **`"user": "app"`:** confirm it runs the service as the mod's app uid, so the
  app can read its files without root. Otherwise use `system` and share state
  another way.
- **Licensing:** HMG-API's client library (`hmgapi-client.jar`) is AGPL-3.0.
  Battery Watchdog is MIT, so the mod talks to HMG-API only through the
  `hmgapid` command (from the service and through a shell), not by bundling
  the jar. Ask VysseCodes whether the client is meant to carry a linking
  exception.
- **Version:** the mod keeps the app's version (`VERSION`). Bump `mod.json`
  with each release.
