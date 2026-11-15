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
| Forbric at 063e7b9a (after) | the native screen: the same title, layout, text and buttons as the native run (byte-identical screenshots, see B7 under "DLG review fixes") | `screenshot-forbric-after.png`, `forbric-after-listener/` |

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
  `ClientModLoader.reportFatalError(Throwable, Path, CrashReport)`, the client's one entry for an error that stops it (in the
  carrier: Main's "Initialization" handler, before `new Minecraft`, and `ClientModLoader.finish`, which the kernel neuters —
  so under the kernel Main's handler is the one live caller; corrected per A-N2, see "DLG review fixes").
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

Nothing on their branches, but lane STOP's merge interacts (B1, see "DLG review fixes"). To keep: gate-m24d now asserts
NeoForge's early window and error screen on a client stop (a change to the ClientModLoader transforms or to the FML config
seed must keep its checks: 45 after the review fixes); any kernel code that calls `FMLConfig.updateConfig` writes
`config/fml.toml` (see `NeoFatalErrorWindow.withoutKernelConfig`). The verdict cache re-runs gate-m24d (its script changed).

## Open items and owners

- Windows/Linux after-state of item 1 not run → orchestrator (a Windows VERIFY client stop, e.g. gate-m24d's listener phase
  there; on a machine with no display NeoForge's fallback runs, by design).
- C-7 (mod construction thread) untouched → as D87(c).
- Native `config/fml.toml` exists, Forbric's does not (the kernel never loads it; pre-existing, unchanged) → none.
- introduction.md (en + cn): "a client's fatal mod-loading error is shown on NeoForge's own error screen" and the
  `-Dforbric.fatalErrorWindow` switch, at the merge into main → orchestrator.
- `build/sweep100-mac` still dangles in lane worktrees → orchestrator (unchanged).

## DLG review fixes

Reviews: `c5/review-dlg-w2d-A.md` (adversarial) and `c5/review-dlg-w2d-B.md` (completeness). Fixes on 1aecbbcd; evidence in
`~/Forbric-backups/stage4-notes/c5/dlg/w2d/review-fix/`. Every gate run below started after the commit it measures (B8);
controls ran in an APFS clone of the worktree (deleted), never by editing the lane worktree.

| finding | verdict | what |
|---|---|---|
| B1 / A-N2: a report made after the game has its window would open a second GLFW window (lane STOP's `KernelNeoClientStop` reports from inside `Minecraft.<init>`) | confirmed, fixed f3119360 | see below |
| A-N3 / B4: m24d proved the window opened, not that the error screen is drawn; checks vanished without Quartz or a held client | confirmed, fixed d393a4ea | see below |
| B3: "control: no fatal-error window was opened" never red | confirmed, control run | red on a kernel that opens the window at every boot: 44/1, exactly that check (`gates/m24d-red-openatboot.out`) |
| A-N4: a switch value with a region or script (`zh-HK`, `zh_hant`) was ignored | confirmed, fixed f26dcd1b | see below |
| A-N1 / B2: the fix reinterprets D87(b) (early window, not Swing) and adds `-Dforbric.fatalErrorWindow` | confirmed (process) | open item: the orchestrator records a ruling; evidence in §1 |
| A-N5: the try phase's screen not compared with native; Windows/Linux after-state not run | confirmed, not run | open items below |
| B5: "carried list" scope | clarified | of VERIFY run 2's carried items this lane took the bare `zh` switch; C-6 (try held vs exit 1) and the stop phases are lane STOP's as the reviewers read it — the orchestrator checks this against the brief |
| B6: 063e7b9a's message says "seven checks per stop phase" and "35/8 … exactly the new checks" | confirmed, record-only | five per stop phase (now six, below); 35/8 was 8 of the 11 new checks (the other three: the two fml.toml checks, red only on the config control, and the control's check, B3). The commit is not rewritten |
| B7: "compared by eye" | confirmed | the native and the after screenshot are byte-identical (md5 802c90bec329db8aeff5429e5c6700ba, re-checked), from separate runs (different pid, window id, time): the screen renders deterministically |
| B8: m24d-tip and the two controls of §1 ran 04:47-04:50, before 063e7b9a existed (04:51); C-8 and lang reds ran by editing the worktree | confirmed | the final gates of §4 ran after every code commit and stand; this round's runs are on commits and clones |
| B9: `windows-all.txt` holds every window title on the owner's screen | confirmed | `tools/winlist.py` now needs a pid or owner name and `tools/drive.sh` lists only the game tree's and osascript's windows; the existing `windows-all.txt` files (notes only, not in the repo) are left as they are for the orchestrator to filter or not |

### f3119360: no second window for a report made from inside the game

Natively the early window exists from startup and the game takes it over in `Minecraft.<init>`, so a report made later
(`ClientModLoader.finish`) draws on the game's own window: `DisplayWindow.displayFatalErrorAndExit` is `takeOverGlfwWindow`,
`GL.createCapabilities`, `close`, then `ErrorDisplay.fatal(window, …)` (javap of the staged carrier). Under the kernel the
game's window is the game's own, so opening the provider then would put a second GLFW window, with its own render thread,
beside it. `NeoFatalErrorWindow.open` now answers `IN_GAME` — no window, a WARN line, NeoForge's fallback as before this
lane — when `net.minecraft.client.Minecraft` is on the calling thread's stack (its constructor or anything it runs).
Structural, not backend-bound: 26.2 has a Vulkan backend too, so a current-GL-context test would not see every game window.
On this branch the one live caller stays `Main.main`'s "Initialization" handler (before `new Minecraft`; `finish` is
neutered by `MethodBodyNeuter`), so nothing changes here: m24d 45/0 below. The injector's javadoc now names the callers that
way (A-N2).

Test: `NeoFatalErrorWindowTest.aReportFromInsideTheGameOpensNoSecondWindow` (a stand-in `Minecraft` whose constructor
reports gets `IN_GAME`, nothing initialised, no provider; the same report outside it opens). Red with the stack check
stubbed to false: `expected: <IN_GAME> but was: <OPENED>` (`unit-red-controls.log`, XML beside it).

For the merge with lane STOP (B1): STOP's stops reported from inside `Minecraft.<init>` (its common phase, through
`KernelNeoClientStop`) now get NeoForge's fallback — on macOS the tinyfd box with "INVALID MESSAGE WITH QUOTES" — where
native draws its error screen on the game's window. Not measured (STOP is not on this branch). m24d's early-window checks
apply to stop phases reported from `Main`'s handler (listener, try, and any phase stopped in `ClientModLoader.begin`); a
phase reported from inside the game should assert the `IN_GAME` line instead; STOP's fabric phase does not go through
`reportFatalError` and gets neither. Drawing `ErrorDisplay.fatal` on the game's own window for those reports (what native
does) is the follow-up, to be measured against a native finish-phase stop.

### d393a4ea: gate-m24d proves the error screen is drawn

A held client is thread-dumped (`jstack`, 60 s alarm) before the gate stops it; each stop phase asserts its `"main"` thread
is in `net.neoforged.fml.earlydisplay.error.ErrorDisplay.fatal` — a window stuck on its loading screen, or Swing's fallback
(no child process), passed every check before. The message-box child check also names tinyfd's non-macOS helpers (zenity,
kdialog, …). A check that cannot run prints `[kernel] SKIP` with the reason (no Quartz; the client did not sit in the report),
not counted as PASS or FAIL. 45 checks when both stop phases hold on a host with Quartz.

| run (all at d393a4ea's gate) | PASS / FAIL | red |
|---|---|---|
| tip | **45 / 0**, no SKIP (`gates/m24d-tip.out`); main thread: `GLFW.glfwWaitEventsTimeout` ← `ErrorDisplay.fatal` ← `DisplayWindow.displayFatalErrorAndExit` ← `FatalErrorReporting.reportFatalError` ← `ClientModLoader.reportFatalError` ← `Main.main` | — |
| tip, `FORBRIC_JVM=-Dforbric.fatalErrorWindow=off` | 35 / 10 (`gates/m24d-red-switchoff.out`) | per stop phase: kernel line, GL line, osascript box ("INVALID MESSAGE WITH QUOTES"), main thread not in `ErrorDisplay.fatal`, no window |
| kernel that also calls `NeoFatalErrorWindow.open` at every client boot (clone) | 44 / 1 (`gates/m24d-red-openatboot.out`) | control: no fatal-error window was opened (B3) |

### f26dcd1b: a switch value with a region or script

`ofSystem` honoured an exact table tag or a bare language; `zh-HK`, `zh_hant`, `en_GB`, `pt_PT` fell through to the system's
language. Now a value with a region or script resolves through `of(Locale.forLanguageTag(…))` (`zh-HK`/`zh_hant`/`ZH_MO` →
zh_tw, `zh_sg`/`zh-Hans` → zh_cn, `en_GB` → en, `pt_PT` → pt_br); a language with no table still leaves it to the system.
Test `DialogLangTest.aSwitchWithARegionOrScriptResolvesLikeThatLocale`: red with `ofLocaleTag` stubbed to null (1aecbbcd's
behaviour: `zh-HK on an English system` got EN), green here. Gate language pins are exact tags (`test_gate_locale`), so no
gate's reach changed.

### Results at the review-fix tip

- Unit (touched areas, `unit-1.log`): NeoFatalErrorWindowTest 7/0, FatalErrorWindowInjectorTest 4/0, DialogLangTest 15/0,
  InjectorExecutionCensusTest 4/0, MacAwtBootstrapTest 2/0. Red controls: exactly the two new tests (`unit-red-controls.log`, 22 run, 2 failed).
- gate-m24d 45/0 (above). No other gate's reach changed: the in-game check never fires on this branch (the one live caller
  is before `new Minecraft`), and DialogLang's change touches only switch values no gate uses. No gates-all (D87/D89).
- Full suite (same command as §4, at d393a4ea, `review-fix/suite/`): **5907 tests, 0 skipped, 5 failed** = the five D58 rows
  of `failures-w2c.txt` name for name, 0 new; 5905 + the 2 new tests. 909 result XML files (review B counted 910 in the
  lane's run; that run's XML was not kept, so the difference cannot be named — the test total matches exactly).
- CI profiles (D47): the two new tests read no staged fixture (stand-ins compiled in the test, and `Locale`); not
  re-measured in the ci-unstaged shape — the same shape as NeoFatalErrorWindowTest's and DialogLangTest's cases the lane
  measured there (none skip), so no skip row.

### Open items after the review fixes

- A ruling recording that D87(b) was met through NeoForge's early-window path, with `-Dforbric.fatalErrorWindow=off` as its
  switch (A-N1, B2) → orchestrator.
- Merge with lane STOP: per-phase window checks in m24d, and NeoForge's error screen on the game's own window for in-game
  reports (above) → orchestrator / merger.
- Try phase compared with native; Windows/Linux after-state (A-N5) → orchestrator (not run: the native install was deleted
  with the lane's scratch; D89).
- Existing `windows-all.txt` files in the notes (B9) → orchestrator.
