# M36 final Mixin application and late decisions

The NeoForge canary first loads a plain target class from the first live server tick. Its selected Mixin
has a working HEAD injector and an INVOKE injector whose target does not exist. The ordinary guest config
uses original `defaultRequire: 1`; the loader's existing relaxation permits the class to be defined. The
final-definition audit must identify the exact absent handler using Mixin's real rename metadata.

`gate-m36-mixin-outcome.sh` builds and runs four isolated, hash-bound instances:

- Required + strict: the real target and working handler execute, then the completed-tick boundary requests
  a normal halt. The third tick must not occur, the report must name exactly the necessary missing handler,
  and all dimensions must save without a crash report.
- Required + explicit continue: the third tick occurs; the confirmed required finding remains in the report.
- Optional: the missing injector explicitly declares `require = 0`; strict mode reaches the third tick.
- Declined: the mod's own plugin turns off both Mixins; strict mode reaches the third tick with no invented loss.

The canary neither calls a kernel diagnostic API nor manufactures a compatibility finding. Its ordinary
classes live outside the dedicated Mixin package. The config name is a regular guest name, because names
reserved for the kernel are intentionally not relaxed. Startup failure cannot satisfy any case.
