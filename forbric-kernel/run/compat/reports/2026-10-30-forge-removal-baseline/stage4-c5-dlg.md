# Stage 4 C5 — lane DLG (a client's fatal error on NeoForge's own screen, the ConfigApi false WARN, a bare "zh"), wave 2d

Branch `s4-c5-dlg`, cut from 421c53cc (worktree `.claude/worktrees/s4c5-dlg`, ports 46920-46939 from `.stage4/lane-env.sh`).
Notes and logs: `~/Forbric-backups/stage4-notes/c5/dlg/w2d/` (paths below are relative to it). Harness: D68 (pinned build,
plain `--offline` from `.stage4/props.txt`, no init script, no private Gradle home); every Gradle and game run through
`.stage/heavy.sh`, every client under `.stage/client.sh`. No mod jar was downloaded; the native client was installed from the
NeoForge 26.2.0.93 installer already in the stage's `build/native-controls/downloads/` (sha1 matches its `.sha1`), offline
(every library "exists. Checksum valid."). Reason for the lane: D87(b) and VERIFY run 2's C-8 and carried list.

## Worktree

Bootstrap reported two PROBLEMs: `.stage4/minecraft` was a relative link (`../.stage/minecraft`) that dangles in a lane
worktree — re-pointed to the absolute `suspicious-chaum-b28730/.stage/minecraft` as the brief says; and
`forbric-kernel/build/sweep100-mac`, whose source is gone from the main checkout (the open item every lane since wave 2c
reports; nothing here reads it).

## Commits (on 421c53cc)

| sha | what |
|---|---|
| 063e7b9a | a client's fatal mod-loading error is drawn on NeoForge's own early-window error screen (FatalErrorWindowInjector + NeoFatalErrorWindow), gate-m24d asserts it |
| e95558c8 | ConfigApiAbiInjector no longer counts the carrier's own (ModContainer, Screen) constructor as a by-id construction (C-8) |
| 68021ff4 | `-Dforbric.dialogLanguage=zh` resolves by the system's region (zh_tw for TW/HK/MO or Hant, else zh_cn) instead of being ignored |
| 50c8e740 | the two real-carrier cases of FatalErrorWindowInjectorTest listed in the ci-unstaged skip baseline (measured) |
| (this file) | record |

## 1. D87(b): the macOS registration-stop dialog

### Measured first (same canary jar `run/canary/forbricregisterstop.jar`, `-Dforbric.registerstop=listener`, this Mac)

| run | what the player sees | evidence |
|---|---|---|
| native NeoForge 26.2.0.93 client (installed with NeoForge's installer, launched as the vanilla launcher builds the command: `-XstartOnFirstThread`, `net.neoforged.fml.startup.Client`) | FML's early window turns into NeoForge's error screen: title "Minecraft: NeoForge Loading...", "Error loading mods / 1 error has occurred during loading", the mod's name and id, the event, the exception, buttons Open Mods Folder, Open Log File, Open Crash Report, Quit Game | `screenshot-native-neoforge-93.png`, `native-listener/` (log: "Loading ImmediateWindowProvider fmlearlywindow", `[fml-loadingscreen/INFO] GL info`) |
| native, `config/fml.toml` `earlyWindowControl = false` (NeoForge's fallback path on purpose) | **nothing**: the main thread waits in `JOptionPane.showMessageDialog` (`FatalErrorReporting.showErrorUsingSwing`), no window is ever on screen (two 0x0 off-screen java windows) — Swing cannot show under `-XstartOnFirstThread` | `native-listener-noearlywindow/` (jstack, windows-all.txt) |
| Forbric at 421c53cc (before) | NeoForge's tinyfd fallback: an osascript box "NeoForge - Fatal Startup Error" reading "INVALID MESSAGE WITH QUOTES" | `screenshot-forbric-before-421c53cc.png`, `forbric-before-listener/` |
| Forbric at 063e7b9a (after) | the native screen: the same title, layout, text and buttons as the native run (compared by eye) | `screenshot-forbric-after.png`, `forbric-after-listener/` |

So D87(b)'s premise — make NeoForge's **Swing** path work — is refuted by the native measurement: on macOS that path shows
nothing even on native NeoForge. What native NeoForge shows is its early window's error screen, the provider path of the same
`FatalErrorReporting.reportFatalError` (`ImmediateWindowHandler.displayFatalErrorAndExit` is its first step; the message box is
only its no-provider fallback). That is the path made to work.

### Cause

The kernel never opens FML's early window (the game creates its own window in `Minecraft.<init>`; `PassiveSeeder` seeds
`earlyWindowControl=false` in memory), so `ImmediateWindowHandler.provider` is null and NeoForge takes its fallback: Swing when
AWT is not headless, else tinyfd with `"`/`'` replaced by a backquote. Forbric's `MacAwtBootstrap` (5d1780f6, "Isolate macOS
game font rendering from the AWT window event loop") makes the macOS game JVM AWT-headless, so tinyfd ran and rejected the text.

### Fix

- `FatalErrorWindowInjector` prepends `KernelLifecycle.onClientFatalError()` to NeoForge's
  `ClientModLoader.reportFatalError(Throwable, Path, CrashReport)`, the client's one entry for an error that stops it (Main's
  handler and ClientModLoader's own begin/finish).
- `NeoFatalErrorWindow.open` then does what native FML did at startup: the provider named by FML's config (`fmlearlywindow`
  by default) from `ServiceLoader`, `initialize(ProgramArgs.from(<the game's arguments>))` on the calling (main) thread, and
  installs it as the handler's provider. NeoForge's report runs unchanged and draws its own screen. Nothing Forbric-only is
  drawn.
- Kept: `MacAwtBootstrap` is untouched — the game JVM stays AWT-headless on macOS, so fonts/bitmaps and the forked dialog
  child work as before (MacAwtBootstrapTest 2/2, DependencyDialogTest 48/0, CompatibilityDecisionTest 21/0).
- Never worse than before: no provider by that name, `glfwInit` false (no display), or any throw → provider stays null and
  NeoForge's fallback runs as before; `-Dforbric.fatalErrorWindow=off` keeps the fallback.
- The provider's `initialize` calls `FMLConfig.updateConfig` (window size), which saves the config to `config/fml.toml` when
  config data is loaded — and the kernel's in-memory config (`earlyWindowControl=false`) is loaded. It is set aside for the
  call (every value reads its default meanwhile) and put back, so no `fml.toml` is written (a native launch of the same folder
  would otherwise read `earlyWindowControl=false`). Measured: the control below writes it.
- `ForeignType` gains `IMMEDIATE_WINDOW_HANDLER`, `IMMEDIATE_WINDOW_PROVIDER`, `PROGRAM_ARGS` (ForeignTypeCarrierTest: all
  resolve in the staged carrier).

### Checks and controls

| check | tip | control |
|---|---|---|
| gate-m24d (+5 checks per stop phase: kernel line, renderer GL line, no osascript child, NeoForge's window titled "Minecraft: NeoForge Loading..." on screen via Quartz, no `config/fml.toml`; +1 in the control: no window opened when nothing fails) | 43/0 at 063e7b9a (`gates/m24d-tip.out`) and at the final tip (§4) | kernel jar at 421c53cc (`FORBRIC_BOOT_JAR`): 35/8, exactly the 4 new stop-phase checks other than fml.toml in each of the two stop phases (`gates/m24d-red-421c53cc.out`); kernel that keeps FML's config in place during `initialize`: 41/2, the two fml.toml checks (`gates/m24d-red-configsave.out`) |
| FatalErrorWindowInjectorTest (4): the carrier's method is static and of that shape and calls FatalErrorReporting; the hook is first and the carrier's body remains (verified); execution: hook runs before NeoForge's report; another shape/class untouched | green | the class is new: red on 421c53cc by not compiling; behaviour red = m24d above |
| NeoFatalErrorWindowTest (6), against stand-ins under FML's names: the configured provider is opened with the game's arguments and installed, config data null during `initialize` and restored; an existing provider left alone; no display / unknown name / switch off / no carrier → fallback | green | new class (as above); the config-set-aside assertion is the unit face of the fml.toml control |
| InjectorExecutionCensusTest | 4/0 (FatalErrorWindowInjector credited by @ExecutesInjector) | — |

Count correction: 063e7b9a's message says "seven checks per stop phase"; it is five per stop phase plus one in the control
(11 new of the gate's 43; the gate had 32).

Not measured: Windows and Linux. The hook is not macOS-specific (native NeoForge shows the early window on every OS by
default, and Forbric showed the Swing/tinyfd fallback there too); on those systems the after-state was not run.

## 2. C-8: ConfigApiAbiInjector's false WARN

`routeTheConfigScreenThroughTheBridge` scanned from `NEW ConfigurationScreen; DUP` to the first `INVOKESPECIAL
ConfigurationScreen.<init>` of any descriptor and counted the allocation as "direct" whenever that was not the by-id
`(String, Screen)` one — so the carrier's own `(ModContainer, Screen)` constructor (KernelConfigApiBridge, Balm's NeoForge
classes, any NeoForge mod) printed "constructs ConfigurationScreen from a mod id directly ... no safe constructor rewrite was
proved" on every boot. Bytes never changed (only `rerouted > 0` rewrites); the sentence was wrong.

Fix (e95558c8): the constructor an allocation reaches is found at its own nesting depth; a non-by-id one is left alone and not
counted; a by-id one is rewritten when its region is straight-line and counted (the WARN) only when it crosses a frame, label,
jump, switch or holds a nested allocation — the same cases as before.

Test: `ConfigApiAbiInjectorExecutionTest.theCarriersOwnConstructorIsNotCountedAsAByIdConstruction` — a native consumer is left
byte-identical with no WARN, a by-id construction with a ternary argument is still left alone and reported once. Red on
421c53cc's injector (`unit-c8-red-421c53cc.log`: the false WARN for `NativeConsumer`), green here (`unit-c8.log`, 5/0 and
ConfigApiAbiInjectorTest 8/0). In real boots: see §4 (the WARN line counted in the gate logs).

## 3. A bare "zh" for `-Dforbric.dialogLanguage`

`byTag("zh")` matched no tag exactly and prefixed two (zh_cn, zh_tw), so `ofSystem` fell through to the system locale: on an
English system the switch did nothing. Fix (68021ff4): a bare language that files more than one table resolves the way a
locale of it does in `of(Locale)` — with the system's region, and its script when the system speaks that language: zh on a
TW/HK/MO or Hant system → zh_tw, anywhere else zh_cn. Exact tags, single-table languages and unknown names unchanged.

Test: `DialogLangTest.aBareChineseSwitchTakesTheSystemsRegionLikeALocaleDoes` (en_US → zh_cn, zh_TW → zh_tw, en_HK → zh_tw,
zh-Hant → zh_tw, "ZH" on zh_CN → zh_cn; `ofBareLanguage` null for a language with no or one table). Red on 421c53cc's resolution
(`unit-lang-red-421c53cc.log`: EN for zh on an English system; the control kept the test compiling with an `ofBareLanguage`
stub answering null, which is what 421c53cc did), green here (14/0). `test_gate_locale.py` still requires an exact tag in a
gate's language pin (a bare zh would leave the table to the host's region); its docstrings now say that instead of "is
ignored" (10/10).

## 4. Final tip

At 50c8e740 (code = this record's parent), each gate run directly (`bash run/gate-*.sh` under `.stage/client.sh` +
`.stage/heavy.sh`, not through the verdict cache), counting `^[kernel] PASS` / `^[kernel] FAIL` lines (`gates/*-final.out`):

| gate | PASS / FAIL | note |
|---|---|---|
| gate-m24d-registerstop-client | 43 / 0 | listener and try: the kernel line, NeoForge's renderer, no osascript child, the "Minecraft: NeoForge Loading..." window on screen, no fml.toml; control: no window opened |
| gate-m20-depdialog | 41 / 0 | |
| gate-m26-forgeclient | 49 / 0 | |
| gate-m29-forgecaps | 22 / 0 | |

ConfigApi WARN in real boots: the listener boot on the 421c53cc kernel logged "KernelConfigApiBridge constructs
ConfigurationScreen from a mod id directly in 1 place(s)" once (`forbric-before-listener/latest.log`); the same phase at the
tip logs it 0 times, and so does every log of the four gates above (0 in 19 logs).

Full suite (`heavy.sh ./gradlew --no-daemon $(cat .stage4/props.txt) -Pforbric.requireFixtures=all jar cleanTest test
--continue`, 361 s with the slot wait): **5905 tests, 0 skipped, 5 failed** = the five D58 rows of `failures-w2c.txt`
(LiquidBounce x3, SharedFinalSourceActual x2), name for name; 0 new (`suite/suite-summary.txt`). 5893 at 421c53cc + 12 new
(FatalErrorWindowInjectorTest 4, NeoFatalErrorWindowTest 6, ConfigApiAbiInjectorExecutionTest +1, DialogLangTest +1).
`run/compat` test_gate_locale 10/10. No gates-all (not in the brief).

CI profiles (D47): measured in the ci-unstaged shape (tracked-files clone, no stage; `unstaged.sh`, `unstaged-rows.txt`):
FatalErrorWindowInjectorTest's two real-carrier cases skip on [fixture:staged] — two rows added to
`src/test/skip-baseline/ci-unstaged.tsv` (50c8e740); its other two cases and NeoFatalErrorWindowTest,
ConfigApiAbiInjectorExecutionTest, DialogLangTest, InjectorExecutionCensusTest skip nothing. ci-prepared stages the carrier:
no row.

## What other lanes must change

Nothing. To keep: gate-m24d now asserts NeoForge's early window on a client stop (a change to the ClientModLoader transforms
or to the FML config seed must keep its 43 checks); any kernel code that calls `FMLConfig.updateConfig` writes
`config/fml.toml` (see `NeoFatalErrorWindow.withoutKernelConfig`). The verdict cache re-runs gate-m24d (its script changed).

## Open items and owners

- Windows/Linux after-state of item 1 not run → orchestrator (a Windows VERIFY client stop, e.g. gate-m24d's listener phase
  there; on a machine with no display NeoForge's fallback runs, by design).
- C-7 (mod construction thread) untouched → as D87(c).
- Native `config/fml.toml` exists, Forbric's does not (the kernel never loads it; pre-existing, unchanged) → none.
- introduction.md (en + cn): "a client's fatal mod-loading error is shown on NeoForge's own error screen" and the
  `-Dforbric.fatalErrorWindow` switch, at the merge into main → orchestrator.
- `build/sweep100-mac` still dangles in lane worktrees → orchestrator (unchanged).
