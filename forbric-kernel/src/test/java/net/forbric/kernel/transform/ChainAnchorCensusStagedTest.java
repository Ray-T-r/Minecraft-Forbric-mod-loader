/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.mixin.NativeGameReferences;

/**
 * Every transformer KernelBoot.launch registers, replayed over NeoForge's whole base and carrier on each side, lands each of
 * its REQUIRED anchors (decision D31 of Stage 4: the Stage 2.0 every-chain-transformer anchor census, as a test).
 *
 * <p>The boot's own ledger ({@code [Forbric/Anchor] … landed; … never loaded on this side}) judges only the classes one
 * boot happens to load: a repair whose class a zero-mod boot never defines is "never loaded", whatever it would do. This
 * feeds every class of the staged base and the NeoForge carrier, in jar order, through a {@link TransformChain} built as
 * the launch builds it, so a REQUIRED anchor is either edited (HIT) or a defect (declined, or its class missing from the
 * game). The registrations are recovered from {@code KernelBoot.launch}'s own bytecode, not copied: a transformer added
 * there is replayed here, and one this cannot build fails the test until it gets a recipe.
 *
 * <p>Built as at a default launch with no mods: every switch at its default, the transfer and hopper repairs included
 * (a boot registers those when fabric-transfer-api is installed), the client-only ones on the client only. Not built:
 * what a game-only census has nothing for (mod access wideners, NeoForge enum extensions declared by mods) — {@link
 * #SKIPPED} says which and why. The vanilla twins copy vanilla's bodies from the vanilla-reference sidecar, as the launch
 * does ({@link TestFixtures#launchSources}). Each side's verdict per anchor is written to
 * {@code build/chain-anchor-census/<side>.tsv}.
 *
 * <p>The red check: the same census with one transformer silenced (it hands every class back unchanged) names that
 * transformer's REQUIRED anchors as declined, and only its, so the assertion can fail.
 */
@ResourceLock("native-game-references")
class ChainAnchorCensusStagedTest {
	private static final String BOOT = "net/forbric/kernel/boot/KernelBoot";
	private static final String CHAIN = "net/forbric/kernel/transform/TransformChain";

	/** Registration sites a default no-mod launch does not register, by the produced type's simple name, with why. */
	private static final Map<String, String> SKIPPED = Map.of(
			"ClassTweakerTransformer", "built from the installed mods' access wideners: none in a game-only census",
			"NeoEnumExtensionInjector", "registered only when an installed mod declares NeoForge enum extensions");
	/** Registered on the client only (KernelBoot guards each with side == CLIENT). */
	private static final Set<String> CLIENT_ONLY = Set.of("HudContextQueryInjector", "CreativePagerBridgeInjector");

	private static Map<EnvType, Census> censuses;

	@BeforeAll static void runCensus() throws Exception {
		Path base = TestFixtures.stagedBase(), carrier = TestFixtures.neoCarrier();
		TestFixtures.requireFiles(Fixture.STAGED, "NeoForge's base and carrier", base, carrier);
		NativeGameReferences.configure(TestFixtures.launchSources(base));
		censuses = new EnumMap<>(EnvType.class);
		for (EnvType side : EnvType.values()) censuses.put(side, census(side, List.of(base, carrier), null));
	}

	@AfterAll static void forget() throws Exception {
		NativeGameReferences.configure(null);
		setActive(null);
	}

	@Test void everyRegistrationIsBuiltOrSkippedForAStatedReason() {
		for (Census census : censuses.values()) {
			assertTrue(census.sites() >= 80, "KernelBoot.launch's registrations were not recovered: " + census.sites());
			assertEquals(List.of(), census.unbuilt(), census.side() + ": registrations this census cannot build; give each a recipe");
			for (String skipped : census.skipped()) {
				assertTrue(SKIPPED.keySet().stream().anyMatch(skipped::contains)
						|| census.side() == EnvType.SERVER && CLIENT_ONLY.stream().anyMatch(skipped::contains),
						census.side() + ": skipped without a stated reason: " + skipped);
			}
		}
	}

	@Test void everyRequiredAnchorLandsOnNeoForgesBase() throws IOException {
		for (Census census : censuses.values()) {
			census.write(Path.of(System.getProperty("user.dir"), "build", "chain-anchor-census"));
			assertTrue(census.report().declared() > 100, census.side() + ": " + census.report().declared() + " anchors declared");
			assertEquals(List.of(), census.report().misses(), census.side() + ": REQUIRED anchors declined on NeoForge's base");
			assertEquals(List.of(), census.missingRequired(), census.side() + ": REQUIRED anchors on classes the game does not have");
			assertEquals(List.of(), census.errors(), census.side() + ": a transformer threw");
		}
	}

	/** A hedge is carried for a carrier that may grow the method; none of today's finds nothing, as the boot line says. */
	@Test void noHedgeIsIdle() {
		for (Census census : censuses.values()) assertEquals(List.of(), census.report().hedged(), census.side() + ": idle hedges");
	}

	@Test void aSilencedTransformerIsNamed() throws Exception {
		Census server = censuses.get(EnvType.SERVER);
		String silenced = server.firstWithRequiredHits();
		assertFalse(silenced == null, "no transformer with a landed REQUIRED anchor to silence");
		Census red = census(EnvType.SERVER, List.of(TestFixtures.stagedBase(), TestFixtures.neoCarrier()), silenced);
		assertFalse(red.report().misses().isEmpty(), "silencing " + silenced + " must decline its anchors");
		assertTrue(red.report().misses().stream().allMatch(m -> m.transformer().equals(silenced)),
				"only the silenced transformer is named: " + red.report().misses());
	}

	// ------------------------------------------------------------------------------------------------------------
	// One census
	// ------------------------------------------------------------------------------------------------------------

	record Census(EnvType side, int sites, List<String> skipped, List<String> unbuilt, AnchorLedger.Report report,
			List<String> missingRequired, List<String> errors, List<Rec> recs) {
		/** The name of the first transformer with a REQUIRED anchor that landed. */
		String firstWithRequiredHits() {
			for (Rec rec : recs) {
				for (AnchorSet.Anchor anchor : rec.anchorList) {
					if (anchor.severity() != AnchorSet.Severity.HEDGE && rec.edited.contains(anchor.binaryName())) return rec.name();
				}
			}
			return null;
		}

		void write(Path dir) throws IOException {
			Files.createDirectories(dir);
			try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(dir.resolve(side.name().toLowerCase() + ".tsv")))) {
				w.println("transformer\tkind\tid\tclass\tseverity\tverdict");
				for (Rec r : recs) {
					for (AnchorSet.Anchor a : r.anchorList) {
						w.println(String.join("\t", r.name(), "ANCHOR", r.name(), a.binaryName(), a.severity().name(),
								verdict(r.seen.contains(a.binaryName()), r.edited.contains(a.binaryName()), a.severity())));
					}
					for (ClassTransformer.Claim c : r.claimList) {
						for (AnchorSet.Anchor a : c.anchors().anchors()) {
							w.println(String.join("\t", r.name(), "CLAIM", c.id(), a.binaryName(), a.severity().name(),
									verdict(r.seen.contains(a.binaryName()), r.claimHits.getOrDefault(c.id(), Set.of()).contains(a.binaryName()),
											a.severity())));
						}
					}
				}
				for (String s : skipped) w.println("#skipped\t" + s);
			}
		}
	}

	/** Whether {@code binaryName} is in a package of the game itself (vanilla's, or NeoForge's base and carrier). */
	static boolean gamePackage(String binaryName) {
		return binaryName.startsWith("net.minecraft.") || binaryName.startsWith("com.mojang.") || binaryName.startsWith("net.neoforged.");
	}

	private static String verdict(boolean seen, boolean edited, AnchorSet.Severity severity) {
		if (edited) return "HIT";
		if (!seen) return "ABSENT";
		return severity == AnchorSet.Severity.HEDGE ? "HEDGE_DECLINED" : "DECLINED";
	}

	static Census census(EnvType side, List<Path> jarPaths, String silenced) throws Exception {
		try (Jars jars = new Jars(jarPaths)) {
			setActive(jars);
			List<Site> sites = sites();
			TransformChain chain = new TransformChain();
			List<Rec> recs = new ArrayList<>();
			List<String> skipped = new ArrayList<>(), unbuilt = new ArrayList<>();
			Object accessWideners = null, forgeAts = null;
			for (Site s : sites) {
				String simple = s.type().substring(s.type().lastIndexOf('/') + 1);
				if (SKIPPED.containsKey(simple)) {
					skipped.add(s.order() + " " + simple + ": " + SKIPPED.get(simple));
					continue;
				}
				if (side == EnvType.SERVER && CLIENT_ONLY.contains(simple)) {
					skipped.add(s.order() + " " + simple + ": client only");
					continue;
				}
				Object t;
				try {
					if (s.producer().equals("new")) {
						t = simple.equals("RestoredAccessTransformer")
								? new net.forbric.kernel.access.RestoredAccessTransformer(
										(net.forbric.kernel.access.ClassTweakerTransformer) accessWideners,
										(net.forbric.kernel.access.AccessTransformer) forgeAts)
								: newInstance(s.type(), s.initDesc(), jars);
					} else {
						t = fromFactory(s, jars, side, jarPaths.subList(1, jarPaths.size()));
					}
				} catch (Throwable e) {
					Throwable root = e;
					while (root.getCause() != null) root = root.getCause();
					unbuilt.add(s.order() + " " + s.type() + " (" + s.producer() + "): " + root);
					continue;
				}
				if (t == null) {
					unbuilt.add(s.order() + " " + s.type() + ": its factory built nothing at a default launch");
					continue;
				}
				if (s.producer().contains("forgeFamilyAccessTransformer")) forgeAts = t;
				Rec rec = new Rec((ClassTransformer) t, silenced != null && silenced.equals(((ClassTransformer) t).name()));
				recs.add(rec);
				chain.register(TransformPhase.valueOf(s.phase()), rec, s.sortIndex() == Integer.MIN_VALUE ? 0 : s.sortIndex());
			}
			// The launch's protocol extensions, which register into the chain themselves (none ships a transformer today).
			net.forbric.api.ProtocolExtensions.forLoader(ChainAnchorCensusStagedTest.class.getClassLoader())
					.registerTransformers(net.forbric.kernel.interop.protocol.ProtocolTransformAdapters.registry(chain));

			TransformContext ctx = new TransformContext(side, false, "named");
			for (String internal : jars.classes()) chain.applyBeforeMixin(internal.replace('/', '.'), jars.bytes(internal), ctx);

			List<String> missing = new ArrayList<>(), errors = new ArrayList<>();
			for (Rec r : recs) {
				r.anchorList = List.copyOf(r.anchors().anchors());
				r.claimList = List.copyOf(r.claims());
				for (AnchorSet.Anchor a : r.anchorList) {
					// A class of the game's own packages; an anchor on a guest mod's class (fabric-api's
					// FabricBlockStateModel, say) is absent from a game-only census by design and only listed in the TSV.
					if (a.severity() != AnchorSet.Severity.HEDGE && !r.seen.contains(a.binaryName()) && gamePackage(a.binaryName())) {
						missing.add(r.name() + " -> " + a.binaryName() + " (" + a.severity() + ")");
					}
				}
				r.errors.forEach((c, e) -> errors.add(r.name() + " on " + c + ": " + e));
			}
			return new Census(side, sites.size(), skipped, unbuilt, chain.ledger().report(), missing, errors, recs);
		} finally {
			setActive(null);
		}
	}

	/** Records what one transformer saw and edited; when silenced, edits nothing. */
	static final class Rec implements ClassTransformer {
		final ClassTransformer inner;
		final boolean silenced;
		final Set<String> seen = new TreeSet<>(), edited = new TreeSet<>();
		final Map<String, Set<String>> claimHits = new TreeMap<>();
		final Map<String, String> errors = new TreeMap<>();
		/** Its anchors and claims, read while the census's jars are open (some answer from the game's classes). */
		List<AnchorSet.Anchor> anchorList = List.of();
		List<ClassTransformer.Claim> claimList = List.of();

		Rec(ClassTransformer inner, boolean silenced) {
			this.inner = inner;
			this.silenced = silenced;
		}

		@Override public String name() { return inner.name(); }
		@Override public AnchorSet anchors() { return inner.anchors(); }
		@Override public List<Claim> claims() { return inner.claims(); }

		@Override
		public byte[] transform(String className, byte[] classBytes, TransformContext context) {
			return transform(className, classBytes, context, ClaimReporter.NONE);
		}

		@Override
		public byte[] transform(String className, byte[] classBytes, TransformContext context, ClaimReporter reporter) {
			seen.add(className);
			if (silenced) return classBytes;
			byte[] out;
			try {
				out = inner.transform(className, classBytes, context, id -> {
					claimHits.computeIfAbsent(id, k -> new TreeSet<>()).add(className);
					reporter.hit(id);
				});
			} catch (Throwable t) {
				errors.putIfAbsent(className, t.toString());
				return classBytes;
			}
			if (out != null && out != classBytes) edited.add(className);
			return out;
		}
	}

	// ------------------------------------------------------------------------------------------------------------
	// The registrations, from KernelBoot.launch's bytecode
	// ------------------------------------------------------------------------------------------------------------

	/** One chain.register call site in launch(). */
	record Site(int order, String phase, int sortIndex, String producer, String type, String initDesc) {
	}

	static List<Site> sites() throws Exception {
		ClassNode boot = new ClassNode();
		try (InputStream in = ChainAnchorCensusStagedTest.class.getClassLoader().getResourceAsStream(BOOT + ".class")) {
			new ClassReader(in.readAllBytes()).accept(boot, 0);
		}
		MethodNode launch = null;
		for (MethodNode m : boot.methods) if (m.name.equals("launch") && m.instructions.size() > 0) launch = m;
		if (launch == null) throw new IllegalStateException("KernelBoot.launch not found");
		Frame<SourceValue>[] frames = new Analyzer<>(new SourceInterpreter()).analyze(BOOT, launch);
		AbstractInsnNode[] code = launch.instructions.toArray();
		List<Site> out = new ArrayList<>();
		for (int i = 0; i < code.length; i++) {
			if (!(code[i] instanceof MethodInsnNode call) || !call.owner.equals(CHAIN) || !call.name.equals("register")) continue;
			Frame<SourceValue> f = frames[i];
			if (f == null) continue;
			org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(call.desc);
			int top = f.getStackSize();
			int sortIndex = args.length >= 3 ? intConstant(resolve(first(f.getStack(top - args.length + 2)), frames, launch)) : 0;
			AbstractInsnNode p = resolve(first(f.getStack(top - args.length)), frames, launch);
			String phase = p instanceof FieldInsnNode fi ? fi.name : "?";
			AbstractInsnNode producer = resolve(first(f.getStack(top - args.length + 1)), frames, launch);
			String desc, type, initDesc = null;
			if (producer instanceof TypeInsnNode tn && tn.getOpcode() == Opcodes.NEW) {
				desc = "new";
				type = tn.desc;
				int depth = 0;
				for (AbstractInsnNode x = tn.getNext(); x != null; x = x.getNext()) {
					if (x instanceof TypeInsnNode n2 && n2.getOpcode() == Opcodes.NEW && n2.desc.equals(type)) depth++;
					if (x instanceof MethodInsnNode init && init.getOpcode() == Opcodes.INVOKESPECIAL && init.name.equals("<init>")
							&& init.owner.equals(type)) {
						if (depth == 0) {
							initDesc = init.desc;
							break;
						}
						depth--;
					}
				}
			} else if (producer instanceof MethodInsnNode mi) {
				desc = "call:" + mi.owner + "." + mi.name + mi.desc;
				type = org.objectweb.asm.Type.getReturnType(mi.desc).getInternalName();
			} else {
				desc = "unknown:" + (producer == null ? "null" : producer.getClass().getSimpleName() + "/" + producer.getOpcode());
				type = "?";
			}
			out.add(new Site(out.size(), phase, sortIndex, desc, type, initDesc));
		}
		return out;
	}

	private static AbstractInsnNode first(SourceValue v) {
		return v.insns.iterator().next();
	}

	/** Follows DUP / ALOAD-ASTORE / CHECKCAST back to the instruction that really made the value. */
	private static AbstractInsnNode resolve(AbstractInsnNode insn, Frame<SourceValue>[] frames, MethodNode m) {
		for (int guard = 0; guard < 64 && insn != null; guard++) {
			Frame<SourceValue> f = frames[m.instructions.indexOf(insn)];
			int op = insn.getOpcode();
			if (op == Opcodes.DUP || op == Opcodes.CHECKCAST) insn = first(f.getStack(f.getStackSize() - 1));
			else if (op == Opcodes.ALOAD || op == Opcodes.ILOAD) insn = first(f.getLocal(((VarInsnNode) insn).var));
			else if (op == Opcodes.ASTORE || op == Opcodes.ISTORE) insn = first(f.getStack(f.getStackSize() - 1));
			else return insn;
		}
		return insn;
	}

	private static int intConstant(AbstractInsnNode insn) {
		if (insn == null) return 0;
		int op = insn.getOpcode();
		if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return op - Opcodes.ICONST_0;
		if (insn instanceof IntInsnNode ii) return ii.operand;
		if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer n) return n;
		return Integer.MIN_VALUE;
	}

	// ------------------------------------------------------------------------------------------------------------
	// Building each transformer as launch() does, with the staged jars as the game's resources
	// ------------------------------------------------------------------------------------------------------------

	static Object argFor(Type t, Class<?> raw, Jars jars, String forType) {
		if (raw == boolean.class) return true;
		if (raw == int.class) return 0;
		if (raw == BooleanSupplier.class) return (BooleanSupplier) () -> true;
		if (raw == ClassLoader.class) return jars.loader();
		if (raw == Function.class && t instanceof ParameterizedType pt) {
			Type out = pt.getActualTypeArguments()[1];
			if (out == byte[].class) return (Function<String, byte[]>) jars::lookup;
			if (out == ClassNode.class) return (Function<String, ClassNode>) jars::node;
			if (out == Boolean.class) return (Function<String, Boolean>) n -> jars.bytes(n) != null;
			return (Function<String, Object>) n -> null;
		}
		if (raw == BiFunction.class) return (BiFunction<Object, Object, Object>) (x, y) -> null;
		if (raw == Supplier.class) return (Supplier<Object>) Set::of;
		if (raw == Set.class) return Set.of();
		if (raw == List.class) return List.of();
		if (raw == Predicate.class) return (Predicate<String>) n -> jars.bytes(n) != null;
		throw new IllegalArgumentException("no argument recipe for " + t + " in " + forType);
	}

	static Object newInstance(String internal, String initDesc, Jars jars) throws Exception {
		Class<?> type = Class.forName(internal.replace('/', '.'));
		if (type == VanillaMemberTwinInjector.class) {
			// As the launch builds it: vanilla's bodies through this launch's native-reference sources (the sidecar).
			return new VanillaMemberTwinInjector(VanillaMemberTwinInjector.vanillaReferences(jars::lookup));
		}
		Constructor<?> best = null;
		for (Constructor<?> c : type.getDeclaredConstructors()) {
			if (org.objectweb.asm.Type.getConstructorDescriptor(c).equals(initDesc)) best = c;
		}
		if (best == null) throw new NoSuchMethodException(internal + ".<init>" + initDesc);
		best.setAccessible(true);
		Type[] g = best.getGenericParameterTypes();
		Class<?>[] r = best.getParameterTypes();
		Object[] a = new Object[r.length];
		for (int i = 0; i < r.length; i++) a[i] = argFor(g[i], r[i], jars, internal);
		return best.newInstance(a);
	}

	/** Factory calls in launch(), reproduced with the staged jars standing in for the game loader. */
	static Object fromFactory(Site s, Jars jars, EnvType side, List<Path> runtimeJars) throws Exception {
		String p = s.producer();
		Class<?> sideType = Class.forName("net.forbric.kernel.boot.KernelBoot$Side");
		Object sideValue = sideType.getField(side.name()).get(null);
		if (p.contains("EnvironmentStripTransformer.configured")) return EnvironmentStripTransformer.configured(n -> null);
		if (p.contains("KernelBoot.forgeFamilyAccessTransformer")) {
			Method m = Class.forName("net.forbric.kernel.boot.KernelBoot").getDeclaredMethod("forgeFamilyAccessTransformer", List.class, List.class);
			m.setAccessible(true);
			return m.invoke(null, List.of(), runtimeJars);
		}
		if (p.contains("KernelBoot$Side.injector")) {
			Method m = sideType.getDeclaredMethod("injector");
			m.setAccessible(true);
			return m.invoke(sideValue);
		}
		if (p.contains("KernelBoot.neuters")) {
			Method m = Class.forName("net.forbric.kernel.boot.KernelBoot").getDeclaredMethod("neuters", sideType);
			m.setAccessible(true);
			return m.invoke(null, sideValue);
		}
		if (p.contains(".create(Ljava/lang/ClassLoader;)")) {
			String owner = p.substring("call:".length(), p.indexOf(".create("));
			return Class.forName(owner.replace('/', '.')).getMethod("create", ClassLoader.class).invoke(null, jars.loader());
		}
		throw new IllegalArgumentException("no recipe for factory " + p);
	}

	/** What ForbricMixinService.bind does at launch: the active native-reference reader over the game's resources. */
	private static void setActive(Jars jars) throws Exception {
		var active = NativeGameReferences.class.getDeclaredField("active");
		active.setAccessible(true);
		active.set(null, jars == null ? null
				: new NativeGameReferences(jars::lookup, jars::bytes, TestFixtures.launchSources(TestFixtures.stagedBase())));
	}

	/** The staged jars, read as the game loader would: first jar wins. */
	static final class Jars implements AutoCloseable {
		final List<Path> paths;
		final List<ZipFile> zips = new ArrayList<>();
		private URLClassLoader loader;

		Jars(List<Path> paths) throws IOException {
			this.paths = paths;
			for (Path p : paths) zips.add(new ZipFile(p.toFile()));
		}

		/** A resource path (META-INF/…, a/b/C.class) first, then a class name. */
		byte[] lookup(String s) {
			if (s == null) return null;
			for (ZipFile z : zips) {
				ZipEntry e = z.getEntry(s);
				if (e == null) continue;
				try (InputStream in = z.getInputStream(e)) {
					return in.readAllBytes();
				} catch (IOException x) {
					return null;
				}
			}
			if (!s.endsWith(".class")) return bytes(s);
			// The JDK and Minecraft's libraries, which the game loader serves too: a frame proof walks up to them.
			try (InputStream in = ChainAnchorCensusStagedTest.class.getClassLoader().getResourceAsStream(s)) {
				if (in != null) return in.readAllBytes();
			} catch (IOException unreadable) {
				return null;
			}
			for (ZipFile z : libraries()) {
				ZipEntry e = z.getEntry(s);
				if (e == null) continue;
				try (InputStream in = z.getInputStream(e)) {
					return in.readAllBytes();
				} catch (IOException x) {
					return null;
				}
			}
			return null;
		}

		private List<ZipFile> libraries;

		/** Every jar of the Minecraft libraries the game side compiles against (MC_DIR/libraries), opened once. */
		private List<ZipFile> libraries() {
			if (libraries != null) return libraries;
			libraries = new ArrayList<>();
			Path root = TestFixtures.minecraftDir().resolve("libraries");
			if (!Files.isDirectory(root)) return libraries;
			try (var walk = Files.walk(root)) {
				for (Path jar : walk.filter(f -> f.toString().endsWith(".jar")).sorted().toList()) libraries.add(new ZipFile(jar.toFile()));
			} catch (IOException unreadable) {
				throw new IllegalStateException("could not read the Minecraft libraries under " + root, unreadable);
			}
			return libraries;
		}

		/** Accepts a/b/C, a.b.C, a/b/C.class. */
		byte[] bytes(String name) {
			if (name == null) return null;
			String entry = name.endsWith(".class") ? name : name.replace('.', '/') + ".class";
			for (ZipFile z : zips) {
				ZipEntry e = z.getEntry(entry);
				if (e == null) continue;
				try (InputStream in = z.getInputStream(e)) {
					return in.readAllBytes();
				} catch (IOException x) {
					return null;
				}
			}
			return null;
		}

		ClassNode node(String name) {
			byte[] b = bytes(name);
			if (b == null) return null;
			ClassNode n = new ClassNode();
			new ClassReader(b).accept(n, 0);
			return n;
		}

		/** Every class entry, first jar winning, in jar order. */
		List<String> classes() {
			LinkedHashMap<String, Boolean> all = new LinkedHashMap<>();
			for (ZipFile z : zips) {
				for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements();) {
					String n = en.nextElement().getName();
					if (!n.endsWith(".class") || n.startsWith("META-INF/") || n.endsWith("module-info.class")
							|| n.endsWith("package-info.class")) continue;
					all.putIfAbsent(n.substring(0, n.length() - 6), Boolean.TRUE);
				}
			}
			return new ArrayList<>(all.keySet());
		}

		ClassLoader loader() {
			if (loader == null) {
				try {
					URL[] urls = new URL[paths.size()];
					for (int i = 0; i < urls.length; i++) urls[i] = paths.get(i).toUri().toURL();
					loader = new URLClassLoader(urls, ChainAnchorCensusStagedTest.class.getClassLoader());
				} catch (IOException e) {
					throw new IllegalStateException(e);
				}
			}
			return loader;
		}

		@Override
		public void close() throws IOException {
			for (ZipFile z : zips) z.close();
			if (libraries != null) for (ZipFile z : libraries) z.close();
			if (loader != null) loader.close();
		}
	}
}
