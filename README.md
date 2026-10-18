# Forbric

English | [简体中文](README.zh-CN.md)

**One Minecraft instance that runs Fabric mods, Forge mods and NeoForge mods at the same time.**

Version 0.3.0 · Minecraft 26.2

## What it does

Minecraft mods come in three kinds, and normally you have to pick one. A mod is built for **Fabric**, or
for **Forge**, or for **NeoForge**, and it only works on the one it was built for. Put a Fabric mod into
a Forge game and nothing happens. So most people keep several separate setups, and whichever one they
start, most of their mods are sitting in the other ones.

Forbric is a fourth thing you install instead of those three. You put **every** mod into **one** folder —
Fabric, Forge and NeoForge mixed together, no sorting. Forbric opens each file, works out what kind
it is, and loads it. All of them are running in the same world at the same time.

It also gives you one list of everything you have installed. The pause menu and the title screen get a
Forbric mods button, and from that list you can open a mod's own settings screen, whichever of the
three it belongs to (for Fabric mods, only when Mod Menu is installed too).

**You may have heard of Kilt or Sinytra Connector.** Those are mods you add to a normal loader, and they
re-create one side's features inside the other — a translator in the room. Forbric is the loader itself.
Fabric Loader and the loaders inside Forge and NeoForge never start; Forbric does their job — finding the
mods, starting them, running them in order — and tries to do it the way each mod's own loader would. Your
mods call the real Fabric API and the real Forge and NeoForge code; that part is not re-created. So this is
not three loaders running side by side: it is one new loader that puts all three kinds of mods, and the
real code they rely on, into one game.

But Forge and NeoForge both change Minecraft, often in the same spots, and one game can hold only one
version of each spot, so Forbric mostly keeps NeoForge's. Its own glue then keeps the other mods working:
it passes game events on to Forge mods, moves Fabric mods' changes to where the code now sits, and lets
mods from different loaders hand each other items, fluids and energy. That glue is translation too, and
it is not finished, which is one reason some mods still fail.

Connector is mature and Forbric is not, so if Connector already runs the mods you want, use Connector.

## How to install

### Before you start

- **A launcher that starts versions from your `.minecraft/versions` folder.** Forbric has been tested
  with **PCL2** on Windows. HMCL reads the same files and should work, but has not been tested yet. The
  official Minecraft Launcher has not been tested either (see step 6). Prism Launcher and MultiMC keep
  their own instances and will not see Forbric.
- **Java.** If you can already play Minecraft, you have it. The installer finds the copy your launcher
  downloaded, even if you never installed Java yourself.
- **An internet connection**, and about 730 MB of free disk while it works (about 190 MB is kept
  afterwards).

You do **not** need to install Minecraft 26.2 first. If you do not have it, the installer downloads it.
You also do **not** need Fabric, Forge or NeoForge, and you do not need to find any other files: the
installer downloads and builds everything Forbric needs. Your mods still need their own prerequisites as
usual, for example Fabric API for most Fabric mods.

### Install

1. Open the [latest release](https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader/releases/latest).

2. Download **two** files into the **same folder**:

   | You are on | Download |
   | --- | --- |
   | Windows | `forbric-kernel-installer-0.3.0.jar` **and** `Forbric-Installer.bat` |
   | macOS | `forbric-kernel-installer-0.3.0.jar` **and** `Forbric-Installer.command` |
   | Linux | `forbric-kernel-installer-0.3.0.jar` (run it with `java -jar`) |

3. **Double-click the `.bat` (Windows) or the `.command` (macOS).** It looks for Java, including the copy
   a launcher keeps in the usual Minecraft folder, and starts the installer with it. On some Windows PCs,
   double-clicking the jar itself only flashes a black window, because Windows was once told to open
   `.jar` files in a way that does not work; the script avoids that. If double-clicking the jar does open
   the installer window, that is fine too: it is the same installer.

   On macOS the first time, you may need to right-click the file and choose **Open**, then confirm. That
   is macOS being careful about downloads, not an error.

4. **A window opens.** The only field that matters is **Game directory** — the `.minecraft` folder your
   launcher uses. It starts out filled in with the usual place for your system:

   - Windows — `C:\Users\<your name>\AppData\Roaming\.minecraft`
   - macOS — `~/Library/Application Support/minecraft`
   - Linux — `~/.minecraft`

   If your launcher keeps `.minecraft` somewhere else (PCL2 and HMCL can keep it in the same folder as the
   launcher program), press **Browse…** next to Game directory and choose that folder.

   Leave everything else alone. In particular, leave **Built artifacts** empty — it is only for developers
   who built Forbric's game files from source.

5. **Press Install and wait.** The first install takes several minutes. It is downloading Minecraft's,
   Forge's and NeoForge's own files and putting them together on your computer, because those files
   cannot legally be handed out ready-made. Stay connected while it runs. Installing again later reuses
   what is already on disk and is quick.

6. **Open your launcher.** A new version called **`26.2-forbric`** is in the list. Start it like any
   other version. PCL2 shows it as a Fabric version; that is expected (see the next section).

   The installer does not add it to the official Minecraft Launcher's list of installations. There you
   would probably have to create a new installation and pick `26.2-forbric` yourself.

> Want to check your computer before you press Install? Run this in the folder with the jar. It only
> looks, and writes nothing:
>
> ```bash
> java -jar forbric-kernel-installer-0.3.0.jar --doctor
> ```

### Where to put mods

**Fabric, Forge and NeoForge mods all go in the same `mods` folder.** Which folder that is depends on
your launcher, not on Forbric:

- If your launcher keeps each version separate (often called version isolation; PCL2 and HMCL can do
  this): `.minecraft/versions/26.2-forbric/mods/`
- Otherwise the shared `.minecraft/mods/` in the Game directory you chose. Every version that does not keep
  its own folder uses this one, so Forbric will also try to load any mods already in it.

The installer lists both locations when it finishes. Check your launcher's settings to work out which
one it uses.

Your launcher may call `26.2-forbric` a Fabric version. That is intentional: during testing, I found
that some launchers, such as PCL2, did not offer their built-in mod installation feature for vanilla
Minecraft versions. Forbric identifies itself as a modded version so that feature is available.

One thing to watch: many mods come as a Fabric build, a Forge build and a NeoForge build. Put **one**
build of each mod in the folder. If you add more than one, Forbric still runs only one of them. The first
time this happens it writes its choice to `forbric-mods.txt` next to your `mods` folder, where you can pick
the other build.

The same goes for a prerequisite (library) mod that several of your mods need: one build is usually
enough, because a Forge or NeoForge mod can normally use the Fabric build of its prerequisite and the
other way round. Adding the prerequisite for both loaders does not give each mod its own copy: Forbric
still runs only one. If one mod plugs straight into another (Iris into Sodium, for example), use the same
loader's build of both. For Sodium, also see *A known crash* below.

### Did it work?

Open the pause menu. There is a button with **three overlapping squares**, and the tooltip says
*Mods (Forbric)*. It opens one list of every mod you installed, each row labelled with the kind it is.
Select a mod and press **Config**, or double-click the row, to open that mod's own settings.

Fabric mods hand their settings screens to Mod Menu, so a Fabric mod gets a **Config** button in this list
only when Mod Menu is installed too. With Mod Menu there are **two** mods buttons, on the title screen and in
the pause menu. Use the one with three squares: it opens settings for all three kinds, while Mod Menu's own
button opens settings only for Fabric mods.

### If something goes wrong

| What you see | What to do |
| --- | --- |
| **A window says a mod is missing something it needs** | It names the mod and what to install. Install it, or press **Launch anyway**. |
| **A window says required mod features are unavailable** | Some part of a mod could not start. You can continue playing, or quit and remove that mod. |
| **The game crashes** | Open the `.forbric-kernel` folder next to your `mods` folder. The `crash-analysis.txt` there names the mods most likely to blame; the full crash report is in `crash-reports/`. Remove those mods and try again. **Not in 0.3.0 yet:** on the next start a window asks whether to start without those mods. That writes their file names into `forbric-disabled.txt` next to your `mods` folder; delete a line there to turn that mod back on. Using the NeoForge build of Sodium? See *A known crash* below. |
| **A mod is installed but does nothing** | Open the Forbric mods list — a mod that did not finish loading is marked there. The same list is in `load-report.txt`, in the `.forbric-kernel` folder next to your `mods` folder. Often the mod was built for a different Minecraft version, or you have two builds of it. |
| **A dedicated server will not start** and the log says the compatibility policy stopped it | A server has no screen to ask you on, so it stops instead. Remove the mod it names, or add `-Dforbric.compatibilityPolicy=continue` to the server's start command to run anyway. |
| **Continuity loads, but glass still has borders between blocks** | In **Options → Resource Packs**, enable **Default Connected Textures** (included with Continuity). Its built-in packs are optional and are not enabled just by installing the mod. On 0.3.0 the Fabric build of Continuity can still leave the borders after that. That is a Forbric bug. It is fixed in the 0.3.1 beta, a pre-release on the Releases page, but not yet in a regular release. A NeoForge build of Continuity made for your Minecraft version is the other choice. |
| **The install seems stuck** | Usually a proxy or VPN sitting between you and Mojang's servers. Run the `--doctor` check from the end of *Install*, then try again with the proxy or VPN off. |

Something else? You can report it [here](https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader/issues/new?template=bug_report.yml).

### Updating and uninstalling

**To update**, run the new installer with the same settings. Your mods folder and worlds are left alone.
The first install after updating from 0.2.0 builds Forbric's game files again, so it takes several minutes
once more.

**To uninstall**, delete `.minecraft/versions/26.2-forbric/`. If your launcher keeps each version
separate, that folder also holds this version's mods, worlds and settings, so first copy out anything you
want to keep. To get the disk space back as well, also delete `.minecraft/.forbric-build/` and
`.minecraft/libraries/net/forbric/`.

## What's new in 0.3.0

**More mods work.** We picked three batches of about 100 random mods from Modrinth (popular ones and
random ones, all three kinds) and started the game with each mod on its own. **80.5% loaded without errors
on 0.2.0, 89.0% on 0.3.0** (no mod failing to load in the log). On 0.3.0, 91.8% got into a world, and
79.1% also had no part reported as not working. This test checks that a mod loads and a world opens. It
does not try each mod's features, and it does not test mods together.

New:

- **Mods from different loaders can pass items, fluids and energy to each other** — for example a Fabric
  pipe or hopper can feed a NeoForge or Forge machine.
- **A window before you play when part of a mod cannot work**, so you can choose to continue or quit
  instead of finding out later.
- **The Forbric mods list marks mods that did not finish loading**, and says why.
- **After a crash, a `crash-analysis.txt`** names the mods most likely responsible.
- **The warning windows speak 10 languages**, including Chinese, and suggest what to install.
- Forbric now uses the full NeoForge release instead of a beta, so NeoForge mods that need a newer NeoForge
  can load, and the title screen no longer says "beta".

Fixed:

- Crashes when smelting in a furnace, crafting or brewing with Fabric API installed, fighting the Ender
  Dragon, using a flower pot, or placing a fluid from a Fabric or Forge mod.
- Some mod sets left the game on a black screen at startup.
- Dungeons did not generate, and a seed did not give the same terrain as vanilla Minecraft.
- Item tooltips were missing enchantments, lore, attributes and durability.
- Many Forge mods loaded but did nothing — their commands, key bindings, on-screen displays, settings files,
  mobs and world changes now work.
- Xaero's Minimap and World Map (Forge builds) crashed at startup.
- Mods that crashed or failed on 0.2.0 and work now include Farmer's Delight Refabricated, Better End,
  Better Nether, Entity Culling, More Culling, Friends & Foes, Repurposed Structures, Traveler's Backpack,
  Shoulder Surfing and YetAnotherConfigLib.

Worse than 0.2.0: in the same test, **Alex's Mobs Continued**, **Drippy Loading Screen**, **FancyMenu** and
**Easy Magic** (NeoForge builds) worked on 0.2.0 and do not on 0.3.0.

Changed for server owners: a dedicated server now stops at startup if a mod is missing a part it needs,
because there is no screen to ask you on (see *If something goes wrong* above).

## What we promise

>
> Most of the code in this repository was generated by AI agents. Please keep that in mind when evaluating the project.
>

**Your existing Minecraft is not touched.** Forbric installs alongside everything else. Your Fabric,
Forge and NeoForge setups, your worlds, and your other mod folders are exactly as they were.

**Uninstalling is deleting a folder.** Nothing is scattered around your system, and nothing is left
running when you are not playing.

**Nothing is hidden.** All the source code is here and the licence is Apache-2.0. This repository
contains no Minecraft, Forge or NeoForge code — all of that is fetched from their own servers and
assembled on your machine when you install.

And what we do **not** promise:

**We cannot promise any particular mod works.** In our own test about one mod in ten still fails on its
own, and mods that each work alone can still clash when put together.

**Part of a mod can stop working without a crash.** When a piece of a mod cannot attach to the game,
Forbric keeps the rest of the mod running instead of stopping, and usually tells you — in the window
before you play and in the Forbric mods list. If a piece attaches but then behaves wrongly, neither
Forbric nor our tests can tell.

**A known crash:** the NeoForge build of Sodium crashes at startup unless Fabric API is also installed,
and so do mods that need it, such as the NeoForge builds of Iris and Sodium Extra. This is a Forbric bug,
not a mistake in how you installed them. Until it is fixed, put Fabric API in your `mods` folder as well, or
use the Fabric builds of Sodium and of the mods that plug into it.

**This is a research project at version 0.3.0.** There is no support, no roadmap, and things will change.

Forbric is not affiliated with Mojang, FabricMC, MinecraftForge or NeoForged.

---

### For mod developers

**Your mod does not need to change.** It calls the genuine Fabric API, MinecraftForge or NeoForge classes,
so there is no compatibility layer to code against. I do not want other mods to need changes just to
work with Forbric. What Forbric re-implements is the loader: class
loading, mod discovery, load order, the lifecycle, the Mixin service, and Fabric Loader's API (Forbric
carries Fabric Loader's public API types, which keep FabricMC's copyright, and implements them; Fabric
Loader itself never runs). The game is different too: the installer builds one merged game jar from both
Forge families' patches. What this means for your mod:

- **The game is one merged jar.** Where MinecraftForge and NeoForge patched the same method (about a
  thousand of them), only one version was kept: NeoForge's in all but five, MinecraftForge's in those
  five. An event whose call was lost that way — nearly always a MinecraftForge one, plus a few NeoForge
  ones such as item tooltips and screen opening — reaches your listener only if Forbric re-emits it, and
  one without a bridge never fires ([introduction.md §8](introduction.md#8-event-bridges)). Events whose
  call survived the merge fire as usual. The coremods NeoForge itself ships are not loaded; Forbric
  applies their rewrites itself.
- **Mixins are applied to that merged code.** Forbric relaxes mods' mixin configs (`required: false`,
  `defaultRequire: 0`), so an injector whose target is missing does nothing instead of failing, unless it
  sets `require` itself. It moves an injector whose target moved, and drops a whole mixin when none of its
  targets exist ([§7](introduction.md#7-mixin-on-the-merged-base)).
- **Start-up runs in Forbric's order**, close to but not the same as each loader's native order
  ([§3](introduction.md#3-boot-order)).
- **Start-up extensions are not supported:** MinecraftForge's ModLauncher services
  (`ITransformationService`, `ILaunchPluginService`) and `coremods.json`, NeoForge's
  `ClassProcessorProvider`, and custom mod or dependency locators. Today they are skipped without a
  warning.

More detail:

- [introduction.md](introduction.md) — how Forbric works inside, for developers: boot order, how the
  three kinds of mods are loaded together, what the installer builds, and how it is tested.
- [forbric-kernel/README.md](forbric-kernel/README.md) — a shorter summary of the kernel, which is what
  the installer installs.

To build the kernel from source you need `git` and a JDK 21 or newer. The kernel has its
own Gradle build; boot-side compilation does not require the Fabric substrate:

```bash
git clone https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader.git
cd Minecraft-Forbric-mod-loader
cd forbric-kernel && ./gradlew build
```

**A green build on a fresh clone does not mean the game can launch.** Without locally staged game jars,
the runtime source set and transfer tests are skipped, and tests that need those jars may also skip.
CI verifies the boot-side build and the separate loader build; it does not launch Minecraft.

To run the current source in a development game, install **JDK 25+ and Python 3.9+**, then run from the
repository root (Windows: replace `python3` with `py`):

```bash
python3 tools/dev.py client                 # automatically prepare dependencies, build and launch
python3 tools/dev.py server --accept-eula   # a separate local server instance
```

Downloads, assembled jars and instances stay under the ignored `forbric-kernel/.dev/` directory.
Gradle entry points are also available: `prepareDev`, then `runClient` or `runServer` in a separate invocation.
See [the kernel development guide](forbric-kernel/run/README.md) for configuration and test coverage.
`check` includes tool self-tests; `integrationTest` rejects skipped assertions and requires the full fixtures.
Run `./bootstrap.sh` when building the first-generation `forbric-loader/` itself; standalone merge tools
and the current kernel development workflow do not need it.

### Licence

Apache-2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE). The clean-room boundary is documented in
[forbric-loader/CREDITS.md](forbric-loader/CREDITS.md) and
[forbric-loader/MAPPINGS.md](forbric-loader/MAPPINGS.md).
