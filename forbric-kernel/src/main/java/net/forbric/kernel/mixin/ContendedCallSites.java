/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.kernel.util.ForbricLog;

/**
 * Two mods that want the same call site, where one mod's injector takes it before the other mod's raw post-Mixin
 * patch looks for it. Reported, never arbitrated.
 *
 * <p>The shape. Mod A's mixin claims a call (or field access) X in method m of class T with an injector that takes the
 * instruction out of m: an {@code @Redirect} swaps it for a call to A's handler, and MixinExtras'
 * {@code @WrapOperation} moves it into a bridge behind A's handler. Mod B patches T with raw ASM from its mixin config
 * plugin's {@code postApply}, scanning m for X. Mixin calls a config plugin's {@code postApply} only after every
 * injector of every mixin on T has been applied ({@code MixinApplicatorStandard.apply}: all {@code preApply}, the
 * applicator passes ending in {@code INJECT_APPLY}, then all {@code postApply}), so B finds no X, and its patch does
 * nothing. Priorities do not enter into it: they order mixins against each other (and decide between two
 * {@code @Redirect}s of one call, where Mixin keeps the first applied and skips the other with "conflict. Skipping"),
 * and every one of them is applied before any {@code postApply} runs.
 *
 * <p>This is the outcome Mixin itself gives the pair, not one the merged base produces: the merge does not change
 * which instruction both mods want or move {@code postApply} relative to the injectors. Fabric runs the same
 * applicator. On NeoForge, FML's simple-processor group runs after {@code neoforge:mixin}
 * ({@code SimpleProcessorsGroup.runsAfter} is {@code COMPUTING_FRAMES} and {@code MIXIN}), so a raw patch shipped as a
 * class processor that runs after that group is handed the same post-Mixin bytes. Forbric runs the real applicator
 * and keeps that order. What it adds is the one thing neither loader does: it says which two mods contended for which
 * call. Picking a winner instead would be a built-in mod priority list, and would make this game differ from what
 * Mixin does with the same two mods anywhere else.
 *
 * <p>All of these must hold, each read off the class Mixin hands to {@code postApply}, or nothing is said:
 * <ul>
 *   <li>a method merged into T from another mod's mixin ({@code @MixinMerged}) is that mixin's injector handler, and
 *       its {@code @At} names X — read from the final mixin the kernel handed Mixin ({@link #remember}), so the claim
 *       is the one that applied, after every adapter, not the one the jar declares;</li>
 *   <li>m calls that handler, and m no longer contains X anywhere (an injector limited by ordinal that left another
 *       occurrence of X is not a contention);</li>
 *   <li>B's {@code postApply} returned with m exactly as it was;</li>
 *   <li>B's own jar holds X's name and X's owner as string constants, which is how a raw patch names the instruction
 *       it scans for. A plugin that patches something else, or names nothing, is left alone.</li>
 * </ul>
 * The other order — a raw patch from {@code preApply}, which runs before the injectors and takes the call first — is
 * not observed here.
 *
 * <p>{@code -Dforbric.contendedCallSites=off} stops the observation and the report. The woven bytes are the same
 * either way: this class never changes a class. {@code =trace} also says, at INFO, why each candidate it turned away
 * was not a contention.
 */
public final class ContendedCallSites {
	public static final String SWITCH = "forbric.contendedCallSites";
	static final String FEATURE = "Contended call site";
	static final String SOURCE = "ContendedCallSites";
	static final String ID_PREFIX = "call-site-contention:";

	private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";

	/** One injector's claim on a call or field access, as the final mixin handed to Mixin declares it. */
	record Claim(String handler, String desc, String kind, MixinFit.Member site) {
	}

	/** A method of the target that calls another mod's handler for a site it no longer contains. */
	record Candidate(String method, String desc, Claim claim, String mixin, String config, String modId,
			List<String> before) {
	}

	/** Mixin class (internal name) → its call-point injectors, from the node the kernel handed Mixin. */
	private static final Map<String, List<Claim>> CLAIMS = new ConcurrentHashMap<>();
	/** The target nodes whose plugin {@code postApply} is running on this thread, and what was seen before it ran. */
	private static final ThreadLocal<Map<Object, List<Candidate>>> PENDING = ThreadLocal.withInitial(IdentityHashMap::new);
	private static final Map<String, Boolean> REPORTED = new ConcurrentHashMap<>();
	/** Code root → {@code owner name} → whether a string constant there names it. */
	private static final Map<String, Map<String, Boolean>> NAMED = new ConcurrentHashMap<>();

	private ContendedCallSites() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	/** {@code -Dforbric.contendedCallSites=trace}: also say, at INFO, why each candidate was not a contention. */
	static boolean tracing() {
		return "trace".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	/** A candidate turned away: said only when tracing, or under {@code -Dforbric.debug}. */
	private static void dismissed(String format, Object... args) {
		if (tracing()) ForbricLog.info(format, args);
		else ForbricLog.debug(format, args);
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The claims, as Mixin was handed them
	// ---------------------------------------------------------------------------------------------------------------

	/**
	 * Records the call-point injectors of {@code mixin}, the final node handed to Mixin. Called where
	 * {@link FinalMixinApplications#remember} is: after every adapter, so a retargeted {@code @At} is read as it
	 * applies.
	 */
	static void remember(ClassNode mixin) {
		if (!enabled() || mixin == null || mixin.methods == null || MixinFit.mixinTargets(mixin).isEmpty()) return;
		List<Claim> claims = new ArrayList<>();
		for (MethodNode method : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(method);
			if (injector == null) continue;
			String kind = injector.desc.substring(injector.desc.lastIndexOf('/') + 1, injector.desc.length() - 1);
			for (AnnotationNode at : MixinFit.atNodes(injector)) {
				String value = MixinFit.asString(MixinFit.value(at, "value"));
				String target = MixinFit.asString(MixinFit.value(at, "target"));
				if (value == null || target == null || !MixinFit.RESOLVABLE_AT.contains(value)) continue;
				MixinFit.Member site = MixinFit.parseMember(target);
				if (site == null || site.name() == null || site.name().isEmpty()) continue;
				claims.add(new Claim(method.name, method.desc, kind, site));
			}
		}
		if (claims.isEmpty()) CLAIMS.remove(mixin.name);
		else CLAIMS.put(mixin.name, List.copyOf(claims));
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Around a plugin's postApply
	// ---------------------------------------------------------------------------------------------------------------

	/**
	 * Before {@code plugin.postApply(target, node, mixin, info)}: the methods of {@code node} where another mod's
	 * injector has already taken a call site out. Never throws.
	 */
	public static void before(Object plugin, String target, Object node, String mixin) {
		try {
			if (!enabled() || !(node instanceof ClassNode targetNode) || CLAIMS.isEmpty()) return;
			Map<Object, List<Candidate>> pending = PENDING.get();
			pending.remove(targetNode);
			List<Candidate> found = candidates(targetNode, ownerOf(mixin));
			if (!found.isEmpty()) pending.put(targetNode, found);
		} catch (Throwable unexpected) {
			ForbricLog.debug("[Forbric/CallSite] could not read %s before %s's postApply: %s", target, mixin, unexpected);
		}
	}

	/**
	 * After {@code postApply} returned: each candidate method the plugin left exactly as it was, for a call its own
	 * jar names, is a contention. Never throws.
	 */
	public static void after(Object plugin, String target, Object node, String mixin) {
		try {
			if (!(node instanceof ClassNode targetNode)) return;
			List<Candidate> found = PENDING.get().remove(targetNode);
			if (found == null || plugin == null) return;
			String patcherConfig = configOf(mixin);
			String patcher = patcherConfig == null ? null : MixinConfigOwners.modIdOf(patcherConfig);
			if (patcher == null) return;
			for (Candidate candidate : found) {
				String where = targetNode.name.replace('/', '.') + "." + candidate.method();
				MethodNode method = method(targetNode, candidate.method(), candidate.desc());
				if (method == null || !fingerprint(method).equals(candidate.before())) {
					dismissed("[Forbric/CallSite] %s: %s's postApply changed the method, so %s taking %s cost it "
							+ "nothing it looked for", where, patcher, candidate.modId(), member(candidate.claim().site()));
					continue;
				}
				if (sameFamily(candidate.modId(), patcher)) continue;
				if (!names(plugin.getClass(), candidate.claim().site())) {
					dismissed("[Forbric/CallSite] %s: %s's jar does not name %s, so its postApply was not looking "
							+ "for the call %s took", where, patcher, member(candidate.claim().site()), candidate.modId());
					continue;
				}
				report(targetNode.name, candidate, patcher, patcherConfig, plugin.getClass().getName());
			}
		} catch (Throwable unexpected) {
			ForbricLog.debug("[Forbric/CallSite] could not read %s after %s's postApply: %s", target, mixin, unexpected);
		}
	}

	/** The plugin threw out of {@code postApply}; whatever {@link #before} saw is no longer anyone's question. */
	public static void forget(Object node) {
		try {
			PENDING.get().remove(node);
		} catch (Throwable ignored) {
			// bookkeeping only
		}
	}

	/** Package-private for the tests: the candidates {@link #before} would hold for a plugin owned by {@code patcher}. */
	static List<Candidate> candidates(ClassNode target, String patcher) {
		if (patcher == null || target.methods == null) return List.of();
		// The merged handlers on this target that are another mod's call-point injector, by name and descriptor.
		Map<String, Claim> claimOf = new LinkedHashMap<>();
		Map<String, String[]> ownerOf = new LinkedHashMap<>();
		for (MethodNode handler : target.methods) {
			String mixin = mergedFrom(handler);
			if (mixin == null) continue;
			String internal = mixin.replace('.', '/');
			List<Claim> claims = CLAIMS.get(internal);
			if (claims == null) continue;
			String config = MixinStubRebind.configOf(internal);
			String modId = config == null ? null : MixinConfigOwners.modIdOf(config);
			if (modId == null || modId.equals(patcher)) continue;
			for (Claim claim : claims) {
				if (!claim.desc().equals(handler.desc)) continue;
				if (!handler.name.equals(claim.handler()) && !handler.name.endsWith("$" + claim.handler())) continue;
				String key = handler.name + handler.desc;
				claimOf.putIfAbsent(key, claim);
				ownerOf.putIfAbsent(key, new String[] { mixin, config, modId });
			}
		}
		if (claimOf.isEmpty()) return List.of();

		List<Candidate> out = new ArrayList<>();
		for (MethodNode method : target.methods) {
			if (method.instructions == null || method.instructions.size() == 0) continue;
			Set<String> called = new LinkedHashSet<>();
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(target.name)
						&& claimOf.containsKey(call.name + call.desc)) {
					called.add(call.name + call.desc);
				}
			}
			for (String key : called) {
				if (key.equals(method.name + method.desc)) continue;
				Claim claim = claimOf.get(key);
				if (contains(method, claim.site())) continue;
				String[] owner = ownerOf.get(key);
				out.add(new Candidate(method.name, method.desc, claim, owner[0], owner[1], owner[2], fingerprint(method)));
			}
		}
		return out;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Reading the class
	// ---------------------------------------------------------------------------------------------------------------

	/** The mixin {@code @MixinMerged} says {@code method} came from, dotted, or null. */
	private static String mergedFrom(MethodNode method) {
		for (List<AnnotationNode> table : java.util.Arrays.asList(method.visibleAnnotations, method.invisibleAnnotations)) {
			if (table == null) continue;
			for (AnnotationNode annotation : table) {
				if (MERGED.equals(annotation.desc) && MixinFit.value(annotation, "mixin") instanceof String mixin) return mixin;
			}
		}
		return null;
	}

	/** Whether {@code method} still contains an instruction for {@code site}. An owner or descriptor left out matches any. */
	static boolean contains(MethodNode method, MixinFit.Member site) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && matches(site, call.owner, call.name, call.desc)) return true;
			if (insn instanceof FieldInsnNode field && matches(site, field.owner, field.name, field.desc)) return true;
		}
		return false;
	}

	private static boolean matches(MixinFit.Member site, String owner, String name, String desc) {
		if (!site.name().equals(name)) return false;
		if (site.owner() != null && !site.owner().equals(owner)) return false;
		return site.desc() == null || site.desc().equals(desc);
	}

	private static MethodNode method(ClassNode target, String name, String desc) {
		for (MethodNode method : target.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}

	/**
	 * Every instruction of {@code method} as text, labels as their position: a patch that touches the method at all,
	 * in place or by replacing nodes, changes this. Line numbers and frames are left out.
	 */
	static List<String> fingerprint(MethodNode method) {
		Map<LabelNode, Integer> labels = new IdentityHashMap<>();
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof LabelNode label) labels.put(label, labels.size());
		Function<LabelNode, String> at = label -> "@" + labels.getOrDefault(label, -1);
		List<String> out = new ArrayList<>(method.instructions.size());
		for (AbstractInsnNode insn : method.instructions) {
			String op = String.valueOf(insn.getOpcode());
			if (insn instanceof LineNumberNode || insn instanceof FrameNode) continue;
			if (insn instanceof LabelNode label) out.add("label" + at.apply(label));
			else if (insn instanceof MethodInsnNode m) out.add(op + " " + m.owner + "." + m.name + m.desc + (m.itf ? " itf" : ""));
			else if (insn instanceof FieldInsnNode f) out.add(op + " " + f.owner + "." + f.name + ":" + f.desc);
			else if (insn instanceof LdcInsnNode l) out.add("ldc " + (l.cst == null ? "null" : l.cst.getClass().getSimpleName() + ":" + l.cst));
			else if (insn instanceof TypeInsnNode t) out.add(op + " " + t.desc);
			else if (insn instanceof VarInsnNode v) out.add(op + " " + v.var);
			else if (insn instanceof IntInsnNode i) out.add(op + " " + i.operand);
			else if (insn instanceof IincInsnNode i) out.add("iinc " + i.var + " " + i.incr);
			else if (insn instanceof JumpInsnNode j) out.add(op + " " + at.apply(j.label));
			else if (insn instanceof InvokeDynamicInsnNode d) {
				out.add("indy " + d.name + d.desc + " " + d.bsm + " " + java.util.Arrays.deepToString(d.bsmArgs));
			} else if (insn instanceof MultiANewArrayInsnNode a) out.add(op + " " + a.desc + " " + a.dims);
			else if (insn instanceof TableSwitchInsnNode s) {
				StringBuilder row = new StringBuilder(op + " " + s.min + ".." + s.max + " " + at.apply(s.dflt));
				for (LabelNode label : s.labels) row.append(' ').append(at.apply(label));
				out.add(row.toString());
			} else if (insn instanceof LookupSwitchInsnNode s) {
				StringBuilder row = new StringBuilder(op + " " + s.keys + " " + at.apply(s.dflt));
				for (LabelNode label : s.labels) row.append(' ').append(at.apply(label));
				out.add(row.toString());
			} else out.add(op);
		}
		return out;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Whether the patcher looks for the site
	// ---------------------------------------------------------------------------------------------------------------

	/**
	 * Whether the jar (or class directory) {@code type} was defined from holds {@code site}'s name, and its owner when
	 * the site names one, as string constants. Read once per jar and site; false when the code source is not a local
	 * file, so a plugin whose origin cannot be read is never accused.
	 */
	static boolean names(Class<?> type, MixinFit.Member site) {
		Path root = codeRoot(type);
		if (root == null) return false;
		Map<String, Boolean> answers = NAMED.computeIfAbsent(root.toString(), k -> new ConcurrentHashMap<>());
		return answers.computeIfAbsent(site.owner() + " " + site.name(), k -> scan(root, site));
	}

	/** Package-private for the tests: {@link #names} without the class or the cache. */
	static boolean scan(Path root, MixinFit.Member site) {
		Set<String> owners = site.owner() == null ? Set.of()
				: Set.of(site.owner(), site.owner().replace('/', '.'), "L" + site.owner() + ";");
		boolean[] seen = { false, site.owner() == null };
		Predicate<byte[]> done = bytes -> {
			stringConstants(bytes, constant -> {
				if (constant.equals(site.name())) seen[0] = true;
				if (owners.contains(constant)) seen[1] = true;
			});
			return seen[0] && seen[1];
		};
		try {
			if (Files.isDirectory(root)) {
				try (Stream<Path> walk = Files.walk(root)) {
					for (Path file : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".class"))::iterator) {
						if (done.test(Files.readAllBytes(file))) return true;
					}
				}
				return false;
			}
			try (ZipFile zip = new ZipFile(root.toFile())) {
				for (var entries = zip.entries(); entries.hasMoreElements(); ) {
					ZipEntry entry = entries.nextElement();
					if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
					try (var in = zip.getInputStream(entry)) {
						if (done.test(in.readAllBytes())) return true;
					}
				}
			}
		} catch (IOException | RuntimeException unreadable) {
			ForbricLog.debug("[Forbric/CallSite] could not read %s: %s", root, unreadable);
		}
		return false;
	}

	/** Every {@code CONSTANT_String} in {@code classBytes}: what {@code ldc} pushes, read off the constant pool alone. */
	static void stringConstants(byte[] classBytes, java.util.function.Consumer<String> sink) {
		ClassReader reader;
		try {
			reader = new ClassReader(classBytes);
		} catch (RuntimeException notAClass) {
			return;
		}
		char[] buffer = new char[reader.getMaxStringLength()];
		for (int i = 1; i < reader.getItemCount(); i++) {
			int offset = reader.getItem(i);
			if (offset > 0 && reader.readByte(offset - 1) == 8) sink.accept(reader.readUTF8(offset, buffer));
		}
	}

	/** The jar or class directory {@code type} came from, or null. */
	static Path codeRoot(Class<?> type) {
		try {
			ProtectionDomain domain = type.getProtectionDomain();
			CodeSource source = domain == null ? null : domain.getCodeSource();
			URL location = source == null ? null : source.getLocation();
			if (location == null || !"file".equals(location.getProtocol())) return null;
			Path path;
			try {
				path = Path.of(location.toURI());
			} catch (java.net.URISyntaxException | IllegalArgumentException unencoded) {
				// A location spelled with raw spaces, brackets or non-ASCII (mod jars are named like that) is not a
				// valid URI as it stands; quote its path rather than decode it, since '+' is literal in a file name.
				path = Path.of(new java.net.URI("file", null, location.getPath(), null));
			}
			String spelling = path.toString().replace('\\', '/');
			String entry = type.getName().replace('.', '/') + ".class";
			// A class defined out of a directory has its own file as its code source (ForbricClassLoader.jarUrlOf).
			if (spelling.endsWith("/" + entry)) return Path.of(spelling.substring(0, spelling.length() - entry.length()));
			return Files.exists(path) ? path : null;
		} catch (Exception unreadable) {
			return null;
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Owners and the report
	// ---------------------------------------------------------------------------------------------------------------

	private static String configOf(String mixin) {
		return mixin == null ? null : MixinStubRebind.configOf(mixin.replace('.', '/'));
	}

	private static String ownerOf(String mixin) {
		String config = configOf(mixin);
		return config == null ? null : MixinConfigOwners.modIdOf(config);
	}

	/** One mod, or one jar a player installed that carries both: its own business, as for {@link MixinOverlapLint}. */
	private static boolean sameFamily(String a, String b) {
		if (a.equals(b)) return true;
		// Read when a report is about to be made, which is rare, so a catalog published late is still seen.
		Function<String, String> family = MixinOverlapLint.installedAs(net.forbric.api.ModCatalog.everything());
		return family.apply(a).equals(family.apply(b));
	}

	static String spell(MixinFit.Member site) {
		String desc = site.desc() == null ? "" : site.desc().startsWith("(") ? site.desc() : ":" + site.desc();
		return (site.owner() == null ? "" : "L" + site.owner() + ";") + site.name() + desc;
	}

	private static String member(MixinFit.Member site) {
		return (site.owner() == null ? "" : site.owner().substring(site.owner().lastIndexOf('/') + 1) + ".") + site.name();
	}

	/** The WARN line, which gate-m9 reads; package-private so its test can hold the gate's pattern against it. */
	static String line(String where, String call, String injecting, String kind, String injector, String patcher,
			String plugin) {
		return String.format("[Forbric/CallSite] contended call site %s -> %s: %s's %s (%s) took the call before %s's "
				+ "post-Mixin patch (%s.postApply) ran, and that patch changed nothing in the method. Mixin applies every "
				+ "injector before any config plugin's postApply, so wherever both mods run under Mixin the call goes to %s; "
				+ "Forbric picks no winner (-D%s=off stops this report)",
				where, call, injecting, kind, injector, patcher, plugin, injecting, SWITCH);
	}

	private static void report(String target, Candidate c, String patcher, String patcherConfig, String plugin) {
		String id = ID_PREFIX + target.replace('/', '.') + "." + c.method() + c.desc() + "@" + spell(c.claim().site());
		if (REPORTED.putIfAbsent(id + "|" + c.modId() + "|" + patcher, Boolean.TRUE) != null) return;
		String where = target.replace('/', '.') + "." + c.method();
		String call = member(c.claim().site());
		String kind = "@" + c.claim().kind();
		String injector = c.config() + ":" + c.mixin().substring(c.mixin().lastIndexOf('.') + 1) + "." + c.claim().handler();
		ForbricLog.warn("%s", line(where, call, c.modId(), kind, injector, patcher, plugin));
		List<String> evidence = List.of("target=" + where + c.desc(), "site=" + spell(c.claim().site()),
				"injector=" + c.modId() + " " + kind + " " + injector, "patcher=" + patcher + " " + patcherConfig + " " + plugin + ".postApply");
		CompatibilityFindings.record(new CompatibilityFinding(id, patcher, FEATURE, SOURCE,
				CompatibilityFinding.Confidence.SUSPECTED, false,
				String.format("its post-Mixin patch of %s looks for the call to %s, which %s's %s had already taken: Mixin "
						+ "applies every injector before a config plugin's postApply, so the patch changed nothing there, as it "
						+ "would on its own loader", where, call, c.modId(), kind), evidence));
		CompatibilityFindings.record(new CompatibilityFinding(id, c.modId(), FEATURE, SOURCE,
				CompatibilityFinding.Confidence.SUSPECTED, false,
				String.format("its %s of the call to %s in %s takes the call site %s's post-Mixin patch looks for; that "
						+ "patch changed nothing there", kind, call, where, patcher), evidence));
	}

	/** Test seam: what {@link #remember} recorded for {@code mixin} (internal name). */
	static List<Claim> claimsOf(String mixin) {
		return CLAIMS.getOrDefault(mixin, List.of());
	}

	/** Test seam. */
	static void reset() {
		CLAIMS.clear();
		REPORTED.clear();
		NAMED.clear();
		PENDING.remove();
	}
}
