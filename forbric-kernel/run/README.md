# Kernel development and verification

Start from the repository root with a full **JDK 25+** and **Python 3.9+**. No Fabric substrate checkout,
player installer, existing game installation, or manually assembled game jars are required for the new
workflow. `tools/dev.py` uses the installer's artifact builder and the kernel from your current source.

## Start a development instance

```bash
python3 tools/dev.py client
```

On Windows, use `py tools/dev.py client` (or `python` if that is your Python command). The first run
prepares the game automatically: it downloads Minecraft 26.2, assembles both carriers and the merged base,
checks their links, downloads platform libraries/assets and pinned Fabric API/Energy APIs, then builds and
launches the kernel. Later launches reuse those inputs and rebuild changed kernel source.

Everything goes under `forbric-kernel/.dev/`: `minecraft/` holds upstream downloads and build caches,
`staged/run/` holds the assembled game inputs, `api/` holds the compile APIs, `client/` and `server/`
hold separate instances. That directory is ignored by Git. Put development mods in the instance's `mods/`;
Fabric API and Energy are included automatically. First preparation needs network access and several
minutes; Gradle caches its dependencies and preparation verifies downloaded file hashes.

For a dedicated server, accept Minecraft's EULA for the development instance:

```bash
python3 tools/dev.py server --accept-eula
```

New development servers bind to localhost and allow the client's local identity; edit `server.properties`
inside the instance if your testing needs other settings. The server console is interactive: type `stop`
to save and shut down. The client uses a local development
identity; these commands are for local development, not authenticated online play.

You can also use Gradle (Windows: `gradlew.bat`):

```bash
cd forbric-kernel
./gradlew prepareDev
./gradlew runClient
# Or: ./gradlew runServer -Pforbric.acceptEula
```

Keep preparation and launch in **separate invocations**. The runtime source set is wired when Gradle
configures the build, before preparation has written the jars. The one-command Python entry point handles
this separation automatically.

## Check the environment and customize a launch

```bash
python3 tools/dev.py doctor          # read-only; exit 2 means preparation is needed
python3 tools/dev.py prepare         # prepare/download without launching
python3 tools/dev.py client --dry-run # build and print the command without opening a game window
python3 tools/dev.py client --jvm=-Xmx4G -- --width 1280 --height 720
```

| Option | Environment variable | Default |
| --- | --- | --- |
| `--mc-dir` | `MC_DIR` | `.dev/minecraft/`; an existing Minecraft directory can be used explicitly |
| `--staged` | `FORBRIC_OLD` | `.dev/staged/`; the directory **containing** `run/` |
| `--instance` | `RUNDIR` | `.dev/client/` or `.dev/server/` |
| `--natives` | `NATIVES_DIR` | `.dev/natives/<platform-architecture>/` |
| `--java` | `FORBRIC_JAVA`, then `JAVA_HOME`, then PATH | JDK home or Java executable |
| Repeat `--jvm=...` | — | Additional JVM arguments |

The portable launcher uses native Java classpath separators and a Java argument file, so Windows paths
with spaces and long classpaths work. Only macOS receives `-XstartOnFirstThread`. Native libraries are
selected for the chosen JVM architecture. Developer launches use strict compatibility decisions.

Gradle also accepts `-Pforbric.python=<executable>`, `-Pforbric.instance=<directory>` and
`-Pforbric.dryRun`. Compilation overrides remain `-Pforbric.stagedRoot=<run directory>`,
`-Pforbric.mcLibraries=<libraries directory>`, `-Pforbric.fabricApi=<jar>` and
`-Pforbric.rebornEnergy=<jar>`. A partial staged tree fails with a prerequisite error.

## Know what a green result means

| Entry point | What it verifies |
| --- | --- |
| `cd forbric-kernel && ./gradlew build` (JDK 21+ on a fresh clone) | Boot compilation and available unit tests; with prepared inputs, also builds the game side |
| `python3 tools/dev.py test` / `./gradlew check` | Unit suite, available transfer tests, Python tooling self-tests and packaged installer link-gate positive/negative controls |
| `python3 tools/dev.py tool-test` / `./gradlew toolTest` | Development, evidence, soak and world-parity tools' self-tests; no game jars required |
| `python3 tools/dev.py integration` / `./gradlew integrationTest` | Unit, transfer and tool suites with strict enforcement: missing game inputs, zero tests or **any skipped assertion fail** |
| `python3 tools/dev.py gate --gate m0` | Existing full staged-build/discovery/link/oracle gate, using its documented mod sets |
| `python3 tools/dev.py gate --gate m33-transfer` | A particular real-instance gate; [the gate table](../../introduction.md#16-build-and-test) lists coverage |

Gradle prints test and skip counts, and HTML reports live in `forbric-kernel/build/reports/tests/`.
A boot-only build cannot prove the game or mods work. `integration` prepares the base game automatically,
but the full suite also needs the particular third-party fixtures named in its reports (Create, Carpet,
compatibility packs, etc.). It fails when those are missing rather than claiming complete coverage.

Legacy `gate-m*.sh` checks are Bash integration tools with gate-specific packs, worlds and platform
assumptions; they are not a fresh-clone smoke test. They remain runnable at their original paths, and
`gate-m0.sh` retains its no-skips contract. CI runs the boot build plus tool checks without game files,
and separately checks the portable tools and packaged link gate on Windows, Linux and macOS.

## Repository navigation

| Location | Purpose |
| --- | --- |
| `tools/dev.py`, `tools/test_dev.py` | Current portable development entry point and its tests |
| `forbric-kernel/src/test/`, `src/transferTest/` | Gradle-managed unit and transfer-engine tests |
| `forbric-kernel/canary/` | Tracked probe-mod source; generated jars go to `run/canary/` |
| `forbric-kernel/run/gate-m*.sh`, `build-*-canary.sh` | Legacy integration gates and their fixture builders |
| `forbric-kernel/run/compat/` | Compatibility sweep, evidence and platform tools; see [PROTOCOL.md](compat/PROTOCOL.md) |
| `forbric-kernel/.dev/`, `run/client-*/`, `run/server-*/` | Ignored local runtime state |
| `forbric-kernel/build/` | Build outputs, test reports and gate logs |
| `forbric-loader/src/tools/` | Shared standalone merge/link tools; no bootstrap required for `mergeToolsJar` |
| `forbric-kernel-installer/` | Current player installer and the artifact pipeline reused by `devToolsJar` |
| `forbric-loader/run/launch-*.sh`, `forbric-installer/` | First-generation boot and installer paths |

Keep new probes under `forbric-kernel/canary/` and verification tools under `run/compat/`, rather than
inside an instance directory. The old scripts retain their paths because existing gates and external
automation call them directly; use the entry points above instead of choosing among them by filename.
