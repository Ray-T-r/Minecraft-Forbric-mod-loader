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

**What that means in practice today.** Forbric supports all three, but a single game instance carries
one Forge-family base: MinecraftForge *or* NeoForge, not both. The installer builds the MinecraftForge
one — so what you get from double-clicking it is **Fabric + MinecraftForge in one instance**. NeoForge
instances, and the combined base that carries all three at once, do work, but you have to build them
yourself with the scripts in [`forbric-loader/run/`](forbric-loader/run/).

It is version 0.1.0 and it is a research project. Read [What it cannot do yet](#what-it-cannot-do-yet)
before you plan a modpack around it.

## How is this different from Kilt or Sinytra Connector?

You may already have seen mods that promise something similar. They are a different kind of thing.

**Kilt** and **Sinytra Connector** are *mods*. You still run a normal loader, and the mod re-creates the
other side's API inside it. Connector, for example, is installed on NeoForge and re-implements Fabric's
API so that Fabric mods can run there. Kilt did the mirror image on Fabric.

Think of it as an interpreter standing in the room. It listens to the visiting mod, translates, and
speaks to the host loader on its behalf. It works — but the mod is only ever talking to the interpreter,
so anything the interpreter has not learned to say does not get through.

**Forbric is the loader itself.** It starts the game, and it starts the *genuine* Forge-family runtime —
MinecraftForge or NeoForge, whichever the instance carries — inside that same game, next to Fabric's. A
Forge mod calling Forge's event system is calling the real Forge event system, not a re-creation of it.
Nobody is translating; both sides are actually present.

| | Kilt / Sinytra Connector | Forbric |
| --- | --- | --- |
| What it is | a mod you add to a loader | the loader |
| The other side's API | re-implemented by the compat layer | the real thing, running |
| Direction | one-way (Fabric mods on NeoForge, or the reverse) | both directions at once, in one instance |
| Limits | whatever the layer has re-implemented | whatever actually breaks when two runtimes share a game |
| Maturity | years of use, large communities | version 0.1.0, a research project |

**So which should you use?** If Connector already runs the mods you want, use Connector — it is mature
and Forbric is not. Forbric is for the cases it cannot reach, and for people who want to see whether
running both real runtimes together is possible at all.

## What you need

- A launcher that supports custom versions — **PCL2** or **HMCL**.
- **Minecraft 26.2**, if you want Forge-family mods to work.
- Java. If you can already play Minecraft you have it; the installer will find the copy your launcher
  downloaded, even if you never installed Java yourself.

## Install

1. Open the [latest release](https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader/releases/latest).
2. Download two files into the **same folder**:
   - `forbric-installer-0.1.0.jar`
   - `Forbric-Installer.bat` on Windows, or `Forbric-Installer.command` on macOS.
     On Linux you do not need the second file.
3. Double-click the `.bat` / `.command`. From a terminal it is:

   ```bash
   java -jar forbric-installer-0.1.0.jar
   ```

   > **Windows:** double-click the `.bat`, **not** the `.jar`. See
   > [If something goes wrong](#if-something-goes-wrong) for why.

4. A window opens. Three things to set:

   | Field | What to put |
   | --- | --- |
   | Minecraft folder | your `.minecraft`. It guesses; fix it if you use a custom folder. |
   | Mode | **`full-forge-26.2`** — Fabric + MinecraftForge on 26.2. This is the one that runs Forge mods. |
   | Download the base version | leave it ticked. |

5. Press install and wait. **The first install takes a few minutes.** It is downloading Minecraft and
   Forge's own files and assembling them on your computer — they cannot be shipped ready-made, for
   licensing reasons. Installs after that reuse what is on disk and are quick.

6. Open PCL2 or HMCL. A new version called **`forbric-forge-26.2`** is in the list. Launch it like any
   other version.

Nothing you already have is touched. Your Fabric, Forge and NeoForge installs, your worlds and your
other mod folders are exactly as they were.

## Adding mods

The installer prints the folder when it finishes. It is:

```
.minecraft/versions/forbric-forge-26.2/mods/
```

Put your jars in there. Fabric mods and Forge-family mods go in the **same** folder — Forbric opens each
jar and reads what is inside to decide which kind it is. You never have to tell it.

Three jars are already in that folder: `forbricruntime`, `forge-runtime` and `forbric-bridge`. Those are
Forbric's own machinery. **Leave them alone** — deleting them breaks the profile.

One thing to watch: a popular mod is usually published as a Fabric build, a Forge build *and* a NeoForge
build. Download **one** of them, not several — two builds of the same mod in one folder will collide.
And on a `full-forge-26.2` instance, pick the Forge build rather than the NeoForge one.

## Did it work?

Two easy checks.

- The game's **mod list** should show your Fabric mods and your Forge-family mods together in one list.
- `logs/latest.log` inside the version folder starts with a Forbric banner, followed by a line per mod
  it found. If a mod is missing from the game, search the log for its name — the reason is usually
  written there in plain English.

## Updating and uninstalling

**Updating Forbric:** run the installer again with the same settings. It overwrites the version in
place, and your mods folder is untouched.

**Uninstalling:** delete `.minecraft/versions/forbric-forge-26.2/`. That is all of it. Nothing else in
your Minecraft folder was modified. (If you want the space back too, the build cache lives in
`.minecraft/.forbric-build/`.)

## If something goes wrong

| What you see | What to do |
| --- | --- |
| **Windows: double-clicking the jar flashes a black window and nothing happens** | Windows' *"always open with"* dialog wrote a broken association for `.jar` files, and Java quits before the installer even starts. Use `Forbric-Installer.bat` — it starts Java itself and ignores the association. Note that *installing Java does not reliably fix this*; the bad choice keeps winning until you clear it. |
| **The game crashes on startup** | Open `logs/latest.log` in the version folder. Look for the first line that names a mod. Then remove half your mods and try again — repeat, and you will find the culprit in a few rounds. |
| **A mod is installed but does nothing** | Check the log for its name. The most common cause is a mod built for a different Minecraft version; the second most common is having two builds of the same mod installed at once (the Fabric one and the Forge one, say). |
| **A NeoForge mod will not load** | Expected on an installer-built instance: it carries the **MinecraftForge** base, and one instance carries only one of the two. Forbric fully supports NeoForge — but building a NeoForge instance, or the combined base that carries both, is a developer job today. See [`forbric-loader/run/`](forbric-loader/run/). Forbric says so in the log, naming the mod and the family it needs. |

## What it cannot do yet

- **No promise that any particular mod works.** Three mod ecosystems is an enormous surface, and only a
  fraction of it has ever been run. Expect trial and error.
- **One Forge family per instance.** MinecraftForge *or* NeoForge — the installer builds the
  MinecraftForge one. A NeoForge instance, or a combined base carrying both, has to be built with the
  scripts in `forbric-loader/run/`.
- **No support, no roadmap, no stable API.** Version 0.1.0. Things move.

Forbric is not affiliated with Mojang, FabricMC, MinecraftForge or NeoForged.

## For mod developers

**[introduction.md](introduction.md)** is the technical document: the boot sequence, the transform
pipeline, the mapping spine, the classloader topology, how the genuine MinecraftForge and NeoForge
runtimes are brought up without ModLauncher, the repository layout, and the invariants you have to
respect if you change any of it.

The short version, if you write mods: **your mod does not need to change.** Forbric loads it in its own
ecosystem's real runtime. Nothing is re-implemented, so there is no compatibility layer to code against.

To build from source you need `git` and a JDK 17 or newer:

```bash
git clone https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader.git
cd Minecraft-Forbric-mod-loader
./bootstrap.sh
cd forbric-loader && ./gradlew build
```

## Licence

Apache-2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

This repository contains **no Minecraft code, no Forge or NeoForge code and no name-mapping data**. All
of it is fetched from Mojang's, Forge's and NeoForged's own servers and assembled on your machine at
install time. Forbric's Forge-family half was written from public specifications rather than copied,
which is what keeps the whole project under one permissive licence. Details in
[forbric-loader/CREDITS.md](forbric-loader/CREDITS.md) and
[forbric-loader/MAPPINGS.md](forbric-loader/MAPPINGS.md).
