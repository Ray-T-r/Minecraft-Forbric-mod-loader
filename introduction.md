# Forbric, explained

This page is for anyone who wants to understand what Forbric is and how it works — players first,
curious people second. There is no code in it. If you just want to install and play, the
[README](README.md) is shorter.

**Contents**

1. [The problem: two mod loaders, one game](#the-problem-two-mod-loaders-one-game)
2. [What a mod loader actually does](#what-a-mod-loader-actually-does)
3. [Why you cannot just use both at once](#why-you-cannot-just-use-both-at-once)
4. [How Forbric solves it](#how-forbric-solves-it)
5. [What "clean room" means, and why it is everywhere in this project](#what-clean-room-means-and-why-it-is-everywhere-in-this-project)
6. [The repository, folder by folder](#the-repository-folder-by-folder)
7. [How things flow](#how-things-flow)
8. [Words you will see in the log](#words-you-will-see-in-the-log)
9. [What Forbric can and cannot do today](#what-forbric-can-and-cannot-do-today)

---

## The problem: two mod loaders, one game

Minecraft does not come with a way to add mods. It ships as one sealed program. Everything the modding
world does is built on top of a *mod loader* — a program that starts first, opens the game up, and lets
mods in.

Two of them dominate. **Fabric** is small and fast-moving. **Forge** is older and heavier, and it now
has a second branch called **NeoForge**. They were built independently, they do the same job in
different ways, and a mod written for one simply does not run on the other.

That leaves you choosing. Say you want Sodium, the Fabric mod that makes the game render far better,
*and* Create, the Forge mod full of gears and contraptions. You cannot. You pick one profile or the
other, and half your mod list sits unused.

Forbric exists to remove that choice. It is one loader that runs Fabric mods and Forge-family mods in
the same instance, at the same time.

## What a mod loader actually does

Think of Minecraft as a play that is already fully scripted and rehearsed. Mods are people who want to
change the script — add a scene, repaint the set, give a character a new line. A mod loader is the stage
manager who lets them do it in the few minutes before the curtain goes up.

It has three jobs.

**1. Read the labels.** Every mod is a `.jar` file — a zip archive with a small text label inside it
saying what the mod is called, what version it is, and what else it needs. A Fabric mod's label is a
file called `fabric.mod.json`. A Forge mod's is `META-INF/mods.toml`. A NeoForge mod's is
`META-INF/neoforge.mods.toml`. The loader opens every jar in your `mods` folder, reads the label,
and builds a list.

**2. Rewrite the game before it starts.** This is the part people underestimate. Mods do not politely
ask Minecraft for permission — they *edit its code*, in memory, as the game is being loaded. When
Sodium replaces the renderer, it is rewriting the game's own instructions on the way through. Nothing
on your disk changes; the loader intercepts each piece of the game as it is about to be used and hands
over an edited version instead.

**3. Call the mods at the right moments.** A mod that adds a new block has to register it in the exact
window when the game is collecting blocks — too early and nothing is listening, too late and the list
is already sealed. The loader knows the schedule and calls each mod at its moment.

## Why you cannot just use both at once

Given all that, "put both loaders in the same folder" fails for three separate reasons. Each one is
worth understanding, because Forbric's whole design is three answers to these three problems.

### Problem 1 — two front doors

Both loaders want to be the program your launcher starts. Only one program can start. Whichever one
wins, the other never runs at all, and its mods are invisible.

### Problem 2 — two dictionaries

For most of its life Minecraft has shipped *scrambled*. Not encrypted — just stripped of meaningful
names, so that the class that handles a block is called something like `dfj` instead of `Block`. Both
communities had to invent their own naming table to work with it, and they invented different ones.

It is the same person being "Mr. Tanaka" at the office and "Dad" at home. Both names are correct, both
refer to the same man, and a letter addressed to "Dad" will not reach him at the office. A Fabric mod
says `class_2248`; a Forge mod says `Block`; the game itself, underneath, says `dfj`. Put those mods in
one room and half of them are talking to a name nobody answers to.

### Problem 3 — two editors on one manuscript

Both loaders rewrite the game's code, and each assumes it is the only one doing it. Run them side by
side and you get two independently edited copies of the same game. Sodium's rendering changes land on
one copy, Create's machinery lands on the other, and neither mod can see what the other did — the
symptoms are not clean crashes but strange, silent nothingness.

## How Forbric solves it

Forbric's answer is not "make the two loaders cooperate". It is: **one loader, doing the job once,
that understands both dialects.**

### One front door

Forbric is the program your launcher starts. There is no negotiation, because there is nobody to
negotiate with.

Rather than rebuild everything from nothing, Forbric reuses Fabric's loader as its foundation — the
class loading, the mod-list solving, the metadata reading. That code is open source under a permissive
licence, it is proven, and re-implementing it would have bought nothing. On top of that foundation
Forbric adds its own Forge-compatible half. Roughly: it kept Fabric's engine and chassis and built a
cabin that has doors on both sides.

### One guest list

Forbric reads all three label formats into a single list. `fabric.mod.json`, `mods.toml` and
`neoforge.mods.toml` say the same kinds of things in different notations, so each is translated into
one shared form and dependency-checked together, once.

Even the small details need translating. "I need version 2 or newer" is written one way by Fabric and
another way by Forge; Forbric converts Forge's notation into the one the solver already speaks. Forge
mods can also carry other mods nested inside them, like a box inside a box — Forbric unpacks those too.

### One dictionary

Forbric keeps a table that joins the two naming systems by lining them up against the scrambled names
underneath. `class_2248` and `Block` both point at `dfj`, so they can be matched to each other, and a
Forge mod's code can be rewritten to use whichever names the running game actually answers to.

There is a happy twist here. Since Minecraft **26.2**, the game ships with real names already in place
— nothing is scrambled any more. On 26.2 there is nothing to translate, so Forbric skips the whole step
and runs at full speed. The translation machinery matters for older versions like 1.21.11, where the
scrambling is still there.

### One editing desk

All the rewriting happens once, in one queue, on one copy of the game. Every piece of the game passes
through in a fixed order:

1. **Unlock** — Forge mods can ask for parts of the game that are normally private to be opened up.
   Those requests are applied first.
2. **Rename** — if this version of Minecraft needs translating, it happens here.
3. **Mixin last** — Mixin is the tool most mods of both families use to make their real changes.

The order is the point. Mixin works by finding a specific method and splicing into it, so by the time
it looks, everything must already be unlocked and under its final name. Rename after Mixin and Mixin
would have been editing text that was about to change underneath it. So Mixin is pinned to the end of
the queue, always, for mods of both families — and because there is only one queue and one copy of the
game, every mod sees every other mod's changes.

### The real Forge, not an imitation

This is the part that matters most for whether your mods actually work, and it is easy to miss.

Forbric does **not** re-implement Forge's API. A Forge mod that calls Forge's event system is calling
*Forge's actual event system* — the genuine MinecraftForge or NeoForge runtime, downloaded from their
own servers and running inside your game. What Forbric replaces is only the *startup* part: the piece
that normally boots Forge is bypassed, because it would insist on building its own separate world for
classes and immediately collide with the one Fabric's loader has already built.

So: Forge's engine, started by a different ignition. Mods talk to the same Forge they have always
talked to.

### The bridge mod, or: reconciling two schedules

One more concrete example, because it shows the flavour of the whole project.

Fabric mods and Forge mods both register their blocks and items — but each ecosystem does it in its own
window, at its own moment, and each window slams shut afterwards. Register a block outside the window
and the game refuses it.

So Forbric ships a tiny Forge mod of its own, called `forbric-bridge`, whose entire job is timing. It
sits inside the genuine Forge registration window and holds the door open long enough for the Fabric
mods to walk through it. Both families end up registering their content into the same window. That jar
is one of the three you see already sitting in your mods folder after installing.

## What "clean room" means, and why it is everywhere in this project

You will see the phrase *clean room* in every technical file here. It is a legal idea, and it shapes
what this repository is allowed to contain.

Imagine you want to build a lock that fits an existing key. One way is to get hold of the original
manufacturer's blueprints and copy them. The other is to measure the key, read the published standard,
and design your own lock from scratch in a room where nobody has ever seen those blueprints. Both locks
fit. Only the second one is yours.

Forge's own code carries a licence that would spread to anything built from it. So Forbric's
Forge-compatible half was written the second way — from public, published descriptions of the file
formats and the rules, without copying Forge's source. That is what lets the whole of Forbric sit under
one simple permissive licence (Apache-2.0).

The visible consequence, and the reason installing takes a few minutes the first time:

- **No Minecraft code is in this repository.** It belongs to Mojang.
- **No Forge or NeoForge code is in this repository.** Their runtimes are downloaded from their own
  servers and assembled *on your machine*.
- **No name-mapping data is in this repository.** Some of those naming tables cannot be redistributed
  by third parties, so Forbric ships none at all and generates what it needs locally.

Everything you download from here is Forbric's own work plus the openly licensed Fabric code it builds
on. The rest is assembled at your end, from the original sources.

## The repository, folder by folder

```
Minecraft-Forbric-mod-loader/
│
├── README.md                     start here — what it is, how to install
├── introduction.md               this file
├── LICENSE, NOTICE               Apache-2.0, plus credit for the code Forbric reuses
├── bootstrap.sh                  fetches the Fabric loader Forbric builds on, and patches it
├── .github/workflows/build.yml   the robot that rebuilds everything on every push
│
├── fabric-loader/                NOT part of this repository. bootstrap.sh clones it here.
│                                 Borrowed code, kept as its own checkout so you can see exactly
│                                 what was reused and exactly what was changed.
│
├── forbric-loader/               ← the loader itself
│
└── forbric-installer/            ← the thing you download and double-click
```

### `forbric-loader/` — the loader

```
forbric-loader/
├── README.md                 the technical version of this page
├── CREDITS.md                what was reused, what was written from scratch, and why
├── MAPPINGS.md               why no naming tables are shipped, and what is generated instead
├── NOTICE                    attribution for every third-party library
│
├── build.gradle              the build recipe. Also produces the two output jars (see below)
├── gradle.properties         one line names the exact Fabric release reused: fabric_loader_ref
│
├── patches/fabric-loader/    the eight changes Forbric makes to the borrowed Fabric code,
│                             kept as eight small readable patch files rather than a copied
│                             tree — so "what did you change in someone else's code?" has a
│                             short, checkable answer
│
├── src/main/java/net/forbric/loader/impl/
│   ├── discovery/            opens every jar in mods/ and works out what it is
│   ├── metadata/             the three label formats, and the one shared form they translate into
│   ├── mapping/              the dictionary that joins the two naming systems
│   ├── transformer/          the single editing queue, with Mixin pinned last
│   ├── access/               unlocking the private parts of the game a Forge mod asked for
│   ├── forge/                the Forge-compatible half
│   │   ├── minecraftforge/     starting the genuine traditional-Forge runtime
│   │   ├── neoforge/           the same for NeoForge
│   │   ├── bridge/             the pieces that make the two ecosystems agree — resource packs,
│   │   │                       registries, the shared registration window
│   │   ├── mixin/              Forbric's own small edits to the game
│   │   └── runtime/            the half that must live inside the game's class loader
│   ├── launch/               what actually starts when you press Play
│   ├── compat/, util/        smaller cross-ecosystem fixes, logging
│
├── src/main/resources/       the lists of game edits above
├── src/runtime-meta/         the label on the second output jar
├── src/test/                 65 automated tests, run on every build
├── src/stub/                 stand-ins for a handful of game classes, so this code can be
│                             compiled without a copy of Minecraft present
├── src/tools/                build-time tools, e.g. the one that merges both Forge families
│                             into a single game base
│
└── run/                      the workshop (see below)
```

**The two output jars.** Building produces `forbric-loader-*.jar` and `forbricruntime-*.jar`, and the
split is not packaging taste. Java loads classes in nested worlds, and the game — with all its edits
applied — lives inside an inner one. Code that only *organises* things can stand outside; code that
actually *touches* the game must be inside. So Forbric is a stage manager in the wings
(`forbric-loader`) and an actor on stage (`forbricruntime`), and the actor cannot be in the wings.

**`run/` — the workshop.** These scripts are how a developer produces a runnable instance and proves it
still works. They are not needed to play.

| Script | What it does |
| --- | --- |
| `build-patched-forge.sh` | builds the Forge-patched Minecraft jar on your machine |
| `assemble-minecraftforge-runtime.sh` | assembles the traditional Forge runtime from Forge's own downloads |
| `assemble-neoforge-runtime.sh` | the same for NeoForge |
| `build-merged-base.sh` | builds one game base carrying **both** Forge families at once — this is what lets all three ecosystems run in a single instance |
| `dedupe-runtime-overlap.sh` | resolves the files both Forge runtimes ship copies of |
| `build-testmods.sh` | compiles the small canary mods used for testing |
| `launch-*.sh` | boot a real client or server with a chosen combination |
| `regress-*.sh` | the standing regression gate: patches, tests, a real boot with real mods |
| `verify-substrate-patches.sh` | checks the eight patches still match, so they cannot silently rot |

### `forbric-installer/` — the installer

A standalone program with no dependencies at all, which is why it is one small file you can just run.

```
forbric-installer/
├── README.md                        every command-line flag, and what it does
├── src/main/java/net/forbric/installer/
│   ├── Main.java, InstallerGui.java   the command line, and the window you see
│   ├── Installer.java                 the actual install: stage the jars, write the version entry
│   ├── MojangDownloader.java          fetches vanilla Minecraft if you do not have that version
│   ├── ForgeRuntimeBuilder.java       assembles the Forge runtime on your machine
│   ├── PatchedMcBuilder.java          applies Forge's patches to your Minecraft jar
│   ├── ForgeArtifacts.java,
│   │   ForgeTool.java                 which files to fetch, and running Forge's own build tools
│   └── Http, Json, Util, Zips,
│       RemoteSource, ArtifactResult   downloading, unzipping, verifying
└── packaging/                       the double-click launchers for Windows and macOS
```

Two details worth knowing:

- **It never modifies an existing install.** It writes a brand-new version entry beside your others.
  Your Fabric and Forge setups are not touched.
- **Everything it downloads is verified twice.** Each file is checked against a fingerprint in a
  manifest, and the manifest itself is checked against a fingerprint built into the installer. A
  fingerprint that travels with the files it describes proves nothing — anyone who could swap the files
  could swap the list too — so the trusted copy is the one compiled in.

## How things flow

### 1. What you do

```
download installer  →  run it, pick your .minecraft and a mode  →  it builds and installs
                    →  open PCL2 / HMCL, choose "forbric-forge-26.2"
                    →  put every mod jar, Fabric and Forge alike, in that version's mods/ folder
                    →  play
```

### 2. What happens in the seconds after you press Play

This is the interesting one.

1. **Your launcher reads the version entry Forbric installed.** It is an ordinary Minecraft version
   file that says: inherit everything from 26.2, but start *this* program instead of the game.
2. **Java starts Forbric**, not Minecraft.
3. **Forbric opens every jar in the `mods` folder** and reads its label — `fabric.mod.json`,
   `mods.toml`, `neoforge.mods.toml` — into one list. Nothing is sorted by hand; the file inside decides.
4. **Dependencies are checked once, for everything**, across both families together.
5. **Forge-family jars get prepared**: nested jars unpacked, unlock requests collected, names
   translated if this Minecraft version needs it. Results are cached, so this only costs time once.
6. **The whole list is handed to the borrowed Fabric machinery** as if it had always been one ecosystem.
7. **The class loader starts.** From here on, every piece of the game is fetched, run through the
   editing queue — unlock, rename, then Mixin — and handed over in edited form.
8. **Forbric wakes up the Forge runtime.** Both drivers look for their runtime; whichever one is
   actually present is started, and the other logs a line saying it is idle and goes back to sleep.
9. **Minecraft starts.** Forge's own mod-loading runs for the Forge mods, Fabric's own start-up hooks
   run for the Fabric mods, and the bridge mod holds the registration window open so both register
   into the same one.
10. **Title screen.** Everything in your mods folder is now loaded in one game.

### 3. What a developer does

```
./bootstrap.sh                          fetch the Fabric loader at the pinned release, apply the
                                        eight patches, then verify them

cd forbric-loader && ./gradlew build    compile both code trees, run the 65 tests,
                                        write the two jars

run/build-patched-forge.sh              assemble a game base and the Forge runtimes locally
run/assemble-*-runtime.sh

run/launch-*.sh                         boot a real client or server
run/regress-*.sh                        the gate that has to stay green
```

The robot in `.github/workflows/build.yml` does the first two steps on every push. Its real purpose is
to keep the borrowed-code arrangement honest: because the Fabric substrate is fetched rather than
copied in, a moved tag or a patch that stops applying would quietly break a fresh clone. If that
happens, the build goes red immediately.

## Words you will see in the log

If Forbric crashes, `logs/latest.log` inside the version folder is where the story is. Here is the
vocabulary.

| Word | What it means |
| --- | --- |
| **Knot** | The class loader borrowed from Fabric — the thing that fetches each piece of the game, runs it through the editing queue, and hands it over. "Knot" in a crash means it happened during loading. |
| **Mixin** | The tool most mods use to splice their changes into the game. "Mixin apply failed" almost always means a mod is looking for a part of the game that this version does not have — usually a mod built for a different Minecraft version. |
| **Access Transformer** (AT) | A Forge mod's request to open up a part of the game that is normally private. |
| **Mappings**, **Mojmap**, **intermediary** | Naming tables. Mojmap is Mojang's own names; intermediary is Fabric's. Since 26.2 the game ships with real names, so no translation is needed. |
| **Entrypoint** | A moment a mod asked to be called at. "Could not execute entrypoint" means a mod threw an error during its own start-up — the mod named right after it is the culprit. |
| **JiJ** ("jar-in-jar") | A mod carrying another jar nested inside it. |
| **Patched game jar** | Your Minecraft jar with Forge's changes baked in, built on your machine at install time. |
| **forbricruntime** | The half of Forbric that lives inside the game's class loader. One of the three jars in your mods folder. |
| **forbric-bridge** | Forbric's own tiny Forge mod, whose only job is holding the registration window open. |
| **Substrate** | The Fabric loader code Forbric is built on top of. |
| **Registry** / "locked registry" | The game's list of blocks, items and so on. "Can not register to a locked registry" means a mod tried to add content after the window closed — a timing problem, not a broken mod. |

## What Forbric can and cannot do today

**It can:**

- Run Fabric mods and Forge-family mods in a single Minecraft instance, from one mods folder.
- Run the genuine MinecraftForge and NeoForge runtimes, so Forge mods use Forge's real APIs.
- Be installed into PCL2 or HMCL like any other loader, without touching what you already have.

**It cannot, yet:**

- **Guarantee any particular mod works.** Two ecosystems is a huge surface. What has been proven is
  what has actually been run, and that is listed in [forbric-loader/README.md](forbric-loader/README.md)
  rather than promised here.
- **Carry both Forge families in one instance out of the box.** An instance has one Forge-family base.
  The installer builds a traditional MinecraftForge one; a combined base exists but is assembled with
  the developer scripts in `forbric-loader/run/`.
- **Promise stability.** Version 0.1.0. Internals move, and there is no support commitment.

**Where to go next**

- [README.md](README.md) — install and play
- [forbric-loader/README.md](forbric-loader/README.md) — the architecture in technical terms
- [forbric-loader/run/README.md](forbric-loader/run/README.md) — how a runnable instance is assembled
- [forbric-installer/README.md](forbric-installer/README.md) — every installer flag
- [forbric-loader/CREDITS.md](forbric-loader/CREDITS.md) and
  [forbric-loader/MAPPINGS.md](forbric-loader/MAPPINGS.md) — provenance and licensing in detail

Forbric is not affiliated with Mojang, FabricMC, MinecraftForge or NeoForged.
