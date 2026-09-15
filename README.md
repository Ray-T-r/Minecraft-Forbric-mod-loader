# Forbric

**A mod loader that runs Fabric, Forge and NeoForge mods in the same Minecraft, at the same time.**

## What it does

Minecraft modding is split three ways. **Fabric** is one loader. **Forge** is another. **NeoForge** is a
third — it broke away from Forge a few years ago and is now a separate loader with its own mods, not
simply a newer Forge.

A mod is built for exactly one of the three. Put a Fabric mod into a Forge instance and nothing happens —
same file extension, wrong machine, like a PlayStation disc in an Xbox. So you keep separate profiles,
and whichever one you launch, most of your mod list is sitting unused in the other two.

Forbric is a loader you install instead of any of them. You drop every jar into one `mods` folder —
Fabric, Forge and NeoForge together, no sorting — and it opens each one, works out which kind it is, and
loads it.

**All three run in one instance.** That is what changed in 0.2.0: the installer now builds the merged
base that carries Fabric, traditional MinecraftForge *and* NeoForge together, so a NeoForge mod and a
Forge mod and a Fabric mod are all live in the same world at the same time. In 0.1.0 an instance carried
one Forge family and you had to build the combined base by hand.

It is version 0.2.0 and it is a research project. Read [What it cannot do yet](#what-it-cannot-do-yet)
before you plan a modpack around it.

## How is this different from Kilt or Sinytra Connector?

You may already have seen mods that promise something similar. They are a different kind of thing.

**Kilt** and **Sinytra Connector** are *mods*. You still run a normal loader, and the mod re-creates the
other side's API inside it. Connector, for example, is installed on NeoForge and re-implements Fabric's
API so that Fabric mods can run there. Kilt did the mirror image on Fabric.

Think of it as an interpreter standing in the room. It listens to the visiting mod, translates, and
speaks to the host loader on its behalf. It works — but the mod is only ever talking to the interpreter,
so anything the interpreter has not learned to say does not get through.

**Forbric is the loader itself.** It starts the game, and the real MinecraftForge and NeoForge runtimes
are present inside that same game, next to Fabric's. A Forge mod calling Forge's event system is calling
the real Forge event system, not a re-creation of it. Nobody is translating; all three are actually
present.

| | Kilt / Sinytra Connector | Forbric |
| --- | --- | --- |
| What it is | a mod you add to a loader | the loader |
| The other side's API | re-implemented by the compat layer | the real thing, running |
| Direction | one-way (Fabric mods on NeoForge, or the reverse) | all three at once, in one instance |
| Limits | whatever the layer has re-implemented | whatever actually breaks when three runtimes share a game |
| Maturity | years of use, large communities | version 0.2.0, a research project |

**So which should you use?** If Connector already runs the mods you want, use Connector — it is mature
and Forbric is not. Forbric is for the cases it cannot reach, and for people who want to see whether
running all three real runtimes together is possible at all.

## What you need

- A launcher that supports custom versions — **PCL2** or **HMCL**.
- **Minecraft 26.2**, already installed once, so the installer has the base version to build from.
- Java. If you can already play Minecraft you have it; the installer will find the copy your launcher
  downloaded, even if you never installed Java yourself.

## Install

1. Open the [latest release](https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader/releases/latest).
2. Download two files into the **same folder**:
   - `forbric-kernel-installer-0.2.0.jar`
   - `Forbric-Installer.bat` on Windows, or `Forbric-Installer.command` on macOS.
     On Linux you do not need the second file.
3. Double-click the `.bat` / `.command`. From a terminal it is:

   ```bash
   java -jar forbric-kernel-installer-0.2.0.jar
   ```

   > **Windows:** double-click the `.bat`, **not** the `.jar`. See
   > [If something goes wrong](#if-something-goes-wrong) for why.

   To check whether your machine is ready before installing anything:

   ```bash
   java -jar forbric-kernel-installer-0.2.0.jar --doctor
   ```

   It prints the Minecraft folder it found, the JVMs it can build with, the pinned Forge/NeoForge
   versions, and how much disk the build needs. It writes nothing.

4. A window opens. The field that matters is **Game directory** — your `.minecraft`. It guesses; fix it
   if you use a custom folder. Leave the rest alone unless you know why you are changing it.

5. Press install and wait. **The first install takes a few minutes** — about four and a half on a recent
   desktop, longer on a slow link. It is downloading Minecraft's, Forge's and NeoForge's own files and
   assembling them on your computer; they cannot be shipped ready-made, for licensing reasons. Installs
   after that reuse what is on disk and are quick.

6. Open PCL2 or HMCL. A new version called **`26.2-forbric`** is in the list. Launch it like any other
   version.

Nothing you already have is touched. Your Fabric, Forge and NeoForge installs, your worlds and your
other mod folders are exactly as they were.

## Adding mods

The installer prints the folder when it finishes. With a launcher that isolates versions — PCL2 and HMCL
both can — it is:

```
.minecraft/versions/26.2-forbric/mods/
```

Otherwise it is the shared `.minecraft/mods/`.

Put your jars in there. Fabric mods, Forge mods and NeoForge mods go in the **same** folder — Forbric
opens each jar and reads what is inside to decide which kind it is. You never have to tell it.

One thing to watch: a popular mod is usually published as a Fabric build, a Forge build *and* a NeoForge
build. Download **one** of them, not several. Forbric will notice two builds of the same mod and pick
one, but it is better to choose yourself than to find out which it picked.

## Did it work?

- The pause menu has a **Forbric mods button** — three overlapping squares, and the tooltip says
  *Mods (Forbric)*. It opens a single list of every mod in the instance, each row tagged with the loader
  it came from. Select a mod and press **Config**, or double-click its row, to open that mod's own
  settings screen — whichever of the three it belongs to.
- `logs/latest.log` inside the version folder starts with a Forbric banner, followed by a line per mod
  it found. If a mod is missing from the game, search the log for its name — the reason is usually
  written there in plain English.

Note that a modded pause menu often has **two** mods buttons: Mod Menu, if you have it, inserts its own
next to Forbric's. The one that says *Mods (Forbric)* is the one that lists all three ecosystems.

## Updating and uninstalling

**Updating Forbric:** run the installer again with the same settings. It overwrites the version in
place, and your mods folder is untouched.

**Uninstalling:** delete `.minecraft/versions/26.2-forbric/`. That is all of it. Nothing else in your
Minecraft folder was modified. (If you want the space back too, the build cache lives in
`.minecraft/.forbric-build/` and the staged jars in `.minecraft/libraries/net/forbric/`.)

## If something goes wrong

| What you see | What to do |
| --- | --- |
| **Windows: double-clicking the jar flashes a black window and nothing happens** | Windows' *"always open with"* dialog wrote a broken association for `.jar` files, and Java quits before the installer even starts. Use `Forbric-Installer.bat` — it starts Java itself and ignores the association. Note that *installing Java does not reliably fix this*; the bad choice keeps winning until you clear it. |
| **The installer sits on a download and never finishes** | It should not any more — every download now has a deadline and says so when it passes one. If it still stalls, the usual cause is a proxy or VPN intercepting Mojang's servers. `--doctor` first, then try with the proxy off. |
| **The game crashes on startup** | Open `logs/latest.log` in the version folder. Look for the first line that names a mod. Then remove half your mods and try again — repeat, and you will find the culprit in a few rounds. |
| **A mod is installed but does nothing** | Check the log for its name. The most common cause is a mod built for a different Minecraft version; the second is having two builds of the same mod installed at once. |
| **A mod needs a dependency you do not have** | Forbric puts a window in front of you before the game starts, naming the mod, what it needs, and what is installed instead. You can launch anyway. |

## What it cannot do yet

- **No promise that any particular mod works.** Three mod ecosystems is an enormous surface, and only a
  fraction of it has ever been run. Expect trial and error.
- **Builds are not reproducible.** Installing twice on the same machine produces game jars with
  different checksums — the decompile-and-merge pipeline does not promise byte-for-byte output. What is
  checked is behaviour: the jars a real install produced are run through the project's own gates.
- **No support, no roadmap, no stable API.** Version 0.2.0. Things move.

Forbric is not affiliated with Mojang, FabricMC, MinecraftForge or NeoForged.

## For mod developers

**Your mod does not need to change.** Forbric loads it in its own ecosystem's real runtime. Nothing is
re-implemented, so there is no compatibility layer to code against.

- **[forbric-kernel/README.md](forbric-kernel/README.md)** — the kernel, which is what the installer
  installs: its architecture, the unified API, and how the three ecosystems are reduced to adapters over
  kernel-owned services.
- **[introduction.md](introduction.md)** — the architecture of `forbric-loader`, the previous-generation
  "weld" the kernel replaced. Still accurate about that module, which is still in the tree and still
  builds the shared game artifacts; it does **not** describe the kernel.

To build from source you need `git` and a JDK 21 or newer:

```bash
git clone https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader.git
cd Minecraft-Forbric-mod-loader
./bootstrap.sh
cd forbric-kernel && ./gradlew build
```

## Licence

Apache-2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

This repository contains **no Minecraft code, no Forge or NeoForge code and no name-mapping data**. All
of it is fetched from Mojang's, Forge's and NeoForged's own servers and assembled on your machine at
install time. Forbric's Forge-family half was written from public specifications rather than copied,
which is what keeps the whole project under one permissive licence. Details in
[forbric-loader/CREDITS.md](forbric-loader/CREDITS.md) and
[forbric-loader/MAPPINGS.md](forbric-loader/MAPPINGS.md).
