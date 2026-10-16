/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.NativeGameReferences;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Puts a native {@code field.clear()} back where a carrier replaced it with removing a computed subset of the field's
 * keys, so a guest's injector on that clear lands where it lands natively and runs with its native semantics.
 *
 * <p>The case that found it: vanilla's {@code LivingEntity.removeAllEffects} copies {@code activeEffects}, calls
 * {@code activeEffects.clear()} and hands the copy to {@code onEffectsRemoved}. NeoForge's body asks its own
 * per-effect veto, collects the effects it lets go in a local map, removes exactly those keys
 * ({@code toRemove.keySet().forEach(activeEffects::remove)}) and hands that map on — there is no {@code clear()} at
 * all. A guest mod's {@code @WrapOperation} of the native {@code clear()} (fabric-api's clear-all veto, Balm's) then
 * had nothing to bind to, so the kernel ran a substitute in its place, and the substitute could not keep what the
 * native code does: fabric-api's put-back veto leaves the vetoed effect in the vanilla copy, so natively it still goes
 * through {@code onEffectsRemoved} (its {@code AFTER_REMOVE} fires, the client is told it is gone), while Balm takes
 * it out of that copy through its own {@code @Local} so natively it does not.
 *
 * <p>The relay replaces the carrier's subset removal with {@code ScopedClearView.of(field, subset).clear()}: one
 * {@code Map.clear()} call on the field, through a view that sees exactly the entries the carrier is removing and
 * writes through to the field. Without a guest it removes the same keys the carrier removed. With one, the guest's
 * handler receives what the native clear's map would hold (minus what the carrier itself kept — its veto runs first,
 * as natively it would not run at all), calls the original clear through its {@code Operation}, and whatever it puts
 * back goes into the field; the carrier's subset map stays the method's local, so a guest's {@code @Local} of the
 * native copy finds it where the native copy was.
 *
 * <p>Applied only where it is proved, never by a class or mod name: in a method whose native reference (as Fabric or
 * MinecraftForge compiled the class) calls {@code Map.clear()} on a field read from {@code this}, and whose merged
 * body calls no {@code clear()} on that field and instead removes {@code local.keySet()} from it through a
 * {@code Map::remove} method reference, as javac lowers {@code local.keySet().forEach(this.field::remove)}.
 * {@code -Dforbric.scopedClearRelay=off} leaves such bodies as shipped.
 */
public final class ScopedClearRelayInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.scopedClearRelay";
	static final String VIEW = "net/forbric/kernel/interop/ScopedClearView";
	private static final String MAP = "java/util/Map";
	private final BiFunction<Ecosystem, String, ClassNode> originals;

	public ScopedClearRelayInjector() {
		this(NativeGameReferences::reference);
	}

	ScopedClearRelayInjector(BiFunction<Ecosystem, String, ClassNode> originals) {
		this.originals = originals;
	}

	@Override public String name() { return "forbric-scoped-clear-relay"; }

	@Override public AnchorSet anchors() {
		return AnchorSet.scanned("a native field.clear() a carrier replaced with removing a computed key subset, proved per method "
				+ "against the native reference");
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (bytes == null || "off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return bytes;
		String text = new String(bytes, StandardCharsets.ISO_8859_1);
		if (!text.contains("keySet") || !text.contains("LambdaMetafactory") || !text.contains("java/util/Map")) return bytes;
		ClassNode owner = new ClassNode();
		new ClassReader(bytes).accept(owner, 0);
		List<String> relayed = relay(owner);
		if (relayed.isEmpty()) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		owner.accept(writer);
		ForbricLog.info("[Forbric/ScopedClear] %s: %s — the carrier removes a computed subset of the field's keys where the "
				+ "native method clears it; a guest's injector on that clear lands on it again", className, relayed);
		return writer.toByteArray();
	}

	/** Rewrites every proved site in {@code owner}; returns {@code method(field)} for each. */
	List<String> relay(ClassNode owner) {
		List<String> relayed = new ArrayList<>();
		for (MethodNode method : owner.methods) {
			if (method.instructions == null || method.instructions.size() == 0 || (method.access & Opcodes.ACC_STATIC) != 0) continue;
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (!(insn instanceof InvokeDynamicInsnNode indy)) continue;
				Site site = site(owner, indy);
				if (site == null || clears(method, owner.name, site.field) || !nativeClears(owner.name, method, site.field)) continue;
				InsnList replacement = new InsnList();
				replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
				replacement.add(new FieldInsnNode(Opcodes.GETFIELD, owner.name, site.field.name, site.field.desc));
				replacement.add(new VarInsnNode(Opcodes.ALOAD, site.subset.var));
				replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, VIEW, "of", "(Ljava/util/Map;Ljava/util/Map;)Ljava/util/Map;", false));
				replacement.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP, "clear", "()V", true));
				method.instructions.insertBefore(site.first, replacement);
				for (AbstractInsnNode dead : site.run) method.instructions.remove(dead);
				relayed.add(method.name + method.desc + "(" + site.field.name + ")");
			}
		}
		return relayed;
	}

	/** The carrier's removal: the contiguous run javac emits for {@code local.keySet().forEach(this.field::remove)}. */
	private record Site(VarInsnNode subset, FieldInsnNode field, AbstractInsnNode first, List<AbstractInsnNode> run) { }

	private static Site site(ClassNode owner, InvokeDynamicInsnNode indy) {
		if (!"accept".equals(indy.name) || !"(Ljava/util/Map;)Ljava/util/function/Consumer;".equals(indy.desc)
				|| !"java/lang/invoke/LambdaMetafactory".equals(indy.bsm.getOwner()) || indy.bsmArgs.length < 2
				|| !(indy.bsmArgs[1] instanceof Handle impl) || !MAP.equals(impl.getOwner()) || !"remove".equals(impl.getName())
				|| !"(Ljava/lang/Object;)Ljava/lang/Object;".equals(impl.getDesc())) return null;
		List<AbstractInsnNode> run = new ArrayList<>();
		run.add(indy);
		AbstractInsnNode back = previous(indy);
		// javac's null check of the bound receiver: DUP, Objects.requireNonNull, POP (absent from some compilers).
		if (back != null && back.getOpcode() == Opcodes.POP) {
			AbstractInsnNode check = previous(back), dup = check == null ? null : previous(check);
			if (!(check instanceof MethodInsnNode require) || !"java/util/Objects".equals(require.owner)
					|| !"requireNonNull".equals(require.name) || dup == null || dup.getOpcode() != Opcodes.DUP) return null;
			run.add(0, back);
			run.add(0, check);
			run.add(0, dup);
			back = previous(dup);
		}
		if (!(back instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD || !owner.name.equals(field.owner)
				|| !"Ljava/util/Map;".equals(field.desc)) return null;
		AbstractInsnNode self = previous(field);
		if (!(self instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0) return null;
		AbstractInsnNode keySet = previous(self);
		if (!(keySet instanceof MethodInsnNode keys) || !MAP.equals(keys.owner) || !"keySet".equals(keys.name)
				|| !"()Ljava/util/Set;".equals(keys.desc)) return null;
		AbstractInsnNode local = previous(keySet);
		if (!(local instanceof VarInsnNode subset) || subset.getOpcode() != Opcodes.ALOAD || subset.var == 0) return null;
		AbstractInsnNode forEach = next(indy);
		if (!(forEach instanceof MethodInsnNode each) || !"forEach".equals(each.name) || !"(Ljava/util/function/Consumer;)V".equals(each.desc)
				|| !List.of("java/util/Set", "java/util/Collection", "java/lang/Iterable").contains(each.owner)) return null;
		run.add(0, field);
		run.add(0, self);
		run.add(0, keySet);
		run.add(0, local);
		run.add(forEach);
		// Contiguous: nothing between the run's instructions but line numbers (a label could be a jump target).
		for (AbstractInsnNode at = local; at != forEach; at = at.getNext()) {
			if (at.getOpcode() < 0 && at.getType() != AbstractInsnNode.LINE) return null;
		}
		return new Site(subset, field, local, run);
	}

	/** Whether {@code method} already calls {@code clear()} on {@code this.field}. */
	private static boolean clears(MethodNode method, String owner, FieldInsnNode field) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && "clear".equals(call.name) && "()V".equals(call.desc)
					&& previous(call) instanceof FieldInsnNode read && read.getOpcode() == Opcodes.GETFIELD && owner.equals(read.owner)
					&& field.name.equals(read.name) && field.desc.equals(read.desc)) return true;
		}
		return false;
	}

	/** Whether the method as Fabric or MinecraftForge compiled it clears {@code this.field} with {@code Map.clear()}. */
	private boolean nativeClears(String owner, MethodNode merged, FieldInsnNode field) {
		for (Ecosystem ecosystem : List.of(Ecosystem.FABRIC, Ecosystem.FORGE)) {
			ClassNode reference;
			try {
				reference = originals.apply(ecosystem, owner);
			} catch (RuntimeException unavailable) {
				reference = null;
			}
			if (reference == null) continue;
			for (MethodNode method : reference.methods) {
				if (method.name.equals(merged.name) && method.desc.equals(merged.desc) && method.instructions != null
						&& clears(method, owner, field)) return true;
			}
		}
		return false;
	}

	private static AbstractInsnNode previous(AbstractInsnNode insn) {
		do insn = insn.getPrevious(); while (insn != null && insn.getOpcode() < 0);
		return insn;
	}

	private static AbstractInsnNode next(AbstractInsnNode insn) {
		do insn = insn.getNext(); while (insn != null && insn.getOpcode() < 0);
		return insn;
	}
}
