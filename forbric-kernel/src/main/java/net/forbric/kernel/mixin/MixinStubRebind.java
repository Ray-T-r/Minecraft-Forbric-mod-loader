/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Moves a Fabric mod's injector off a merge-added delegating stub onto the method that carries the body.
 *
 * <p>Mixin binds a selector without a descriptor to the FIRST declared method of that name (the selector's default
 * quantifier is one match; {@code TargetSelectors} stops there), and one with a descriptor to exactly that method. A
 * carrier that widened a vanilla method usually kept vanilla's signature in place as a stub —
 * {@code Player.getDestroySpeed(BlockState)} is {@code return getDestroySpeed(state, null)} on the merged base — and
 * put the body in the new overload after it. A Fabric mod was compiled against vanilla, where that one method IS the
 * body, so its injector lands on the stub: an anchor inside the body is simply missing, and a {@code HEAD} or
 * {@code RETURN} injection runs only when something calls the stub. Nothing on the merged base calls
 * {@code getDestroySpeed(BlockState)}: architectury's and Collective's break-speed events never fired.
 *
 * <p>The injector's selector moves to the delegate. A handler that captures the target's arguments is wrapped: the
 * outer takes the delegate's parameters and hands the original the stub's, read off the stub's own delegation call —
 * each stub parameter must reach the call unchanged, or nothing moves. Kept exactly:
 * <ul>
 *   <li>only along a row of {@code carrier-stubs.txt}: the stub's signature is vanilla's and the overload is the
 *       carrier's. Vanilla keeps stub-and-overload pairs of its own ({@code Minecraft.disconnect(Screen, boolean)}
 *       forwarding to the three-argument one) and a mod that chose the short one there meant it;</li>
 *   <li>only mixins from Fabric mods — a NeoForge or MinecraftForge mod was compiled against the stub-first shape and
 *       gets what it would get natively;</li>
 *   <li>only a PURE stub: loads, constants, static fields, zero-argument static factories, non-capturing lambdas and
 *       method references (a constant, like a static field — NeoForge's {@code Language.loadFromJson(InputStream,
 *       BiConsumer)} passes a no-op component consumer) and argument construction, then one call to a same-name
 *       overload of the same class and static-ness, returning its result unchanged; the overload must have a body
 *       (an interface default forwarding to an abstract overload is no stub);</li>
 *   <li>every {@code INVOKE}/{@code FIELD}/{@code NEW} anchor absent from the stub and present in the delegate
 *       ({@code HEAD}, {@code RETURN} and {@code TAIL} are equivalent on both: the stub returns what the delegate
 *       returns);</li>
 *   <li>{@code @Inject} capturing nothing or exactly the stub's arguments, no locals capture; the {@code @At}-driven
 *       kinds without trailing captures; a MixinExtras {@code @Local} only by a name the delegate's local variable
 *       table has in that type; no {@code @Share}, no {@code @Group}.</li>
 * </ul>
 * {@code -Dforbric.mixinStubRebind=off} leaves every selector as compiled.
 */
public final class MixinStubRebind {
	public static final String PROPERTY = "forbric.mixinStubRebind";

	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String CALLBACK_INFO = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	private static final String CALLBACK_INFO_RETURNABLE = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;";
	private static final String LOCAL = "Lcom/llamalad7/mixinextras/sugar/Local;";
	private static final String GROUP = "Lorg/spongepowered/asm/mixin/injection/Group;";
	private static final Set<String> CALL_POINTS = Set.of("INVOKE", "INVOKE_ASSIGN", "INVOKE_STRING", "FIELD", "NEW");
	private static final Set<String> EDGE_POINTS = Set.of("HEAD", "RETURN", "TAIL");

	/** The shipped table of carrier-added stubs; CarrierStubCensusTest pins it to the staged artifacts. */
	static final String TABLE = "/net/forbric/kernel/mixin/carrier-stubs.txt";
	private static volatile Set<String> carrierStubs;

	/** Mixin class (internal name) → the ecosystem of the mod whose config declares it; filled as configs are read. */
	private static final Map<String, Ecosystem> ECOSYSTEMS = new ConcurrentHashMap<>();

	private MixinStubRebind() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Records which family's mod declared {@code mixinInternalName}; null when the config's owner is ambiguous. */
	public static void noteEcosystem(String mixinInternalName, Ecosystem ecosystem) {
		if (mixinInternalName != null && ecosystem != null) ECOSYSTEMS.put(mixinInternalName, ecosystem);
	}

	/** Test seam. */
	static void forget() {
		ECOSYSTEMS.clear();
	}

	/** Moves every eligible injector of a Fabric mod's {@code mixin}; returns how many. {@code targets} must return nodes WITH code. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!enabled() || mixin == null || mixin.methods == null || targets == null) return 0;
		if (ECOSYSTEMS.get(mixin.name) != Ecosystem.FABRIC) return 0;
		List<String> targetNames = MixinOverloadPin.targetsOf(mixin);
		if (targetNames.size() != 1) return 0;   // one target: a selector means one method
		ClassNode target = targets.apply(targetNames.getFirst());
		if (target == null || target.methods == null) return 0;
		int moved = 0;
		for (MethodNode handler : new ArrayList<>(mixin.methods)) {
			if (handler.name.endsWith(MixinHandlerShim.INNER_SUFFIX)) continue;
			MethodNode outer = move(mixin, handler, target);
			if (outer == null) continue;
			if (outer != handler) mixin.methods.add(outer);
			moved++;
		}
		return moved;
	}

	/** The handler to carry the injector after the move (the same one, or a new outer), or null when nothing moves. */
	private static MethodNode move(ClassNode mixin, MethodNode handler, ClassNode target) {
		List<AnnotationNode> annotations = new ArrayList<>();
		if (handler.visibleAnnotations != null) annotations.addAll(handler.visibleAnnotations);
		if (handler.invisibleAnnotations != null) annotations.addAll(handler.invisibleAnnotations);
		if (annotations.stream().anyMatch(a -> GROUP.equals(a.desc))) return null;
		AnnotationNode injector = MixinFit.injectorOf(handler);
		if (injector == null) return null;
		boolean inject = INJECT.equals(injector.desc);
		if (!inject && !MixinRetarget.AT_DRIVEN.contains(injector.desc)) return null;
		if (MixinFit.value(injector, "locals") != null || MixinFit.value(injector, "slice") != null) return null;
		List<String> selectors = MixinFit.stringList(MixinFit.value(injector, "method"));
		if (selectors.size() != 1) return null;
		MethodNode stub = bound(target, selectors.getFirst());
		if (stub == null) return null;
		Delegation delegation = delegation(target, stub);
		if (delegation == null) return null;
		MethodNode delegate = delegation.delegate();
		// Only where the carrier added the overload: when vanilla has both, a Fabric mod that chose the short one meant it.
		if (!carrierStubs().contains(target.name + "#" + stub.name + stub.desc + " -> " + delegate.desc)) return null;

		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.isEmpty()) return null;
		for (AnnotationNode at : points) {
			String value = MixinFit.asString(MixinFit.value(at, "value"));
			if (EDGE_POINTS.contains(value)) continue;
			String member = MixinFit.asString(MixinFit.value(at, "target"));
			if (!CALL_POINTS.contains(value) || member == null) return null;
			if (MixinFit.containsMember(stub, member) || !MixinFit.containsMember(delegate, member)) return null;
		}

		Type[] params = Type.getArgumentTypes(handler.desc);
		Type[] stubParams = Type.getArgumentTypes(stub.desc);
		// Where the handler's call-shaped part ends and its trailing (sugar) parameters begin.
		int plain;
		if (inject) {
			int callback = -1;
			for (int i = 0; i < params.length; i++) {
				String d = params[i].getDescriptor();
				if (CALLBACK_INFO.equals(d) || CALLBACK_INFO_RETURNABLE.equals(d)) { callback = i; break; }
			}
			if (callback != 0 && callback != stubParams.length) return null;
			if (callback == stubParams.length && !Arrays.equals(Arrays.copyOf(params, callback), stubParams)) return null;
			plain = callback + 1;
		} else {
			plain = params.length;
			for (int i = 0; i < params.length; i++) if (annotated(handler, i)) { plain = i; break; }
		}
		for (int i = 0; i < plain; i++) if (annotated(handler, i)) return null;
		for (int i = plain; i < params.length; i++) {
			AnnotationNode local = local(handler, i);
			if (local == null) return null;   // a trailing capture of the stub's arguments, or @Share
			List<String> names = MixinFit.stringList(MixinFit.value(local, "name"));
			if (names.size() != 1 || MixinFit.value(local, "argsOnly") != null || !hasLocal(delegate, names.getFirst(), params[i])) return null;
		}

		String selector = delegate.name + delegate.desc;
		boolean captures = inject && plain - 1 == stubParams.length && stubParams.length > 0;
		MethodNode carrier = handler;
		if (captures) {
			for (int i = 0; i < stubParams.length; i++) if (delegation.positions()[i] < 0) return null;
			carrier = shim(mixin, handler, injector, delegate, delegation.positions(), stubParams.length);
		}
		for (int i = 0; i + 1 < injector.values.size(); i += 2) {
			if ("method".equals(injector.values.get(i))) injector.values.set(i + 1, new ArrayList<>(List.of(selector)));
		}
		ForbricLog.info("[Forbric/Mixin] %s: %s now targets %s.%s%s — Mixin bound its selector to the merge-added stub %s, "
				+ "which only forwards to it%s", mixin.name.replace('/', '.'), handler.name, target.name.replace('/', '.'),
				delegate.name, delegate.desc, stub.desc, captures ? "; the handler still receives the stub's arguments" : "");
		return carrier;
	}

	/** The outer handler for an {@code @Inject} that captured the stub's arguments: delegate parameters in, the original called. */
	private static MethodNode shim(ClassNode mixin, MethodNode handler, AnnotationNode injector, MethodNode delegate, int[] positions, int stubCount) {
		boolean handlerStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		Type[] params = Type.getArgumentTypes(handler.desc);
		Type[] delegateParams = Type.getArgumentTypes(delegate.desc);
		List<Type> outerParams = new ArrayList<>(Arrays.asList(delegateParams));
		outerParams.addAll(Arrays.asList(params).subList(stubCount, params.length));   // callback and sugar
		MethodNode outer = new MethodNode(Opcodes.ASM9, handler.access, handler.name,
				Type.getMethodDescriptor(Type.getReturnType(handler.desc), outerParams.toArray(Type[]::new)), null, null);
		outer.visibleAnnotations = handler.visibleAnnotations == null ? null : new ArrayList<>(handler.visibleAnnotations);
		outer.invisibleAnnotations = handler.invisibleAnnotations == null ? null : new ArrayList<>(handler.invisibleAnnotations);
		int shift = delegateParams.length - stubCount;
		outer.visibleParameterAnnotations = shifted(handler.visibleParameterAnnotations, stubCount, shift, outerParams.size());
		outer.invisibleParameterAnnotations = shifted(handler.invisibleParameterAnnotations, stubCount, shift, outerParams.size());
		int[] slots = new int[outerParams.size()];
		int slot = handlerStatic ? 0 : 1;
		for (int i = 0; i < outerParams.size(); i++) { slots[i] = slot; slot += outerParams.get(i).getSize(); }
		if (!handlerStatic) outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		for (int i = 0; i < stubCount; i++) {
			outer.instructions.add(new VarInsnNode(outerParams.get(positions[i]).getOpcode(Opcodes.ILOAD), slots[positions[i]]));
		}
		for (int i = delegateParams.length; i < outerParams.size(); i++) {
			outer.instructions.add(new VarInsnNode(outerParams.get(i).getOpcode(Opcodes.ILOAD), slots[i]));
		}
		outer.instructions.add(new MethodInsnNode(handlerStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKESPECIAL, mixin.name,
				handler.name + MixinHandlerShim.INNER_SUFFIX, handler.desc, false));
		outer.instructions.add(new InsnNode(Type.getReturnType(handler.desc).getOpcode(Opcodes.IRETURN)));
		outer.maxLocals = slot;
		outer.maxStack = slot + 2;
		handler.name = handler.name + MixinHandlerShim.INNER_SUFFIX;
		handler.visibleAnnotations = without(handler.visibleAnnotations, injector.desc);
		handler.invisibleAnnotations = without(handler.invisibleAnnotations, injector.desc);
		handler.visibleParameterAnnotations = null;
		handler.invisibleParameterAnnotations = null;
		return outer;
	}

	static Set<String> carrierStubs() {
		Set<String> rows = carrierStubs;
		if (rows != null) return rows;
		Set<String> loaded = new java.util.HashSet<>();
		try (java.io.InputStream in = MixinStubRebind.class.getResourceAsStream(TABLE)) {
			if (in != null) {
				for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
					if (!line.isBlank() && !line.startsWith("#")) loaded.add(line.trim());
				}
			}
		} catch (java.io.IOException unreadable) {
			ForbricLog.warn("[Forbric/Mixin] could not read %s; no injector moves off a stub", TABLE);
		}
		carrierStubs = Set.copyOf(loaded);
		return carrierStubs;
	}

	/** The target method Mixin binds {@code selector} to: the first declared of that name, or the one with that descriptor. */
	static MethodNode bound(ClassNode target, String selector) {
		String s = selector.trim();
		if (s.indexOf('*') >= 0 || s.startsWith("/") || s.indexOf(' ') >= 0 || s.indexOf('=') >= 0) return null;
		int semi = s.indexOf(';');
		if (s.startsWith("L") && semi > 0) s = s.substring(semi + 1);
		int paren = s.indexOf('(');
		String name = paren < 0 ? s : s.substring(0, paren), desc = paren < 0 ? null : s.substring(paren);
		for (MethodNode m : target.methods) if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) return m;
		return null;
	}

	/** A stub's delegate, and for each stub parameter the delegate position it reaches unchanged (-1: not directly). */
	record Delegation(MethodNode delegate, int[] positions) {
	}

	/**
	 * The one same-name overload {@code stub} forwards to, with the argument mapping, when {@code stub} is nothing else:
	 * loads, constants, static fields, zero-argument static factories and argument construction feeding one call,
	 * whose result is returned unchanged. Null otherwise.
	 */
	static Delegation delegation(ClassNode owner, MethodNode stub) {
		if (stub.instructions == null || stub.instructions.size() == 0) return null;
		if ((stub.access & (Opcodes.ACC_BRIDGE | Opcodes.ACC_SYNTHETIC)) != 0) return null;   // a compiler's bridge, not a carrier's stub
		boolean isStatic = (stub.access & Opcodes.ACC_STATIC) != 0;
		Type[] stubParams = Type.getArgumentTypes(stub.desc);
		int[] paramBySlot = new int[256];
		Arrays.fill(paramBySlot, -1);
		int slot = isStatic ? 0 : 1;
		for (int i = 0; i < stubParams.length; i++) { if (slot < 256) paramBySlot[slot] = i; slot += stubParams[i].getSize(); }
		List<Integer> stack = new ArrayList<>();   // each entry: the stub parameter it is, -1 synthesized, -2 this
		MethodInsnNode call = null;
		MethodNode found = null;
		int[] mapping = null;
		AbstractInsnNode after = null;
		for (AbstractInsnNode insn = stub.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			int op = insn.getOpcode();
			if (op < 0) continue;
			if (call != null) { after = insn; break; }
			if (insn instanceof VarInsnNode load && op >= Opcodes.ILOAD && op <= Opcodes.ALOAD) {
				stack.add(load.var < 256 ? paramBySlot[load.var] : -1);
				if (!isStatic && load.var == 0) stack.set(stack.size() - 1, -2);
			} else if (insn instanceof LdcInsnNode || (op >= Opcodes.ACONST_NULL && op <= Opcodes.DCONST_1) || op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) {
				stack.add(-1);
			} else if (insn instanceof FieldInsnNode get && op == Opcodes.GETSTATIC) {
				stack.add(-1);
			} else if (insn instanceof TypeInsnNode type && op == Opcodes.NEW) {
				stack.add(-1);
			} else if (insn instanceof InvokeDynamicInsnNode indy && Type.getArgumentTypes(indy.desc).length == 0
					&& "java/lang/invoke/LambdaMetafactory".equals(indy.bsm.getOwner())
					&& ("metafactory".equals(indy.bsm.getName()) || "altMetafactory".equals(indy.bsm.getName()))) {
				stack.add(-1);   // a non-capturing lambda or method reference: a constant
			} else if (op == Opcodes.DUP) {
				if (stack.isEmpty()) return null;
				stack.add(stack.getLast());
			} else if (op == Opcodes.CHECKCAST) {
				if (stack.isEmpty()) return null;
				stack.set(stack.size() - 1, -1);
			} else if (insn instanceof MethodInsnNode m) {
				Type[] args = Type.getArgumentTypes(m.desc);
				if (m.name.equals("<init>") && op == Opcodes.INVOKESPECIAL) {
					if (stack.size() < args.length + 1) return null;
					for (int k = 0; k <= args.length; k++) stack.removeLast();   // the args and the dup'd instance
					continue;
				}
				if (op == Opcodes.INVOKESTATIC && args.length == 0 && !(m.owner.equals(owner.name) && m.name.equals(stub.name))) {
					stack.add(-1);
					continue;
				}
				boolean delegationCall = m.owner.equals(owner.name) && m.name.equals(stub.name) && !m.desc.equals(stub.desc)
						&& (op == Opcodes.INVOKESTATIC) == isStatic;
				if (!delegationCall) return null;
				int receiver = isStatic ? 0 : 1;
				if (stack.size() != args.length + receiver) return null;
				if (!isStatic && stack.getFirst() != -2) return null;
				int[] positions = new int[stubParams.length];
				Arrays.fill(positions, -1);
				for (int j = 0; j < args.length; j++) {
					int source = stack.get(receiver + j);
					if (source >= 0) {
						if (positions[source] >= 0) return null;   // one stub argument passed twice: ambiguous
						positions[source] = j;
					}
				}
				call = m;
				stack.clear();
				MethodNode delegate = null;
				for (MethodNode candidate : owner.methods) if (candidate.name.equals(m.name) && candidate.desc.equals(m.desc)) delegate = candidate;
				if (delegate == null || (delegate.access & Opcodes.ACC_STATIC) != (stub.access & Opcodes.ACC_STATIC)) return null;
				if ((delegate.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || delegate.instructions == null
						|| delegate.instructions.size() == 0) return null;   // nothing there to inject into
				if (!Type.getReturnType(delegate.desc).equals(Type.getReturnType(stub.desc))) return null;
				mapping = positions;
				found = delegate;
			} else {
				return null;
			}
		}
		if (call == null || after == null || after.getOpcode() < Opcodes.IRETURN || after.getOpcode() > Opcodes.RETURN) return null;
		for (AbstractInsnNode insn = after.getNext(); insn != null; insn = insn.getNext()) if (insn.getOpcode() >= 0) return null;
		return new Delegation(found, mapping);
	}

	private static boolean hasLocal(MethodNode method, String name, Type type) {
		if (method.localVariables == null) return false;
		for (LocalVariableNode local : method.localVariables) if (local.name.equals(name) && local.desc.equals(type.getDescriptor())) return true;
		return false;
	}

	private static AnnotationNode local(MethodNode handler, int parameter) {
		for (List<AnnotationNode>[] all : List.of(nonNull(handler.visibleParameterAnnotations), nonNull(handler.invisibleParameterAnnotations))) {
			if (parameter < all.length && all[parameter] != null) for (AnnotationNode a : all[parameter]) if (LOCAL.equals(a.desc)) return a;
		}
		return null;
	}

	private static boolean annotated(MethodNode handler, int parameter) {
		for (List<AnnotationNode>[] all : List.of(nonNull(handler.visibleParameterAnnotations), nonNull(handler.invisibleParameterAnnotations))) {
			if (parameter < all.length && all[parameter] != null && !all[parameter].isEmpty()) return true;
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	private static List<AnnotationNode>[] nonNull(List<AnnotationNode>[] annotations) {
		return annotations == null ? new List[0] : annotations;
	}

	@SuppressWarnings("unchecked")
	private static List<AnnotationNode>[] shifted(List<AnnotationNode>[] original, int from, int shift, int size) {
		if (original == null) return null;
		List<AnnotationNode>[] moved = new List[size];
		for (int i = from; i < original.length; i++) if (original[i] != null && i + shift < size) moved[i + shift] = new ArrayList<>(original[i]);
		return moved;
	}

	private static List<AnnotationNode> without(List<AnnotationNode> annotations, String desc) {
		if (annotations == null) return null;
		List<AnnotationNode> kept = new ArrayList<>();
		for (AnnotationNode a : annotations) if (!desc.equals(a.desc)) kept.add(a);
		return kept;
	}
}
