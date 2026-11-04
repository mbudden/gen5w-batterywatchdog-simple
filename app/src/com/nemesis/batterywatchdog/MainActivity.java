package com.nemesis.batterywatchdog;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.UiModeManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

/**
 * Battery Watchdog. Shows what the battery.watchdog service has logged and edits
 * its one-value config files.
 * Kotlin/lambdas avoided on purpose: built with legacy dx for KitKat (DEX 035).
 *
 * Two screens for the 1920x720 Gen5W display, neither of which scrolls:
 *   home     : 12V battery, last 7 days with the drives chart, checks
 *   settings : warnings, sensor, tools
 */
public class MainActivity extends Activity {
    private static final String STATE = RootShell.STATE, NEM = RootShell.NEM;
    private static final String SVC_ID = RootShell.SVC_ID, SVC_LOG = RootShell.SVC_LOG, SVC_SCRIPT = RootShell.SVC_SCRIPT;
    private static final int HOME = 0, SETTINGS = 1;
    private static final String[] CHECK_LABELS = { "12V at Start", "Parked Drain", "Last Drive", "HV Battery Health", "Firmware" };

    private final Handler handler = new Handler();
    private final Config config = new Config();
    private RootShell sh;
    private Firmware firmware;
    private UsbExport export;
    private Palette palette = Palette.DARK;
    private Ui ui;
    private FrameLayout content;
    private int screen = HOME;
    private boolean carNight, compact, splitObserved, saveDirty;

    // home
    private TextView heroValue, statusWord, updatedText, detailText, mainBattery, outside;
    private TextView statDrives, statLowest, statRate, statRecharges;
    private Gauge gauge;
    private DriveChart chart;
    private final CheckMark[] checkMarks = new CheckMark[CHECK_LABELS.length];
    private final TextView[] checkValues = new TextView[CHECK_LABELS.length];

    // settings
    private ToggleSwitch monitorSwitch;
    private TextView warnValue, dropValue, readButton;
    private View overrideTile;

    // auto-refresh while the screen is open (the service samples every few minutes)
    private final Runnable tick = new Runnable() {
        public void run() { refreshData(); handler.postDelayed(this, 30000); }
    };
    // settings save shortly after the last change, so repeated taps write once
    private final Runnable saveTask = new Runnable() { public void run() { flushSave(); } };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // drop the system title bar: the cards are the whole screen
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        sh = new RootShell(this);
        firmware = new Firmware(this, sh);
        export = new UsbExport(sh, firmware);
        config.load(sh);

        content = new FrameLayout(this);
        setContentView(content);
        carNight = night(getResources().getConfiguration());
        palette = Palette.of(carNight);
        ui = new Ui(this, palette);
        applyWindowColors();
        compact = splitOn();
        applySplitPadding();
        showScreen();
        startVerification();
        handler.postDelayed(askShare, 2000);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A day/night switch while the app was hidden may not have reached onConfigurationChanged.
        carNight = night(getResources().getConfiguration());
        applyAppearance();
        try {
            getContentResolver().registerContentObserver(Settings.System.getUriFor(SPLIT_SETTING), false, splitObserver);
            splitObserved = true;
        } catch (Exception e) { splitObserved = false; }
        applySplit();
        handler.removeCallbacks(tick); tick.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
        flushSave();
        if (splitObserved) { getContentResolver().unregisterContentObserver(splitObserver); splitObserved = false; }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (screen == SETTINGS) { show(HOME); return; }
        super.onBackPressed();
    }

    // ---- day and night ---------------------------------------------------------
    // uiMode is in configChanges, so a day/night switch arrives here instead of recreating.
    // Found by the Launchpad / gen5w-preconditioning project: the car's Day/Night display
    // mode sets Android night mode, and the configuration follows it.
    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        carNight = night(configuration);
        applyAppearance();
    }

    private boolean night(Configuration configuration) {
        int night = configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (night != Configuration.UI_MODE_NIGHT_UNDEFINED) return night == Configuration.UI_MODE_NIGHT_YES;
        UiModeManager manager = (UiModeManager) getSystemService(Context.UI_MODE_SERVICE);
        return manager == null || manager.getNightMode() != UiModeManager.MODE_NIGHT_NO;
    }

    private void applyAppearance() {
        Palette next = Palette.of(carNight);
        if (next == palette) return;
        palette = next;
        ui = new Ui(this, palette);
        applyWindowColors();
        showScreen();
    }

    private void applyWindowColors() {
        content.setBackgroundColor(palette.background);
        getWindow().setBackgroundDrawable(new ColorDrawable(palette.background));
    }

    // ---- split screen -----------------------------------------------------------
    // Hyundai's split view draws the map panel over the right of the screen without
    // resizing this app. Settings.System "split_screen_status" is 1 while it's open
    // (found by the Launchpad / gen5w-preconditioning project). The panel's width
    // isn't reported (about 29 % in photos), so a third is kept clear. There's only
    // room for the battery and the drives then; close the split view to change settings.
    private static final String SPLIT_SETTING = "split_screen_status";
    private final ContentObserver splitObserver = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) { applySplit(); }
    };

    private boolean splitOn() {
        try { return Settings.System.getInt(getContentResolver(), SPLIT_SETTING, 0) == 1; }
        catch (Exception e) { return false; }
    }

    private void applySplit() {
        boolean next = splitOn();
        if (next == compact) return;
        compact = next;
        applySplitPadding();
        if (compact) screen = HOME;
        showScreen();
    }

    private void applySplitPadding() {
        int right = compact ? Math.round(getResources().getDisplayMetrics().widthPixels / 3f) : ui.dp(64);
        content.setPadding(ui.dp(32), ui.dp(24), right, ui.dp(24));
    }

    // ---- screens ------------------------------------------------------------------
    private void show(int next) {
        screen = next;
        showScreen();
    }

    private void showScreen() {
        flushSave();
        content.removeAllViews();
        heroValue = null; monitorSwitch = null; overrideTile = null;
        if (screen == SETTINGS) buildSettings(); else { buildHome(); refreshData(); }
    }

    private void buildHome() {
        LinearLayout body = ui.row();
        content.addView(body, new FrameLayout.LayoutParams(Ui.FILL, Ui.FILL));

        // 12V battery: heading at the top, then evenly spaced reading, gauge and context
        LinearLayout battery = ui.panel(body, compact ? 0.38f : 0.28f, 0);
        battery.addView(ui.caption("12V BATTERY"));
        battery.addView(ui.spacer());
        heroValue = ui.text(Fmt.NONE, 72, palette.ink, true);
        heroValue.setSingleLine(true);
        battery.addView(heroValue);
        LinearLayout status = ui.row();
        status.setBaselineAligned(true);
        status.setGravity(Gravity.BOTTOM);
        statusWord = ui.text("", 22, palette.ink, true);
        statusWord.setSingleLine(true);
        status.addView(statusWord);
        updatedText = ui.text("", 14, palette.muted, false);
        updatedText.setSingleLine(true);
        updatedText.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams updatedParams = new LinearLayout.LayoutParams(0, Ui.WRAP, 1);
        updatedParams.leftMargin = ui.dp(10);
        status.addView(updatedText, updatedParams);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(Ui.FILL, Ui.WRAP);
        statusParams.topMargin = ui.dp(6);
        battery.addView(status, statusParams);
        detailText = ui.text("", 16, palette.muted, false);
        detailText.setLineSpacing(0, 1.15f);
        detailText.setVisibility(View.GONE);
        battery.addView(detailText, ui.below(10));
        battery.addView(ui.spacer());
        gauge = new Gauge(this, palette);
        battery.addView(gauge, new LinearLayout.LayoutParams(Ui.FILL, ui.dp(62)));
        battery.addView(ui.spacer());
        battery.addView(ui.hairline());
        LinearLayout context = ui.row();
        context.setPadding(0, ui.dp(14), 0, 0);
        mainBattery = stat(context, "Main Battery", 14);
        outside = stat(context, "Outside", 14);
        battery.addView(context, new LinearLayout.LayoutParams(Ui.FILL, Ui.WRAP));

        // last 7 days: four short numbers above the chart
        LinearLayout week = ui.panel(body, compact ? 0.62f : 0.46f, 16);
        week.addView(ui.caption("LAST 7 DAYS"));
        LinearLayout stats = ui.row();
        statDrives = stat(stats, "Drives", 13);
        statLowest = stat(stats, "Lowest Start", 13);
        statRate = stat(stats, "Parked, Per Day", 13);
        statRecharges = stat(stats, "Parked Recharge", 13);
        LinearLayout.LayoutParams statsParams = new LinearLayout.LayoutParams(Ui.FILL, Ui.WRAP);
        statsParams.topMargin = ui.dp(10);
        week.addView(stats, statsParams);
        chart = new DriveChart(this, palette);
        LinearLayout.LayoutParams chartParams = new LinearLayout.LayoutParams(Ui.FILL, 0, 1);
        chartParams.topMargin = ui.dp(12);
        week.addView(chart, chartParams);

        checkMarks[0] = null;   // refreshData skips the checks when this card isn't shown
        if (compact) return;

        // checks, with the way into Settings
        LinearLayout checks = ui.panel(body, 0.26f, 16);
        LinearLayout header = ui.row();
        header.addView(ui.caption("CHECKS"), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        header.addView(settingsPill(), new LinearLayout.LayoutParams(Ui.WRAP, ui.dp(44)));
        checks.addView(header, new LinearLayout.LayoutParams(Ui.FILL, Ui.WRAP));
        for (int i = 0; i < CHECK_LABELS.length; i++) {
            LinearLayout row = ui.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            checkMarks[i] = new CheckMark(this);
            row.addView(checkMarks[i], new LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)));
            LinearLayout text = ui.column();
            TextView label = ui.text(CHECK_LABELS[i], 17, palette.ink, true);
            label.setSingleLine(true);
            text.addView(label);
            checkValues[i] = ui.text(Fmt.NONE, 14, palette.muted, false);
            checkValues[i].setSingleLine(true);
            checkValues[i].setEllipsize(TextUtils.TruncateAt.END);
            text.addView(checkValues[i], ui.below(4));
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, Ui.WRAP, 1);
            textParams.leftMargin = ui.dp(12);
            row.addView(text, textParams);
            checks.addView(row, new LinearLayout.LayoutParams(Ui.FILL, 0, 1));
        }
    }

    private View settingsPill() {
        LinearLayout pill = ui.row();
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(ui.dp(14), 0, ui.dp(18), 0);
        ui.background(pill, ui.pill(palette.card, 22));
        pill.setContentDescription("Settings");
        pill.addView(new SlidersIcon(this, palette.ink), new LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)));
        TextView label = ui.text("Settings", 16, palette.ink, false);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(Ui.WRAP, Ui.WRAP);
        labelParams.leftMargin = ui.dp(8);
        pill.addView(label, labelParams);
        pill.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { show(SETTINGS); } });
        return pill;
    }

    /** A bold value over a short label, taking an equal share of the row. Returns the value. */
    private TextView stat(LinearLayout parent, String label, int labelSize) {
        LinearLayout column = ui.column();
        TextView value = ui.text(Fmt.NONE, 22, palette.ink, true);
        value.setSingleLine(true);
        column.addView(value);
        TextView name = ui.text(label, labelSize, palette.muted, false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(name, ui.below(5));
        parent.addView(column, new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        return value;
    }

    // ---- home data -----------------------------------------------------------------
    private void refreshData() {
        if (screen != HOME || heroValue == null) return;
        String status = sh.runRoot("cat " + STATE + "/status 2>/dev/null");
        String state = Fmt.kv(status, "state"), warn = Fmt.kv(status, "warn");
        String verifyState = firmware.verifyState;
        int p = Fmt.parse(Fmt.kv(status, "pct10"), -1);
        boolean paused = "unverified".equals(state) || "unverified".equals(verifyState) || "interface_mismatch".equals(verifyState);
        if (paused && !firmware.verified()) { showPaused(verifyState); return; }

        int warnPct = config.warnPct, dropPct = config.dropPct;
        boolean disabled = "disabled".equals(state), noRead = "no_reading".equals(state);
        boolean reading = p >= 0 && !disabled;
        boolean low = reading && p < warnPct * 10;
        boolean attention = reading && (low || warn.length() > 0);

        if (reading) showHero(Fmt.percent(p), low ? palette.warn : palette.ink);
        else if (disabled) showWord("Off", palette.ink);
        else if (state.length() == 0) showWord("Waiting…", palette.ink);
        else showWord("No Reading", palette.ink);

        statusWord.setText(!reading ? "" : low ? "Low" : attention ? "Attention" : "Normal");
        statusWord.setTextColor(attention ? palette.warn : palette.ink);
        statusWord.setVisibility(reading ? View.VISIBLE : View.GONE);
        long now = System.currentTimeMillis() / 1000, updated = Fmt.parseL(Fmt.kv(status, "updated"), 0);
        updatedText.setText(updated > 0 ? "Updated " + Fmt.age(now, updated) : "");
        String detail = disabled ? "Monitoring is off. Turn it on in Settings."
                : noRead ? "The 12V value couldn't be decoded. Check Settings → Sensor."
                : state.length() == 0 ? "No reading yet. If this lasts, see Settings → Diagnostics." : "";
        detailText.setText(detail);
        detailText.setVisibility(detail.length() > 0 ? View.VISIBLE : View.GONE);
        gauge.setVisibility(reading ? View.VISIBLE : View.GONE);
        if (reading) gauge.setValue(p, warnPct);

        // context: main battery charge and outside temperature
        int hv = Fmt.parse(Fmt.kv(status, "hv10"), -1);
        int tc = Fmt.parse(Fmt.kv(status, "temp_c"), Integer.MIN_VALUE);
        mainBattery.setText(hv >= 0 ? Fmt.percent(hv) : Fmt.NONE);
        outside.setText(tc != Integer.MIN_VALUE ? Math.round(tc * 9 / 5f + 32) + "°F" : Fmt.NONE);

        // the chart and the last 7 days
        long minOff = Math.max(60, sh.readInt("min_off_s", 600));
        List<long[]> finished = Drives.finished(sh.runRoot("cat " + STATE + "/sessions 2>/dev/null"), minOff);
        List<long[]> drives = Drives.withCurrent(finished, status, minOff);
        boolean endsNow = !drives.isEmpty() && updated > 0 && drives.get(drives.size() - 1)[1] == updated;
        chart.setData(drives.size() > 30 ? drives.subList(drives.size() - 30, drives.size()) : drives,
                warnPct * 10, dropPct * 10, endsNow);
        showSummary(Drives.summary(drives, now));

        if (checkMarks[0] == null) return;   // split view: no checks card

        // 12V when this drive started
        String[] ss = Fmt.kv(status, "session_start").split(" ");
        int startPct = ss.length > 1 ? Fmt.parse(ss[1], -1) : -1;
        if (startPct < 0) startPct = p;
        if (startPct < 0) setCheck(0, CheckMark.UNKNOWN, "No reading yet");
        else if (startPct < warnPct * 10) setCheck(0, CheckMark.FAIL, "Below " + warnPct + "%");
        else setCheck(0, CheckMark.PASS, "Above " + warnPct + "%");

        // parked time before this drive, and how the 12V changed meanwhile
        int pMin = Fmt.parse(Fmt.kv(status, "parked_min"), -1);
        int pDrop = Fmt.parse(Fmt.kv(status, "parked_drop10"), Integer.MIN_VALUE);
        if (pMin >= 0 && pDrop != Integer.MIN_VALUE) {
            String change = pDrop > 0 ? Fmt.MINUS + Fmt.points(pDrop) : pDrop < 0 ? "+" + Fmt.points(pDrop) : "No change";
            setCheck(1, pDrop >= dropPct * 10 ? CheckMark.FAIL : CheckMark.PASS, change + " in " + Fmt.span(pMin));
        } else {
            setCheck(1, CheckMark.UNKNOWN, "Not enough data");
        }

        // did the newest long drive hold or raise the 12V?
        long[] last = Drives.lastLong(finished);
        if (last == null) {
            setCheck(2, CheckMark.UNKNOWN, "No " + Drives.MIN_RECOVERY_MINUTES + "-min drive yet");
        } else {
            int change = (int) (last[3] - last[2]);
            if (change < 0) setCheck(2, CheckMark.FAIL, "Dropped " + Fmt.points(change));
            else setCheck(2, CheckMark.PASS, change == 0 ? "Held steady" : "Recovered +" + Fmt.points(change));
        }

        // HV battery health: read live, not logged or monitored. Good >= 90, Fair 75-89, Low < 75.
        int soh = firmware.readSoh();
        if (soh < 0) soh = Fmt.parse(Fmt.kv(status, "soh_raw"), -1);   // fall back to the service's last value
        if (soh < 0) setCheck(3, CheckMark.UNKNOWN, Fmt.NONE);
        else if (soh >= 90) setCheck(3, CheckMark.PASS, soh + "%, Good");
        else if (soh >= 75) setCheck(3, CheckMark.CAUTION, soh + "%, Fair");
        else setCheck(3, CheckMark.FAIL, soh + "%, Low");

        showFirmwareCheck(verifyState);
    }

    /** Nothing is read on firmware that couldn't be verified. */
    private void showPaused(String verifyState) {
        showWord("Paused", palette.warn);
        statusWord.setText(""); statusWord.setVisibility(View.GONE);
        updatedText.setText("");
        detailText.setText("interface_mismatch".equals(verifyState)
                ? "The car's telematics service isn't the one this package was built for, so nothing is read."
                : "This firmware isn't verified, so nothing is read from the car. See Settings → Diagnostics.");
        detailText.setVisibility(View.VISIBLE);
        gauge.setVisibility(View.GONE);
        mainBattery.setText(Fmt.NONE); outside.setText(Fmt.NONE);
        statDrives.setText(Fmt.NONE); statLowest.setText(Fmt.NONE); statRate.setText(Fmt.NONE); statRecharges.setText(Fmt.NONE);
        chart.setData(new java.util.ArrayList<long[]>(), config.warnPct * 10, config.dropPct * 10, false);
        if (checkMarks[0] == null) return;
        for (int i = 0; i < 4; i++) setCheck(i, CheckMark.UNKNOWN, Fmt.NONE);
        showFirmwareCheck(verifyState);
    }

    private void showSummary(Drives.Summary s) {
        statDrives.setText(String.valueOf(s.count));
        statLowest.setText(s.count > 0 ? Fmt.percent(s.lowest10) : Fmt.NONE);
        statRate.setText(!s.hasRate ? Fmt.NONE
                : s.rate10 > 0 ? Fmt.MINUS + Fmt.fmt10(s.rate10)
                : s.rate10 < 0 ? "+" + Fmt.fmt10(-s.rate10) : "0.0");
        statRecharges.setText(String.valueOf(s.recharges));
    }

    private void showFirmwareCheck(String verifyState) {
        if ("checking".equals(verifyState)) setCheck(4, CheckMark.UNKNOWN, "Checking");
        else if ("override".equals(verifyState)) setCheck(4, CheckMark.CAUTION, "Manual override");
        else if ("unverified".equals(verifyState)) setCheck(4, CheckMark.FAIL, "Not verified");
        else if ("interface_mismatch".equals(verifyState)) setCheck(4, CheckMark.FAIL, "Different service");
        else setCheck(4, CheckMark.PASS, "Verified");
    }

    private void setCheck(int row, int state, String value) {
        checkMarks[row].setState(state);
        checkValues[row].setText(value);
    }

    private void showHero(String value, int color) {
        heroValue.setTextSize(72); heroValue.setText(value); heroValue.setTextColor(color);
    }
    private void showWord(String value, int color) {
        heroValue.setTextSize(48); heroValue.setText(value); heroValue.setTextColor(color);
    }

    // ---- settings -------------------------------------------------------------------
    private interface Pick { void pick(int index); }

    private void buildSettings() {
        LinearLayout body = ui.row();
        content.addView(body, new FrameLayout.LayoutParams(Ui.FILL, Ui.FILL));

        // warnings
        LinearLayout warnings = ui.panel(body, 0.38f, 0);
        warnings.addView(ui.caption("WARNINGS"));
        LinearLayout monitorRow = settingRow(warnings, false);
        monitorRow.addView(ui.text("Monitor 12V", 17, palette.ink, true), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        monitorSwitch = new ToggleSwitch(this, palette);
        monitorSwitch.setOn(config.enabled);
        // the whole row is the touch target, which is easier to hit in a car
        monitorRow.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
            config.enabled = !config.enabled; monitorSwitch.setOn(config.enabled); scheduleSave(); } });
        monitorRow.addView(monitorSwitch, new LinearLayout.LayoutParams(ui.dp(64), ui.dp(36)));

        LinearLayout warnRow = settingRow(warnings, true);
        warnRow.addView(labelled("Warn Below", "At car start"), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        warnValue = addStepper(warnRow, "warning level", new Pick() { public void pick(int delta) {
            config.warnPct = Config.step(config.warnPct, delta, 30, 90); refreshSettingValues(); scheduleSave(); } });

        LinearLayout dropRow = settingRow(warnings, true);
        dropRow.addView(labelled("Warn on Drop", "While parked"), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        dropValue = addStepper(dropRow, "parked drop limit", new Pick() { public void pick(int delta) {
            config.dropPct = Config.step(config.dropPct, delta, 5, 40); refreshSettingValues(); scheduleSave(); } });

        LinearLayout popupRow = settingRow(warnings, true);
        popupRow.addView(ui.text("Pop-Up", 17, palette.ink, true), new LinearLayout.LayoutParams(0, Ui.WRAP, 1));
        popupRow.addView(segments(new String[] { "Warnings Only", "Every Start" }, config.toastStart ? 1 : 0, false,
            new Pick() { public void pick(int i) { config.toastStart = i == 1; scheduleSave(); } }));
        refreshSettingValues();

        // sensor
        LinearLayout sensor = ui.panel(body, 0.31f, 16);
        sensor.addView(ui.caption("SENSOR"));
        int intervalIndex = -1;
        for (int i = 0; i < Config.INTERVALS.length; i++) if (Config.INTERVALS[i] == config.interval) intervalIndex = i;
        sensor.addView(ui.spacer());
        addOption(sensor, "Read Every", segments(new String[] { "1 min", "5 min", "15 min" }, intervalIndex, true,
            new Pick() { public void pick(int i) { config.interval = Config.INTERVALS[i]; scheduleSave(); } }));
        sensor.addView(ui.spacer());
        addOption(sensor, "Decode", segments(Config.DECODE, config.decode, true,
            new Pick() { public void pick(int i) { config.decode = i; scheduleSave(); } }));
        sensor.addView(ui.spacer());
        addOption(sensor, "Scale", segments(new String[] { "×1", "×0.5" }, config.scaleX10 == 10 ? 0 : 1, true,
            new Pick() { public void pick(int i) { config.scaleX10 = i == 0 ? 10 : 5; scheduleSave(); } }));
        sensor.addView(ui.spacer());
        readButton = ui.text("Read Sensor Now", 17, palette.onAccent, true);
        readButton.setGravity(Gravity.CENTER);
        readButton.setSingleLine(true);
        readButton.setEllipsize(TextUtils.TruncateAt.END);
        ui.background(readButton, ui.pill(palette.accent, 28));
        readButton.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { readNow(); } });
        sensor.addView(readButton, new LinearLayout.LayoutParams(Ui.FILL, ui.dp(56)));

        // tools
        LinearLayout tools = ui.panel(body, 0.31f, 16);
        tools.addView(ui.caption("TOOLS"));
        addToolRow(tools, 10,
            tile("Diagnostics", palette.ink, new Runnable() { public void run() { showDiagnostics(); } }),
            tile("Restart Monitor", palette.ink, new Runnable() { public void run() { restartService(); } }));
        addToolRow(tools, 10,
            tile("Test Run (10 s)", palette.ink, new Runnable() { public void run() { testRun(); } }),
            tile("Export to USB", palette.ink, new Runnable() { public void run() { exportToUsb(); } }));
        addToolRow(tools, 10,
            tile("Share Data", palette.ink, new Runnable() { public void run() { showShareSteps(); } }),
            tile("Clear History", palette.danger, new Runnable() { public void run() { confirmClearHistory(); } }));
        overrideTile = tile("Unverified Firmware: Use Built-In Numbers…", palette.warn,
            new Runnable() { public void run() { confirmOverride(); } });
        LinearLayout.LayoutParams overrideParams = new LinearLayout.LayoutParams(Ui.FILL, 0, 1);
        overrideParams.topMargin = ui.dp(10);
        tools.addView(overrideTile, overrideParams);
        refreshOverrideTile();
    }

    /** A row that shares the card's spare height, with a hairline above it when asked. */
    private LinearLayout settingRow(LinearLayout parent, boolean rule) {
        if (rule) parent.addView(ui.hairline());
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row, new LinearLayout.LayoutParams(Ui.FILL, 0, 1));
        return row;
    }

    private View labelled(String title, String subtitle) {
        LinearLayout text = ui.column();
        text.addView(ui.text(title, 17, palette.ink, true));
        text.addView(ui.text(subtitle, 14, palette.muted, false), ui.below(4));
        return text;
    }

    /** Minus, value, plus. The handler's index is -1 or +1. Returns the value's view. */
    private TextView addStepper(LinearLayout row, String name, final Pick onStep) {
        row.addView(round("−", "Lower " + name, new View.OnClickListener() { public void onClick(View v) { onStep.pick(-1); } }),
            new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)));
        TextView value = ui.text("", 22, palette.ink, true);
        value.setGravity(Gravity.CENTER);
        value.setSingleLine(true);
        row.addView(value, new LinearLayout.LayoutParams(ui.dp(84), Ui.WRAP));
        row.addView(round("+", "Raise " + name, new View.OnClickListener() { public void onClick(View v) { onStep.pick(1); } }),
            new LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)));
        return value;
    }

    private View round(String symbol, String description, View.OnClickListener listener) {
        TextView button = ui.text(symbol, 24, palette.ink, false);
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(description);
        ui.background(button, ui.pill(palette.card, 28));
        button.setOnClickListener(listener);
        return button;
    }

    private void refreshSettingValues() {
        warnValue.setText(config.warnPct + "%");
        dropValue.setText(config.dropPct + " pts");
    }

    private void addOption(LinearLayout parent, String label, View control) {
        parent.addView(ui.text(label, 14, palette.muted, false));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.FILL, Ui.WRAP);
        params.topMargin = ui.dp(4);
        parent.addView(control, params);
    }

    /** A row of choices with one selected. stretch: the choices share the row's width. */
    private LinearLayout segments(String[] labels, int selected, boolean stretch, final Pick pick) {
        LinearLayout box = ui.row();
        box.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
        ui.background(box, ui.rounded(palette.card, 28));
        final TextView[] items = new TextView[labels.length];
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView item = ui.text(labels[i], 15, palette.ink, false);
            item.setGravity(Gravity.CENTER);
            item.setSingleLine(true);
            if (!stretch) item.setPadding(ui.dp(16), 0, ui.dp(16), 0);
            item.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                styleSegments(items, index); pick.pick(index); } });
            box.addView(item, stretch ? new LinearLayout.LayoutParams(0, ui.dp(48), 1) : new LinearLayout.LayoutParams(Ui.WRAP, ui.dp(48)));
            items[i] = item;
        }
        styleSegments(items, selected);
        return box;
    }

    private void styleSegments(TextView[] items, int selected) {
        for (int i = 0; i < items.length; i++) {
            boolean on = i == selected;
            items[i].setSelected(on);
            items[i].setTextColor(on ? palette.onAccent : palette.ink);
            ui.background(items[i], on ? ui.pill(palette.accent, 24) : null);
        }
    }

    private void addToolRow(LinearLayout tools, int topMargin, View left, View right) {
        LinearLayout row = ui.row();
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(Ui.FILL, 0, 1);
        rowParams.topMargin = ui.dp(topMargin);
        row.addView(left, new LinearLayout.LayoutParams(0, Ui.FILL, 1));
        LinearLayout.LayoutParams rightParams = new LinearLayout.LayoutParams(0, Ui.FILL, 1);
        rightParams.leftMargin = ui.dp(10);
        row.addView(right, rightParams);
        tools.addView(row, rowParams);
    }

    private TextView tile(String label, int color, final Runnable action) {
        TextView tile = ui.text(label, 17, color, false);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        ui.background(tile, ui.pill(palette.card, 14));
        tile.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { action.run(); } });
        return tile;
    }

    private void refreshOverrideTile() {
        if (overrideTile != null) overrideTile.setVisibility("unverified".equals(firmware.verifyState) ? View.VISIBLE : View.GONE);
    }

    private void scheduleSave() {
        saveDirty = true;
        handler.removeCallbacks(saveTask);
        handler.postDelayed(saveTask, 500);
    }

    private void flushSave() {
        handler.removeCallbacks(saveTask);
        if (!saveDirty) return;
        saveDirty = false;
        String r = config.save(sh);
        if (r.startsWith("ERR")) toast("Save failed: " + r);
    }

    private void readNow() {
        if (firmware.txSoc <= 0) { readButton.setText("Not Verified"); return; }
        String r = sh.runRoot("service call tm_hscan_service " + firmware.txSoc).trim();
        int i = r.indexOf("Parcel(");
        if (i >= 0) {
            String[] w = r.substring(i + 7).trim().split("\\s+");
            if (w.length >= 2 && "00000000".equals(w[0])) {
                try {
                    readButton.setText("Sensor Now: " + Long.parseLong(w[1], 16) + "% (raw " + w[1] + ")");
                    return;
                } catch (Exception e) { }
            }
        }
        readButton.setText("No Reading");
        toast(r);
    }

    // ---- firmware verification ------------------------------------------------
    private void startVerification() {
        new Thread(new Runnable() { public void run() {
            final String result = firmware.verify();
            handler.post(new Runnable() { public void run() {
                firmware.verifyState = result;
                refreshOverrideTile();
                refreshData();
            } });
        } }).start();
    }

    @SuppressWarnings("deprecation")
    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, palette.dark ? AlertDialog.THEME_HOLO_DARK : AlertDialog.THEME_HOLO_LIGHT);
    }

    private void confirmOverride() {
        dialog().setTitle("Use built-in numbers on this firmware?")
            .setMessage("This firmware (" + firmware.fw + ") hasn't been verified, and Battery Watchdog couldn't confirm the "
                + "transaction numbers by name. On a different firmware the same numbers can reach a different "
                + "function, including ones that reset or change settings.\n\nOnly continue if you've run the USB "
                + "probe on this car and its txn 137 / 74 / 214 values look like a 12V %, a main battery %, and a "
                + "health index (see docs/TECHNICAL.md).")
            .setPositiveButton("I've checked, use them", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface di, int w) {
                    firmware.applyOverride();
                    toast("Saved. The monitor starts within a minute.");
                    refreshOverrideTile(); refreshData();
                } })
            .setNegativeButton("Cancel", null).show();
    }

    // ---- one-time, opt-in request to share data ---------------------------------
    // Shown once, the first time the app opens with verified readings (on an
    // unverified firmware nothing is collected, so it waits). Nothing is ever sent
    // from the car: it only explains how to export and share, if the owner wants to.
    private static final String PREFS = "ui", ASKED_SHARE = "share_asked";
    private int shareTries;
    private final Runnable askShare = new Runnable() { public void run() { maybeAskToShare(); } };

    private void maybeAskToShare() {
        if (isFinishing()) return;
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (p.getBoolean(ASKED_SHARE, false)) return;
        if (!firmware.verified()) {
            // still checking: look again shortly; paused: ask on a later start
            if ("checking".equals(firmware.verifyState) && ++shareTries < 20) handler.postDelayed(askShare, 3000);
            return;
        }
        p.edit().putBoolean(ASKED_SHARE, true).apply();
        dialog().setTitle("Help improve Battery Watchdog?")
            .setMessage("The warnings are simple for now. Logs from real cars, especially ones whose 12V battery "
                + "later failed, would help build smarter checks for everyone.\n\n"
                + "This is completely optional, and nothing is ever sent from your car. If you'd like to help, "
                + "after a few weeks of driving you can export your data to a USB drive and share it on GitHub.\n\n"
                + "You won't be asked again. The steps are always in Settings → Share Data.")
            .setPositiveButton("Show me how", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface di, int w) { showShareSteps(); } })
            .setNegativeButton("No thanks", null).show();
    }

    private void showShareSteps() {
        dialog().setTitle("Share your data (optional)")
            .setMessage("1. Drive as usual for a few weeks. The longer the log, the more useful it is.\n"
                + "2. Insert a USB drive and tap Export below (or Settings → Export to USB). Choose "
                + "\"For sharing, dates hidden\" if you'd rather not show when you drive.\n"
                + "3. On a computer or phone, go to github.com/zpoon/gen5w-BatteryWatchdog, open Issues → "
                + "New issue → Share battery data, fill in what you know about your 12V battery, and attach "
                + "the .zip from the drive.\n\n"
                + "If your battery fails or is replaced later, a comment on that issue helps a lot.\n\n"
                + "An export has no location, VIN or account details.")
            .setPositiveButton("Export", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface di, int w) { exportToUsb(); } })
            .setNegativeButton("Close", null).show();
    }

    // ---- tools ----------------------------------------------------------------------
    private void showDiagnostics() {
        dialog().setTitle("Diagnostics").setMessage(Diagnostics.build(sh, firmware))
            .setPositiveButton("Close", null).show();
    }

    private void restartService() {
        // empty the old log first so anything shown afterwards is from this run
        String r = sh.runRoot(": > " + SVC_LOG + " 2>/dev/null; "
                + NEM + " service stop " + SVC_ID + " 2>&1; "
                + NEM + " service start " + SVC_ID + " 2>&1; sleep 3; "
                + NEM + " service status " + SVC_ID + " 2>&1");
        dialog().setTitle("Restart Monitor")
            .setMessage(r.trim() + "\n\nCheck Diagnostics in 30 seconds.")
            .setPositiveButton("Close", null).show();
    }

    // Runs the installed monitor script directly as root for a couple of loops
    // (BWD_TEST shortens the timings), bypassing NEMESIS. Real readings are saved.
    private void testRun() {
        if (!firmware.verified()) { toast("Not verified on this firmware, so the test run is disabled."); return; }
        toast("Running the monitor for about 10 seconds…");
        String out = sh.runRoot("cd " + STATE + " && BWD_TEST=1 BWD_TEST_LOOPS=3 /system/bin/sh " + SVC_SCRIPT
                + " > " + STATE + "/testrun.log 2>&1; echo \"exit code $?\"; cat " + STATE + "/testrun.log");
        dialog().setTitle("Test run").setMessage(out.trim().length() == 0 ? "(no output)" : out.trim())
            .setPositiveButton("Close", null).show();
        refreshData();
    }

    private void exportToUsb() {
        dialog().setTitle("Export to USB")
            .setMessage("Export writes a folder you can open in Excel or Google Sheets, and a zip of the same "
                + "files, ready to attach to a \"Share battery data\" issue.\n\n"
                + "\"For sharing, dates hidden\" writes only a zip with the battery log, with every date moved back "
                + "by the same random number of weeks. Use it if you'd rather not show when you drive.")
            .setPositiveButton("Export", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface di, int w) { runExport(false); } })
            .setNeutralButton("For sharing, dates hidden", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface di, int w) { runExport(true); } })
            .setNegativeButton("Cancel", null).show();
    }

    private void runExport(final boolean hideDates) {
        toast("Exporting…");
        new Thread(new Runnable() { public void run() {
            final String msg = hideDates ? export.doShareExport() : export.doExport();
            handler.post(new Runnable() { public void run() {
                dialog().setTitle("Export to USB").setMessage(msg)
                    .setPositiveButton("Close", null).show();
            } });
        } }).start();
    }

    private void confirmClearHistory() {
        dialog().setTitle("Clear history?")
            .setMessage("This deletes your readings, drives and warnings from the head unit. It can't be undone.")
            .setPositiveButton("Clear", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface di, int w) { clearHistory(); } })
            .setNegativeButton("Cancel", null).show();
    }

    private void clearHistory() {
        sh.runRoot("rm -f " + STATE + "/log.csv " + STATE + "/log.old.csv " + STATE + "/sessions "
                + STATE + "/warnings " + STATE + "/lines");
        toast("History cleared");
        refreshData();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
