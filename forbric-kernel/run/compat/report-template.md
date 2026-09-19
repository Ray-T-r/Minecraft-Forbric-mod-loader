# Compatibility run: {label}

- Commit: `{commit}`
- Mod set / manifest: {manifest}
- Minecraft / profile: {version}
- Started: {started}
- Server generation: {server}
- Client world join: {client}
- Log assertions: {assertions}
- Frame: {frame}
- Region: {region}
- Load report / named failures: {degraded}
- Observation / transport failures: {errors}
- Verdict: **{verdict}**

Evidence is kept next to this report: `artifacts/`, `assertions.txt`, `frame.txt`,
`region.txt`, `degraded.txt`, `errors.json`, saved job handles/results, and the input manifest. A missing log, fresh screenshot,
or readable region is a failure. Baselines record failures without discarding the mod.

Compare the same fields with the baseline. Record any quarantined jar, its reason,
the frame-based bisection result, and the replacement mod's actual loader/build.
