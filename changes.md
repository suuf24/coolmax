# CoolMax: antislop audit and change record

**Date:** 2026-11-10
**Scope:** whole project (`app/src/main`, Kotlin + Compose UI, resources), checked against the antislop rules (R-01 to R-38).
**Mode:** fix now, document here. Chosen by the project owner before the work started.

This file records what was checked, what was changed, why, and what was verified. Every change below cites the rule it answers. Nothing was changed that is not listed here.

---

## 1. Design Read and dials

> Reading this as: a root CPU/thermal control utility for Android power users, in a neumorphic single-slab visual language, dial **ENERGY 2 / RHYTHM 1 / MOTION 2**.

- **ENERGY 2:** tactile depth plus one strong accent. It says hello without shouting.
- **RHYTHM 1:** every screen is a scrolling column of the same card. This is deliberate: six data-dense tabs where a control sits in the same place every time beats composition variety.
- **MOTION 2:** switch thumb and track animate, pressed controls sink to INSET, the sidebar slides and fades. Nothing moves purely for decoration.
- **Identity motif:** one highlight/shadow pair moulds every surface, and the accent appears only for a live value, a selection, or keyboard focus.

Direction already exists in code (`ui/theme/Theme.kt` and `ui/components/Neu.kt` explain why neumorphism and why dynamic color is off). There is no standalone `DESIGN.md` yet; see section 6.

---

## 2. Findings

| # | Rule | Finding | Severity | Status |
|---|---|---|---|---|
| 1 | R-02 | Em dash in 6 user-facing strings per language (24 total) plus 2 UI strings in Kotlin | Hard Gate | Fixed |
| 2 | R-25 | Light-theme accent, muted, danger and success failed AA as text (4.29 to 4.46) | Hard Gate | Fixed |
| 3 | R-25 | `muted` at 85% alpha on the log screen dropped below AA | Hard Gate | Fixed |
| 4 | R-25, R-34 | Primary button text was `Color.White` in both themes: 2.32:1 on the dark accent | Hard Gate | Fixed |
| 5 | R-25 | Danger text on the 14% danger-tinted safe-mode card was 3.84:1 | Hard Gate | Fixed |
| 6 | R-32 | `indication = null` on every neumorphic control removed the focus ring entirely | Hard Gate | Fixed |
| 7 | R-32 | `NeuSwitch` was a plain `clickable`, so assistive tech never heard on/off | Hard Gate | Fixed |
| 8 | R-03 | `NeuSwitch` was 54x30dp and `NeuChip` about 38dp tall, under the 44px tap target | Hard Gate | Fixed |
| 9 | R-03 | Four profile action buttons in a non-wrapping `Row`, clipping off-screen on narrow phones | Hard Gate | Fixed |
| 10 | R-27 | No loading state while root was being checked: the app rendered an empty shell | Hard Gate | Fixed |
| 11 | R-27 | No empty state when no CPU cluster could be read | Hard Gate | Fixed |
| 12 | R-02 | Em dash in 6 KDoc comments (developer text, same character) | Quality | Fixed |
| 13 | R-37 | No `DESIGN.md`; direction lives only in code comments | Quality | Not fixed, recorded |
| 14 | none | Core-state labels hardcoded in English inside an Indonesian UI | Not an antislop rule | Not fixed, recorded |

---

## 3. Changes applied

### 3.1 Colour palette (`ui/theme/Color.kt`, `ui/theme/Theme.kt`)

R-25 requires WCAG AA (4.5:1 for normal text). Five light-theme roles failed on the light surface. The hues were kept; only lightness moved, so the identity does not change.

| Token | Before | After | Rule |
|---|---|---|---|
| `NeuAccentLight` | `#3E63DD` | `#355AD4` | R-25 |
| `NeuMutedLight` | `#6B7889` | `#596677` | R-25 |
| `NeuDangerLight` | `#C2403B` | `#B02E29` | R-25 |
| `NeuWarningLight` | `#9A5B00` | `#945500` | R-25 |
| `NeuSuccessLight` | `#2E7D5B` | `#22714F` | R-25 |

`NeuPalette` gained an `onAccent` field. Light is `Color.White`, dark is `#10141B`. Reason (R-31): the dark accent is a light periwinkle, so white ink on it measured 2.32:1 and failed; dark ink measures 7.97:1. `NeuButton` now reads `palette.onAccent` instead of a hardcoded `Color.White`.

Measured contrast after the change (light values are against the base surface, with the darker INSET surface in brackets, since INSET is the worst case):

| Role | Light | Dark |
|---|---|---|
| muted | 5.01 (4.69) | 6.08 |
| accent | 5.05 (4.72) | 6.40 |
| danger | 5.15 (4.69) | 6.49 |
| warning | 5.06 (4.73) | 9.42 |
| success | 5.08 (4.75) | 8.67 |
| onBase | 10.79 | 12.60 |
| primary button ink on accent | white, 5.89 | ink, 7.97 |

### 3.2 Design system (`ui/components/Neu.kt`)

- **Focus ring (R-32).** `NeuSurface` takes `focused: Boolean` and draws a 2dp accent border on top of the surface when true. Every control is clickable with `indication = null` because a Material ripple breaks the moulded-slab look, and that also killed the default focus indicator. A keyboard or D-pad user previously got zero feedback. Reason (R-31): focus must be visible without reintroducing a ripple that fights the surface.
- **`NeuSwitch` (R-32, R-03).** `clickable` replaced with `toggleable(value = checked, role = Role.Switch)`, so the checked state is published to accessibility services. The track is wrapped in a box with `minimumInteractiveComponentSize()`, which lifts the tap target to 48dp while the drawn switch stays 54x30dp.
- **`NeuChip` (R-03, R-32).** Same treatment: an outer box carries the 48dp minimum tap target and the focus ring, so the pill keeps its own size.
- **`NeuButton`, `NeuIconButton` (R-32).** Both now track `collectIsFocusedAsState()` and pass it to `NeuSurface`.

### 3.3 Navigation shell (`MainActivity.kt`)

- **Loading state (R-27).** Added `LoadingScreen()` for the window where `isCheckingRoot` is true. Before, that window rendered the full shell with no data, which read as a broken app. Copy: `R.string.app_loading`.
- **Sidebar focus (R-32).** `SidebarItem` now uses a remembered interaction source and shows the focus ring.

### 3.4 Screens

- **`DashboardScreen.kt`**
  - Em dash in the time-in-state header replaced with a colon (R-02).
  - Safe-mode card tint lowered from 0.14 to 0.12 because danger text on that tint measured 3.84:1 (R-25). It now measures 4.58:1.
  - Added the empty state for the cluster list (R-27), copy in `dashboard_empty_clusters_title` and `dashboard_empty_clusters_desc`.
- **`ScreenOffScreen.kt`**
  - The saved-profile action row (Apply, Duplicate, Export, Delete) is now a `FlowRow` so it wraps instead of pushing the last button off-screen on a narrow phone (R-03).
- **`LogScreen.kt`**
  - Command excerpt colour no longer applies `alpha = 0.85f`. Dimming muted text that already sat near the threshold pushed it under AA (R-25).

### 3.5 Copy (`res/values*/strings.xml`, 4 files)

Six em dashes removed per file (R-02). Replacements use a colon, a comma, or a full stop, whichever keeps the sentence natural:

| String | Before (EN) | After (EN) |
|---|---|---|
| `screen_off_cores_title` | `Screen-Off Core Online State — %1$s` | `Screen-Off Core Online State: %1$s` |
| `thermal_alert_title` | `High CPU Temperature — Profile Cancelled` | `High CPU Temperature: Profile Cancelled` |
| `thermal_high_risk_warning` | `... is risky — the device may ...` | `... is risky. The device may ...` |
| `perapp_governor_unavailable_warning` | `... in this kernel — the rule ...` | `... in this kernel, so the rule ...` |
| `notif_status_thermal_normal` | `Temperature normal — throttle released` | `Temperature normal, throttle released` |
| `thermal_throttle_alert_title` | `High CPU Temperature — Throttling Active` | `High CPU Temperature: Throttling Active` |

Indonesian copies were changed the same way (`Profil Dibatalkan`, `Throttling Aktif`, `karena itu`, and similar). Three strings were added to all four files: `app_loading`, `dashboard_empty_clusters_title`, `dashboard_empty_clusters_desc`.

### 3.6 Kotlin text and comments

- `CoolMaxViewModel.kt`: the 15s timeout banner now reads "15s confirmation timed out, so the previous safe configuration was restored." (R-02).
- `NetworkModeController.kt` (4), `SysfsReader.kt` (1), `SysfsWriter.kt` (1): em dashes in KDoc replaced with a comma or full stop. Developer text only, changed so the character appears nowhere in the repository (R-02).

---

## 4. Verification

Commands run from the project root with JDK 17 (`C:/bg/jdk-17.0.21.0.2+1`) and the local Android SDK, offline:

```
./gradlew :app:compileDebugKotlin --offline   -> EXIT=0
./gradlew :app:assembleDebug :app:testDebugUnitTest --offline
  -> BUILD SUCCESSFUL in 12s, 44 actionable tasks
  -> app/build/outputs/apk/debug/app-debug.apk (18 MB) produced
```

The existing unit suites all ran and passed: `SysfsParser`, `SafeFrequencyPlanner`, `SysfsWriter`, `MtkOppFallbackWriter`, `TriggerPriorityResolver`, `GovernorPriorityResolver`, `ProfileJsonValidator`, `ThermalThrottlePlanner`, `AppFreezer`, `NetworkModeController`.

Additional checks:

- `grep -rn "—" app/src/main` returns nothing. Zero em dashes remain in resources, Kotlin strings, or comments.
- All four `strings.xml` files parse as valid XML and hold the same 171 keys, so no locale is missing a string.
- Contrast ratios were computed from the shipped hex values, not estimated.

**Not performed:** an interactive click-through on a device or emulator. There is no emulator or system image in this environment (`C:/bg/android-sdk` has build-tools, platform-tools and platforms only). See R-35 in the gate below.

---

## 5. Delivery Gate

### Block 1: Hard Gate

| Item | Result | Evidence |
|---|---|---|
| R-02 em dash | **PASS** | `grep -rn "—" app/src/main` returns nothing. 24 string entries and 8 code strings/comments changed. |
| R-03 mobile | **PASS by inspection** | Profile actions now `FlowRow`; switch and chip tap targets raised to 48dp. Re-checked card paddings at a 320dp width on paper: no non-wrapping fixed-width row remains. |
| R-17 statistics | **PASS** | No statistics in any screen. |
| R-18 testimonials | **PASS** | No testimonials or people anywhere. |
| R-23 assets | **PASS** | No logo, avatar or illustration was generated. Navigation uses Material symbols that match their destination. |
| R-24 navigation | **PASS** | Six sidebar entries, six implemented screens in the `when (currentTab)` branch. No dead destination. |
| R-25 contrast | **PASS** | Measured table in 3.1. Lowest value is 4.58:1 (danger on the tinted safe-mode card). |
| R-26 dead controls | **PASS by inspection** | Every `NeuButton` and `NeuOutlinedButton` calls a view model action, opens a dialog, or fires an `Intent` (`Settings.ACTION_USAGE_ACCESS_SETTINGS`, `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `ACTION_SEND` chooser). |
| R-27 states | **PASS** | Loading: `LoadingScreen`, app picker spinner. Empty: logs, profiles, per-app, app picker, clusters. Error: `RootRequiredScreen`, banner messages, thermal sensor unavailable. |
| R-28 FAQ | **PASS** | No FAQ section exists. |
| R-32 keyboard | **PASS by inspection** | Focus ring added to button, icon button, switch, chip, sidebar item. Switch state exposed via `toggleable`. Dialogs close on Escape (`onDismissRequest`). Sliders and text fields use Material defaults, which include focus handling. |
| R-33 patch scripts | **PASS** | No script mutates source or CSS. All edits are in source. |
| R-34 both themes | **PASS by calculation** | Every role reaches AA in both palettes (table 3.1). The one broken pairing, white on the dark accent, is fixed. |
| R-35 run before delivering | **BUILD PASS, DEVICE CLICK-THROUGH NOT RUN** | Build, resource compilation and all unit tests pass and the APK is produced. No emulator or device is available here, so the per-element click-through was not performed. This is a stated limitation, not a pass. |
| R-36 fabricated claims | **PASS** | No security, compliance or performance claim in the UI. |
| R-37 design direction | **PASS with note** | Direction is present and documented in `Theme.kt` and `Neu.kt`; dials declared in section 1. There is no standalone `DESIGN.md` (finding 13). |
| R-38 fabricated content | **PASS** | Every string describes real app behaviour. Empty states say there is nothing yet instead of inventing content. |

### Block 2: Purpose-Gate

| Item | Result | Reason |
|---|---|---|
| R-01 gradients | **PASS** | The only gradient is the 1.5dp INSET border (`shadow` to `highlight`). Reason: it draws the embossed lip that tells an inset control from a raised one. No page-level gradient. |
| R-04 icons | **PASS** | Material icons map to their destination (Dashboard, Tune, Thermostat, BatterySaver, Apps, History), and the per-app fallback is `Android`. No sparkle, orb or robot. |
| R-06 typography | **PASS** | Material 3 default type scale on the platform font. Reason: a system utility should match the system, and bundling a display face would add weight for no readability gain. |
| R-07 background | **PASS** | No grid, dots or blueprint pattern. |
| R-08 button arrows | **PASS** | No arrows anywhere. |
| R-09 badges | **PASS** | `NeuStatusPill` shows real state only (Normal, Throttling). The dry-run label is a real mode indicator, not decoration. |
| R-10 glassmorphism | **PASS** | No backdrop blur. `Modifier.blur` is used solely to soften the neumorphic shadow layers. |
| R-12 shadow | **PASS** | One shadow pair per raised surface, and it is the identity of the style. INSET controls carry none. |
| R-13 glow | **PASS** | No glow. |
| R-14 feature cards | **PASS** | Not a marketing page. Card content varies by screen and hierarchy. |
| R-19 animations | **PASS** | Switch thumb and track, press-to-INSET, sidebar slide and fade, banner visibility. Each maps to a state change. Matches MOTION 2. |
| R-22 illustrations | **PASS** | None. |

### Block 3: Liveliness

| Item | Result |
|---|---|
| Dials explicit | **PASS**: ENERGY 2 / RHYTHM 1 / MOTION 2, section 1. |
| Output matches the dials | **PASS**: sections are uniform by design, which is what RHYTHM 1 claims, and motion stays at state feedback for MOTION 2. |
| One focal point per screen | **PASS**: the live frequency or temperature value is the largest element on every screen. |
| Whitespace is structural | **PASS**: 14 to 18dp insets separate control groups inside cards, 12 to 14dp separates cards. |
| One deliberate accent | **PASS**: the accent marks live values, selections, progress and focus, and nothing else. |
| Identity motif | **PASS**: the highlight/shadow pair is applied consistently across all six screens. |
| Design Read declared | **PASS**: section 1, before the edits. |

### Block 4: Craftsmanship and Quality Locks

| Item | Result |
|---|---|
| C-1 intentionality | **PASS**: every non-obvious choice has a one-line reason in code or in section 3. |
| C-2 functional completeness | **PASS by inspection**: no unwired control. |
| C-3 content-driven composition | **PASS**: each screen exists for one job, not to fill a template. |
| C-4 resilience | **PASS by inspection**: empty, loading and error states exist; both themes reach AA; no mouse-only interaction. Visual confirmation on a device is the missing step from R-35. |
| C-5 evidence over claims | **PASS**: no claim is presented. |
| R-05 layout template | **PASS**: no hero, no logo bar, no pricing, no bento, no fake terminal. Uniform rhythm is the declared RHYTHM 1. |
| R-11 radius | **PASS**: 16dp for buttons and rows, 20 to 22dp for cards, 28dp for the sidebar, 50 for chips and the switch. Variation tracks hierarchy. |
| R-15 CTA | **PASS**: "Apply (15s Auto-Revert Confirmation)", "Save Current as Profile", "Manage App Whitelist". No "Get Started". |
| R-16 buzzwords | **PASS**: none in any string file. |
| R-20 identity | **PASS**: swap the name and the moulded-slab system still reads as this app. |
| R-21 dark mode | **PASS**: the theme follows the system setting and both palettes meet AA. The theme is not hardcoded to dark. |
| R-29 palette | **PASS**: one neutral surface ramp, one blue accent, plus semantic danger, warning and success used only for state. |
| R-30 clone check | **PASS**: not a Linear, Vercel, Stripe or Notion look. |
| R-31 reasons written | **PASS**: sections 3.1 to 3.5 and the inline comments. |

---

## 6. Observed, not changed

These were found during the check and deliberately left alone, with the reason.

1. **No `DESIGN.md` (R-37).** The direction is real and documented in code, but it is not in the file the rule expects. Recommend writing one that states the identity, the palette roles, the type scale and `Dial: ENERGY 2 / RHYTHM 1 / MOTION 2`. The owner should author it, since agent-written direction tends toward the default taste this audit exists to filter.
2. **Core labels hardcoded in English (not an antislop rule).** `ScreenOffScreen.kt` renders `cpu0 (Locked ON)`, `cpuX (Offline)` and `cpuX (Online)` directly in Kotlin while the rest of the app is bilingual. It needs three new strings. Out of scope for a design audit.
3. **"Pertahankan (15s)" style countdown text.** Reads correctly; no change needed.
4. **RHYTHM 1.** Every screen being a card column is intentional. If the product later grows a marketing or onboarding surface, that surface should get its own rhythm rather than raising the dial for the whole app.

---

## 7. Re-running the checks

```bash
# 1. No em dash anywhere
grep -rn "—" app/src/main || echo "clean"

# 2. String files parse and stay in sync
python - <<'PY'
import glob, xml.etree.ElementTree as ET
sets = {f: {e.get('name') for e in ET.parse(f).getroot() if e.get('name')}
        for f in sorted(glob.glob('app/src/main/res/values*/strings.xml'))}
base = next(iter(sets.values()))
print({f: sorted(base - s) for f, s in sets.items() if base - s} or "in sync")
PY

# 3. Build and unit tests
./gradlew :app:assembleDebug :app:testDebugUnitTest --offline

# 4. No stale package name in code, resources or Gradle
grep -ri "<former name>" app/src app/build.gradle.kts app/proguard-rules.pro settings.gradle.kts || echo clean
```

---

## 8. Rename to CoolMax

Applied on request after the antislop pass. The product and the package were both renamed; nothing else changed behaviour.

| Area | Change |
|---|---|
| Package | `id.freqguard.app` becomes `id.coolmax.app`, in `namespace` and `applicationId` |
| Source tree | `app/src/{main,test}/java/id/freqguard/app` moved to `.../id/coolmax/app` |
| Classes | `CoolMaxApplication`, `CoolMaxPreferences`, `CoolMaxViewModel`, `CoolMaxWidgetProvider`, `CoolMaxTheme`, `CoolMaxMainApp`, `CoolMaxSidebar` |
| Resources | `coolmax_widget_info.xml`, `widget_coolmax.xml`, `Theme.CoolMax` |
| Strings | `app_name` is `CoolMax`; tile and widget labels updated in all four locales |
| DataStore | `freqguard_prefs` file becomes `coolmax_prefs` |
| Manifest | widget broadcast actions become `id.coolmax.app.ACTION_WIDGET_*` |
| Build | `rootProject.name = "CoolMax"`; release keystore references renamed |
| Docs | this file, `README.md`, `perubahan.md` |

381 occurrences across 60 files. Verified after the change: `grep -ri` over `app/src`, the Gradle files and the ProGuard rules returns nothing, and the build produces `package: name='id.coolmax.app.debug'`.

Because `applicationId` changed, the renamed app is a new package to Android and to KernelSU: root must be approved once more for `id.coolmax.app.debug`, and the old `id.freqguard.app` packages can be uninstalled.

Device test results for this round are in `test-results/device-test-report.md`, also copied to `/sdcard/Alarms/` on the test phone.

Still open before this can be called device-verified: install `app-debug.apk` on a rooted test device and walk each tab in both themes, confirming that toggles announce on/off with TalkBack, that focus is visible with a keyboard or D-pad, and that the profile action row wraps instead of clipping at 320dp.

---

## 9. Production readiness

Work done to turn the renamed build into something that can actually be installed and shipped, rather than only run from Android Studio.

| Area | Change | Why |
|---|---|---|
| App icon | `mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml` adaptive icons, plus `ic_launcher_foreground`, `ic_launcher_monochrome` and `ic_launcher_background` | The manifest pointed at `@android:drawable/ic_menu_manage`, a framework icon, so the launcher showed a stock Android glyph. Monochrome layer makes it work with themed icons on Android 13 and later |
| Signing | `app/build.gradle.kts` reads `keystore.properties` through `java.util.Properties`; the file is gitignored; `keystore.properties.example` documents the keys | The password was hardcoded in the build script, so it leaked with the repository the first time it was pushed |
| Signing material | `freqguard-release.jks` renamed to `app/coolmax-release.jks`; `*.jks` and `keystore.properties` added to `.gitignore` | Consistency after the rename, and no keystore in version control |
| Notifications | `POST_NOTIFICATIONS` runtime request added in `MainActivity` before the early returns | On Android 13 and later the request is mandatory. Without it, notification permission is silently denied and no guard notification ever appears |
| Widget | Theme-qualified drawables in `drawable/` and `drawable-night/`, colours in `values/colors.xml` and `values-night/colors.xml`, layout updated in `layout/widget_coolmax.xml` | The widget used hardcoded hex colours, so it stayed light in dark mode |
| Launch | Launch themes for light and dark with `window_background` | Cold start flashed a white window in dark mode |
| R8 | `isShrinkResources = true` and a rewritten `proguard-rules.pro` with libsu, model, kotlinx.serialization and enum keep rules | A release build without shrinking shipped everything, including dead code. Keep rules prevent R8 from stripping names that reflection and serialization use |

### 9.1 Lint findings fixed

`lintDebug` first reported 8 errors and 77 warnings. All 8 errors were real defects:

| Finding | Where | Effect if shipped |
|---|---|---|
| `unsafeCheckOpNoThrow` requires API 29 | `service/ForegroundAppDetector.kt` | `NoSuchMethodError` on Android 8 and 9 when the per app detector checked its permission, because minSdk is 26. Now gated by `SDK_INT >= Q` with a fallback to the deprecated `checkOpNoThrow` |
| `Tile.setSubtitle` requires API 29 | `service/ScreenOffTileService.kt` | `NoSuchMethodError` on Android 8 and 9 when the quick settings tile refreshed. Now gated by `SDK_INT >= Q`; older versions simply show the tile label |
| `StringFormatInvalid` on `thermal_info_floor` (`30% batas` reads as the `% b` conversion) | `values`, `values-in`, `values-en` | Any future call passing arguments would throw `FormatFlagsConversionMismatchException`. Rewritten as `30 persen` / `30 percent`. The same latent problem in `warning_max_below_50_percent` (`50%`) was fixed the same way |

Warnings also fixed:

- `DefaultLocale`, 9 sites: every `String.format("%.1f", ...)` for temperatures now passes `Locale.US`, so a device set to a locale with a comma decimal separator shows `61.2 °C` consistently and the strings stay parseable.
- `UnusedResources`, 5 strings: `thermal_threshold_label`, `thermal_alert_title`, `thermal_alert_body`, `thermal_guard_toggle` and `thermal_current_temp_format` removed from all string files.
- `ObsoleteSdkInt`, 2 sites: dead `SDK_INT >= O` branches removed from `service/ScreenOffGuardService.kt`, because minSdk 26 makes them always true.
- `LocaleFolder`: `res/values-id` duplicated `res/values-in` with the same Indonesian text, so the flagged copy was deleted. Indonesian now lives in `values-in`, English in `values-en`.

`abortOnError = true` was added to the `lint` block, so a new lint error fails the build instead of reaching a release.

### 9.2 Verification

```bash
export JAVA_HOME=/c/bg/jdk-17.0.21.0.2+1
./gradlew :app:lintDebug :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest
```

Result: `BUILD SUCCESSFUL`. Lint is down from 8 errors and 77 warnings to 0 errors and 59 warnings, and the unit test suite passes.

| Artefact | Size | Notes |
|---|---|---|
| `app-debug.apk` | 18 MB | `package: name='id.coolmax.app.debug'` |
| `app-release.apk` | 1.7 MB | `package: name='id.coolmax.app'`, `application-label: CoolMax`, signed, certificate SHA-256 `12:b5:c1:...:44:1c` |

The release APK verifies with `apksigner verify`, which confirms the `keystore.properties` path, the shrinking and the keep rules all work together.

### 9.3 Remaining warnings, deliberately not fixed

| Count | Id | Why it stays |
|---|---|---|
| 42 | `GradleDependency` | Newer library versions exist. Upgrading Compose and AndroidX in the same pass as the hardening would make the device result unattributable, and this build is verified on the current versions |
| 8 | `PluralsCandidate` | Strings such as "3 clusters" would read better as plurals. Cosmetic, and it touches copy in three files |
| 3 | `ObsoleteLintCustomCheck` | Third party lint jars in the dependencies were built against an older lint API. Comes from the libraries, not this code |
| 3 | `AndroidGradlePluginVersion` | AGP 8.13 exists but needs Gradle 8.13 and an SDK platform newer than the installed `android-34` |
| 1 | `ObsoleteSdkInt` | `mipmap-anydpi-v26` is "unnecessary" at minSdk 26, but moving it to `mipmap-anydpi` makes aapt2 stop resolving `@mipmap/ic_launcher` entirely, so the folder stays |
| 1 | `DataExtractionRules` | `allowBackup` is already false, so backup rules would have nothing to describe |
| 1 | `BatteryLife` | The screen off guard is a foreground service on purpose; the warning is a design statement, not a defect |

### 9.4 Still open

These need a person at the device, not another code change:

1. The full walkthrough of the renamed build. The phone was locked with a secure keyguard during the last attempt, so `id.coolmax.app.debug` was never installed.
2. KernelSU has to approve `id.coolmax.app.debug` once, because the package name changed.
3. The test device had `policy0` and `policy6` pinned at `cpuinfo_max_freq` with the `powersave` governor, which cannot be cleared from `adb shell` without root. Reboot or use Reset System Default before judging frequency behaviour.

## 10. Screen-off 2G guard: the phone stayed on 2G after screen-on

### 10.1 Symptom

Reported on the device: after the display came back on, the cellular radio stayed at **2G only**
instead of returning to 4G. On the test phone both SIM slots read `GSM`, every
`preferred_network_mode*` entry was `1`, and `gsm.network.type` was `EDGE`.

### 10.2 Root cause, confirmed on the device

The guard captures the "default" network mode right before forcing GSM, and the capture was taken
from live settings **without checking what those settings already held**. Once one cycle ended with
the radio on 2G, the next capture recorded 2G as the user's default, so the screen-on restore
faithfully wrote 2G back. The evidence is the app's own event log and the settings mirror:

```
15:25:07 Screen-Off Network (2G only)   settings put global preferred_network_mode 1 ; ... mode4 1
15:32:41 Screen-On Network Restore      -s 0 <lte mask> ; -s 1 1000000000000000 ; ... mode3 1 ; mode4 11
mirror: preferred_network_mode=11;preferred_network_mode1=1;preferred_network_mode2=1;...;1=GSM
```

Slot 1 was restored to the GSM mask and `preferred_network_mode1..3` to `1`, so the restore reported
success while the phone stayed on 2G. A second defect hid the first one: any key that was already
`1` before the apply was captured as `1` too, and a restore has nothing better to write for it.

### 10.3 Fixes in `NetworkModeController`

1. `isGsmOnlyModeValue` / `isGsmOnlyTypes` recognise a 2G-only setting value or SIM slot mask.
2. `apply2gOnly` **never captures** a 2G-only value as the baseline, for either the mode entries or
   the per-slot allowed types.
3. `restoreDefault` runs `healStaleGsmOnly` after a successful restore: any entry or slot still
   restricted to GSM is reset to the restored default (falling back to mode `9`,
   `GSM|UMTS|LTE`). Per-subscription keys take a single value, so only the first entry of a comma
   list such as `11,11` is copied over.
4. The baseline mirror in global settings, the LTE-safe fallback for a wiped install, and the
   screen-on watchdog from the earlier fix stay in place as the second and third layers.

### 10.4 Fixes in the guard service

`CoolMaxApplication.onCreate` now starts `ScreenOffGuardService` when any guard feature is enabled.
An app update kills the foreground service and nothing restarted it until the user changed a setting
or rebooted, so the guard silently stopped working after every install. Observed on the device: after
`pm install-commit` the service was gone until the app was opened again.

### 10.5 Reset System Default moved to the sidebar

The always-visible top bar button is gone; `CoolMaxSidebar` now owns it above the root badge and
closes the drawer before applying, so every screen keeps a full width title area.

### 10.6 Verified on the device

| Check | Result |
|---|---|
| Screen off drops the radio to 2G | **PASS**, `mode=1`, both slots `GSM` |
| Screen on restores 4G | **PASS**, `LTE\|LTE_CA` on both slots within 4 s of the wake |
| Restore no longer writes 2G back | **PASS**, log shows LTE masks and mode `11` |
| Self-heal of a stale entry | **PASS**, planted `preferred_network_mode3=1` and slot 1 `GSM` both returned to `11` / `LTE\|LTE_CA` |
| Guard service survives an update | **PASS**, `dumpsys activity services` shows it after `pm install-commit` plus one app start |
| Root grant through ReSukiSU | **PASS**, `ROOT` badge in the manager, "Root active" in the app sidebar |
| Sidebar reset button | **PASS**, screenshot `test-results/device-sidebar.png` |

### 10.7 Residual risk

- The restore is instant only while the process is awake. Android can freeze the app while the
  display is off, in which case the restore runs when the app is next scheduled or the user opens it.
  The watchdog retries once a minute while the screen is on.
- The guard is now deliberately allowed to apply while charging on this phone, because "Skip when
  device is charging" was switched off for the test run. Turn it back on in the Screen-Off tab to
  keep the old behaviour.
