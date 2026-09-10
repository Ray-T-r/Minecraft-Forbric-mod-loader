# Forbric

**Run Fabric mods and Forge mods in the same Minecraft, at the same time.**

Today you have to pick a side. A mod built for Fabric will not load under Forge, and a Forge mod will
not load under Fabric — same file extension, wrong machine, like putting a PlayStation disc in an Xbox.
So most people keep two separate profiles and never get to use both halves of their mod list at once.

Forbric is a mod loader that runs both kinds in one instance. One version entry, one `mods` folder,
one game.

> **New here?** [**introduction.md**](introduction.md) explains what Forbric is, how it works, and what
> every folder in this repository is for — in plain language, with no code.

## Before you install

Forbric is version **0.1.0**. It is a research project you can try, not a finished product.

- **Some mods will crash it.** Two whole mod ecosystems is an enormous surface, and only a fraction of
  it has ever actually been run. Expect trial and error, and expect to remove a mod now and then.
- **Nothing you already have is touched.** The installer adds a *new* version next to your existing
  ones. Your Fabric and Forge installs, your worlds and your other mod folders stay exactly as they are.
  Uninstalling means deleting the folder the installer created.
- **Use Minecraft 26.2** if you want Forge mods to run. The other mode targets 1.21.11 and is
  effectively Fabric-only.
- **No support, no promises, no roadmap.** Things move, and nothing here is a stable API.

## Install

You need a launcher that can use custom versions — **PCL2** and **HMCL** both work — and a copy of Java.
If you can already play Minecraft you already have one; the installer will find the copy your launcher
downloaded, even if you never installed Java yourself.

1. Open the [latest release](https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader/releases/latest).
2. Download **`forbric-installer-0.1.0.jar`**, plus the launcher for your system:
   - **Windows** → `Forbric-Installer.bat`
   - **macOS** → `Forbric-Installer.command`
   - **Linux** → nothing extra.
3. Put both files in the same folder and double-click the `.bat` / `.command`. From a terminal it is:

   ```bash
   java -jar forbric-installer-0.1.0.jar
   ```

4. In the window that opens:
   - **Minecraft folder** — your `.minecraft`. The installer guesses it; correct it if you use a custom one.
   - **Mode** — choose **`full-forge-26.2`** to run Forge mods. `intermediary-v1` is the lighter
     mode: vanilla 1.21.11 with no Forge runtime present, so treat it as Fabric-only.
   - Leave *download the base version if it is missing* ticked.
5. Press install. The first `full-forge-26.2` install takes a few minutes, because it builds the Forge
   pieces on your own machine (they contain Mojang's and Forge's code, so they cannot be shipped
   ready-made). Later installs reuse what is already on disk and are quick.

Then open PCL2 or HMCL, pick the new version — **`forbric-forge-26.2`** — and launch it like any other.

### Where the mods go

The installer prints the exact path when it finishes. In `full-forge-26.2` it is:

```
.minecraft/versions/forbric-forge-26.2/mods/
```

Drop your Fabric jars and your Forge jars in there **together**. Forbric opens each one and works out
which kind it is by itself — you do not have to sort them.

You will find three jars already sitting in that folder (`forbricruntime`, `forge-runtime`,
`forbric-bridge`). Those are Forbric's own machinery. Leave them there.

### If something goes wrong

| What you see | What it means |
| --- | --- |
| On Windows, double-clicking the jar flashes a black window and nothing else happens | Windows' *"always open with"* dialog wrote a broken file association for `.jar`, and Java exits before the installer runs. Use `Forbric-Installer.bat` — it starts Java itself and ignores the association. Installing Java does **not** reliably fix this; the stale choice keeps winning. |
| The download crawls, stalls or times out | github.com is slow where you are. Put a relay in front of it: `java -jar forbric-installer-0.1.0.jar --mirror https://your-relay.example/` |
| The game crashes on startup | The log is `logs/latest.log` inside the version folder. The [glossary in introduction.md](introduction.md#words-you-will-see-in-the-log) translates the words you will find in it. Then try again with half your mods removed. |
| A **NeoForge** mod does not load | The installer builds a traditional **MinecraftForge** base, and an instance carries exactly one of the two. Forbric supports NeoForge as well, but assembling a NeoForge instance is a developer path today — see [`forbric-loader/run/`](forbric-loader/run/). |

## What is in this repository

```
forbric-loader/      the loader itself
forbric-installer/   the installer you downloaded above
bootstrap.sh         fetches the Fabric loader that Forbric is built on top of
fabric-loader/       appears once you run bootstrap.sh — borrowed code, not part of this repository
```

[introduction.md](introduction.md) walks through all of it, folder by folder.

## Build it yourself

You need `git` and a JDK 17 or newer. The Gradle wrapper is checked in.

```bash
git clone https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader.git
cd Minecraft-Forbric-mod-loader
./bootstrap.sh
cd forbric-loader && ./gradlew build
```

`bootstrap.sh` clones the Fabric loader Forbric reuses, at the exact release it was tested against, and
applies eight small patches to it; re-running it is safe. `./gradlew build` then compiles both code
trees, runs the tests, and writes two jars into `forbric-loader/build/libs/`.

The technical detail lives in [forbric-loader/README.md](forbric-loader/README.md) and
[forbric-installer/README.md](forbric-installer/README.md).

## Licence, and what is deliberately not here

Forbric is licensed under Apache-2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE).

This repository contains **no Minecraft code, no Forge code, and no name-mapping data**. All of that is
downloaded from Mojang's and Forge's own servers and assembled on your machine at install time.
Forbric's Forge-compatible half was written from public specifications rather than copied from Forge,
which is what lets the whole project stay under one permissive licence. The details are in
[forbric-loader/CREDITS.md](forbric-loader/CREDITS.md) and
[forbric-loader/MAPPINGS.md](forbric-loader/MAPPINGS.md).

Forbric is not affiliated with Mojang, FabricMC, MinecraftForge or NeoForged.
