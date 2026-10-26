package com.petrobot.hume;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;

/**
 * Aerial Views as the screensaver.
 *
 * <p>Hume used to carry a screensaver of its own (the full-screen carousel took over, holding the
 * screen on). That is gone: the screensaver is
 * <a href="https://github.com/theothernt/AerialViews">Aerial Views</a>, the Apple-TV-style video
 * screensaver, which is better at it than a launcher holding the screen awake ever was.
 *
 * <p>This class knows only the three things the launcher needs: whether Aerial Views is installed,
 * which screensaver the TV is set to, and how to make Aerial Views that screensaver. Setting it
 * writes {@code screensaver_components}, which Android guards with {@code WRITE_SECURE_SETTINGS} —
 * an install-time permission only a computer can grant, so when it is missing the launcher says so
 * in plain words with the one command to run (the same way {@link Perms} handles the other
 * permissions no TV has a screen for).
 *
 * <p>Starting it needs no permission at all: Aerial Views ships an exported test activity that
 * begins the dream, which is what its own "preview" button uses.
 */
final class Screensaver {
    static final String PKG = "com.neilturner.aerialviews";
    /** The dream component the TV's settings hold, and the one Android starts when idle. */
    static final String DREAM = PKG + "/.ui.screensaver.DreamActivity";
    /** Starts the screensaver on demand, exported by Aerial Views for exactly this. */
    private static final String TEST = PKG + ".ui.screensaver.TestActivity";

    private Screensaver() {
    }

    static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Whether Aerial Views is the screensaver the TV will start when it goes idle. */
    static boolean active(Context c) {
        return DREAM.equals(component(c));
    }

    /** The screensaver component the TV is set to, or null when it uses its own default. */
    static String component(Context c) {
        String all = Settings.Secure.getString(c.getContentResolver(), "screensaver_components");
        if (all == null || all.trim().isEmpty()) return null;
        return all.split(",")[0].trim();  // a TV may list several; the first one is used
    }

    /** The screensaver is switched off in the TV's own settings. */
    static boolean off(Context c) {
        return "0".equals(Settings.Secure.getString(c.getContentResolver(), "screensaver_enabled"));
    }

    /**
     * Name of whatever the TV is set to use, for the settings row: the app's own label, or a plain
     * description when the TV has no choice of its own.
     */
    static String label(Context c) {
        if (off(c)) return "Off in TV settings";
        String comp = component(c);
        if (comp == null) return "TV default";
        ComponentName cn = ComponentName.unflattenFromString(comp);
        if (cn == null) return "TV default";
        try {
            return c.getPackageManager()
                    .getApplicationLabel(c.getPackageManager().getApplicationInfo(cn.getPackageName(), 0))
                    .toString();
        } catch (Exception e) {
            return "TV default";
        }
    }

    /** Whether the launcher may write the TV's secure settings (granted once over adb). */
    static boolean canSet(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Makes Aerial Views the TV's screensaver. False when the TV refuses, which on every TV that
     * hasn't had the permission granted by hand means "say how to grant it".
     */
    static boolean set(Context c) {
        if (!installed(c)) return false;
        try {
            Settings.Secure.putString(c.getContentResolver(), "screensaver_components", DREAM);
            Settings.Secure.putString(c.getContentResolver(), "screensaver_enabled", "1");
            return active(c);
        } catch (Exception e) {
            return false;
        }
    }

    /** Starts the screensaver now. No permission needed: Aerial Views' own preview entry point. */
    static boolean start(Activity a) {
        if (installed(a) && Suggestions.start(a, new Intent().setClassName(PKG, TEST)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return true;
        // Anything else that is set as the TV's screensaver: the system's dream starter.
        return Suggestions.start(a, new Intent().setClassName("com.android.systemui",
                "com.android.systemui.Somnambulator").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Aerial Views' own settings (video source, overlays, clock, playlist). */
    static boolean settings(Activity a) {
        Intent i = a.getPackageManager().getLeanbackLaunchIntentForPackage(PKG);
        if (i == null) i = a.getPackageManager().getLaunchIntentForPackage(PKG);
        return i != null && Suggestions.start(a, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** The Play Store page, for a TV that doesn't have Aerial Views yet. */
    static void install(Activity a) {
        if (Suggestions.start(a, new Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=" + PKG)))) return;
        Suggestions.start(a, new Intent(Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=" + PKG)));
    }

    /**
     * Explains the one-time command that lets the launcher set the screensaver, the same way the
     * other permissions no TV has a switch for are explained.
     */
    static void explainGrant(Activity a) {
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("One-time setup")
                .setMessage("This TV doesn't let an app choose the screensaver. Whoever set up the TV can "
                        + "allow it once, from a computer on the same network:\n\n"
                        + "adb shell pm grant com.petrobot.hume android.permission.WRITE_SECURE_SETTINGS\n\n"
                        + "Then choose “Use Aerial Views” again. Until then, the TV's own screensaver "
                        + "setting decides (Settings → Device Preferences → Screen saver).")
                .setPositiveButton("OK", null)
                .setNeutralButton("TV settings", (d, w) -> Suggestions.start(a, new Intent(Settings.ACTION_SETTINGS)))
                .show();
    }

    /** Reverts to the TV's stock screensaver, so the choice is not a one-way door. */
    static void restoreDefault(Context c) {
        try {
            Settings.Secure.putString(c.getContentResolver(), "screensaver_components",
                    "com.android.dreams.basic/com.android.dreams.basic.Colors");
        } catch (Exception ignored) {
            // nothing to do: the row reports what the TV ends up with
        }
    }
}
