package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;

/**
 * Re-derives {@code uncalled-methods.txt} — every merged-base method that an ecosystem's own game calls and nothing in the
 * merged game calls — and asserts the shipped table equals it. MixinFit reads an injector bound only to such a method,
 * for a mod of a listed ecosystem, as one that never runs.
 *
 * <p>The merged game is what the kernel puts on the game classpath: the merged jar, {@code forge-runtime-interop.jar},
 * {@code neoforge-runtime.jar}, and the kernel's own boot and game-side classes (a repair may call a vanilla method, or
 * synthesize a call by name). The references are the jars each kind of mod was compiled against: stock 26.2 for
 * Fabric, MinecraftForge's and NeoForge's patched clients plus their runtime for theirs.
 */
class UncalledMethodCensusTest {
	private static final Path STAGED = TestFixtures.stagedRoot();
	private static final Path MERGED = STAGED.resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final Path INTEROP = STAGED.resolve("merged-base/forge-runtime-interop.jar");
	private static final Path NEO_RUNTIME = STAGED.resolve("neoforge-runtime/neoforge-runtime.jar");
	private static final Path FORGE_RUNTIME = STAGED.resolve("forge-runtime/forge-runtime.jar");
	private static final Path VANILLA = TestFixtures.vanillaJar();
	private static final Path FORGE = STAGED.resolve("forge-patched/patched-mc-forge-26.2.jar");
	private static final Path NEOFORGE = STAGED.resolve("neoforge-patched/patched-mc-neoforge-26.2.jar");
	private static final Path KERNEL_MAIN = mainClasses();
	private static final Path KERNEL_RUNTIME = Path.of(System.getProperty("forbric.test.runtimeClasses", "build/classes/java/runtime"));
    private static Path mainClasses() {
        String configured=System.getProperty("forbric.test.mainClasses");
        if(configured!=null)return Path.of(configured);
        try{return Path.of(MergedBaseUncalledMethods.class.getProtectionDomain().getCodeSource().getLocation().toURI());}
        catch(Exception invalid){throw new IllegalStateException("The census needs the actual compiled kernel classes",invalid);}
    }

	/** What the hierarchy and override checks need of a class: its supertypes and its methods' access, by name+desc. */
	private record Shape(String superName, List<String> interfaces, Map<String, Integer> methods) {
	}

	private record Call(String owner, String caller) {
	}

	@Test void theShippedTableIsExactlyWhatTheArtifactsSay() throws Exception {
		for (Path jar : List.of(MERGED, INTEROP, NEO_RUNTIME, FORGE_RUNTIME, VANILLA, FORGE, NEOFORGE)) {
			TestFixtures.require(jar == VANILLA ? Fixture.MC_LIBRARIES : Fixture.STAGED, Files.isRegularFile(jar), jar + " required");
		}
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(KERNEL_RUNTIME), "the game-side classes are compiled only with staged jars");

		// The merged game: what it declares, what it names, and every string it holds.
		Map<String, Shape> game = new HashMap<>();
		Map<String, Shape> mergedOnly = new LinkedHashMap<>();
		Set<String> named = new HashSet<>();
		Set<String> strings = new HashSet<>();
		Set<String> owned = new HashSet<>();
		Map<String, Set<String>> reflective = new HashMap<>();
		scan(MERGED, (c, m) -> {}, game, mergedOnly, named, strings, owned, reflective);
		for (Path code : List.of(INTEROP, NEO_RUNTIME, KERNEL_MAIN, KERNEL_RUNTIME)) scan(code, (c, m) -> {}, game, null, named, strings, owned, reflective);

		Map<String, Map<String, Integer>> uncalled = new TreeMap<>();
		for (Map.Entry<String, Shape> e : mergedOnly.entrySet()) {
			for (Map.Entry<String, Integer> m : e.getValue().methods().entrySet()) {
				String key = m.getKey();
				int access = m.getValue();
				if (key.startsWith("<") || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
				String name = key.substring(0, key.indexOf('('));
				if ((access & Opcodes.ACC_PRIVATE) != 0) {
					// A private method is reached only through its own class (its nest calls it naming that class): a call or a
					// handle naming another owner is another method — Level.removeBlock(BlockPos, boolean) is not
					// ServerPlayerGameMode's private removeBlock(BlockPos, boolean). A string reaches it only when the class that
					// holds it can make the call from it: with its descriptor too (a call a transformer synthesizes), as one
					// owner-qualified target, or through a reflective lookup made by a class that also names the declaring class.
					if (owned.contains(e.getKey() + "#" + key) || spelledForACall(reflective, e.getKey(), name, key.substring(name.length()))) continue;
				} else if (named.contains(key) || strings.contains(name)) continue;
				// Reached through a supertype it overrides — from the JDK, a library or reflection — or unknowable.
				if ((access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0 && overrides(game, e.getKey(), key, new HashSet<>()) != 0) continue;
				uncalled.computeIfAbsent(e.getKey(), k -> new HashMap<>()).put(key, access);
			}
		}
		Set<String> keys = new HashSet<>();
		uncalled.values().forEach(m -> keys.addAll(m.keySet()));

		// Each reference: which of those it calls, from where, through a type that reaches the declaring class.
		Map<Ecosystem, List<Path>> references = new EnumMap<>(Ecosystem.class);
		references.put(Ecosystem.FABRIC, List.of(VANILLA));
		references.put(Ecosystem.FORGE, List.of(FORGE, FORGE_RUNTIME));
		references.put(Ecosystem.NEOFORGE, List.of(NEOFORGE, NEO_RUNTIME));
		TreeMap<String, Set<Ecosystem>> ecosystems = new TreeMap<>();
		Map<String, TreeSet<String>> callers = new HashMap<>();
		for (Map.Entry<Ecosystem, List<Path>> reference : references.entrySet()) {
			Map<String, Shape> shapes = new HashMap<>();
			Map<String, List<Call>> calls = new HashMap<>();
			for (Path jar : reference.getValue()) {
				scan(jar, (c, m) -> {
					for (AbstractInsnNode insn : m.instructions) {
						for (Call call : calls(c, m, insn)) {
							String key = call.owner().substring(call.owner().indexOf('#') + 1);
							if (!keys.contains(key)) continue;
							calls.computeIfAbsent(key, k -> new ArrayList<>()).add(new Call(call.owner().substring(0, call.owner().indexOf('#')), call.caller()));
						}
					}
				}, shapes, null, null, null);
			}
			for (Map.Entry<String, Map<String, Integer>> owner : uncalled.entrySet()) {
				Shape declared = shapes.get(owner.getKey());
				if (declared == null) continue;
				for (String key : owner.getValue().keySet()) {
					if (!declared.methods().containsKey(key)) continue;
					String self = owner.getKey() + "#" + key;
					for (Call call : calls.getOrDefault(key, List.of())) {
						if (call.caller().equals(self) || !reaches(shapes, call.owner(), owner.getKey(), key)) continue;
						ecosystems.computeIfAbsent(self, k -> java.util.EnumSet.noneOf(Ecosystem.class)).add(reference.getKey());
						callers.computeIfAbsent(self, k -> new TreeSet<>()).add(call.caller());
					}
				}
			}
		}
		TreeSet<String> rows = new TreeSet<>();
		ecosystems.forEach((head, which) -> rows.add(head + " | " + String.join(",", which.stream().sorted().map(Enum::name).toList())
				+ " | " + String.join(" ", callers.get(head))));

		List<String> shipped = new ArrayList<>();
		try (InputStream in = MergedBaseUncalledMethods.class.getResourceAsStream(MergedBaseUncalledMethods.TABLE)) {
			assertNotNull(in, MergedBaseUncalledMethods.TABLE + " is missing");
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
				if (!line.isBlank() && !line.startsWith("#")) shipped.add(line.trim());
			}
		}
		if (System.getenv("FORBRIC_WRITE_UNCALLED_METHODS") != null) {
			Files.writeString(Path.of("src/main/resources" + MergedBaseUncalledMethods.TABLE),
					"# Generated by UncalledMethodCensusTest (FORBRIC_WRITE_UNCALLED_METHODS=1): a merged-base method nothing in the\n"
					+ "# merged game (merged jar, carriers, kernel) calls or names, that each listed ecosystem's own game calls from the\n"
					+ "# listed methods. MixinFit reads an injector bound only to one as never running, for a mod of a listed ecosystem.\n"
					+ "# <class>#<method> | <ecosystems> | <callers in those games>\n"
					+ String.join("\n", rows) + "\n");
		}
		assertEquals(rows, new TreeSet<>(shipped), "uncalled-methods.txt must equal what the staged jars and the kernel's own "
				+ "classes say; regenerate with FORBRIC_WRITE_UNCALLED_METHODS=1 after a base rebuild or a kernel change that "
				+ "calls or names one of these methods");
		for (String row : shipped) assertNotNull(MergedBaseUncalledMethods.Row.parse(row), "unreadable row: " + row);
		assertTrue(shipped.stream().anyMatch(r -> r.startsWith("net/minecraft/client/gui/Hud#extractHotbarAndDecorations(")),
				"the case this table exists for: NeoForge's HUD layers replaced vanilla's only call");
	}

	/** Every call or method handle {@code insn} makes, as {@code (owner#name+desc, caller)}. */
	private static List<Call> calls(ClassNode c, MethodNode m, AbstractInsnNode insn) {
		String caller = c.name + "#" + m.name + m.desc;
		List<Call> out = new ArrayList<>();
		if (insn instanceof MethodInsnNode call) out.add(new Call(call.owner + "#" + call.name + call.desc, caller));
		else if (insn instanceof InvokeDynamicInsnNode indy) {
			for (Object argument : indy.bsmArgs) {
				if (argument instanceof Handle h) out.add(new Call(h.getOwner() + "#" + h.getName() + h.getDesc(), caller));
			}
		} else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Handle h) {
			out.add(new Call(h.getOwner() + "#" + h.getName() + h.getDesc(), caller));
		}
		return out;
	}

	private interface Visitor {
		void method(ClassNode c, MethodNode m);
	}

	/** The methods a reflective lookup by name goes through: {@code Class}'s, {@code MethodHandles.Lookup}'s, the loaders' helpers. */
	private static final Set<String> REFLECTIVE_LOOKUPS = Set.of("getDeclaredMethod", "getMethod", "findVirtual", "findSpecial", "findStatic",
			"findMethod");

	/**
	 * Whether some class holds {@code name} as a string in a way a call to {@code owner}'s private {@code name + desc} can be made
	 * from: beside its descriptor as a string (a transformer synthesizing the call), within one string that spells the owner,
	 * the name and the descriptor together, or in a class that makes a reflective lookup and names {@code owner} — as a string
	 * (internal or binary), a class constant, or the owner of an instruction. A bare name a class compares against does not.
	 */
	private static boolean spelledForACall(Map<String, Set<String>> classes, String owner, String name, String desc) {
		String reflective = "\1";
		for (Set<String> held : classes.values()) {
			if (!held.contains("\0" + name)) continue;
			if (held.contains("\0" + desc)) return true;
			for (String string : held) if (string.startsWith("\0") && string.contains(name + desc) && string.contains(owner)) return true;
			if (held.contains(reflective) && (held.contains("\0" + owner) || held.contains("\0" + owner.replace('/', '.')) || held.contains(owner))) return true;
		}
		return false;
	}

	private static void scan(Path code, Visitor visitor, Map<String, Shape> shapes, Map<String, Shape> merged, Set<String> named,
			Set<String> strings) throws IOException {
		scan(code, visitor, shapes, merged, named, strings, null, null);
	}

	/**
	 * Reads every class of a jar or class directory: shapes into {@code shapes} (and {@code merged}), names and strings; and, when
	 * asked, every call and handle by owner ({@code owned}) and, for each class holding a string, the strings it holds (prefixed
	 * with NUL), the classes it names, and whether it makes a reflective lookup ({@code classes}).
	 */
	private static void scan(Path code, Visitor visitor, Map<String, Shape> shapes, Map<String, Shape> merged, Set<String> named,
			Set<String> strings, Set<String> owned, Map<String, Set<String>> reflective) throws IOException {
		List<byte[]> classes = new ArrayList<>();
		if (Files.isDirectory(code)) {
			try (Stream<Path> files = Files.walk(code)) {
				for (Path f : files.filter(p -> p.toString().endsWith(".class")).sorted().toList()) classes.add(Files.readAllBytes(f));
			}
		} else {
			try (ZipFile zip = new ZipFile(code.toFile())) {
				for (ZipEntry entry : Collections.list(zip.entries())) {
					if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/")) continue;
					try (InputStream in = zip.getInputStream(entry)) {
						classes.add(in.readAllBytes());
					}
				}
			}
		}
		for (byte[] bytes : classes) {
			ClassNode c = new ClassNode();
			new ClassReader(bytes).accept(c, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			Map<String, Integer> methods = new LinkedHashMap<>();
			for (MethodNode m : c.methods) methods.put(m.name + m.desc, m.access);
			Shape shape = new Shape(c.superName, c.interfaces == null ? List.of() : List.copyOf(c.interfaces), methods);
			shapes.putIfAbsent(c.name, shape);
			if (merged != null) merged.put(c.name, shape);
			Set<String> held = new HashSet<>();
			boolean looksUp = false;
			for (MethodNode m : c.methods) {
				visitor.method(c, m);
				if (named == null) continue;
				for (AbstractInsnNode insn : m.instructions) {
					for (Call call : calls(c, m, insn)) {
						named.add(call.owner().substring(call.owner().indexOf('#') + 1));
						if (owned != null) owned.add(call.owner());
						held.add(call.owner().substring(0, call.owner().indexOf('#')));
					}
					if (insn instanceof InvokeDynamicInsnNode indy) named.add(indy.bsm.getName() + indy.bsm.getDesc());
					if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
						strings.add(s);
						held.add("\0" + s);
					}
					if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof org.objectweb.asm.Type t && t.getSort() == org.objectweb.asm.Type.OBJECT)
						held.add(t.getInternalName());
					if (insn instanceof org.objectweb.asm.tree.TypeInsnNode type) held.add(type.desc);
					if (insn instanceof org.objectweb.asm.tree.FieldInsnNode field) held.add(field.owner);
					if (insn instanceof MethodInsnNode call && REFLECTIVE_LOOKUPS.contains(call.name)) looksUp = true;
				}
			}
			if (looksUp) held.add("\1");
			if (reflective != null && held.stream().anyMatch(h -> h.startsWith("\0"))) reflective.merge(c.name, held, (a, b) -> { a.addAll(b); return a; });
		}
	}

	/** 1 when a supertype declares {@code key} (non-private, non-static), 0 when none does, -1 when a supertype is unreadable. */
	private static int overrides(Map<String, Shape> game, String name, String key, Set<String> seen) {
		Shape shape = shape(game, name);
		if (shape == null) return -1;
		List<String> supers = new ArrayList<>();
		if (shape.superName() != null) supers.add(shape.superName());
		supers.addAll(shape.interfaces());
		int result = 0;
		for (String s : supers) {
			if (!seen.add(s)) continue;
			Shape sup = shape(game, s);
			if (sup == null) { result = -1; continue; }
			Integer access = sup.methods().get(key);
			if (access != null && (access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0) return 1;
			int deeper = overrides(game, s, key, seen);
			if (deeper == 1) return 1;
			if (deeper < 0) result = -1;
		}
		return result;
	}

	/** The game's own shape, else the JDK's (through the platform loader only, so the test classpath cannot change the answer). */
	private static Shape shape(Map<String, Shape> game, String name) {
		Shape shape = game.get(name);
		if (shape != null) return shape;
		try (InputStream in = ClassLoader.getPlatformClassLoader().getResourceAsStream(name + ".class")) {
			if (in == null) return null;
			ClassNode c = new ClassNode();
			new ClassReader(in.readAllBytes()).accept(c, ClassReader.SKIP_CODE);
			Map<String, Integer> methods = new LinkedHashMap<>();
			for (MethodNode m : c.methods) methods.put(m.name + m.desc, m.access);
			shape = new Shape(c.superName, c.interfaces == null ? List.of() : List.copyOf(c.interfaces), methods);
			game.put(name, shape);
			return shape;
		} catch (IOException unreadable) {
			return null;
		}
	}

	/**
	 * Whether a call to {@code key} through {@code owner} resolves to the one {@code declaring} declares: through the class
	 * itself, or through a subclass that does not declare it again on the way up.
	 */
	private static boolean reaches(Map<String, Shape> shapes, String owner, String declaring, String key) {
		for (int guard = 0; owner != null && guard < 64; guard++) {
			if (owner.equals(declaring)) return true;
			Shape shape = shapes.get(owner);
			if (shape == null || shape.methods().containsKey(key)) return false;
			owner = shape.superName();
		}
		return false;
	}
}
