/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.UnifiedDependency;
import net.forbric.api.VersionPredicate;
import net.forbric.kernel.util.ForbricLog;

/**
 * Injector targets the mod's own platform lacks as well as the merged base: the misses native Mixin drops without a
 * word, and which are therefore nothing the merge cost the mod.
 *
 * <p>Not Enough Crashes 4.4.9 for 26.2 is the worked case. Its {@code MixinTileEntity} injects into
 * {@code BlockEntity.populateCrashReport} — the Yarn name of what 26.2 calls {@code fillCrashReportCategory}, in a
 * mod built from Yarn sources for an unobfuscated game. Neither vanilla nor the merged base has that method.
 * {@link MixinFit} found no anchor, called the mixin UNFIT, and the adapter left it out as a CONFIRMED loss the
 * config's {@code required: true} made required — so the default STRICT policy stopped a server that native Fabric
 * Loader 0.19.5 starts with the same jar.
 *
 * <h2>What native Mixin does with an injector whose selector matches nothing</h2>
 *
 * <p>Read from sponge-mixin 0.17.3 (the kernel's, and native NeoForge 26.2.0.88's), 0.17.4 (Fabric Loader 0.19.5's)
 * and upstream Mixin 0.8.7 (MinecraftForge 26.2-65.0.1's), which agree here. {@code InjectionInfo.parseRequirements}
 * takes the injector's own {@code require} when it is 0 or more, else — for an injector in no {@code @Group} — the
 * config's {@code injectors.defaultRequire}, which is 0 unless the config says otherwise.
 * {@code TargetSelectors.validate} then throws only when that count is above 0 ("Critical injection failure: … could
 * not find any targets matching …"), or when {@code mixin.debug.countInjections} (or {@code mixin.debug}) is on and
 * {@code expect}, default 1, is above 0. Otherwise nothing is said: {@code InjectionInfo.isValid()} is false,
 * {@code MixinTargetContext.prepareInjections} skips the injector, and the rest of the mixin — its fields, their
 * initializers, its other methods, its interfaces, the handler itself — is merged as usual. A {@code @Group} counts
 * its members together and needs at least one injection, so a grouped injector is never silently empty. The config's
 * own {@code required} flag only decides whether one of those errors is fatal; it makes no injector mandatory.
 * A config's {@code defaultRequire} below 0 requires nothing either: {@code InjectionInfo} carries it as the count,
 * and no check fails on a count of 0 or less. That is the {@code "defaultRequire": -1} mods write so their injectors
 * into another mod's added methods may find nothing when that mod is not installed — a selector such as
 * {@code otherMod$cached} on a vanilla class, which no platform declares either — and {@code mergeFrom} keeps it in
 * a child config, since it replaces only a 0 with the parent's.
 * Measured on native Fabric 0.19.5 with Not Enough Crashes alone and {@code -Dmixin.debug.export=true}: the server
 * reaches Done, Mixin logs nothing about the injector, and the woven {@code BlockEntity} carries {@code noNBT}, its
 * constructor initializer, the lambda and the merged handler, with no call to the handler anywhere.
 *
 * <p>Only a selector that is a plain method name, optionally with a descriptor and with the target itself as its
 * owner, is a name lookup that silently finds nothing ({@link #plainSelector}). The rest of the grammar means
 * something else: a leading {@code @} is a registered dynamic selector (MixinSquared's {@code @MixinSquared:Handler}
 * resolves to another mixin's merged handler), a trailing {@code +} or {@code {n,}} is a quantifier whose minimum
 * {@code TargetSelectors.findRootTargets} enforces whatever {@code require} says, a dotted name splits into an owner
 * and a name, and a descriptor that is not one fails validation. Those are never answered here.
 *
 * <h2>Whose native: the mod's own platform</h2>
 *
 * <p>A Fabric mod's native game is vanilla 26.2. A MinecraftForge or NeoForge mod's is its own platform's patched
 * game, which declares methods vanilla does not — {@code KeyMapping.getKeyModifier()}, {@code AxeItem.canPerformAction},
 * NeoForge's {@code EnderDragon.getParts()} — and some of those the merge dropped or retyped. For such a mod "vanilla
 * lacks it too" proves nothing: natively the injector applies, and here it cannot, which is the merge's loss. So the
 * question is asked of the owning mod's platform ({@link MixinConfigOwners#ecosystemOf}), and a config no single mod
 * claims is not asked at all. The patched jars are the whole of what those platforms declare in vanilla's packages:
 * neither runtime ships a class there, and their own transformers add no method there (MinecraftForge 65.0.1's
 * coremods are one redirect list, {@code finalize_spawn_targets.json}; NeoForge 26.2.0.88's coremods jar holds
 * {@code MethodRedirector}, {@code ReplaceFieldWithGetterAccess} and {@code ReplaceFieldComparisonWithInstanceOf},
 * which rewrite instructions only).
 *
 * <h2>Which misses the platform shares</h2>
 *
 * <p>The kernel sees only the merged base at run time, and none of the three platform jars is shipped with it. What is
 * shipped is the difference: {@value #TABLE}, which NativeOnlyMethodsCensusTest re-derives from vanilla 26.2's own jar,
 * the staged MinecraftForge and NeoForge patched jars and the staged merged base, and pins. Measured, every class the
 * three declare in vanilla's packages is in the merged base, and only 565 of vanilla's methods, 424 of
 * MinecraftForge's and 14 of NeoForge's are not (most of them lambdas and anonymous classes the merge renumbered), so a
 * platform's declared methods are the merged class's own minus what the merge added, plus that platform's rows for
 * that class. A method the merged class does not declare and no row of the platform names is a method the platform
 * does not declare either.
 *
 * <p>"The merged class's own" means the merged base's RAW bytes, before the kernel's transform chain: the chain
 * removes some methods (orphaned lambda twins, interface-default shadowing stubs), and a method only the chain took
 * away may well be the platform's — the census never saw that removal. So a method the raw class still declares is
 * never called absent; the miss stays the merge's, as before.
 *
 * <p>The rows hold only for the merged base they were derived from, and a base that lost a method the staged one kept
 * would have no row for it — the direction that hides a loss. So the table carries that base's members digest
 * ({@link #membersDigest}: every class in vanilla's packages with its methods' names and descriptors, bodies left out,
 * so a rebuild that changes only code still matches), and nothing is answered for a class served by a jar whose digest
 * differs, or by no jar the kernel can read; such a miss counts as the merge's, as before, and one warning names the
 * mismatch.
 *
 * <p>Only classes in vanilla's own packages are asked, and only those the raw view can see and the table does not list
 * as the merged base's alone for that platform (a class the platform lacks fails natively for a different reason:
 * Mixin cannot find the target and skips the whole mixin). Another mod's class is never asked: {@link MixinFit} judges
 * those as foreign.
 *
 * <h2>Minecraft's own libraries</h2>
 *
 * <p>{@code com/mojang/} is also where Minecraft's libraries live — brigadier, DataFixerUpper, authlib and five more —
 * and the kernel loads those from their own jars, beside the merged base, so a mod can mixin into them as it can
 * natively. Their raw bytes are what every platform loads: vanilla 26.2 lists them in its version JSON, and
 * MinecraftForge 65.0.1's and NeoForge 26.2.0.88's launcher profiles both inherit that list and add no
 * {@code com.mojang} library (measured on their installers), and no platform jar or merged base ships one of their
 * classes. So the table also records each of those jars' members digest ({@code library} lines, re-derived from the
 * version JSON), and a class served by one of exactly those jars is answered from its raw bytes, as the merged base's
 * classes are. A library jar of another version, or a mod's own copy, has another digest and is not answered for.
 *
 * <h2>Whose native: the version the mod asks for</h2>
 *
 * <p>The rows describe one game per platform: vanilla 26.2, MinecraftForge 65.0.1's patched 26.2 and NeoForge
 * 26.2.0.88's, which the table's {@code platform} lines record ({@code minecraft=}, {@code forge=}, {@code neoforge=}).
 * A mod whose mandatory {@code minecraft} range, or {@code forge}/{@code neoforge} range for its platform, excludes that
 * version is not native to that game: its own loader would refuse it there, and the newer game it was built for may
 * well declare the method this one lacks. Such a mod is not answered for — the miss is the merge's, as before — and
 * one line names the requirement. Nor is a mod whose declared requirements the kernel cannot see (the config's owner
 * has no metadata in {@link net.forbric.api.ModPresence}), or whose range cannot be read: being asked is the claim that
 * would hide a loss, so it needs a requirement that is shown to admit the table's game.
 *
 * <p>What this changes is only the judgement. Such an injector is neither a resolved nor a missing anchor; the mixin
 * is judged on the rest, and when nothing else is missing it goes to Mixin whole, which drops the injector exactly as
 * native does. {@link FinalMixinApplications} then reads the injector's original minimum, 0, and finds nothing to
 * report. {@code -Dforbric.mixinFit.nativeAbsent=off} counts every such target as a miss again, as before.
 */
public final class NativeAbsentTargets {
	/** The shipped table; NativeOnlyMethodsCensusTest pins it to the staged jars. */
	static final String TABLE = "/net/forbric/kernel/mixin/native-only-methods.txt";

	/** {@code -Dforbric.mixinFit.nativeAbsent=off}: a target the platform lacks too is a missing anchor again. */
	static final String PROPERTY = "forbric.mixinFit.nativeAbsent";

	/**
	 * {@code -Dforbric.mixinFit.nativeAbsent.base=<digest>}: the members digest the table is trusted for instead of the
	 * one it records. For a fixture game whose classes the table has no rows for (the weave tests), or a base someone
	 * has checked against the staged one by hand; never needed on an installed game.
	 */
	static final String BASE_PROPERTY = "forbric.mixinFit.nativeAbsent.base";

	/** The packages vanilla's jar puts its classes in; the census asserts it ships none elsewhere. */
	static final List<String> VANILLA_PACKAGES = List.of("net/minecraft/", "com/mojang/");

	/** Whether {@code internalName} is in {@link #VANILLA_PACKAGES}: the only classes this ever answers for. */
	static boolean inVanillaPackages(String internalName) {
		if (internalName == null) return false;
		for (String pkg : VANILLA_PACKAGES) if (internalName.startsWith(pkg)) return true;
		return false;
	}

	/** {@code base <digest>}: the members digest of the merged base the rows were derived from. */
	static final String BASE = "base ";

	/**
	 * {@code library <digest> <maven name>}: the members digest of one of Minecraft's own library jars that ships classes
	 * in vanilla's packages, which every platform loads as it is.
	 */
	static final String LIBRARY = "library ";

	/**
	 * {@code platform <id> <requirement>=<version>...}: the table speaks for this platform, whose game is the one those
	 * versions name — {@code minecraft=} always, and {@code forge=} or {@code neoforge=} for those two. A platform
	 * without the line is never answered for.
	 */
	static final String PLATFORM = "platform ";

	/** {@code <id> merged-only <owner>}: a class in vanilla's packages the merged base has and that platform does not. */
	static final String MERGED_ONLY = "merged-only ";

	/** The requirement id every platform's game answers to. */
	static final String MINECRAFT = "minecraft";

	/**
	 * {@link Context#defaultRequire} when the config's default cannot be known: it may come from a {@code parent}.
	 * Never what a mod's own negative value means — that requires nothing, and is read as 0
	 * ({@link KernelGuestMixinAdapter#declaredDefaultRequire}).
	 */
	static final int UNKNOWN_DEFAULT_REQUIRE = -1;

	/**
	 * What one evaluation needs to ask.
	 *
	 * @param raw            the merged base's bytes before the transform chain, by resource path
	 * @param defaultRequire how many injections the config's {@code injectors.defaultRequire} makes native Mixin require,
	 *                       as {@link KernelGuestMixinAdapter#declaredDefaultRequire} reads it: 0 or more — the
	 *                       {@code -1} a mod writes requires none, so it is 0 here — or
	 *                       {@link #UNKNOWN_DEFAULT_REQUIRE} when that cannot be known (a config that may inherit it
	 *                       from a {@code parent})
	 * @param platform       the owning mod's ecosystem, whose game is the native one; null when no single mod owns the
	 *                       config, which asks nothing
	 * @param base           the members digest of the jar serving a class, by internal name; null for no jar
	 * @param mod            what discovery read from the owning mod's manifest — its id and declared requirements;
	 *                       null when that is not known, which asks nothing
	 */
	public record Context(Function<String, byte[]> raw, int defaultRequire, Ecosystem platform,
			Function<String, String> base, DiscoveredMod mod) {
		/** Asks nothing: every caller that predates this. */
		public static final Context NONE = new Context(null, UNKNOWN_DEFAULT_REQUIRE, null, null, null);
	}

	/**
	 * One platform's rows.
	 *
	 * @param methods    owner → {@code name + descriptor} of every method the platform declares there and the merged
	 *                   class does not
	 * @param mergedOnly classes in vanilla's packages the merged base has and the platform does not
	 * @param versions   requirement id → the version of the game the rows describe, from the {@code platform} line
	 */
	record Rows(Map<String, Set<String>> methods, Set<String> mergedOnly, Map<String, String> versions) {
		/** Whether a row names a method {@code name} on {@code owner} — of {@code desc}, or of any when it is null. */
		boolean lists(String owner, String name, String desc) {
			Set<String> declared = methods.get(owner);
			if (declared == null) return false;
			if (desc != null) return declared.contains(name + desc);
			for (String method : declared) if (method.startsWith(name + "(")) return true;
			return false;
		}
	}

	/**
	 * @param base      the members digest the rows were derived against, or null when the table records none
	 * @param libraries the members digests of Minecraft's own library jars, each answered for as it is
	 * @param platforms the platforms the table speaks for, each with its rows
	 */
	record Table(String base, Set<String> libraries, Map<Ecosystem, Rows> platforms) {
		static final Table EMPTY = new Table(null, Set.of(), Map.of());

		/** The census's lines, comments and blanks skipped; a line of no known shape is ignored. */
		static Table parse(List<String> lines) {
			String base = null;
			Set<String> libraries = new HashSet<>();
			Map<Ecosystem, Map<String, String>> declared = new EnumMap<>(Ecosystem.class);
			Map<Ecosystem, Map<String, Set<String>>> methods = new EnumMap<>(Ecosystem.class);
			Map<Ecosystem, Set<String>> mergedOnly = new EnumMap<>(Ecosystem.class);
			for (String raw : lines) {
				String line = raw.strip();
				if (line.isEmpty() || line.startsWith("#")) continue;
				if (line.startsWith(BASE)) {
					base = line.substring(BASE.length()).strip();
					continue;
				}
				if (line.startsWith(LIBRARY)) {
					String[] words = line.substring(LIBRARY.length()).strip().split("\\s+");
					if (!words[0].isEmpty()) libraries.add(words[0]);
					continue;
				}
				if (line.startsWith(PLATFORM)) {
					String[] words = line.substring(PLATFORM.length()).strip().split("\\s+");
					Ecosystem platform = platformNamed(words[0]);
					if (platform == null) continue;
					Map<String, String> versions = new HashMap<>();
					for (int i = 1; i < words.length; i++) {
						int eq = words[i].indexOf('=');
						if (eq > 0 && eq < words[i].length() - 1) versions.put(words[i].substring(0, eq), words[i].substring(eq + 1));
					}
					declared.put(platform, Map.copyOf(versions));
					continue;
				}
				int space = line.indexOf(' ');
				Ecosystem platform = space <= 0 ? null : platformNamed(line.substring(0, space));
				if (platform == null) continue;
				String row = line.substring(space + 1).strip();
				if (row.startsWith(MERGED_ONLY)) {
					mergedOnly.computeIfAbsent(platform, k -> new HashSet<>()).add(row.substring(MERGED_ONLY.length()).strip());
					continue;
				}
				int hash = row.indexOf('#');
				if (hash <= 0 || row.indexOf('(', hash) < 0) continue;
				methods.computeIfAbsent(platform, k -> new HashMap<>())
						.computeIfAbsent(row.substring(0, hash), k -> new HashSet<>()).add(row.substring(hash + 1));
			}
			Map<Ecosystem, Rows> platforms = new EnumMap<>(Ecosystem.class);
			for (Map.Entry<Ecosystem, Map<String, String>> platform : declared.entrySet()) {
				Map<String, Set<String>> rows = new HashMap<>();
				methods.getOrDefault(platform.getKey(), Map.of()).forEach((owner, set) -> rows.put(owner, Set.copyOf(set)));
				platforms.put(platform.getKey(), new Rows(Map.copyOf(rows),
						Set.copyOf(mergedOnly.getOrDefault(platform.getKey(), Set.of())), platform.getValue()));
			}
			return new Table(base == null || base.isEmpty() ? null : base, Set.copyOf(libraries),
					Collections.unmodifiableMap(platforms));
		}

		/** That platform's rows, or null when the table does not speak for it. */
		Rows of(Ecosystem platform) {
			return platform == null ? null : platforms.get(platform);
		}
	}

	private static volatile Table shipped;

	/** The digests a mismatch was already reported for, so a boot says it once. */
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

	/** The mods whose requirement was already reported as outside the table's game, so a boot says it once. */
	private static final Set<String> OUTSIDE = ConcurrentHashMap.newKeySet();

	private NativeAbsentTargets() {
	}

	static boolean asked() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** How a platform's game is named in a log line. */
	static String describe(Ecosystem platform) {
		if (platform == null) return "the mod's own platform";
		return switch (platform) {
			case FABRIC -> "vanilla 26.2";
			case FORGE -> "MinecraftForge's patched 26.2";
			case NEOFORGE -> "NeoForge's patched 26.2";
		};
	}

	/** {@link #describe(Ecosystem)} with the versions the table's {@code platform} line records for it. */
	static String describe(Ecosystem platform, Table table) {
		Rows rows = table.of(platform);
		if (rows == null || rows.versions().isEmpty()) return describe(platform);
		List<String> versions = new ArrayList<>();
		new TreeMap<>(rows.versions()).forEach((id, version) -> versions.add(id + " " + version));
		return describe(platform) + " (" + String.join(", ", versions) + ")";
	}

	/** The id a platform's rows are written under: the ecosystem's family id. */
	static String idOf(Ecosystem platform) {
		return platform.familyId();
	}

	private static Ecosystem platformNamed(String id) {
		for (Ecosystem platform : Ecosystem.values()) if (idOf(platform).equals(id)) return platform;
		return null;
	}

	/** The shipped table, read once; an unreadable table is empty, which makes every miss the merge's again. */
	static Table shipped() {
		Table loaded = shipped;
		if (loaded != null) return loaded;
		List<String> lines = new ArrayList<>();
		try (InputStream in = NativeAbsentTargets.class.getResourceAsStream(TABLE)) {
			if (in == null) {
				ForbricLog.warn("[Forbric/Mixin] %s is missing; no injector target is judged absent from a mod's own platform", TABLE);
				shipped = Table.EMPTY;
				return Table.EMPTY;
			}
			lines.addAll(List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")));
		} catch (IOException unreadable) {
			ForbricLog.warn("[Forbric/Mixin] could not read %s; no injector target is judged absent from a mod's own platform", TABLE);
			shipped = Table.EMPTY;
			return Table.EMPTY;
		}
		shipped = loaded = Table.parse(lines);
		return loaded;
	}

	/**
	 * Whether native Mixin would drop this injector without a word on the mod's own platform too: its selectors all
	 * name methods that platform's {@code owner} does not declare, and nothing makes it count.
	 *
	 * <p>"Nothing makes it count" is the requirement read the way {@code InjectionInfo.parseRequirements} reads it:
	 * its own {@code require} when that is 0 or more, else the config's {@code defaultRequire} — and never for an
	 * injector in a {@code @Group}, whose group needs at least one injection whatever its members' counts say. Under
	 * {@code mixin.debug.countInjections} native Mixin fails on the empty injector too, so nothing is dropped then.
	 *
	 * <p>And only for a mod native to the game the rows describe: its manifest known, of that platform, with no
	 * mandatory {@code minecraft}, {@code forge} or {@code neoforge} range that game's version fails
	 * ({@link #unmetRequirement}).
	 */
	static boolean dropsNatively(MethodNode handler, AnnotationNode injector, List<String> selectors, String owner,
			Context context) {
		return dropsNatively(handler, injector, selectors, owner, context, shipped());
	}

	static boolean dropsNatively(MethodNode handler, AnnotationNode injector, List<String> selectors, String owner,
			Context context, Table table) {
		if (!asked() || context == null || context.raw() == null || context.platform() == null || selectors.isEmpty()) {
			return false;
		}
		if (countsInjections() || nativeMinimum(handler, injector, context.defaultRequire()) != 0) return false;
		for (String selector : selectors) {
			String[] member = plainSelector(selector, owner);
			if (member == null) return false;
			if (!nativeLacks(context.platform(), owner, member[0], member[1], context.raw(), table)) return false;
		}
		// Whose game the rows describe is asked only once they would answer: the line it may log is about this miss. A
		// mod the kernel has no manifest for, or one of another platform, is not shown to be native to it either.
		Rows rows = table.of(context.platform());
		DiscoveredMod mod = context.mod();
		if (rows.versions().get(MINECRAFT) == null || mod == null || mod.getEcosystem() != context.platform()) return false;
		String unmet = unmetRequirement(rows, mod);
		if (unmet != null) {
			if (OUTSIDE.add(mod.getId() + " " + unmet)) {
				ForbricLog.info("[Forbric/Mixin] %s requires %s, which %s does not satisfy, so its own loader would not run "
						+ "it on the game %s describes; an injector target that game lacks is not judged absent from the game "
						+ "the mod was built for, and counts as the merge's miss", mod.getId(), unmet,
						describe(context.platform(), table), TABLE);
			}
			return false;
		}
		// Last, because the first ask reads the whole serving jar once.
		return speaksFor(table, context, owner);
	}

	/**
	 * The mandatory requirement of {@code mod}'s that the game {@code rows} describe fails, as
	 * {@code "<id> <constraint>"}, or null when there is none. A requirement is held against the versions on the table's
	 * {@code platform} line ({@code minecraft}, and {@code forge} or {@code neoforge} for those two) and must be shown to
	 * admit them ({@link VersionPredicate#matchesStrictly}): a range nobody could read is not one that admits the game.
	 */
	static String unmetRequirement(Rows rows, DiscoveredMod mod) {
		for (UnifiedDependency requirement : mod.getDependencies()) {
			if (requirement == null || !requirement.isMandatory() || requirement.getModId() == null) continue;
			String version = rows.versions().get(requirement.getModId().toLowerCase(Locale.ROOT));
			if (version == null) continue;
			if (!VersionPredicate.matchesStrictly(requirement.getVersionConstraint(), version)) {
				return requirement.getModId() + " " + requirement.getVersionConstraint();
			}
		}
		return null;
	}

	/**
	 * How many injections native Mixin requires of this injector, as {@code InjectionInfo.parseRequirements} decides
	 * it: its own {@code require} when that is 0 or more, else the config's default ({@code defaultRequire}, 0 or more
	 * as {@link Context#defaultRequire} carries it); negative when a {@code @Group} decides instead, or when the config's
	 * default could not be known ({@link #UNKNOWN_DEFAULT_REQUIRE}).
	 */
	static int nativeMinimum(MethodNode handler, AnnotationNode injector, int defaultRequire) {
		if (MixinFit.groupOf(handler) != null) return -1;
		Object require = MixinFit.value(injector, "require");
		if (require instanceof Integer declared && declared > -1) return declared;
		return defaultRequire;
	}

	/** {@code MixinEnvironment.Option.DEBUG_INJECTORS}: its own property, or {@code mixin.debug} it inherits from. */
	static boolean countsInjections() {
		return Boolean.parseBoolean(System.getProperty("mixin.debug.countInjections"))
				|| Boolean.parseBoolean(System.getProperty("mixin.debug"));
	}

	/** {@code MemberInfo.validate}'s name rule. */
	private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("(?i)^<?[\\w\\p{Sc}]+>?$");

	/** {@code MemberInfo.validate}'s descriptor rule; {@link #wellFormed} adds the JVM's grammar. */
	private static final java.util.regex.Pattern DESCRIPTOR =
			java.util.regex.Pattern.compile("^(\\([\\w\\p{Sc}\\[/;]*\\))?\\[*[\\w\\p{Sc}/;]+$");

	/**
	 * {@code [name, descriptor or null]} when {@code selector} is a plain name lookup on {@code owner} — what
	 * {@code MemberInfo.parse} reads as a name, an optional descriptor and an owner that is the target itself, with the
	 * default quantifier — else null. Rejected: a dynamic selector ({@code @Id:…}), a regex ({@code /…/}), a
	 * quantifier ({@code *}, {@code +}, {@code {…}}), a dotted or slashed owner in the name, a {@code name:desc} or
	 * {@code ->} tail, another owner, whitespace, and a name or descriptor {@code MemberInfo.validate} refuses or the
	 * JVM could not declare (Mixin fails on those instead of finding nothing).
	 */
	static String[] plainSelector(String selector, String owner) {
		if (selector == null || owner == null) return null;
		String s = selector.strip();
		if (s.isEmpty() || s.startsWith("@") || s.endsWith("/") || s.contains("->")) return null;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (Character.isWhitespace(c) || c == '.' || c == ':' || c == '*' || c == '+' || c == '{' || c == '}'
					|| c == '@' || c == '=') {
				return null;
			}
		}
		int paren = s.indexOf('(');
		int semi = s.indexOf(';');
		if (s.startsWith("L") && semi > 0 && (paren < 0 || semi < paren)) {
			if (!s.substring(1, semi).equals(owner)) return null;
			s = s.substring(semi + 1);
			paren = s.indexOf('(');
		}
		String name = paren < 0 ? s : s.substring(0, paren);
		String desc = paren < 0 ? null : s.substring(paren);
		// MemberInfo.validate's own name and descriptor rules: anything else is an InvalidMemberDescriptorException.
		if (!NAME.matcher(name).matches()) return null;
		if (desc != null && !(DESCRIPTOR.matcher(desc).matches() && wellFormed(desc))) return null;
		return new String[] {name, desc};
	}

	/** Whether {@code desc} is a method descriptor by the JVM's grammar: arguments in parentheses, then a return type. */
	static boolean wellFormed(String desc) {
		if (desc.isEmpty() || desc.charAt(0) != '(') return false;
		int i = 1;
		while (i < desc.length() && desc.charAt(i) != ')') {
			i = fieldType(desc, i);
			if (i < 0) return false;
		}
		if (i >= desc.length()) return false;
		i++;
		if (i < desc.length() && desc.charAt(i) == 'V') return i + 1 == desc.length();
		return fieldType(desc, i) == desc.length();
	}

	/** The index after the field type starting at {@code i}, or -1 when none starts there. */
	private static int fieldType(String desc, int i) {
		while (i < desc.length() && desc.charAt(i) == '[') i++;
		if (i >= desc.length()) return -1;
		char c = desc.charAt(i);
		if ("ZBCSIJFD".indexOf(c) >= 0) return i + 1;
		if (c != 'L') return -1;
		int semi = desc.indexOf(';', i);
		if (semi <= i + 1) return -1;
		for (int k = i + 1; k < semi; k++) if ("()[".indexOf(desc.charAt(k)) >= 0) return -1;
		return semi + 1;
	}

	/**
	 * Whether {@code platform}'s own {@code owner} provably declares no method {@code name} — of descriptor
	 * {@code desc}, or of any when it is null. False whenever that cannot be shown: no platform, one the table does
	 * not speak for, a class outside vanilla's packages or listed as the merged base's alone, a class {@code raw}
	 * cannot serve, a method the raw class still declares, or a row.
	 */
	static boolean nativeLacks(Ecosystem platform, String owner, String name, String desc, Function<String, byte[]> raw,
			Table table) {
		if (platform == null || owner == null || name == null || raw == null) return false;
		Rows rows = table.of(platform);
		if (rows == null) return false;
		if (!inVanillaPackages(owner) || rows.mergedOnly().contains(owner)) return false;
		if (rows.lists(owner, name, desc)) return false;
		ClassNode declared = new ClassNode();
		try {
			byte[] bytes = raw.apply(owner + ".class");
			if (bytes == null) return false;
			new ClassReader(bytes).accept(declared, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		} catch (RuntimeException unreadable) {
			return false;
		}
		if (declared.methods == null) return true;
		for (MethodNode m : declared.methods) {
			if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return false;
		}
		return true;
	}

	/**
	 * Whether the table speaks for the jar serving {@code owner}: that jar's members digest is the one the rows were
	 * derived against ({@link #BASE_PROPERTY} may name another), or one of the Minecraft library jars it lists. Anything
	 * else — another base, a library of another version, a mod's copy, a class served by no jar the kernel can read — is
	 * reported once and answers nothing.
	 */
	static boolean speaksFor(Table table, Context context, String owner) {
		if (context.base() == null) return false;
		String expected = System.getProperty(BASE_PROPERTY);
		if (expected == null || expected.isBlank()) expected = table.base();
		expected = expected == null ? null : expected.strip();
		String running;
		try {
			running = context.base().apply(owner);
		} catch (RuntimeException unreadable) {
			running = null;
		}
		if (running != null && (running.equals(expected) || table.libraries().contains(running))) return true;
		if (REPORTED.add(String.valueOf(running))) ForbricLog.warn(mismatch(owner, running, expected));
		return false;
	}

	/**
	 * The one warning for a class the table does not speak for. It names the serving jar by its members digest and calls
	 * it neither base nor library, because it may be either: a merged base built from other inputs, or a library jar
	 * of another version.
	 */
	static String mismatch(String owner, String running, String expected) {
		return String.format("[Forbric/Mixin] %s is served by %s, which is neither the merged base %s was derived from "
				+ "(members %s) nor one of the Minecraft library jars it lists; no injector target there is judged absent "
				+ "from a mod's own platform, and every such miss counts as the merge's", owner,
				running == null ? "no jar the kernel can read" : "a jar with members " + running, TABLE,
				expected == null ? "not recorded" : expected);
	}

	// --- the members digest -------------------------------------------------------------------------------------------

	/**
	 * A jar's members digest: SHA-256 over every class in vanilla's packages, in name order, each with the digest of
	 * its methods' {@code name + descriptor}, sorted. Bodies, fields and attributes are left out, so a rebuild that
	 * moves only code keeps it; a method gained or lost anywhere changes it.
	 */
	static final class MembersDigest {
		private final TreeMap<String, byte[]> classes = new TreeMap<>();

		/** Adds one jar entry; anything but a class in vanilla's packages is ignored. */
		MembersDigest add(String resource, byte[] bytes) {
			if (!resource.endsWith(".class")) return this;
			String name = resource.substring(0, resource.length() - ".class".length());
			if (!inVanillaPackages(name)) return this;
			List<String> members = new ArrayList<>();
			new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public MethodVisitor visitMethod(int access, String method, String desc, String signature, String[] exceptions) {
					members.add(method + desc);
					return null;
				}
			}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			Collections.sort(members);
			MessageDigest digest = sha256();
			for (String member : members) digest.update((member + "\n").getBytes(StandardCharsets.UTF_8));
			classes.put(name, digest.digest());
			return this;
		}

		String finish() {
			MessageDigest digest = sha256();
			for (Map.Entry<String, byte[]> one : classes.entrySet()) {
				digest.update((one.getKey() + "\n").getBytes(StandardCharsets.UTF_8));
				digest.update(one.getValue());
			}
			return HexFormat.of().formatHex(digest.digest());
		}

		private static MessageDigest sha256() {
			try {
				return MessageDigest.getInstance("SHA-256");
			} catch (NoSuchAlgorithmException absent) {
				throw new IllegalStateException("every Java platform has SHA-256", absent);
			}
		}
	}

	/**
	 * {@link MembersDigest} of every class in a jar: what the table's {@code base} line records for the staged merged
	 * base, and each {@code library} line for one of Minecraft's library jars.
	 */
	public static String membersDigest(Path jar) throws IOException {
		MembersDigest digest = new MembersDigest();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : Collections.list(zip.entries())) {
				if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
				try (InputStream in = zip.getInputStream(entry)) {
					digest.add(entry.getName(), in.readAllBytes());
				}
			}
		}
		return digest.finish();
	}

	/** {@link MembersDigest} of every class in a map of resource path → bytes. */
	static String membersDigest(Map<String, byte[]> resources) {
		MembersDigest digest = new MembersDigest();
		resources.forEach(digest::add);
		return digest.finish();
	}

	/**
	 * The jar a {@code jar:} URL's entry is served from, or null for any other URL: what a class loader's resource
	 * names as the base that serves a class.
	 */
	static Path servingJar(java.net.URL resource) {
		if (resource == null || !"jar".equals(resource.getProtocol())) return null;
		String spec = resource.getPath();
		int bang = spec.indexOf("!/");
		if (bang < 0) return null;
		String file = spec.substring(0, bang);
		try {
			return Path.of(URI.create(file));
		} catch (RuntimeException notAUri) {
			return file.startsWith("file:") ? Path.of(file.substring("file:".length())) : null;
		}
	}
}
