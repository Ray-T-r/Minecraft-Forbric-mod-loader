/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.mixin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.electronwill.nightconfig.core.UnmodifiableConfig;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * Two mods' mixins that claim the same method in ways that cannot both take effect.
 *
 * <p>{@link MixinFit} asks whether ONE mixin still fits the merged base. A mixed pack fails a second way that no
 * single-mixin judgement can see: each mod fits, and the two of them together do not. When two mods {@code @Overwrite}
 * the same method only one body survives: a higher-priority overwrite replaces a lower one without a word, and at
 * equal priority the second is skipped with one WARN line ({@code MixinApplicatorStandard.mergeMethod}). When two
 * {@code @Redirect} the same call Mixin keeps one of them. And every overwrite is merged before any injector is
 * applied, so another mod's injector always lands in the overwrite's body, not the one it was written against. Which
 * mod "wins" depends on priorities neither author chose with the other in mind.
 *
 * <p>The rules, each between claims of DIFFERENT mods on one target method:
 * <ul>
 *   <li>{@link Rule#R1} two {@code @Overwrite}s of the method;
 *   <li>{@link Rule#R2} two {@code @Redirect}s of the same call, with equal or unspecified ordinals;
 *   <li>{@link Rule#R3} an {@code @Overwrite} and any injector of another mod in the method;
 *   <li>{@link Rule#R4} (a note) a {@code @Redirect} and another mod's {@code @WrapOperation} or
 *       {@code @ModifyExpressionValue} on the same call. MixinExtras is built to compose with a redirect, so this is
 *       where to look, not a conflict.
 * </ul>
 *
 * <p>Like {@link MixinFit} it is conservative in the direction that cannot accuse: a selector it cannot pin to one
 * method (a wildcard, a regex, a bare name it cannot resolve) contributes no claim, and slices are not read, so two
 * redirects confined to different slices of one method still read as the same call.
 *
 * <p>Runtime ({@link #reportRegistered}): one {@link CompatibilityFinding.Confidence#SUSPECTED} finding per mod and
 * conflict, and {@link #conflictsIn} for {@code CrashAttribution}. {@code -Dforbric.mixinOverlapLint=off} skips both.
 * Offline: {@code MixinOverlapLint <merged-base.jar> <mods-dir> [--json out]}.
 */
public final class MixinOverlapLint {
	public static final String SWITCH = "forbric.mixinOverlapLint";

	private static final String OVERWRITE_DESC = "Lorg/spongepowered/asm/mixin/Overwrite;";
	private static final String REDIRECT = "Redirect";
	private static final String OVERWRITE = "Overwrite";
	/** The MixinExtras injectors that wrap a call another mod may have redirected. */
	private static final Set<String> CALL_WRAPPERS = Set.of("WrapOperation", "ModifyExpressionValue");
	/** {@code @At} values whose target names one call or field access inside the method. */
	private static final Set<String> CALL_POINTS = Set.of("INVOKE", "INVOKE_ASSIGN", "FIELD", "NEW");

	public enum Rule {
		R1(true, "two @Overwrite of one method"),
		R2(true, "two @Redirect of one call"),
		R3(true, "@Overwrite and another mod's injector in one method"),
		R4(false, "@Redirect and another mod's @WrapOperation/@ModifyExpressionValue of one call");

		/** False for a note: worth showing beside a crash or in the offline table, not a finding. */
		public final boolean conflict;
		public final String summary;

		Rule(boolean conflict, String summary) {
			this.conflict = conflict;
			this.summary = summary;
		}
	}

	/**
	 * One handler's claim on one target method.
	 *
	 * @param kind     the annotation's simple name: {@code Overwrite}, {@code Redirect}, {@code Inject}, …
	 * @param owner    the target class, internal name
	 * @param atValue  the {@code @At} value ({@code INVOKE}, {@code HEAD}, …), or null for an overwrite or a point-less
	 *                 injector such as {@code @WrapMethod}
	 * @param atTarget the {@code @At} target in one spelling ({@code Lowner;name(desc)}), or null
	 * @param ordinal  the {@code @At} ordinal, {@code -1} when unspecified
	 * @param array    the config array the mixin is listed in: {@code mixins}, {@code client} or {@code server}
	 * @param family   the mod as a player installed it: the jar's own mod for one it carries inside itself. Claims of
	 *                 one family never overlap -- C2ME's modules overwrite and inject into one method by design, and
	 *                 two of the first sweep's eleven R3 rows were exactly that
	 */
	public record Claim(String modId, String config, String mixin, String handler, String kind, String owner,
			String method, String desc, String atValue, String atTarget, int ordinal, String array, String family) {
		String methodKey() {
			return owner + "." + method + desc;
		}

		boolean overwrite() {
			return OVERWRITE.equals(kind);
		}

		/** {@code config:Mixin.handler}, as a log line names it. */
		public String site() {
			return config + ":" + mixin.substring(mixin.lastIndexOf('.') + 1) + "." + handler;
		}
	}

	/**
	 * Two claims of different mods that collide.
	 *
	 * @param first the overwrite for R3, the redirect for R4, otherwise the claim of the mod id that sorts first
	 */
	public record Overlap(Rule rule, Claim first, Claim second) {
		public String owner() {
			return first.owner();
		}

		public String method() {
			return first.method();
		}

		public String desc() {
			return first.desc();
		}

		/** The call both claim, for R2 and R4; null when the whole method is the point. */
		public String at() {
			return rule == Rule.R2 || rule == Rule.R4 ? first.atTarget() : null;
		}

		/** {@code mixin-overlap:<owner>.<name><desc>[@<at>]}: stable, with no prose in it. */
		public String id() {
			return "mixin-overlap:" + owner().replace('/', '.') + "." + method() + desc() + (at() == null ? "" : "@" + at());
		}

		/** {@code Minecraft.tick}, for prose. */
		public String where() {
			return owner().substring(owner().lastIndexOf('/') + 1) + "." + method();
		}
	}

	private static volatile List<Overlap> recorded = List.of();

	private MixinOverlapLint() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Claims
	// ---------------------------------------------------------------------------------------------------------------

	/**
	 * Every method {@code mixinBytes} overwrites or injects into, per handler and per {@code @At}.
	 *
	 * @param resolver maps {@code some/pkg/Name.class} to the target's bytes, as {@link MixinFit#evaluate} takes it;
	 *                 a bare-name selector on a target it cannot see contributes nothing
	 */
	public static List<Claim> claims(String modId, String config, byte[] mixinBytes, Function<String, byte[]> resolver) {
		return claims(modId, modId, config, "mixins", mixinBytes, resolver);
	}

	static List<Claim> claims(String modId, String family, String config, String array, byte[] mixinBytes,
			Function<String, byte[]> resolver) {
		ClassNode mixin = new ClassNode();
		new ClassReader(mixinBytes).accept(mixin, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		List<String> targets = MixinFit.mixinTargets(mixin);
		if (targets.isEmpty() || mixin.methods == null) return List.of();
		String mixinName = mixin.name.replace('/', '.');

		List<Claim> out = new ArrayList<>();
		for (String target : targets) {
			ClassNode targetNode = null;
			boolean read = false;
			for (MethodNode m : mixin.methods) {
				if (m.name.startsWith("<")) continue;
				if (annotated(m, OVERWRITE_DESC)) {
					out.add(new Claim(modId, config, mixinName, m.name, OVERWRITE, target, m.name, m.desc, null, null, -1,
							array, family));
					continue;
				}
				AnnotationNode injector = MixinFit.injectorOf(m);
				if (injector == null) continue;
				if (!read) {
					targetNode = readTarget(target, resolver);
					read = true;
				}
				String kind = injector.desc.substring(injector.desc.lastIndexOf('/') + 1, injector.desc.length() - 1);
				List<AnnotationNode> ats = MixinFit.atNodes(injector);
				for (String[] bound : bound(injector, targetNode, resolver)) {
					if (ats.isEmpty()) {
						out.add(new Claim(modId, config, mixinName, m.name, kind, target, bound[0], bound[1], null, null, -1,
								array, family));
						continue;
					}
					for (AnnotationNode at : ats) {
						String value = MixinFit.asString(MixinFit.value(at, "value"));
						String point = MixinFit.asString(MixinFit.value(at, "target"));
						int ordinal = MixinFit.value(at, "ordinal") instanceof Integer i ? i : -1;
						out.add(new Claim(modId, config, mixinName, m.name, kind, target, bound[0], bound[1], value,
								point == null ? null : canonical(point), ordinal, array, family));
					}
				}
			}
		}
		return out;
	}

	/**
	 * The {@code {name, desc}} pairs an injector binds on {@code target}: the methods {@link MixinFit#resolveSelector}
	 * finds that the target itself declares (Mixin injects into nothing it inherits). A selector with a full
	 * descriptor still names its method when the target cannot be read.
	 */
	private static List<String[]> bound(AnnotationNode injector, ClassNode target, Function<String, byte[]> resolver) {
		List<String[]> out = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		for (String selector : MixinFit.stringList(MixinFit.value(injector, "method"))) {
			if (!MixinFit.exactSelector(selector)) continue;
			if (target == null) {
				String s = selector.replaceAll("\\s+", "");
				int semi = s.indexOf(';');
				if (s.startsWith("L") && semi > 0) s = s.substring(semi + 1);
				int paren = s.indexOf('(');
				if (paren > 0 && seen.add(s)) out.add(new String[] { s.substring(0, paren), s.substring(paren) });
				continue;
			}
			for (MethodNode hit : MixinFit.resolveSelector(target, selector, resolver)) {
				if (target.methods == null || !target.methods.contains(hit)) continue;
				if (seen.add(hit.name + hit.desc)) out.add(new String[] { hit.name, hit.desc });
			}
		}
		return out;
	}

	private static ClassNode readTarget(String target, Function<String, byte[]> resolver) {
		byte[] bytes = resolver == null ? null : resolver.apply(target + ".class");
		if (bytes == null) return null;
		try {
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			return node;
		} catch (RuntimeException unreadable) {
			Caught.warn(unreadable, "[Forbric/MixinOverlap] could not read %s; taken as absent", target);
			return null;
		}
	}

	/**
	 * One spelling for a member target: Mixin takes {@code Lowner;name(desc)} and {@code owner.name(desc)} alike, and
	 * two mods naming one call in different spellings still name one call.
	 */
	static String canonical(String point) {
		MixinFit.Member m = MixinFit.parseMember(point);
		if (m == null) return point.replaceAll("\\s+", "");
		String desc = m.desc() == null ? "" : m.desc().startsWith("(") ? m.desc() : ":" + m.desc();
		return (m.owner() == null ? "" : "L" + m.owner() + ";") + m.name() + desc;
	}

	private static boolean annotated(MethodNode m, String desc) {
		for (List<AnnotationNode> table : java.util.Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations)) {
			if (table == null) continue;
			for (AnnotationNode a : table) if (desc.equals(a.desc)) return true;
		}
		return false;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Overlaps
	// ---------------------------------------------------------------------------------------------------------------

	/** Every collision between claims of different mod families, one per rule, method, call and pair of mods. */
	public static List<Overlap> overlaps(Collection<Claim> claims) {
		Map<String, List<Claim>> byMethod = new TreeMap<>();
		for (Claim c : claims) byMethod.computeIfAbsent(c.methodKey(), k -> new ArrayList<>()).add(c);

		Map<String, Overlap> found = new LinkedHashMap<>();
		for (List<Claim> group : byMethod.values()) {
			if (group.stream().map(Claim::family).distinct().count() < 2) continue;
			for (int i = 0; i < group.size(); i++) {
				for (int j = i + 1; j < group.size(); j++) {
					Claim a = group.get(i);
					Claim b = group.get(j);
					if (sameMod(a, b) || !coexist(a, b)) continue;
					Rule rule = rule(a, b);
					if (rule == null) continue;
					Overlap overlap = ordered(rule, a, b);
					String key = rule + "|" + overlap.id() + "|" + overlap.first().modId() + "|" + overlap.second().modId();
					found.putIfAbsent(key, overlap);
				}
			}
		}
		return List.copyOf(found.values());
	}

	private static Rule rule(Claim a, Claim b) {
		if (a.overwrite() && b.overwrite()) return Rule.R1;
		if (a.overwrite() || b.overwrite()) return Rule.R3;
		if (!sameCall(a, b)) return null;
		if (REDIRECT.equals(a.kind()) && REDIRECT.equals(b.kind())) return Rule.R2;
		if (REDIRECT.equals(a.kind()) && CALL_WRAPPERS.contains(b.kind())) return Rule.R4;
		if (REDIRECT.equals(b.kind()) && CALL_WRAPPERS.contains(a.kind())) return Rule.R4;
		return null;
	}

	private static Overlap ordered(Rule rule, Claim a, Claim b) {
		boolean swap = switch (rule) {
			case R3 -> !a.overwrite();
			case R4 -> !REDIRECT.equals(a.kind());
			default -> a.modId().compareTo(b.modId()) > 0;
		};
		return swap ? new Overlap(rule, b, a) : new Overlap(rule, a, b);
	}

	/** The same call: one target, a point that names a call, and ordinals that can select the same occurrence. */
	private static boolean sameCall(Claim a, Claim b) {
		if (a.atTarget() == null || !a.atTarget().equals(b.atTarget())) return false;
		if (!CALL_POINTS.contains(a.atValue()) || !CALL_POINTS.contains(b.atValue())) return false;
		return a.ordinal() < 0 || b.ordinal() < 0 || a.ordinal() == b.ordinal();
	}

	/**
	 * One mod: one family, or one id. Two jars of one id -- Sodium's Fabric and NeoForge builds side by side -- are
	 * arbitrated down to one before Mixin sees either, so they never both apply.
	 */
	private static boolean sameMod(Claim a, Claim b) {
		return a.family().equals(b.family()) || a.modId().equals(b.modId());
	}

	/** A client-array mixin and a server-array one are never applied in the same game. */
	private static boolean coexist(Claim a, Claim b) {
		return !("client".equals(a.array()) && "server".equals(b.array()))
				&& !("server".equals(a.array()) && "client".equals(b.array()));
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Runtime
	// ---------------------------------------------------------------------------------------------------------------

	/** What the last {@link #reportRegistered} found; read by {@code CrashAttribution} from its shutdown hook. */
	public static void publish(List<Overlap> overlaps) {
		recorded = overlaps == null ? List.of() : List.copyOf(overlaps);
	}

	public static List<Overlap> recorded() {
		return recorded;
	}

	/**
	 * The recorded conflicts (not notes) in a method named {@code name} of {@code dottedOwner}, any descriptor: a stack
	 * frame carries no descriptor, so an overload of the same name answers too.
	 */
	public static List<Overlap> conflictsIn(String dottedOwner, String name) {
		if (dottedOwner == null || name == null) return List.of();
		String owner = dottedOwner.replace('.', '/');
		List<Overlap> out = new ArrayList<>();
		for (Overlap o : recorded) {
			if (o.rule().conflict && o.owner().equals(owner) && o.method().equals(name)) out.add(o);
		}
		return out;
	}

	/**
	 * Lints every registered config as Mixin was served it — after the kernel's own drops, on this side — once Mixin has
	 * prepared them. Records the findings and publishes the overlaps; logs how long it took.
	 */
	public static void reportRegistered() {
		if (!enabled()) return;
		report(ForbricMixinService.registeredConfigNames(), ForbricMixinService::servedConfig,
				ForbricMixinService.adapterResource(), ForbricMixinService.side(), MixinConfigOwners::modIdOf,
				installedAs(net.forbric.api.ModCatalog.everything()));
	}

	/** The mod a player installed that carries a mod id: itself, or the jar it is bundled in, followed up. */
	static Function<String, String> installedAs(List<net.forbric.api.ModCatalog.Entry> catalog) {
		Map<String, String> bundledBy = new java.util.HashMap<>();
		for (net.forbric.api.ModCatalog.Entry e : catalog) bundledBy.putIfAbsent(e.modId(), e.bundledBy());
		return modId -> {
			String current = modId;
			for (int guard = 0; guard < 8; guard++) {
				String parent = bundledBy.get(current);
				if (parent == null || parent.isEmpty() || parent.equals(current)) break;
				current = parent;
			}
			return current;
		};
	}

	/**
	 * @param served   a config name to the JSON Mixin read
	 * @param resource a mixin or target class ({@code some/pkg/Name.class}) to its bytes
	 * @param owner    a config name to its one owning mod, or null; a config no single mod owns is not linted, since an
	 *                 overlap "between" two names of one mod would be an accusation with nobody behind it
	 * @param family   a mod id to the mod a player installed that carries it; see {@link Claim#family}
	 */
	static List<Overlap> report(Iterable<String> configs, Function<String, byte[]> served,
			Function<String, byte[]> resource, net.fabricmc.api.EnvType side, Function<String, String> owner,
			Function<String, String> family) {
		long started = System.nanoTime();
		List<Claim> claims = new ArrayList<>();
		int linted = 0;
		int mixins = 0;
		List<String> unowned = new ArrayList<>();
		for (String config : configs) {
			String modId = owner.apply(config);
			if (modId == null) {
				unowned.add(config);
				continue;
			}
			byte[] json = served.apply(config);
			UnmodifiableConfig parsed = json == null ? null : parse("mixin config " + config, json);
			if (parsed == null) continue;
			Object pkg = parsed.get(List.of("package"));
			if (pkg == null || pkg.toString().isEmpty()) continue;
			linted++;
			String pkgPath = pkg.toString().replace('.', '/');
			for (String entry : KernelGuestMixinAdapter.appliedEntries(parsed, side)) {
				byte[] bytes = resource.apply(pkgPath + "/" + entry.replace('.', '/') + ".class");
				if (bytes == null) continue;
				mixins++;
				try {
					claims.addAll(claims(modId, family.apply(modId), config, "mixins", bytes, resource));
				} catch (RuntimeException unreadable) {
					Caught.warn(unreadable, "[Forbric/MixinOverlap] could not read %s:%s; its overlaps are not linted", config,
							entry);
				}
			}
		}
		List<Overlap> overlaps = overlaps(claims);
		publish(overlaps);
		for (CompatibilityFinding finding : findings(overlaps)) CompatibilityFindings.record(finding);

		long ms = (System.nanoTime() - started) / 1_000_000;
		Map<Rule, Integer> counts = counts(overlaps);
		ForbricLog.info("[Forbric/MixinOverlap] linted %d mixin(s) in %d config(s) in %d ms: R1=%d R2=%d R3=%d R4=%d "
				+ "(%d config(s) with no single owner not linted)", mixins, linted, ms, counts.get(Rule.R1),
				counts.get(Rule.R2), counts.get(Rule.R3), counts.get(Rule.R4), unowned.size());
		for (Overlap o : overlaps) {
			String line = String.format("[Forbric/MixinOverlap] %s %s: %s (%s) and %s (%s)", o.rule(), o.id(),
					o.first().modId(), o.first().site(), o.second().modId(), o.second().site());
			if (o.rule().conflict) ForbricLog.warn("%s", line); else ForbricLog.debug("%s", line);
		}
		return overlaps;
	}

	static Map<Rule, Integer> counts(List<Overlap> overlaps) {
		Map<Rule, Integer> counts = new LinkedHashMap<>();
		for (Rule r : Rule.values()) counts.put(r, 0);
		for (Overlap o : overlaps) counts.merge(o.rule(), 1, Integer::sum);
		return counts;
	}

	/** One SUSPECTED finding per mod and conflict id, naming every other mod in it; notes make none. */
	static List<CompatibilityFinding> findings(List<Overlap> overlaps) {
		Map<String, List<Overlap>> byModAndId = new LinkedHashMap<>();
		for (Overlap o : overlaps) {
			if (!o.rule().conflict) continue;
			byModAndId.computeIfAbsent(o.first().modId() + "\n" + o.id(), k -> new ArrayList<>()).add(o);
			byModAndId.computeIfAbsent(o.second().modId() + "\n" + o.id(), k -> new ArrayList<>()).add(o);
		}
		List<CompatibilityFinding> out = new ArrayList<>();
		for (Map.Entry<String, List<Overlap>> e : byModAndId.entrySet()) {
			String modId = e.getKey().substring(0, e.getKey().indexOf('\n'));
			List<Overlap> involved = e.getValue();
			Overlap o = involved.get(0);
			Set<String> others = new LinkedHashSet<>();
			List<String> evidence = new ArrayList<>();
			for (Overlap one : involved) {
				Claim own = one.first().modId().equals(modId) ? one.first() : one.second();
				Claim other = own == one.first() ? one.second() : one.first();
				others.add(other.modId());
				evidence.add("rule=" + one.rule());
				evidence.add("own=" + own.kind() + " " + own.site());
				evidence.add("other=" + other.modId() + " " + other.kind() + " " + other.site());
			}
			out.add(new CompatibilityFinding(o.id(), modId, "Mixin overlap", "MixinOverlapLint",
					CompatibilityFinding.Confidence.SUSPECTED, false, detail(o, modId, String.join(", ", others)), evidence));
		}
		return out;
	}

	private static String detail(Overlap o, String modId, String others) {
		return switch (o.rule()) {
			case R1 -> String.format("its @Overwrite of %s and %s's cannot both take effect: Mixin keeps the one with the "
					+ "higher priority, or the first at equal priority", o.where(), others);
			case R2 -> String.format("its @Redirect of the call to %s in %s and %s's cannot both apply: Mixin keeps one "
					+ "redirect and skips the other", member(o.at()), o.where(), others);
			case R3 -> o.first().modId().equals(modId)
					? String.format("its @Overwrite of %s replaces the body %s's injector there was written against",
							o.where(), others)
					: String.format("its injector in %s was written against a body %s's @Overwrite replaces", o.where(),
							others);
			case R4 -> String.format("its mixin and %s's both wrap the call to %s in %s", others, member(o.at()),
					o.where());
		};
	}

	private static String member(String at) {
		if (at == null) return "?";
		MixinFit.Member m = MixinFit.parseMember(at);
		if (m == null) return at;
		return (m.owner() == null ? "" : m.owner().substring(m.owner().lastIndexOf('/') + 1) + ".") + m.name();
	}

	private static UnmodifiableConfig parse(String name, byte[] json) {
		try (Reader reader = new InputStreamReader(new ByteArrayInputStream(json), StandardCharsets.UTF_8)) {
			return MixinConfigJson.parse(reader);
		} catch (RuntimeException | IOException notAConfig) {
			Caught.warn(notAConfig, "[Forbric/MixinOverlap] could not parse %s; taken as absent", name);
			return null;
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Offline
	// ---------------------------------------------------------------------------------------------------------------

	private record Pending(String modId, String jar, String config, String array, byte[] bytes) {
	}

	private static final Pattern TOML_MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"");

	/**
	 * Usage: {@code MixinOverlapLint <merged-base.jar> <mods-dir> [--json out]}. Every mixin config of every jar and
	 * nested jar in {@code mods-dir}, against the merged base; a mixin into another mod's class resolves against that
	 * mod's class. Resolves against RAW jar bytes, as {@link MixinFitReport} does.
	 */
	public static void main(String[] args) throws IOException {
		if (args.length < 2) {
			System.err.println("usage: MixinOverlapLint <merged-base.jar> <mods-dir> [--json out]");
			System.exit(2);
		}
		Path json = null;
		for (int i = 2; i + 1 < args.length; i++) if (args[i].equals("--json")) json = Path.of(args[i + 1]);

		long started = System.nanoTime();
		Map<String, byte[]> merged = MixinFitReport.readJar(Path.of(args[0]));
		List<Path> jars = new ArrayList<>();
		// Walked rather than listed: a download cache keeps each project's jars in a folder of their own.
		try (var stream = Files.walk(Path.of(args[1]), 4)) {
			stream.filter(p -> p.toString().endsWith(".jar") && Files.isRegularFile(p)).sorted().forEach(jars::add);
		}

		// Pass 1: every mixin, and the classes they target that the merged base does not have.
		List<Pending> pending = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		Set<String> foreignTargets = new LinkedHashSet<>();
		for (Path jar : jars) {
			for (Map.Entry<String, Map<String, byte[]>> unit : MixinFitReport.expand(jar).entrySet()) {
				Map<String, byte[]> content = unit.getValue();
				String modId = declaredModId(content, unit.getKey());
				for (Map.Entry<String, byte[]> cfg : MixinFitReport.mixinConfigs(content).entrySet()) {
					MixinFitReport.Parsed parsed = MixinFitReport.parseConfig(cfg.getValue());
					if (parsed == null) continue;
					for (String entry : parsed.mixins()) {
						byte[] bytes = content.get(parsed.pkg().replace('.', '/') + "/" + entry.replace('.', '/') + ".class");
						// Mixin registers a config once by name, so a library two jars bundle is one mod's mixins.
						if (bytes == null || !seen.add(cfg.getKey() + ":" + entry)) continue;
						pending.add(new Pending(modId, jar.getFileName().toString(), cfg.getKey(),
								parsed.arrays().getOrDefault(entry, "mixins"), bytes));
						try {
							for (String t : MixinFit.mixinTargets(MixinFit.parse(bytes))) {
								if (!merged.containsKey(t + ".class")) foreignTargets.add(t + ".class");
							}
						} catch (RuntimeException unreadable) {
							// counted below, when claims() meets it again
							Caught.expected(unreadable);
						}
					}
				}
			}
		}
		// Pass 2: only those classes, so a hundred mods' bytecode is never held at once.
		Map<String, byte[]> foreign = new LinkedHashMap<>();
		if (!foreignTargets.isEmpty()) {
			for (Path jar : jars) {
				for (Map<String, byte[]> content : MixinFitReport.expand(jar).values()) {
					for (String name : foreignTargets) {
						byte[] bytes = content.get(name);
						if (bytes != null) foreign.putIfAbsent(name, bytes);
					}
				}
			}
		}
		Function<String, byte[]> resolver = name -> {
			byte[] bytes = merged.get(name);
			return bytes != null ? bytes : foreign.get(name);
		};

		List<Claim> claims = new ArrayList<>();
		Set<String> mods = new LinkedHashSet<>();
		int failed = 0;
		for (Pending p : pending) {
			mods.add(p.modId());
			try {
				claims.addAll(claims(p.modId(), p.jar(), p.config(), p.array(), p.bytes(), resolver));
			} catch (RuntimeException e) {
				Caught.warn(e, "[Forbric/MixinOverlap] could not read %s:%s of %s", p.config(), p.array(), p.modId());
				failed++;
			}
		}
		List<Overlap> overlaps = overlaps(claims);
		long ms = (System.nanoTime() - started) / 1_000_000;
		Map<Rule, Integer> counts = counts(overlaps);

		System.out.printf("[overlap] merged base: %d classes from %s%n", merged.size(), Path.of(args[0]).getFileName());
		System.out.printf("[overlap] %d jar(s), %d mod(s) with mixins, %d mixin(s) (%d unreadable), %d claim(s), %d ms%n",
				jars.size(), mods.size(), pending.size(), failed, claims.size(), ms);
		for (Rule r : Rule.values()) {
			System.out.printf("[overlap]   %s %4d  %s%s%n", r, counts.get(r), r.summary, r.conflict ? "" : " (note)");
		}
		for (Rule r : Rule.values()) {
			List<Overlap> of = overlaps.stream().filter(o -> o.rule() == r).toList();
			if (of.isEmpty()) continue;
			System.out.printf("%n=== %s (%d) ===%n", r, of.size());
			for (Overlap o : of) {
				System.out.printf("  %s%n      %s %s %s%n      %s %s %s%n", o.id(), o.first().modId(), o.first().kind(),
						o.first().site(), o.second().modId(), o.second().kind(), o.second().site());
			}
		}
		if (json != null) {
			Files.writeString(json, toJson(args[0], args[1], jars.size(), mods.size(), pending.size(), claims.size(), ms,
					counts, overlaps), StandardCharsets.UTF_8);
			System.out.printf("%n[overlap] wrote %s%n", json);
		}
	}

	/** The unit's own mod id from its manifest; the unit's file name when it has none. */
	static String declaredModId(Map<String, byte[]> content, String unit) {
		byte[] fabric = content.get("fabric.mod.json");
		if (fabric != null) {
			UnmodifiableConfig parsed = parse(unit + "!/fabric.mod.json", fabric);
			Object id = parsed == null ? null : parsed.get(List.of("id"));
			if (id != null && !id.toString().isBlank()) return id.toString();
		}
		for (String toml : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
			byte[] bytes = content.get(toml);
			if (bytes == null) continue;
			Matcher m = TOML_MOD_ID.matcher(new String(bytes, StandardCharsets.UTF_8));
			if (m.find()) return m.group(1);
		}
		String name = unit.substring(unit.lastIndexOf('/') + 1);
		return name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
	}

	private static String toJson(String mergedBase, String modsDir, int jars, int mods, int mixins, int claims, long ms,
			Map<Rule, Integer> counts, List<Overlap> overlaps) {
		StringBuilder out = new StringBuilder("{\"mergedBase\":").append(quote(mergedBase))
				.append(",\"modsDir\":").append(quote(modsDir)).append(",\"jars\":").append(jars)
				.append(",\"mods\":").append(mods).append(",\"mixins\":").append(mixins).append(",\"claims\":").append(claims)
				.append(",\"elapsedMs\":").append(ms).append(",\"counts\":{");
		int i = 0;
		for (Map.Entry<Rule, Integer> e : counts.entrySet()) {
			if (i++ > 0) out.append(',');
			out.append(quote(e.getKey().name())).append(':').append(e.getValue());
		}
		out.append("},\"overlaps\":[");
		for (int j = 0; j < overlaps.size(); j++) {
			Overlap o = overlaps.get(j);
			if (j > 0) out.append(',');
			out.append("{\"rule\":").append(quote(o.rule().name())).append(",\"conflict\":").append(o.rule().conflict)
					.append(",\"id\":").append(quote(o.id())).append(",\"first\":").append(claimJson(o.first()))
					.append(",\"second\":").append(claimJson(o.second())).append('}');
		}
		return out.append("]}\n").toString();
	}

	private static String claimJson(Claim c) {
		return "{\"mod\":" + quote(c.modId()) + ",\"config\":" + quote(c.config()) + ",\"mixin\":" + quote(c.mixin())
				+ ",\"handler\":" + quote(c.handler()) + ",\"kind\":" + quote(c.kind()) + ",\"at\":"
				+ (c.atTarget() == null ? "null" : quote(c.atTarget())) + ",\"ordinal\":" + c.ordinal()
				+ ",\"array\":" + quote(c.array()) + "}";
	}

	private static String quote(String s) {
		StringBuilder out = new StringBuilder("\"");
		for (char ch : s.toCharArray()) {
			switch (ch) {
				case '\\' -> out.append("\\\\");
				case '"' -> out.append("\\\"");
				case '\n' -> out.append("\\n");
				default -> { if (ch < 32) out.append(String.format("\\u%04x", (int) ch)); else out.append(ch); }
			}
		}
		return out.append('"').toString();
	}
}
