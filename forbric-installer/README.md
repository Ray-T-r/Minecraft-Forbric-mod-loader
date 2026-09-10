# forbric-installer

A standalone, dependency-free installer that makes Forbric launchable from a stock Minecraft launcher
(PCL2, HMCL, or anything else that reads the vanilla `versions/` layout). It is pure JDK — no third-party
libraries — so the build produces one runnable jar.

It does two things:

1. Stages Forbric's jars into the launcher's `libraries/` tree, in the normal Maven layout, verifying each
   file's SHA-1 after the copy.
2. Writes a version profile at `versions/<id>/<id>.json` with `inheritsFrom` set to the base Minecraft
   version and `mainClass` set to `net.forbric.loader.impl.launch.ForbricClient`. Selecting `<id>` in the
   launcher then boots Forbric against the vanilla base the launcher already resolves.

Nothing in an existing Fabric or Forge install is modified: the profile is new and separate.

The two jar categories in the loader's manifest are treated differently, and that difference is load-bearing.
`classpath` entries (the loader core plus the substrate dependencies it reuses) are both staged and listed in
the profile's `libraries`, so the launcher puts them on `-cp`. The `knot-addmods` entry (`forbricruntime.jar`)
is staged but deliberately *not* listed — on `-cp` its intermediary-named game references cannot resolve — and
is instead handed to Forbric's Knot classloader through a `-Dfabric.addMods=${library_directory}/…` JVM
argument in the profile.

Re-running the installer is idempotent: it overwrites the profile, re-stages the jars, and reuses artifacts it
already built.

## Install modes

`Installer.MODE_INTERMEDIARY_V1` = `intermediary-v1` (the default), base version `1.21.11`.
Vanilla Minecraft on the Fabric substrate. Produces `versions/forbric-<mc>/forbric-<mc>.json` with
`-Dfabric.addMods=…` pointing at the staged `forbricruntime` jar, and adds
`net.fabricmc:intermediary:<mc>` (from `https://maven.fabricmc.net/`) to the profile's libraries.

`Installer.MODE_FULL_FORGE_26_2` = `full-forge-26.2`, base version `26.2`, Forge `26.2-65.0.1`.
Drives the genuine Forge lifecycle, so a raw Forge mod jar dropped into the profile's `mods/` loads. This mode
builds two heavy artifacts on the local machine (see below) and writes a profile that selects the patched game
jar via `-Dfabric.gameJarPath.client`, plus `-Dforbric.runtimeNamespace=named` and
`-Dforbric.fabricMainDeferred=true`. Because the Forge lifecycle is discovered by scanning `gameDir/mods`, the
three infrastructure jars (`forbricruntime.jar`, `forge-runtime.jar`, `forbric-bridge.jar`) are also copied
into `versions/forbric-forge-<mc>/mods/` alongside the user's own mods.

`forbric-bridge.jar` only reaches the manifest if `forbric-loader/run/forge-runtime/forbric-bridge.jar`
exists when the loader's `generateInstallerManifest` runs; it is produced by
`forbric-loader/run/assemble-minecraftforge-runtime.sh`. Without it, a `full-forge-26.2` install fails with an
explicit error.

## Running it

With no arguments, and a display available, `Main` opens the Swing GUI (`InstallerGui`): a Minecraft-folder
field, a mode dropdown, a base-version dropdown populated from the installed versions under `versions/`, a
"download the base version if it is missing" checkbox, and a log pane. Install runs on a background thread and
streams the same log the CLI prints. The GUI is a thin shell over the same `Installer`.

```
java -jar forbric-installer.jar                      # GUI
java -jar forbric-installer.jar --headless [options] # no GUI
```

| Flag | Meaning |
| --- | --- |
| `--mc-dir <path>` | Minecraft directory. Default is the per-OS launcher directory (`%APPDATA%\.minecraft`, `~/Library/Application Support/minecraft`, or `~/.minecraft`). A leading `~/` is expanded. |
| `--mode <mode>` | `intermediary-v1` (default) or `full-forge-26.2`. |
| `--game-version <id>` | Base Minecraft version. Defaults to `1.21.11`, or `26.2` when the mode is `full-forge-26.2`. |
| `--no-download-mc` | Do not fetch the base version from Mojang when it is missing. Downloading is on by default. |
| `--manifest <path>` | Development override: read an external `forbric-libraries.json` instead of the manifest bundled in the installer jar. |
| `--remote` | Download Forbric's jars from the GitHub release even when this installer bundles them. |
| `--release <tag>` | Install a specific release tag instead of the one this installer was built against. Passing this discards the compiled-in manifest digest, since that digest describes a different release. |
| `--mirror <url-prefix>` | Put a relay in front of every github.com request, for networks where github.com is slow or blocked — e.g. `--mirror https://your-relay.example/`. Maven URLs are not rewritten. |
| `--offline` | Never download Forbric's jars. Fails, listing what it could not satisfy locally. |
| `--headless` | Do not open the GUI. Also implied when the JVM reports a headless graphics environment. |
| `--help`, `-h` | Print usage. |

### Where the jars come from

In order: a jar bundled inside this installer, then a local build named by `--manifest`, then the GitHub
release. A slim installer (`./gradlew jar -Pslim`) carries no payload at all and always downloads, which is
why it is 70 KB rather than 5 MB.

Forbric's own jars come from the release. The third-party libraries come from their canonical Maven homes —
`maven.fabricmc.net` for `net.fabricmc:*`, Maven Central for the rest — and fall back to the release only if
those are unreachable.

Downloads are verified twice over. Each jar is checked against the SHA-1 the manifest declares, and an
already-present file counts as a cache hit only if its digest matches, so a truncated download heals itself
instead of persisting. The manifest itself is checked against a SHA-256 compiled into the installer at
release time: a digest carried in a manifest fetched from the same release as the jars would prove only that
the transfer was not corrupted, since anyone who could replace the jars could replace the manifest too.
A mismatch is fatal, not a warning. An installer built outside a release carries no such digest and says so.

## Launching it without a terminal

`packaging/Forbric-Installer.bat` (Windows) and `packaging/Forbric-Installer.command` (macOS) run the
installer by double-click. Keep each one in the same folder as the jar. They find a Java runtime in
`JAVA_HOME`, then on `PATH`, then in the Minecraft launcher's own `runtime` folder — a Minecraft player
frequently has no system-wide JDK, and their launcher's runtime is the only Java on the machine.

### The Windows .jar association

A jar double-clicks correctly on Windows only if the `.jar` association is right, and that lives in the
registry, not in the jar. Nothing this project ships can change the outcome: if the association is wrong, the
JVM fails before a single byte of the installer runs.

The association a JDK installer writes is `javaw.exe -jar "%1" %*`, and that works. The one Windows' **"open
with"** dialog writes is `<whatever.exe> "%1"` — **no `-jar`** — because that dialog has no idea a jar needs
it. Java then reads the jar's path as a *class name*, throws `ClassNotFoundException` and exits. With
`java.exe` the user sees a console window flash and vanish too fast to read; with `javaw.exe` they see
nothing at all.

Two things make this bite people who believe they have Java installed:

- A Minecraft launcher's bundled runtime (`.minecraft/runtime/...`) is not an installed JDK. It registers no
  file associations. Pointing "open with" at its `java.exe` produces exactly the broken association above.
- `HKCU\...\Explorer\FileExts\.jar\UserChoice` **overrides** whatever a JDK installer later writes. So
  installing a real JDK does not necessarily repair an association that was already hand-set — the stale user
  choice keeps winning. Clearing that key, or re-picking the JDK's own registered Java entry in "open with",
  is what actually switches it over.

`packaging/Forbric-Installer.bat` sidesteps all of this by invoking Java itself, and is the reliable way to
start the installer on a machine whose association is in an unknown state.

The parser (`Main.parseOpts`) accepts one or two leading dashes for any flag. A flag consumes the next token as
its value unless that token itself starts with `-`, in which case the flag is set to `true`. Tokens that start
with no dash and are not consumed as a value are ignored.

## What is built locally, and what is never shipped

The installer jar carries Forbric's own jars — the loader (which includes the compiled Apache-2.0 fabric-loader
substrate), its reused dependencies, `forbricruntime`, and the clean-room `forbric-bridge` `@Mod`. It carries
no Minecraft bytecode and no Forge bytecode. The `full-forge-26.2` artifacts that would contain such bytecode
are built on the user's machine at install time, from files downloaded there:

- `ForgeRuntimeBuilder` downloads the Forge `-universal` jar and the FML/ModLauncher/eventbus libraries listed
  in the userdev `config.json` (Forge Maven at `https://maven.minecraftforge.net`, falling back to Maven
  Central) and merges them into one `forge-runtime.jar` carrying a synthetic `fabric.mod.json` so Knot loads
  the `net.minecraftforge.*` classes in its transforming classloader. Mixin is excluded, because Forge's copy
  would collide with the Fabric sponge-mixin fork already on the Knot classpath.
- `PatchedMcBuilder` produces the Forge-patched, Mojmap-named game jar: extract the Mojang server jar with
  `installertools BUNDLER_EXTRACT`, merge it with the user's own installed client jar via `mergetool`, apply
  Forge's `joined.lzma` binary patches with `binarypatcher`, overlay the patched classes and strip the Mojang
  jar signature, then apply Forge's access transformers through Forge's own `AccessTransformerEngine`.
  `ForgeTool` runs the fatjar tools as subprocesses (they call `System.exit`) using the `java` of the JVM the
  installer is already running under, so no separate JDK is required.
- `MojangDownloader` fetches the vanilla client (version manifest → per-version JSON → client jar) when the
  base version is absent and `--no-download-mc` was not passed. Each file is written to a `.part` temp,
  verified by size and SHA-1, and atomically moved into place.

Both built artifacts land in `libraries/` under `net.forbric` coordinates, with intermediates cached under
`<mc-dir>/.forbric-build/<mc>-<forge>`. The first `full-forge-26.2` install takes a few minutes; later ones
reuse what is on disk.

## Building

```
cd <repo>            && ./bootstrap.sh        # the loader needs its substrate checkout first
cd forbric-installer && ./gradlew jar         # -> build/libs/forbric-installer-0.1.0.jar
```

`jar` depends on the sibling `../forbric-loader`. `generateLoaderArtifacts` shells out to the loader's own
Gradle wrapper for `jar runtimeJar generateInstallerManifest` (keeping the two builds decoupled — the installer
stays pure-JDK), then `bundleForbric` copies every jar named by `build/forbric-libraries.json` into this
project's generated resources under `/forbric/libs/<maven-path>` and writes a portable copy of the manifest,
with the build machine's absolute paths stripped, to the classpath root. That is what makes the released jar
self-contained: an end user downloads one file, and never builds from source or points at an external manifest.

`./gradlew releaseZip` assembles `build/dist/forbric-installer-<version>.zip`, containing the jar plus the
double-click launchers in `packaging/` (`Forbric-Installer.command` for macOS, `Forbric-Installer.bat` for
Windows). Both require a Java runtime on `PATH`.
