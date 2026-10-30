/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;

/**
 * Guest hooks woven into a vanilla method the merged game never calls, run where the carrier computes that method's value.
 *
 * <p>Vanilla's {@code DropExperienceBlock.spawnAfterBreak} (and four more blocks' — the census lists them) calls
 * {@code Block.tryDropExperience(level, pos, tool, provider)}: {@code i = processBlockExperience(level, tool,
 * provider.sample(random))}, then {@code popExperience(level, pos, i)} when {@code i > 0}. A Fabric mod's mixin changes ore
 * experience there — a {@code @ModifyVariable} at the store of {@code i} (PuzzlesLib's takes it and pays it out later), a
 * cancel at its start. NeoForge removed those calls: the same value is {@code BlockDropsEvent}'s experience, computed from the
 * block's {@code getExpDrop} (the same provider, sampled), processed the same way, and popped once by
 * {@code CommonHooks.handleBlockDrops}. Nothing in the merged game calls {@code tryDropExperience}, so every such hook was
 * woven and never ran — and since the merged {@code playerDestroy} reaches NeoForge's drops again, the vanilla experience it
 * meant to replace or take was popped as well, on top of what the mod paid out itself.
 *
 * <p>So, after Mixin, the method's woven body is copied into a relay of the same class ({@code tryDropExperience$forbricrelay}),
 * with three calls replaced by the kernel's: the provider's draw answers nothing (the carrier already drew), the processing
 * answers the value the carrier computed (the event's experience as it stands when the relay runs), and the pop records the
 * amount instead of popping it. Every guest hook woven into the method stays where Mixin put it and runs in the relay, in its
 * order, on the carrier's value. The kernel's drops listener ({@code KernelServerDuties.installExperienceRelay}, on NeoForge's
 * bus itself at {@code LOWEST}, installed whether or not MinecraftForge or its break bridge is there) runs the relay for a
 * block whose native {@code spawnAfterBreak} asked the method (the census's callers, through a class that only forwards to
 * its parent's), and the event drops what the relay would have popped: once, where NeoForge pops it.
 *
 * <p>The relay is made only when it can stand for the method exactly: guest code was woven into it; every hook woven there is
 * a mixin of an ecosystem whose native game calls the method (the census's row — a NeoForge mod's hook there never runs
 * natively either, and does not start running); and the three calls are each made once, the draw handing its value straight
 * to the processing (a hook on the raw draw would be handed nothing). Anything else leaves the class as Mixin wove it and says
 * why. A hook that reads the provider (an {@code @Inject} handed every argument) is handed what the native caller passed — a
 * field of the block, a static field, or a static call on constants, read off the caller's native body; a block whose caller
 * passes anything else is not relayed, and says so. Nothing here knows a mod. {@code -Dforbric.uncalledValueRelay=off} makes
 * no relay.
 */
public final class UncalledValueRelay {
	public static final String PROPERTY = "forbric.uncalledValueRelay";
	/** Extra contracts, for the weave test's stand-in game: {@code owner#name(desc) | draw | process | effect | unread index}, one per line of the file. */
	public static final String EXTRA_PROPERTY = "forbric.uncalledValueRelay.extra";
	public static final String SUFFIX = "$forbricrelay";
	private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
	private static final String SELF = "net/forbric/kernel/mixin/UncalledValueRelay";

	/**
	 * A vanilla method and the three calls that tie it to the carrier's place: {@code draw} (the raw value, already drawn by the
	 * carrier), {@code process} (the value the carrier hands on), {@code effect} (what the method does with it), and the
	 * parameter the relay does not have.
	 */
	public record Contract(String owner, String name, String desc, String draw, String process, String effect, int unread) {
		String key() {
			return owner + "#" + name + desc;
		}

		/** {@code owner#name(desc) | draw | process | effect | unread index}; null when the line is not that. */
		static Contract parse(String line) {
			String[] cells = line.split(" \\| ");
			if (cells.length != 5) return null;
			int hash = cells[0].indexOf('#'), paren = cells[0].indexOf('(');
			if (hash <= 0 || paren < hash) return null;
			return new Contract(cells[0].substring(0, hash).trim(), cells[0].substring(hash + 1, paren).trim(), cells[0].substring(paren).trim(),
					cells[1].trim(), cells[2].trim(), cells[3].trim(), Integer.parseInt(cells[4].trim()));
		}

		/** {@code Block.tryDropExperience}, for a log line. */
		String display() {
			return owner.substring(owner.lastIndexOf('/') + 1) + "." + name;
		}
	}

	/**
	 * Block.tryDropExperience, whose value is NeoForge's BlockDropsEvent experience. Written as the census writes the method's row
	 * ({@code owner#name+desc}), then the three calls and the unread parameter: the kernel names the method by that key and never
	 * calls it, so no string of the kernel spells its bare name (the census reads such a string as a call reflection could make).
	 */
	public static final Contract EXPERIENCE = Contract.parse("net/minecraft/world/level/block/Block#tryDropExperience(Lnet/minecraft/server/level/"
			+ "ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/util/valueproviders/IntProvider;)V"
			+ " | net/minecraft/util/valueproviders/IntProvider.sample(Lnet/minecraft/util/RandomSource;)I"
			+ " | net/minecraft/world/item/enchantment/EnchantmentHelper.processBlockExperience(Lnet/minecraft/server/level/ServerLevel;"
			+ "Lnet/minecraft/world/item/ItemStack;I)I"
			+ " | net/minecraft/world/level/block/Block.popExperience(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;I)V | 3");

	private UncalledValueRelay() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	private static volatile List<Contract> contracts;

	static List<Contract> contracts() {
		List<Contract> known = contracts;
		if (known != null) return known;
		List<Contract> out = new ArrayList<>(List.of(EXPERIENCE));
		String extra = System.getProperty(EXTRA_PROPERTY);
		if (extra != null) {
			try {
				for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(extra))) {
					Contract contract = Contract.parse(line);
					if (contract != null) out.add(contract);
				}
			} catch (java.io.IOException | RuntimeException unreadable) {
				Caught.warn(unreadable, "[Forbric/Mixin] could not read the extra value-relay contracts in %s", extra);
			}
		}
		contracts = List.copyOf(out);
		return contracts;
	}

	/** The contract of {@code owner#name(desc)}, or null. */
	public static Contract contract(String key) {
		for (Contract contract : contracts()) if (contract.key().equals(key)) return contract;
		return null;
	}

	/** The ecosystems whose native game calls {@code contract}'s method: the census rows for it. */
	private static Set<Ecosystem> nativeCallers(Contract contract) {
		Set<Ecosystem> out = EnumSet.noneOf(Ecosystem.class);
		for (MergedBaseUncalledMethods.Row row : MergedBaseUncalledMethods.rows())
			if (row.owner().equals(contract.owner()) && row.method().equals(contract.name() + contract.desc())) out.addAll(row.ecosystems());
		return out;
	}

	/**
	 * Whether a hook of a mod of {@code ecosystem} in {@code owner.name(desc)} runs through a relay — so the liveness rule does
	 * not report it as one that never runs (the final class says so if the relay was declined after all).
	 */
	static boolean relays(String owner, String name, String desc, Ecosystem ecosystem) {
		if (!enabled() || ecosystem == null) return false;
		for (Contract contract : contracts())
			if (contract.owner().equals(owner) && contract.name().equals(name) && contract.desc().equals(desc)) return nativeCallers(contract).contains(ecosystem);
		return false;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// After Mixin: the relay

	/** {@code woven} with a relay of each contract method of the class, when one can be made; {@code woven} itself otherwise. */
	public static byte[] apply(String name, byte[] preMixin, byte[] woven) {
		if (woven == null || preMixin == null || !enabled()) return woven;
		String internal = name.replace('.', '/');
		for (Contract contract : contracts()) {
			if (!contract.owner().equals(internal)) continue;
			try {
				byte[] relayed = synthesize(contract, preMixin, woven);
				if (relayed != null) woven = relayed;
			} catch (RuntimeException unreadable) {
				Caught.warn(unreadable, "[Forbric/Mixin] could not relay %s; its woven hooks stay where nothing calls them", contract.display());
			}
		}
		return woven;
	}

	/** The class with the relay added; null when nothing was woven or the relay cannot stand for the method (said why). */
	static byte[] synthesize(Contract contract, byte[] preMixin, byte[] woven) {
		ClassNode node = new ClassNode(), before = new ClassNode();
		new ClassReader(woven).accept(node, 0);
		new ClassReader(preMixin).accept(before, ClassReader.SKIP_FRAMES);
		MethodNode method = method(node, contract.name(), contract.desc()), original = method(before, contract.name(), contract.desc());
		if (method == null || original == null || method(node, contract.name() + SUFFIX, contract.desc()) != null) return null;
		if (text(method).equals(text(original))) return null;
		String why = declined(contract, node, before, method, original);
		if (why != null) {
			ForbricLog.warn("[Forbric/Mixin] %s never runs in the merged game, and the hooks woven into it are not relayed to where the carrier "
					+ "computes its value — %s; they stay where nothing calls them", contract.display(), why);
			return null;
		}
		MethodNode relay = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC | (method.access & Opcodes.ACC_STATIC), contract.name() + SUFFIX,
				contract.desc(), null, method.exceptions == null ? null : method.exceptions.toArray(String[]::new));
		method.accept(relay);
		relay.visibleAnnotations = null;
		relay.invisibleAnnotations = null;
		relay.visibleParameterAnnotations = null;
		relay.invisibleParameterAnnotations = null;
		relay.localVariables = null;
		for (AbstractInsnNode instruction : relay.instructions.toArray()) {
			if (!(instruction instanceof MethodInsnNode call)) continue;
			String role = key(call).equals(contract.draw()) ? "draw" : key(call).equals(contract.process()) ? "process"
					: key(call).equals(contract.effect()) ? "effect" : null;
			if (role == null) continue;
			relay.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, role, erased(call), false));
		}
		node.methods.add(relay);
		// A hook that reads the parameter the carrier does not hand on is handed what the native caller passed there, when that can
		// be read off the caller (relayFor); a block where it cannot is not relayed.
		int slot = (method.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
		Type[] parameters = Type.getArgumentTypes(contract.desc());
		for (int i = 0; i < contract.unread(); i++) slot += parameters[i].getSize();
		if (loads(method, slot) != loads(original, slot)) READS_UNREAD.add(contract.key());
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		List<String> hooks = hooks(node, before, method);
		ForbricLog.info("[Forbric/Mixin] %s never runs in the merged game; the %d hook(s) woven into it (%s) run in %s%s, on the value the carrier "
				+ "computes in its place", contract.display(), hooks.size(), String.join(", ", hooks), contract.name(), SUFFIX);
		return writer.toByteArray();
	}

	/** Why the relay cannot stand for {@code method}; null when it can. */
	private static String declined(Contract contract, ClassNode node, ClassNode before, MethodNode method, MethodNode original) {
		Set<Ecosystem> natives = nativeCallers(contract);
		if (natives.isEmpty()) return "the census lists no ecosystem whose native game calls it";
		for (String mixin : mixinsWovenInto(node, before, method)) {
			Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.replace('.', '/'));
			if (ecosystem == null || !natives.contains(ecosystem))
				return "a hook of " + mixin + " (" + ecosystem + ") is woven there, and its own game never calls the method either";
		}
		for (String call : List.of(contract.draw(), contract.process(), contract.effect())) {
			if (count(method, call) != 1 || count(original, call) != 1) return "it no longer makes exactly one call of " + call + " (a hook replaced or wrapped it)";
		}
		MethodInsnNode draw = find(method, contract.draw());
		AbstractInsnNode next = draw.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		if (!(next instanceof MethodInsnNode process) || !key(process).equals(contract.process()))
			return "a hook reads or changes the raw value between " + contract.draw() + " and " + contract.process() + ", which the carrier does not hand on";
		for (String role : List.of("draw", "process", "effect")) {
			String call = role.equals("draw") ? contract.draw() : role.equals("process") ? contract.process() : contract.effect();
			String desc = erased(find(method, call));
			try {
				MethodHandles.lookup().findStatic(UncalledValueRelay.class, role,
						java.lang.invoke.MethodType.fromMethodDescriptorString(desc, UncalledValueRelay.class.getClassLoader()));
			} catch (ReflectiveOperationException | RuntimeException absent) {
				Caught.expected(absent);
				return "the kernel has no stand-in of the shape " + desc + " for " + call;
			}
		}
		return null;
	}

	/** The mixins whose merged methods {@code method}'s woven body calls or hands as a method reference. */
	private static Set<String> mixinsWovenInto(ClassNode node, ClassNode before, MethodNode method) {
		Set<String> out = new LinkedHashSet<>();
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof MethodInsnNode call && call.owner.equals(node.name)) addMixin(node, before, call.name, call.desc, out);
			else if (instruction instanceof InvokeDynamicInsnNode indy) {
				for (Object argument : indy.bsmArgs)
					if (argument instanceof Handle handle && handle.getOwner().equals(node.name)) addMixin(node, before, handle.getName(), handle.getDesc(), out);
			}
		}
		return out;
	}

	private static void addMixin(ClassNode node, ClassNode before, String name, String desc, Set<String> out) {
		if (method(before, name, desc) != null) return;
		MethodNode merged = method(node, name, desc);
		if (merged == null || merged.visibleAnnotations == null) return;
		for (AnnotationNode annotation : merged.visibleAnnotations)
			if (annotation.desc.equals(MERGED) && MixinFit.value(annotation, "mixin") instanceof String mixin) out.add(mixin);
	}

	/** {@code mixin.Class.handler} for each hook the woven body calls, for the log. */
	private static List<String> hooks(ClassNode node, ClassNode before, MethodNode method) {
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) {
			if (!(instruction instanceof MethodInsnNode call) || !call.owner.equals(node.name) || method(before, call.name, call.desc) != null) continue;
			MethodNode merged = method(node, call.name, call.desc);
			if (merged == null || merged.visibleAnnotations == null) continue;
			for (AnnotationNode annotation : merged.visibleAnnotations)
				if (annotation.desc.equals(MERGED) && MixinFit.value(annotation, "mixin") instanceof String mixin) out.add(mixin + "." + call.name);
		}
		return out;
	}

	/** The call's descriptor as a static method of the kernel's: its receiver first, every reference type as Object. */
	private static String erased(MethodInsnNode call) {
		List<Type> parameters = new ArrayList<>();
		if (call.getOpcode() != Opcodes.INVOKESTATIC) parameters.add(Type.getType(Object.class));
		for (Type argument : Type.getArgumentTypes(call.desc)) parameters.add(erase(argument));
		return Type.getMethodDescriptor(erase(Type.getReturnType(call.desc)), parameters.toArray(Type[]::new));
	}

	private static Type erase(Type type) {
		return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY ? Type.getType(Object.class) : type;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// While the relay runs

	/** {carried value, effect total, depth}, for the relay this thread is running. */
	private static final ThreadLocal<int[]> RUNNING = new ThreadLocal<>();

	/** The kernel's draw stand-in: the carrier already drew; nothing is drawn again. */
	public static int draw(Object provider, Object random) {
		return 0;
	}

	/** The kernel's processing stand-in: the value the carrier computed, which the relay was started with. */
	public static int process(Object level, Object tool, int ignored) {
		int[] running = RUNNING.get();
		return running == null ? 0 : running[0];
	}

	/** The kernel's effect stand-in: the amount the method would have popped, recorded for the carrier to pop. */
	public static void effect(Object owner, Object level, Object pos, int amount) {
		int[] running = RUNNING.get();
		if (running != null) running[1] += amount;
	}

	/**
	 * Runs {@code relay} on {@code arguments} (the receiver first) as the contract method would have run, handed {@code carried}
	 * where it processed its value; returns what it would have done with it (the amounts its effect was handed, summed).
	 */
	public static int run(Relay relay, int carried, Object... arguments) throws Throwable {
		int[] outer = RUNNING.get();
		int[] running = { carried, 0 };
		Object[] handed = arguments.clone();
		handed[1 + relay.unread()] = relay.argument() == null ? null : relay.argument().apply(arguments[0]);
		RUNNING.set(running);
		try {
			relay.handle().invokeWithArguments(handed);
			return running[1];
		} finally {
			if (outer == null) RUNNING.remove();
			else RUNNING.set(outer);
		}
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Which instances the native game asked

	/** The contracts whose woven body reads the parameter the carrier does not hand on. */
	private static final Set<String> READS_UNREAD = ConcurrentHashMap.newKeySet();

	/** A relay to run for one class: the method, the unread parameter's index, and what the native caller passed there (or null). */
	public record Relay(MethodHandle handle, int unread, java.util.function.Function<Object, Object> argument) {
	}

	private static final Map<Contract, ClassValue<Relay>> RELAYS = new ConcurrentHashMap<>();

	/**
	 * The relay to run for an instance of {@code type} in place of {@code contract}'s method, as the native game would have
	 * called it there; null when there is none, the type overrides the method, or no native caller of it is what {@code type}
	 * runs (the census's callers, followed through classes whose override only adds to its parent's).
	 */
	public static Relay relayFor(Class<?> type, Contract contract) {
		if (type == null || contract == null || !enabled()) return null;
		return RELAYS.computeIfAbsent(contract, c -> new ClassValue<>() {
			@Override protected Relay computeValue(Class<?> of) {
				try {
					return resolveRelay(of, c);
				} catch (LinkageError | RuntimeException unresolvable) {
					// Reading a class's declared methods links their types; one the merged game cannot link is not asked.
					Caught.warn(unresolvable, "[Forbric/Mixin] could not tell whether the native game asks %s of %s; its relay is not run for it",
							c.key(), of.getName());
					return null;
				}
			}
		}).get(type);
	}

	private static Relay resolveRelay(Class<?> type, Contract contract) {
		Class<?> owner = type;
		while (owner != null && !owner.getName().replace('.', '/').equals(contract.owner())) owner = owner.getSuperclass();
		if (owner == null) return null;
		Method relay = null;
		for (Method candidate : owner.getDeclaredMethods())
			if (candidate.getName().equals(contract.name() + SUFFIX) && Type.getMethodDescriptor(candidate).equals(contract.desc())) relay = candidate;
		if (relay == null || !Modifier.isPublic(relay.getModifiers())) return null;
		// An override of the method is what the native game called on this type: a relay of the owner's would stand for the wrong body.
		for (Class<?> c = type; c != owner; c = c.getSuperclass()) {
			for (Method declared : c.getDeclaredMethods())
				if (declared.getName().equals(contract.name()) && Type.getMethodDescriptor(declared).equals(contract.desc())) return null;
		}
		String caller = nativeCaller(type, contract);
		if (caller == null) return null;
		java.util.function.Function<Object, Object> argument = nativeArgument(type, contract, caller);
		if (argument == null && READS_UNREAD.contains(contract.key())) {
			ForbricLog.warn("[Forbric/Mixin] a hook woven into %s reads its parameter %d, and what %s hands it there cannot be read for %s; the relay "
					+ "is not run for it", contract.display(), contract.unread(), caller, type.getName());
			return null;
		}
		try {
			return new Relay(MethodHandles.publicLookup().unreflect(relay), contract.unread(), argument);
		} catch (IllegalAccessException unreachable) {
			Caught.warn(unreachable, "[Forbric/Mixin] the relay %s%s of %s cannot be called", contract.name(), SUFFIX, owner.getName());
			return null;
		}
	}

	/**
	 * Whether the native game asks {@code contract}'s method of an instance of {@code type}: the method the census names as a
	 * native caller is what {@code type} runs — the first class declaring its name and descriptor is that caller's class, or
	 * declares an override whose body calls its parent's (read from the class's own bytes) and its parent's is.
	 */
	static boolean nativelyAsked(Class<?> type, Contract contract) {
		return nativeCaller(type, contract) != null;
	}

	/** The census's native caller {@code type} runs (see {@link #nativelyAsked}); null when none. */
	static String nativeCaller(Class<?> type, Contract contract) {
		Set<String> callers = new LinkedHashSet<>();
		Set<Ecosystem> natives = nativeCallers(contract);
		for (MergedBaseUncalledMethods.Row row : MergedBaseUncalledMethods.rows()) {
			if (!row.owner().equals(contract.owner()) || !row.method().equals(contract.name() + contract.desc())) continue;
			if (row.ecosystems().stream().anyMatch(natives::contains)) callers.addAll(row.callers());
		}
		for (String caller : callers) {
			int hash = caller.indexOf('#'), paren = caller.indexOf('(', hash);
			if (hash < 0 || paren < 0) continue;
			String callerOwner = caller.substring(0, hash), name = caller.substring(hash + 1, paren), desc = caller.substring(paren);
			Class<?> at = type;
			for (int guard = 0; at != null && guard < 16; guard++) {
				Class<?> declaring = declaring(at, name, desc);
				if (declaring == null) break;
				if (declaring.getName().replace('.', '/').equals(callerOwner)) return caller;
				if (!callsParent(declaring, name, desc)) break;
				at = declaring.getSuperclass();
			}
		}
		return null;
	}

	/**
	 * What the native {@code caller} hands the contract method's unread parameter, as a function of the instance: a field of
	 * the instance, a static field, or a static call on constants, read off the caller's native body and evaluated on the merged
	 * classes; null when it is anything else.
	 */
	static java.util.function.Function<Object, Object> nativeArgument(Class<?> type, Contract contract, String caller) {
		int hash = caller.indexOf('#'), paren = caller.indexOf('(', hash);
		String owner = caller.substring(0, hash), name = caller.substring(hash + 1, paren), desc = caller.substring(paren);
		ClassNode nativeOwner = null;
		for (Ecosystem ecosystem : nativeCallers(contract)) {
			nativeOwner = NativeGameReferences.reference(ecosystem, owner);
			if (nativeOwner != null) break;
		}
		MethodNode body = nativeOwner == null ? null : method(nativeOwner, name, desc);
		if (body == null || body.instructions == null) return null;
		org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue>[] frames;
		try {
			frames = new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.SourceInterpreter()).analyze(owner, body);
		} catch (org.objectweb.asm.tree.analysis.AnalyzerException unreadable) {
			Caught.expected(unreadable);
			return null;
		}
		Type[] arguments = Type.getArgumentTypes(contract.desc());
		java.util.function.Function<Object, Object> found = null;
		for (int i = 0; i < body.instructions.size(); i++) {
			if (!(body.instructions.get(i) instanceof MethodInsnNode call) || !call.name.equals(contract.name()) || !call.desc.equals(contract.desc())
					|| frames[i] == null) continue;
			var value = frames[i].getStack(frames[i].getStackSize() - arguments.length + contract.unread());
			if (value.insns.size() != 1 || found != null) return null;
			found = evaluator(type, value.insns.iterator().next(), frames[body.instructions.indexOf(value.insns.iterator().next())]);
			if (found == null) return null;
		}
		return found;
	}

	private static java.util.function.Function<Object, Object> evaluator(Class<?> type, AbstractInsnNode source,
			org.objectweb.asm.tree.analysis.Frame<org.objectweb.asm.tree.analysis.SourceValue> at) {
		ClassLoader loader = type.getClassLoader();
		try {
			if (source instanceof org.objectweb.asm.tree.FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD) {
				var receiver = at.getStack(at.getStackSize() - 1);
				if (receiver.insns.size() != 1 || !(receiver.insns.iterator().next() instanceof VarInsnNode self) || self.var != 0) return null;
				java.lang.reflect.Field reflected = field(Class.forName(field.owner.replace('/', '.'), false, loader), field.name);
				if (reflected == null || Modifier.isStatic(reflected.getModifiers())) return null;
				reflected.setAccessible(true);
				return instance -> get(reflected, instance);
			}
			if (source instanceof org.objectweb.asm.tree.FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC) {
				java.lang.reflect.Field reflected = field(Class.forName(field.owner.replace('/', '.'), false, loader), field.name);
				if (reflected == null || !Modifier.isStatic(reflected.getModifiers())) return null;
				reflected.setAccessible(true);
				return instance -> get(reflected, null);
			}
			if (source instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC) {
				Type[] parameters = Type.getArgumentTypes(call.desc);
				Object[] constants = new Object[parameters.length];
				for (int k = 0; k < parameters.length; k++) {
					var operand = at.getStack(at.getStackSize() - parameters.length + k);
					if (operand.insns.size() != 1) return null;
					Object constant = constant(operand.insns.iterator().next());
					if (constant == null) return null;
					constants[k] = constant;
				}
				Class<?> owner = Class.forName(call.owner.replace('.', '/').replace('/', '.'), false, loader);
				MethodHandle target = MethodHandles.privateLookupIn(owner, MethodHandles.lookup()).findStatic(owner, call.name,
						java.lang.invoke.MethodType.fromMethodDescriptorString(call.desc, loader));
				return instance -> {
					try {
						return target.invokeWithArguments(constants);
					} catch (Throwable thrown) {
						throw new IllegalStateException("the native argument " + call.owner + "." + call.name + " threw", thrown);
					}
				};
			}
		} catch (ReflectiveOperationException | RuntimeException | LinkageError unresolvable) {
			Caught.expected(unresolvable);
		}
		return null;
	}

	private static java.lang.reflect.Field field(Class<?> type, String name) {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			try {
				return c.getDeclaredField(name);
			} catch (NoSuchFieldException absent) {
				Caught.expected(absent);
			}
		}
		return null;
	}

	private static Object get(java.lang.reflect.Field field, Object instance) {
		try {
			return field.get(instance);
		} catch (IllegalAccessException unreachable) {
			throw new IllegalStateException(unreachable);
		}
	}

	/** The value a constant-pushing instruction pushes, boxed as its type; null when it pushes no constant. */
	private static Object constant(AbstractInsnNode push) {
		int op = push.getOpcode();
		if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return op - Opcodes.ICONST_0;
		if (op == Opcodes.LCONST_0 || op == Opcodes.LCONST_1) return (long) (op - Opcodes.LCONST_0);
		if (op >= Opcodes.FCONST_0 && op <= Opcodes.FCONST_2) return (float) (op - Opcodes.FCONST_0);
		if (op == Opcodes.DCONST_0 || op == Opcodes.DCONST_1) return (double) (op - Opcodes.DCONST_0);
		if (push instanceof org.objectweb.asm.tree.IntInsnNode value && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)) return value.operand;
		if (push instanceof org.objectweb.asm.tree.LdcInsnNode ldc && !(ldc.cst instanceof Type) && !(ldc.cst instanceof Handle)) return ldc.cst;
		return null;
	}

	/** The first class from {@code type} up that declares {@code name(desc)}. */
	private static Class<?> declaring(Class<?> type, String name, String desc) {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			for (Method declared : c.getDeclaredMethods())
				if (declared.getName().equals(name) && Type.getMethodDescriptor(declared).equals(desc)) return c;
		}
		return null;
	}

	/** Whether {@code type}'s own {@code name(desc)} calls its parent's, read from the bytes its loader serves for it. */
	private static boolean callsParent(Class<?> type, String name, String desc) {
		Class<?> parent = type.getSuperclass();
		if (parent == null) return false;
		String internal = type.getName().replace('.', '/');
		ClassLoader loader = type.getClassLoader();
		try (var in = loader == null ? ClassLoader.getSystemResourceAsStream(internal + ".class") : loader.getResourceAsStream(internal + ".class")) {
			if (in == null) return false;
			ClassNode node = new ClassNode();
			new ClassReader(in.readAllBytes()).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
			MethodNode method = method(node, name, desc);
			if (method == null || method.instructions == null) return false;
			for (AbstractInsnNode instruction : method.instructions)
				if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && call.name.equals(name) && call.desc.equals(desc)
						&& !call.owner.equals(internal)) return true;
			return false;
		} catch (java.io.IOException | RuntimeException unreadable) {
			Caught.expected(unreadable);
			return false;
		}
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}

	private static MethodInsnNode find(MethodNode method, String call) {
		for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode m && key(m).equals(call)) return m;
		return null;
	}

	private static int count(MethodNode method, String call) {
		int n = 0;
		for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode m && key(m).equals(call)) n++;
		return n;
	}

	private static int loads(MethodNode method, int slot) {
		int n = 0;
		for (AbstractInsnNode instruction : method.instructions)
			if (instruction instanceof VarInsnNode load && load.getOpcode() >= Opcodes.ILOAD && load.getOpcode() <= Opcodes.ALOAD && load.var == slot) n++;
		return n;
	}

	private static String key(MethodInsnNode call) {
		return call.owner + "." + call.name + call.desc;
	}

	private static String text(MethodNode method) {
		StringBuilder out = new StringBuilder();
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction.getOpcode() < 0) continue;
			out.append(instruction.getOpcode()).append(' ');
			if (instruction instanceof MethodInsnNode call) out.append(key(call));
			else if (instruction instanceof VarInsnNode local) out.append(local.var);
			out.append(';');
		}
		return out.toString();
	}
}
