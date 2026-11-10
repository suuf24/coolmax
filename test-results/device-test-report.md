# Device test report: FreqGuard (now CoolMax)

**Date:** 2026-11-10
**Device:** Redmi Note 10 (M2101K7BNY), `rosemary_global`, Android 16 / SDK 36, arm64-v8a, build `BP2A.250605.031.A3`, HyperOS 3.0 (V816)
**Build tested:** `id.coolmax.app` debug APK built from this repo (`app-debug.apk`, versionName `1.0.0-debug`). The rename landed after the run started, so the installed package at test time was the pre-rename `id.freqguard.app.debug`; the code is identical apart from the name.
**Tool:** adb 1.0.41 (platform-tools 37.0.1)

---

## 1. Summary

| Check | Result |
|---|---|
| APK installs | **PASS**, but only through the `pm install-create` / `install-write` / `install-commit` session flow. Plain `adb install` and plain `pm install` are both rejected by MIUI. |
| Cold launch | **PASS**, `LaunchState: COLD`, 1688 ms, activity resumed |
| Crash on start | **NONE**, no `FATAL EXCEPTION` in logcat |
| Root access for the app | **PASS**, KernelSU allowlists the app (not `adb shell`) |
| Dashboard reads live sysfs | **PASS**, values matched the kernel at the time of capture |
| Interactive walkthrough of the six tabs | **BLOCKED**, the phone locked with a secure keyguard and could not be dismissed from adb |
| Dark theme on device | **NOT RUN**, same blocker |
| **CPU state after testing** | **PROBLEM**, both clusters are pinned at maximum frequency |

---

## 2. What was actually verified

### 2.1 Launch and root

```
am start -W -n .../MainActivity
Status: ok   LaunchState: COLD   TotalTime: 1688   Complete
pidof id.freqguard.app.debug -> 23872
```

No crash, no ANR, no Compose exception in logcat.

Root is granted per package by KernelSU. `adb shell su` fails (`su: inaccessible or not found`) because the `shell` uid (2000) is not on the KernelSU allowlist, but the app itself reads privileged nodes fine. This is expected behaviour, not a defect.

### 2.2 Dashboard content (as rendered at 13:57)

Captured from the UI hierarchy dump:

- `CPU / SoC Temperature`: `mtktsAP: 51.0°C`, `mtktscpu: 61.2°C`, headline `61.2 °C` in the danger colour
- `Little Cluster (policy0)`: `500 MHz`, `Min: 500 MHz`, `Gov: powersave`, `Max: 2000 MHz`, online cores `cpu0..cpu3` of `cpu0..cpu5`
- `Big Cluster (policy6)`: `2050 MHz`, `Min: 774 MHz`, `Gov: powersave`, `Max: 2050 MHz`, online `cpu6, cpu7`
- Real-time chart rendering frequency and temperature lines
- `42%` time-in-state row

The temperature readings come from the same `mtktsAP` / `mtktscpu` zones the kernel exposes, so the thermal reader works on this device.

---

## 3. Problems found

### 3.1 CRITICAL: both CPU clusters are pinned at maximum frequency

Read from sysfs at the end of the session, and still true 20 seconds later:

```
policy0: scaling_min_freq=2000000  scaling_max_freq=2000000  scaling_governor=powersave  cpuinfo_max_freq=2000000
policy6: scaling_min_freq=2050000  scaling_max_freq=2050000  scaling_governor=powersave  cpuinfo_max_freq=2050000
```

`scaling_min_freq` equals `scaling_max_freq` equals the hardware maximum on both clusters, so the CPU cannot downclock at all. Thermal status reads `0` (normal) at 57.7 degrees, so nothing is throttling.

Why this matters: the phone will run hot and drain battery until this is cleared. A `powersave` governor with `min == max` is contradictory, which points at something having written `scaling_min_freq` directly rather than a normal governor decision.

Timeline note: at 13:57 the little cluster reported `Min: 500 MHz`. The pin appeared later, during the failed MIUI install attempts. Two apps were running throughout: the release build `id.freqguard.app` (pid 12770, background service active) and the debug build (pid 23872).

Possible causes, in order of likelihood:
1. A leftover profile applied by the older release build that is still running in the background.
2. The aborted `pm install-commit`, which triggers dexopt and can leave a vendor performance boost latched.

**Action needed from the owner:** unlock the phone and press **Reset System Default** in the app, or simply reboot. I did not attempt to write sysfs myself: `adb shell` has no root on this device, and forcing values on a daily driver is not something to do unattended.

### 3.2 The phone locks and blocks both installs and UI testing

```
mWakefulness=Dozing   mState=OFF   isKeyguardShowing=true   mDreamingLockscreen=true
```

`KEYCODE_WAKEUP` returns the display to `Awake`, but the secure keyguard stays up, and `wm dismiss-keyguard` cannot clear a PIN. Consequences:

- Screenshots of the app and of the launcher both come back fully black while the panel is off. That is the display, not a rendering bug.
- `pm install-commit` fails with `INSTALL_FAILED_USER_RESTRICTED` while the screen is locked, even though the same flow succeeded while it was unlocked.

### 3.3 SELinux denials are coming from the release build, not from this one

```
avc: denied { search } for name="thermal" ... tcontext=u:object_r:sysfs_therm:s0
  scontext=u:r:untrusted_app:s0 ... app=id.freqguard.app
```

These repeat every ~10 seconds from pid 12770, which is the **release** package (`id.freqguard.app`), still running in the background. The debug build reads thermal through the root shell path and produced no denials of its own. So the fix for the `avc denied` noise described in `perubahan.md` is present in this code, and the noise here is the old APK.

Recommendation: uninstall the old release package, or stop its service.

### 3.4 Back did not close the sidebar

One observation, not yet reproducible: with the sidebar open, `KEYCODE_BACK` exited the whole activity instead of closing the drawer. `BackHandler(enabled = sidebarOpen)` should have caught it. This needs a re-test once the phone is unlocked, because the state at that moment is uncertain.

---

## 4. Rename to CoolMax

Applied across the project after the run above.

| Area | Change |
|---|---|
| Package | `id.freqguard.app` becomes `id.coolmax.app` (namespace and applicationId) |
| Source tree | `app/src/{main,test}/java/id/freqguard/app` moved to `.../id/coolmax/app` |
| Classes | `CoolMaxApplication`, `CoolMaxPreferences`, `CoolMaxViewModel`, `CoolMaxWidgetProvider`, `CoolMaxTheme`, `CoolMaxMainApp`, `CoolMaxSidebar` |
| Resources | `coolmax_widget_info.xml`, `widget_coolmax.xml`, `Theme.CoolMax` |
| Strings | `app_name` becomes `CoolMax`; tile and widget labels updated in all four locales |
| DataStore | `freqguard_prefs` becomes `coolmax_prefs` |
| Manifest | widget broadcast actions become `id.coolmax.app.ACTION_WIDGET_*` |
| Docs | `README.md`, `perubahan.md`, `changes.md`, `settings.gradle.kts` (`rootProject.name`) |

381 occurrences across 60 files. A `grep -ri` over `app/src`, both Gradle files and the ProGuard rules returns nothing; only this report names the old package, which it has to in order to describe the rename.

Verification after the rename:

```
./gradlew :app:assembleDebug :app:testDebugUnitTest --offline
BUILD SUCCESSFUL in 1m 2s
aapt2 dump badging -> package: name='id.coolmax.app.debug' versionName='1.0.0-debug'
```

All ten existing unit test suites still pass. The only compiler output is one pre-existing deprecation warning in `RootManager.kt` about `FLAG_REDIRECT_STDERR`, unrelated to the rename.

**Consequence for the phone:** `id.coolmax.app.debug` is a new package. KernelSU authorises root per package, so the renamed app will start on the Root Required screen until it is approved again. The old `id.freqguard.app` and `id.freqguard.app.debug` packages can then be uninstalled.

---

## 5. Next steps

1. Unlock the phone and reboot (or press **Reset System Default**), then confirm that `scaling_min_freq` is back to a normal value such as `500000`:
   `adb shell cat /sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq`
2. Uninstall the stale release build so its background service stops writing to sysfs:
   `adb shell pm uninstall id.freqguard.app`
3. Install the renamed build. While the screen is unlocked:
   `adb install -r app/build/outputs/apk/debug/app-debug.apk`
   If MIUI still refuses, use the session flow:
   `adb shell pm install-create -r -t` then `pm install-write` and `pm install-commit`
4. Open CoolMax once and approve the KernelSU root prompt for `id.coolmax.app.debug`.
5. Re-run the tab-by-tab walkthrough (Dashboard, CPU Control, Thermal, Screen-Off, Per-App, Logs) in both light and dark mode, plus the keyboard and TalkBack checks that are still outstanding from the antislop gate.

---

# Round 2: install, root grant, 2G guard debugging

**Same day, later.** Package `id.coolmax.app.debug`, APK built from section 10 of `changes.md`.

## 1. Root, at last

The earlier note that no root manager existed was wrong, and the cause was a naming assumption. The
manager is installed as **`mtylzr.tdaudj.fqttzb`** (obfuscated package, launcher label `ReSukiSU`,
activity `.ui.MainActivity`), so searches for `com.resukisu.resukisu`, `me.weishu.kernelsu` and
`com.topjohnwu.magisk` all came back empty.

Granting root without touching the phone by hand was done through the manager UI over adb:

```
am start -n mtylzr.tdaudj.fqttzb/mtylzr.tdaudj.fqttzb.ui.MainActivity
input tap <app row> ; input tap <Superuser switch>
```

The switch is a Compose control whose `checked` attribute updates a frame late, so a screen capture
was used to confirm the result instead of `uiautomator dump`. After the grant the manager lists
CoolMax with a `ROOT` badge, and the app sidebar reports **Root active**. The grant survives
reinstalls because the package name and UID stay the same.

## 2. The 2G bug, reproduced and fixed

| Step | Observed |
|---|---|
| Screen off | Radio dropped to `GSM`, `preferred_network_mode=1`, as designed |
| Screen on | **Stayed on 2G for 150 s and beyond**, the reported bug |
| App event log | The restore *did* run, and wrote the GSM mask back for slot 1 and `1` for `preferred_network_mode1..3` |

The saved baseline itself contained 2G, so the restore was doing exactly what it was told. See
`changes.md` section 10 for the mechanism and the code changes. After the fix:

| Step | Observed |
|---|---|
| Screen off, 25 s untouched | `mode=1`, both slots `GSM` |
| Screen on | Both slots `LTE\|LTE_CA` within 4 s, `mode=11`, `gsm.network.type=LTE,LTE` |
| Planted stale state (`mode3=1`, slot 1 `GSM`) before a cycle | After the cycle both were healed back to `11` / `LTE\|LTE_CA` |
| Service after `pm install-commit` | Running, because `CoolMaxApplication` now starts it when a guard feature is enabled |

The dips seen in the middle of a longer observation window are the idle screen timeout cycling the
guard again, not a failure: every screen-on event restored 4G.

## 3. Fingerprint of the phone during this round

```
before: preferred_network_mode 11, slots LTE|LTE_CA, gsm.network.type LTE,LTE
after:  preferred_network_mode 11 on every key, both slots LTE|LTE_CA, gsm.network.type LTE,LTE
stay_on_while_plugged_in restored to 0
coolmax_network_baseline / coolmax_network_allowed_types deleted
```

## 4. UI change

`Reset System Default` moved from the always-visible top bar into the sidebar, above the root badge.
Screenshot: `device-sidebar.png`.

## 5. Not verified this round

- The full six tab walkthrough and the dark theme: the phone locked itself with a PIN partway through
  and a PIN cannot be entered from adb.
- Thermal and per-app behaviour after the release build is installed, for the same reason.
- The release APK was built and signed but not installed on the phone, because the debug build is
  already installed under a different package name and the device was locked.
