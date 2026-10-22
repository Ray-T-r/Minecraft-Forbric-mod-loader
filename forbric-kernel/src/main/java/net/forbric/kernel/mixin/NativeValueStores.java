/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Points a value modifier written for the store of a call's result into a local at the store the merged method keeps that
 * result in, where a carrier stores another value first into the local its discriminator picks.
 *
 * <p>Vanilla's {@code LivingEntity.completeUsingItem} stores {@code useItem.finishUsingItem(level, this)} in its first
 * {@code ItemStack} local, and a {@code @ModifyVariable(at = @At("STORE"), ordinal = 0)} there changes what goes back into the
 * hand. NeoForge first keeps a copy of the item in a new first {@code ItemStack} local, then stores
 * {@code EventHooks.onItemUseFinish(this, copy, ticks, useItem.finishUsingItem(level, this))} in a second one. The modifier
 * bound the copy's store: it ran before the item was used at all, on a value vanilla never stores there, and before the
 * {@code @Inject} at {@code finishUsingItem} that fills the {@code @Share} it reads — PuzzlesLib's handler threw on that
 * {@code null}, and eating any food crashed the server.
 *
 * <p>Nothing here knows a mod, a class or a method by name. A modifier moves when its injector is a {@code @ModifyVariable} with
 * one {@code @At("STORE")} — no ordinal, shift or slice there, no slice on the injector — whose local is picked by type and
 * ordinal (or by type alone), not by name or index, and:
 * <ul>
 *   <li>in the method its selectors bind in the class the mod was compiled against, the discriminator picks exactly one store,
 *       and the value stored is the result of the call right before it;</li>
 *   <li>in the merged method none of the stores the discriminator picks stores that call's result, and the merged method makes
 *       that call exactly once; its result is stored, straight on, into a local of the modifier's type — or handed, as the last
 *       argument, to a call answering that type (a carrier's hook around the native value), whose answer is stored so;</li>
 *   <li>that local is stored nowhere else in the merged method.</li>
 * </ul>
 * The discriminator then names that local by its index: the modifier runs once, on the store of the native value — after the
 * carrier's hook, which the native value has passed through — and every injector at the native call runs before it, as
 * natively. {@code -Dforbric.nativeValueStores=off} moves nothing.
 */
public final class NativeValueStores {
	public static final String PROPERTY = "forbric.nativeValueStores";
	private static final String MODIFY_VARIABLE = "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";

	private NativeValueStores() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Moves every eligible modifier of {@code mixin}; returns how many. {@code code} and {@code natives} return nodes WITH code. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> code, BiFunction<Ecosystem, String, ClassNode> natives) {
		if (!enabled() || mixin == null || mixin.methods == null || code == null || natives == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		List<String> owners = MixinOverloadPin.targetsOf(mixin);
		if (ecosystem == null || owners.size() != 1) return 0;
		ClassNode original = null, current = null;
		int moved = 0;
		for (MethodNode handler : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(handler);
			if (injector == null || !MODIFY_VARIABLE.equals(injector.desc) || MixinFit.value(injector, "slice") != null
					|| MixinFit.groupOf(handler) != null || CurrentBodyOrdinals.counted(handler)
					|| MixinFit.value(injector, "index") != null || !MixinFit.stringList(MixinFit.value(injector, "name")).isEmpty()
					|| Boolean.TRUE.equals(MixinFit.value(injector, "argsOnly"))) continue;
			List<AnnotationNode> ats = MixinFit.atNodes(injector);
			if (ats.size() != 1) continue;
			AnnotationNode at = ats.getFirst();
			if (!"STORE".equals(MixinFit.asString(MixinFit.value(at, "value"))) || MixinFit.value(at, "ordinal") != null
					|| MixinFit.value(at, "shift") != null || MixinFit.value(at, "by") != null || MixinFit.value(at, "slice") != null) continue;
			Type[] parameters = Type.getArgumentTypes(handler.desc);
			if (parameters.length == 0 || !Type.getReturnType(handler.desc).equals(parameters[0])) continue;
			Type type = parameters[0];
			if (original == null) original = natives.apply(ecosystem, owners.getFirst());
			if (original == null) return moved;
			if (current == null) current = code.apply(owners.getFirst());
			if (current == null) return moved;
			MethodNode was = MixinTargetSelectors.one(handler, original), host = MixinTargetSelectors.one(handler, current);
			if (was == null || host == null || !was.name.equals(host.name) || !was.desc.equals(host.desc)
					|| was.instructions == null || host.instructions == null) continue;
			int slot = target(injector, type, was, host);
			if (slot < 0) continue;
			List<Object> values = new ArrayList<>(injector.values);
			for (int i = 0; i + 1 < values.size(); i += 2) {
				if (!"ordinal".equals(values.get(i))) continue;
				values.remove(i);
				values.remove(i);
				break;
			}
			values.add("index");
			values.add(slot);
			injector.values = values;
			moved++;
			ForbricLog.info("[Forbric/Mixin] %s.%s: the merged %s.%s stores another value first in the local its STORE picks; the native "
					+ "value is stored in local %d — the modifier moves there, so it runs on the native value's store, after every injector "
					+ "at the call that makes it", mixin.name.replace('/', '.'), handler.name, current.name.replace('/', '.'), host.name, slot);
		}
		return moved;
	}

	/**
	 * The merged local the native value is stored in, when the modifier must move there; -1 when the proof fails or it already
	 * lands on that store.
	 */
	static int target(AnnotationNode injector, Type type, MethodNode was, MethodNode host) {
		List<VarInsnNode> nativeStores = picked(injector, type, was);
		if (nativeStores.size() != 1) return -1;
		MethodInsnNode call = producer(nativeStores.getFirst());
		if (call == null || !Type.getReturnType(call.desc).equals(type)) return -1;
		List<VarInsnNode> bound = picked(injector, type, host);
		// Nothing read off the merged local variable table says what the modifier binds there now: left as compiled.
		if (bound.isEmpty()) return -1;
		for (VarInsnNode store : bound) if (same(producer(store), call)) return -1;
		MethodInsnNode made = null;
		for (AbstractInsnNode instruction : host.instructions) {
			if (!(instruction instanceof MethodInsnNode each) || !same(each, call)) continue;
			if (made != null) return -1;
			made = each;
		}
		if (made == null) return -1;
		AbstractInsnNode next = nextReal(made);
		if (next instanceof MethodInsnNode hook) {
			Type[] arguments = Type.getArgumentTypes(hook.desc);
			if (arguments.length == 0 || !arguments[arguments.length - 1].equals(type) || !Type.getReturnType(hook.desc).equals(type)) return -1;
			next = nextReal(hook);
		}
		if (!(next instanceof VarInsnNode store) || store.getOpcode() != type.getOpcode(Opcodes.ISTORE)) return -1;
		for (AbstractInsnNode instruction : host.instructions)
			if (instruction != store && instruction instanceof VarInsnNode other && other.var == store.var
					&& other.getOpcode() >= Opcodes.ISTORE && other.getOpcode() <= Opcodes.ASTORE) return -1;
		// The discriminator picks that store already (a carrier's hook around the value, kept in the same local): nothing moves.
		if (bound.contains(store)) return -1;
		return store.var;
	}

	/** The stores of {@code type} in {@code method} whose local is the one the modifier's discriminator picks right after them. */
	static List<VarInsnNode> picked(AnnotationNode injector, Type type, MethodNode method) {
		AnnotationNode discriminator = new AnnotationNode("Lcom/llamalad7/mixinextras/sugar/Local;");
		discriminator.values = new ArrayList<>();
		if (MixinFit.value(injector, "ordinal") instanceof Number ordinal) {
			discriminator.values.add("ordinal");
			discriminator.values.add(ordinal.intValue());
		}
		List<VarInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) {
			if (!(instruction instanceof VarInsnNode store) || store.getOpcode() != type.getOpcode(Opcodes.ISTORE)) continue;
			// Read where Mixin reads a STORE's locals: after the store, at the next instruction that executes (a local's table
			// entry starts at a label, which a line number's label may precede).
			AbstractInsnNode next = nextReal(store);
			int after = next == null ? method.instructions.indexOf(store) + 1 : method.instructions.indexOf(next);
			if (MixinLocalOriginProof.slot(discriminator, type, method, after) == store.var) out.add(store);
		}
		return out;
	}

	/** The call whose result {@code store} stores, when it is the instruction right before it; null otherwise. */
	private static MethodInsnNode producer(VarInsnNode store) {
		AbstractInsnNode previous = store.getPrevious();
		while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
		return previous instanceof MethodInsnNode call ? call : null;
	}

	private static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
		AbstractInsnNode next = instruction.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		return next;
	}

	private static boolean same(MethodInsnNode a, MethodInsnNode b) {
		return a != null && b != null && a.getOpcode() == b.getOpcode() && a.owner.equals(b.owner) && a.name.equals(b.name) && a.desc.equals(b.desc);
	}
}
