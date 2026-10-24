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

package net.forbric.kernel.boot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * The families' runtimes (the carriers) as one call graph, and which of their methods the game, the kernel or a guest
 * reaches.
 *
 * <h2>Why a graph and not "any carrier caller counts"</h2>
 *
 * <p>{@link HookCallSiteCensus} first judged a hook reached when anything in a carrier called it. A carrier method is
 * not reached because it exists: {@code IForgeServerPlayer.openMenu(MenuProvider, Consumer)} calls
 * {@code ForgeEventFactory.onPlayerOpenContainer}, and nothing in the merged game calls it — the game's own
 * {@code ServerPlayer.openMenu} posts NeoForge's open event inline — so a MinecraftForge mod's container-open listener
 * never heard a vanilla container open while the census called the event posted. Hooks were already followed to a
 * fixed point; every other caller was taken on trust. This follows every carrier method the same way.
 *
 * <h2>What counts as reached</h2>
 *
 * <p>A carrier method is reached when a reached place calls it, hands it out as a method handle, or the runtime
 * enters it without a call in the scanned bytecode:
 * <ul>
 *   <li>a call names it, or names a type that inherits it (a game class calling {@code openMenu} on itself reaches the
 *       carrier interface's default method), or — for a virtual call — names a supertype of a carrier class that is
 *       instantiated somewhere reached and overrides it (class hierarchy analysis, narrowed to instantiated types);</li>
 *   <li>its class's static initializer, once a reached place touches the class statically or constructs it;</li>
 *   <li>a listener: a method carrying either family's {@code @SubscribeEvent}, which the bus enters;</li>
 *   <li>a method of an instantiated class that overrides one declared outside the scanned code (the JDK, a
 *       library), which code this cannot read may call — an instantiated class whose outside supertype cannot be
 *       read has every method entered;</li>
 *   <li>a class constructed by the runtime itself: a family's {@code @Mod} class, a service provider.</li>
 * </ul>
 *
 * <p>Every rule errs towards reaching more, so the census keeps erring towards calling a dead event live: what this
 * removes is only a caller nothing reaches at all. A reached root is recorded with the method, so a test can print
 * (and check) why each one was reached.
 *
 * <h2>The kernel's reflection</h2>
 *
 * <p>The kernel's boot side cannot name a game type, so it calls a family method by name: {@code PayloadInterop}
 * gathers MinecraftForge's configuration tasks with {@code factory.getMethod("gatherLoginConfigTasks", ...)}. A method
 * that holds a family class's binary name ({@code net.minecraftforge.event.ForgeEventFactory}, the spelling
 * {@code Class.forName} takes) and the name of a method that class declares, and that looks a method up reflectively
 * (directly or through a kernel helper that does), calls every overload of that name. The slashed internal name a
 * transformer matches bytecode with is not that spelling, and is not read as a call.
 */
final class CarrierCallGraph {

	/** Either family's listener annotation: the bus enters such a method without a call. */
	private static final Set<String> LISTENERS = Set.of("Lnet/minecraftforge/eventbus/api/listener/SubscribeEvent;",
			"Lnet/neoforged/bus/api/SubscribeEvent;");

	/** Either family's mod annotation: the loader constructs such a class without a {@code NEW} in scanned code. */
	private static final Set<String> MOD_CLASSES = Set.of("Lnet/minecraftforge/fml/common/Mod;", "Lnet/neoforged/fml/common/Mod;");

	/** How a reference resolves: exactly (static, special, a constructor) or virtually. */
	enum Kind { EXACT, VIRTUAL }

	/** One reference from a method body: a call, or a method handle. */
	record Ref(Kind kind, String owner, String nameDesc) {
		String id() {
			return owner + "." + nameDesc;
		}
	}

	/** One method of a carrier class, and what its body reaches. */
	static final class Method {
		final String id;
		final String owner;
		final String nameDesc;
		final int access;
		final List<Ref> refs = new ArrayList<>();
		/** Types this body touches statically or constructs: their static initializers run. */
		final Set<String> initializes = new HashSet<>();
		/** Types this body constructs. */
		final Set<String> constructs = new HashSet<>();
		boolean listener;

		Method(String owner, String nameDesc, int access) {
			this.id = owner + "." + nameDesc;
			this.owner = owner;
			this.nameDesc = nameDesc;
			this.access = access;
		}

		boolean isStatic() {
			return (access & Opcodes.ACC_STATIC) != 0;
		}

		boolean isPrivate() {
			return (access & Opcodes.ACC_PRIVATE) != 0;
		}

		boolean isInitializer() {
			return nameDesc.startsWith("<");
		}
	}

	/** A type's header: supertypes and declared methods. */
	record Type(String name, int access, String superName, List<String> interfaces, Map<String, Integer> declared) {
		List<String> supers() {
			List<String> out = new ArrayList<>();
			if (superName != null) out.add(superName);
			out.addAll(interfaces);
			return out;
		}
	}

	/** One body outside the carriers (the game, the kernel): what it calls, touches and constructs. */
	static final class Body {
		final Set<Ref> refs = new HashSet<>();
		final Set<String> initializes = new HashSet<>();
		final Set<String> constructs = new HashSet<>();
		/** Carrier types a method names by binary name and looks a method of up reflectively → the methods it names. */
		final Map<String, Set<String>> reflective = new HashMap<>();
		/** The methods read with {@code reflection}, for {@link #resolveReflection}. */
		final List<BodyMethod> methods = new ArrayList<>();
	}

	final Map<String, Type> carrierTypes = new HashMap<>();
	final Map<String, Method> methods = new HashMap<>();
	/** Types outside the carriers whose headers were read: the game's, then the JDK's and libraries' as asked. */
	private final Map<String, Type> otherTypes = new HashMap<>();
	/** Types asked for that nothing could read. */
	private final Set<String> unreadable = new HashSet<>();
	/** name+desc → the carrier types declaring it as an overridable instance method. */
	private final Map<String, List<String>> declarers = new HashMap<>();
	/** Carrier types the runtime constructs itself: service providers and either family's mod classes. */
	final Set<String> runtimeConstructed = new HashSet<>();
	private final Map<String, Set<String>> supertypeCache = new HashMap<>();

	private CarrierCallGraph() {
	}

	/** Reads every class of {@code carrierJars}. */
	static CarrierCallGraph of(List<Path> carrierJars) throws IOException {
		CarrierCallGraph graph = new CarrierCallGraph();
		for (Path jar : carrierJars) graph.readCarrier(jar);
		for (Type type : graph.carrierTypes.values()) {
			for (Map.Entry<String, Integer> m : type.declared().entrySet()) {
				int access = m.getValue();
				if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0 || m.getKey().startsWith("<")) continue;
				graph.declarers.computeIfAbsent(m.getKey(), k -> new ArrayList<>()).add(type.name());
			}
		}
		return graph;
	}

	private void readCarrier(Path jar) throws IOException {
		if (!Files.isRegularFile(jar)) return;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();
				if (name.startsWith("META-INF/services/") && !entry.isDirectory()) {
					try (InputStream in = zip.getInputStream(entry)) {
						for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
							String provider = line.replaceAll("#.*", "").trim();
							if (!provider.isEmpty()) runtimeConstructed.add(provider.replace('.', '/'));
						}
					}
					continue;
				}
				if (!name.endsWith(".class")) continue;
				ClassNode node = new ClassNode();
				try (InputStream in = zip.getInputStream(entry)) {
					new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
				}
				if (node.name == null || node.name.equals("module-info")) continue;
				carrierTypes.put(node.name, header(node));
				if (annotated(node.visibleAnnotations, MOD_CLASSES) || annotated(node.invisibleAnnotations, MOD_CLASSES)) {
					runtimeConstructed.add(node.name);
				}
				for (MethodNode m : node.methods) {
					Method method = new Method(node.name, m.name + m.desc, m.access);
					method.listener = annotated(m.visibleAnnotations, LISTENERS) || annotated(m.invisibleAnnotations, LISTENERS);
					if (m.instructions != null) {
						for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
							refs(insn, method.refs::add);
							touches(insn, method.initializes, method.constructs);
						}
					}
					methods.put(method.id, method);
				}
			}
		}
	}

	private static boolean annotated(List<AnnotationNode> annotations, Set<String> wanted) {
		if (annotations == null) return false;
		for (AnnotationNode a : annotations) if (wanted.contains(a.desc)) return true;
		return false;
	}

	static Type header(ClassNode node) {
		Map<String, Integer> declared = new LinkedHashMap<>();
		for (MethodNode m : node.methods) declared.put(m.name + m.desc, m.access);
		return new Type(node.name, node.access, node.superName, node.interfaces == null ? List.of() : List.copyOf(node.interfaces),
				declared);
	}

	/** Every reference {@code insn} makes: an invoke, or each method handle an indy or an ldc carries. */
	static void refs(AbstractInsnNode insn, Consumer<Ref> out) {
		if (insn instanceof MethodInsnNode mi) {
			Kind kind = mi.getOpcode() == Opcodes.INVOKEVIRTUAL || mi.getOpcode() == Opcodes.INVOKEINTERFACE ? Kind.VIRTUAL : Kind.EXACT;
			out.accept(new Ref(kind, mi.owner, mi.name + mi.desc));
		} else if (insn instanceof InvokeDynamicInsnNode indy) {
			handle(indy.bsm, out);
			if (indy.bsmArgs != null) for (Object arg : indy.bsmArgs) handle(arg, out);
		} else if (insn instanceof LdcInsnNode ldc) {
			handle(ldc.cst, out);
		}
	}

	private static void handle(Object value, Consumer<Ref> out) {
		if (!(value instanceof Handle h) || h.getTag() < Opcodes.H_INVOKEVIRTUAL) return;
		Kind kind = h.getTag() == Opcodes.H_INVOKEVIRTUAL || h.getTag() == Opcodes.H_INVOKEINTERFACE ? Kind.VIRTUAL : Kind.EXACT;
		out.accept(new Ref(kind, h.getOwner(), h.getName() + h.getDesc()));
	}

	/** The types {@code insn} initializes (a static access, a static call, a construction) and constructs. */
	static void touches(AbstractInsnNode insn, Set<String> initializes, Set<String> constructs) {
		switch (insn.getOpcode()) {
			case Opcodes.NEW -> {
				String type = ((TypeInsnNode) insn).desc;
				initializes.add(type);
				constructs.add(type);
			}
			case Opcodes.GETSTATIC, Opcodes.PUTSTATIC -> initializes.add(((FieldInsnNode) insn).owner);
			case Opcodes.INVOKESTATIC -> initializes.add(((MethodInsnNode) insn).owner);
			default -> {
				if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
					for (Object arg : indy.bsmArgs) {
						if (arg instanceof Handle h && h.getTag() == Opcodes.H_NEWINVOKESPECIAL) {
							initializes.add(h.getOwner());
							constructs.add(h.getOwner());
						}
					}
				}
			}
		}
	}

	/**
	 * Reads one body jar or class directory (the game, the kernel) into {@code into}, skipping {@code skip}; each class is
	 * handed through {@code loadTime} first when there is one. {@code keepHeaders} keeps each class's header for resolving
	 * calls through it (the game's); {@code reflection} records each method's string constants and reflective lookups for
	 * {@link #resolveReflection} (the kernel's).
	 */
	void readBodies(Path root, Set<String> skip, BiFunction<String, byte[], byte[]> loadTime, Body into, boolean keepHeaders,
			boolean reflection) throws IOException {
		forEachClass(root, loadTime, node -> {
			if (keepHeaders && !carrierTypes.containsKey(node.name)) otherTypes.put(node.name, header(node));
			if (skip.contains(node.name)) return;
			for (MethodNode m : node.methods) {
				if (m.instructions == null) continue;
				List<String> strings = new ArrayList<>();
				Set<String> calls = new HashSet<>();
				boolean reflects = false;
				for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
					refs(insn, into.refs::add);
					touches(insn, into.initializes, into.constructs);
					if (!reflection) continue;
					if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) strings.add(s);
					if (insn instanceof MethodInsnNode mi) {
						calls.add(mi.owner + "." + mi.name + mi.desc);
						if (looksUpReflectively(mi)) reflects = true;
					}
				}
				if (reflection) into.methods.add(new BodyMethod(node.name + "." + m.name + m.desc, strings, reflects, calls));
			}
		});
	}

	/** A kernel method's string constants, whether it looks a method up itself, and whom it calls. */
	record BodyMethod(String id, List<String> strings, boolean reflects, Set<String> calls) {
	}

	/** A reflective method lookup: {@code Class.getMethod} and its kin, or a {@code MethodHandles.Lookup.find*}. */
	private static boolean looksUpReflectively(MethodInsnNode mi) {
		if (mi.owner.equals("java/lang/Class")) {
			return mi.name.equals("getMethod") || mi.name.equals("getDeclaredMethod") || mi.name.equals("getMethods")
					|| mi.name.equals("getDeclaredMethods");
		}
		return mi.owner.equals("java/lang/invoke/MethodHandles$Lookup") && mi.name.startsWith("find");
	}

	/**
	 * Turns the bodies' reflective lookups into calls: every overload a carrier class declares under a name held beside
	 * that class's binary name, in a method that looks a method up itself or calls a body method that does (to a fixed
	 * point: a helper taking the names as arguments makes its callers the reflective ones). The class is also taken as
	 * initialized and constructed, since a name looked up by reflection is often a constructor's class.
	 */
	void resolveReflection(Body into) {
		Set<String> reflecting = new HashSet<>();
		for (BodyMethod m : into.methods) if (m.reflects()) reflecting.add(m.id());
		boolean grew = true;
		while (grew) {
			grew = false;
			for (BodyMethod m : into.methods) {
				if (reflecting.contains(m.id())) continue;
				for (String callee : m.calls()) {
					if (reflecting.contains(callee)) {
						reflecting.add(m.id());
						grew = true;
						break;
					}
				}
			}
		}
		for (BodyMethod m : into.methods) {
			if (!reflecting.contains(m.id())) continue;
			for (String s : m.strings()) {
				// The binary spelling Class.forName takes; a slashed internal name is a transformer matching bytecode.
				if (s.indexOf('.') < 0 || s.indexOf('/') >= 0 || s.indexOf(' ') >= 0) continue;
				String type = s.replace('.', '/');
				Type carrier = carrierTypes.get(type);
				if (carrier == null) continue;
				Set<String> names = into.reflective.computeIfAbsent(type, k -> new java.util.TreeSet<>());
				for (String other : m.strings()) {
					for (String nameDesc : carrier.declared().keySet()) {
						if (nameDesc.startsWith(other + "(") && !nameDesc.startsWith("<")) {
							names.add(nameDesc);
							into.refs.add(new Ref(Kind.EXACT, type, nameDesc));
						}
					}
				}
				// A class looked up by name may be constructed by reflection: its constructors are called as well.
				for (String nameDesc : carrier.declared().keySet()) {
					if (nameDesc.startsWith("<init>")) into.refs.add(new Ref(Kind.EXACT, type, nameDesc));
				}
				into.initializes.add(type);
				into.constructs.add(type);
			}
		}
	}

	/** Each class in a jar or under a class directory, through {@code loadTime} first when there is one. */
	static void forEachClass(Path root, BiFunction<String, byte[], byte[]> loadTime, Consumer<ClassNode> action) throws IOException {
		if (Files.isDirectory(root)) {
			try (var walk = Files.walk(root)) {
				for (Path file : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".class"))::iterator) {
					action.accept(node(Files.readAllBytes(file), loadTime));
				}
			}
			return;
		}
		if (!Files.isRegularFile(root)) return;
		try (ZipFile zf = new ZipFile(root.toFile())) {
			var entries = zf.entries();
			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();
				if (!e.getName().endsWith(".class")) continue;
				try (InputStream in = zf.getInputStream(e)) {
					action.accept(node(in.readAllBytes(), loadTime));
				}
			}
		}
	}

	private static ClassNode node(byte[] bytes, BiFunction<String, byte[], byte[]> loadTime) {
		if (loadTime != null) {
			byte[] loaded = loadTime.apply(new ClassReader(bytes).getClassName(), bytes);
			if (loaded != null) bytes = loaded;
		}
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
		return cn;
	}

	/** A type's header: a carrier's, a scanned body's, or one the JDK or a library on this class path serves. */
	private Type type(String name) {
		Type t = carrierTypes.get(name);
		if (t != null) return t;
		t = otherTypes.get(name);
		if (t != null || unreadable.contains(name)) return t;
		try (InputStream in = ClassLoader.getSystemResourceAsStream(name + ".class")) {
			if (in != null) {
				ClassNode node = new ClassNode();
				new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_CODE);
				t = header(node);
				otherTypes.put(name, t);
				return t;
			}
		} catch (IOException | RuntimeException unreadableType) {
			net.forbric.kernel.util.Caught.expected(unreadableType);
		}
		unreadable.add(name);
		return null;
	}

	/** Every supertype of {@code name}, itself included, as far as headers can be read. */
	Set<String> supertypes(String name) {
		Set<String> cached = supertypeCache.get(name);
		if (cached != null) return cached;
		Set<String> out = new HashSet<>();
		ArrayDeque<String> work = new ArrayDeque<>(List.of(name));
		while (!work.isEmpty()) {
			String at = work.removeFirst();
			if (!out.add(at)) continue;
			Type t = type(at);
			if (t != null) work.addAll(t.supers());
		}
		supertypeCache.put(name, out);
		return out;
	}

	/** The type declaring {@code nameDesc} that a call on {@code owner} resolves to: its class chain, then its interfaces. */
	private String declaring(String owner, String nameDesc) {
		List<String> interfaces = new ArrayList<>();
		for (String at = owner; at != null; ) {
			Type t = type(at);
			if (t == null) break;
			if (t.declared().containsKey(nameDesc)) return at;
			interfaces.addAll(t.interfaces());
			at = t.superName();
		}
		ArrayDeque<String> work = new ArrayDeque<>(interfaces);
		Set<String> seen = new HashSet<>();
		while (!work.isEmpty()) {
			String at = work.removeFirst();
			if (!seen.add(at)) continue;
			Type t = type(at);
			if (t == null) continue;
			Integer access = t.declared().get(nameDesc);
			if (access != null && (access & Opcodes.ACC_ABSTRACT) == 0) return at;
			work.addAll(t.interfaces());
		}
		return null;
	}

	/** Why a root reached a method: a tag, or the id of the reached method that reached it. */
	static final String GAME = "<game>", KERNEL = "<kernel>", LISTENER = "<listener>", RUNTIME = "<runtime-constructed>",
			OUTSIDE = "<called from outside the scanned code>", GUEST = "<a guest calling public API>";

	/** One reachability fixpoint over the carriers. */
	final class Reach {
		/** Reached carrier method → why: a root tag or the reached method whose body reached it. */
		final Map<String, String> why = new LinkedHashMap<>();
		private final Set<String> instantiated = new HashSet<>();
		/** Types with an instantiated subtype (themselves included): a virtual call can dispatch into them. */
		private final Set<String> live = new HashSet<>();
		/** name+desc → owners it was called virtually on. */
		private final Map<String, Set<String>> virtualCalls = new HashMap<>();
		private final ArrayDeque<String[]> work = new ArrayDeque<>();

		boolean reached(String id) {
			return why.containsKey(id);
		}

		/** Every type a reached method constructs. */
		Set<String> constructed() {
			Set<String> out = new HashSet<>();
			for (String id : why.keySet()) out.addAll(methods.get(id).constructs);
			return out;
		}

		/** The chain from {@code id} back to its root, root last. */
		List<String> chain(String id) {
			List<String> out = new ArrayList<>();
			Set<String> seen = new HashSet<>();
			for (String at = id; at != null && seen.add(at); at = why.get(at)) {
				out.add(at);
				if (at.startsWith("<")) break;
			}
			return out;
		}

		private void enter(String id, String from) {
			if (methods.containsKey(id) && !why.containsKey(id)) work.add(new String[] {id, from});
		}

		void body(Body body, String tag) {
			for (Ref ref : body.refs) ref(ref, tag);
			for (String type : body.initializes) initialize(type, tag);
			for (String type : body.constructs) instantiate(type, tag);
		}

		void ref(Ref ref, String from) {
			String declaring = declaring(ref.owner(), ref.nameDesc());
			if (declaring != null && carrierTypes.containsKey(declaring)) enter(declaring + "." + ref.nameDesc(), from);
			else if (declaring == null && carrierTypes.containsKey(ref.owner())) enter(ref.id(), from);
			if (ref.kind() != Kind.VIRTUAL || ref.nameDesc().startsWith("<")) return;
			if (!virtualCalls.computeIfAbsent(ref.nameDesc(), k -> new HashSet<>()).add(ref.owner())) return;
			for (String declarer : declarers.getOrDefault(ref.nameDesc(), List.of())) {
				if (live.contains(declarer) && supertypes(declarer).contains(ref.owner())) enter(declarer + "." + ref.nameDesc(), from);
			}
		}

		void initialize(String type, String from) {
			for (String t : supertypes(type)) {
				if (carrierTypes.containsKey(t)) enter(t + ".<clinit>()V", from);
			}
		}

		/** A class constructed without a {@code NEW} the scan sees (the loader, a service lookup, reflection): every constructor. */
		void construct(String type, String from) {
			Type t = carrierTypes.get(type);
			if (t == null) return;
			for (String nameDesc : t.declared().keySet()) if (nameDesc.startsWith("<init>")) enter(type + "." + nameDesc, from);
			instantiate(type, from);
		}

		void instantiate(String type, String from) {
			if (!carrierTypes.containsKey(type) || !instantiated.add(type)) return;
			initialize(type, from);
			for (String t : supertypes(type)) {
				if (!live.add(t)) continue;
				Type header = type(t);
				if (header == null) continue;
				boolean carrier = carrierTypes.containsKey(t);
				for (Map.Entry<String, Integer> m : header.declared().entrySet()) {
					int access = m.getValue();
					if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0 || m.getKey().startsWith("<")) continue;
					if (carrier) {
						// A virtual call already seen on a supertype of this type now dispatches here.
						for (String owner : virtualCalls.getOrDefault(m.getKey(), Set.of())) {
							if (supertypes(t).contains(owner)) {
								enter(t + "." + m.getKey(), from);
								break;
							}
						}
					} else if (!otherTypes.containsKey(t) || !isScannedBody(t)) {
						// Declared outside the scanned code (the JDK, a library): whatever overrides it here may be called from
						// code this cannot read.
						String impl = declaring(type, m.getKey());
						if (impl != null && carrierTypes.containsKey(impl)) enter(impl + "." + m.getKey(), OUTSIDE);
					}
				}
			}
			// An outside supertype nothing could read: any method here may override one of its methods.
			for (String t : supertypes(type)) {
				if (type(t) != null || !unreadable.contains(t)) continue;
				for (String id : declaredInstanceMethods(type)) enter(id, OUTSIDE);
				break;
			}
		}

		private List<String> declaredInstanceMethods(String type) {
			List<String> out = new ArrayList<>();
			Type t = carrierTypes.get(type);
			if (t == null) return out;
			for (Map.Entry<String, Integer> m : t.declared().entrySet()) {
				if ((m.getValue() & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0 && !m.getKey().startsWith("<")) {
					out.add(type + "." + m.getKey());
				}
			}
			return out;
		}

		/** Runs the fixpoint from what was entered so far. */
		Reach run() {
			while (!work.isEmpty()) {
				String[] next = work.removeFirst();
				String id = next[0];
				if (why.containsKey(id)) continue;
				why.put(id, next[1]);
				Method m = methods.get(id);
				for (Ref ref : m.refs) ref(ref, id);
				for (String type : m.initializes) initialize(type, id);
				for (String type : m.constructs) instantiate(type, id);
			}
			return this;
		}
	}

	/** Types read as scanned bodies (the game): a call into them from the game itself is seen, not assumed. */
	private final Set<String> scannedBodies = new HashSet<>();

	private boolean isScannedBody(String type) {
		return scannedBodies.contains(type);
	}

	/** Marks the headers read so far as the game's: virtual calls through them are in the scanned bodies. */
	void markScannedBodies() {
		scannedBodies.addAll(otherTypes.keySet());
	}

	/**
	 * A reachability pass: the runtime's own entries (listeners, the classes it constructs) plus {@code bodies}, each with
	 * its root tag.
	 */
	Reach reach(Map<Body, String> bodies) {
		return reach(bodies, true);
	}

	/**
	 * The same; {@code runtime} false leaves the runtime's own entries out, so the pass answers what {@code bodies} alone
	 * reach — the kernel's own code, for what it posts itself.
	 */
	Reach reach(Map<Body, String> bodies, boolean runtime) {
		Reach reach = new Reach();
		if (runtime) {
			for (Method m : methods.values()) if (m.listener) reach.enter(m.id, LISTENER);
			for (String type : runtimeConstructed) reach.construct(type, RUNTIME);
		}
		for (Map.Entry<Body, String> body : bodies.entrySet()) reach.body(body.getKey(), body.getValue());
		return reach.run();
	}

	/**
	 * Public API a guest can call: a public, non-synthetic method of a public carrier type outside {@code excludedTypes}
	 * (the hook classes, whose guest calls {@link GuestEventPosters} reads at boot) that is not in {@code entries} (a method
	 * a family's own patched game calls is the game's entry, not a guest's).
	 */
	Body publicApi(Set<String> excludedTypes, Set<String> entries) {
		Body api = new Body();
		for (Method m : methods.values()) {
			Type t = carrierTypes.get(m.owner);
			if (t == null || excludedTypes.contains(m.owner) || entries.contains(m.id) || m.isInitializer()) continue;
			if ((t.access() & Opcodes.ACC_PUBLIC) == 0 || (m.access & Opcodes.ACC_PUBLIC) == 0) continue;
			if ((m.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != 0) continue;
			api.refs.add(new Ref(Kind.EXACT, m.owner, m.nameDesc));
		}
		return api;
	}

	/**
	 * The carrier methods the families' own patched games ({@code nativeGames}) enter: each game read against its own
	 * class headers, with {@code carrierJars} as the carriers. Empty when no game is given.
	 */
	static Set<String> entries(List<Path> carrierJars, List<Path> nativeGames) throws IOException {
		Set<String> out = new HashSet<>();
		for (Path game : nativeGames) {
			CarrierCallGraph graph = of(carrierJars);
			Body body = new Body();
			graph.readBodies(game, Set.of(), null, body, true, false);
			out.addAll(graph.entered(body));
		}
		return out;
	}

	/** The carrier methods {@code body}'s references resolve to without dispatch: what a family's native game enters. */
	Set<String> entered(Body body) {
		Set<String> out = new HashSet<>();
		for (Ref ref : body.refs) {
			String declaring = declaring(ref.owner(), ref.nameDesc());
			if (declaring != null && carrierTypes.containsKey(declaring)) out.add(declaring + "." + ref.nameDesc());
		}
		return out;
	}

	/** The carrier types among {@code names}. */
	Set<String> carriersAmong(Collection<String> names) {
		Set<String> out = new HashSet<>();
		for (String n : names) if (carrierTypes.containsKey(n)) out.add(n);
		return out;
	}
}
