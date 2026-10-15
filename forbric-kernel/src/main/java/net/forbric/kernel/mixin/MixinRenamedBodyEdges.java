/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Moves a guest's edge injector — its method's start, its end — onto the method a carrier renamed that method's whole
 * body to, when the merged game also runs that body without passing through the method the guest named.
 *
 * <p>NeoForge's {@code PersistentEntitySectionManager.addEntity} posts the entity-join event and hands the rest to
 * {@code addEntityWithoutEvent}, vanilla's body renamed ({@link CarrierRenames}: a whole RENAME, called). Its new
 * {@code addNewEntityWithoutEvent} calls the renamed body directly, and {@code ServerLevel.addPlayer} adds every player
 * through it, having posted the event itself. fabric-lifecycle's {@code ServerEntityEvents.ALLOW_LOAD} is a cancellable
 * {@code HEAD} of vanilla's {@code addEntity}: left on the dispatcher it ran for every entity but players (main too).
 * An anchored injector of the same body is R3's ({@link MixinRetarget}); an edge has no anchor to prove the move by and
 * reads as fitting where it is, so it was never moved.
 *
 * <p>The start and the end of vanilla's method are the start and the end of the renamed body, which carries every call
 * and field access of it; on the renamed method the injector runs once per run of vanilla's body, by whichever path.
 * Kept exactly: the census rows for the mod's ecosystem make the pair a whole RENAME the class still calls, of the same
 * descriptor and static-ness; the named method still calls the renamed body (a dispatcher in front of it, never one that
 * leaves the body to a later continuation); something in the class other than the named method calls the renamed one (otherwise
 * every run of the body still starts at the named method, and nothing moves — NeoForge's {@code ServerLevel.getSeed}
 * answers through a lambda only it creates); every point is a {@code HEAD}, a {@code TAIL} or an unnumbered
 * {@code RETURN}; no slice, group or captured locals; the selector binds the named method alone.
 * {@code -Dforbric.renamedBodyEdges=off} leaves every injector where it was written.
 */
public final class MixinRenamedBodyEdges {
	public static final String PROPERTY = "forbric.renamedBodyEdges";
	private static final Set<String> EDGES = Set.of("HEAD", "TAIL", "RETURN");

	private MixinRenamedBodyEdges() {
	}

	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if ("off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY, "on")) || mixin == null || mixin.methods == null
				|| targets == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		List<String> owners = MixinOverloadPin.targetsOf(mixin);
		if (ecosystem == null || owners.size() != 1) return 0;
		ClassNode target = null;
		int moved = 0;
		for (MethodNode handler : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(handler);
			if (injector == null || MixinFit.value(injector, "slice") != null || MixinFit.value(injector, "locals") != null
					|| MixinFit.groupOf(handler) != null) continue;
			List<AnnotationNode> points = MixinFit.atNodes(injector);
			if (points.isEmpty() || !points.stream().allMatch(MixinRenamedBodyEdges::edge)) continue;
			List<String> selectors = MixinFit.stringList(MixinFit.value(injector, "method"));
			if (selectors.size() != 1) continue;
			if (target == null) {
				target = targets.apply(owners.getFirst());
				if (target == null) return moved;
			}
			MethodNode named = MixinTargetSelectors.one(handler, target);
			if (named == null) continue;
			MethodNode renamed = renamedBody(target, named, ecosystem);
			if (renamed == null || !forwardsTo(target, named, renamed) || !runsWithoutPassingThrough(target, renamed, named)) continue;
			MixinPlayerWorldCallbackAdapter.set(injector, "method", new ArrayList<>(List.of(renamed.name + renamed.desc)));
			moved++;
			ForbricLog.info("[Forbric/Mixin] %s: %s's edge of %s.%s%s now edges %s%s — a carrier renamed that body there, and the merged "
					+ "game also runs it without passing through %s (carrier-renames.txt)", mixin.name.replace('/', '.'), handler.name,
					target.name.replace('/', '.'), named.name, named.desc, renamed.name, renamed.desc, named.name);
		}
		return moved;
	}

	private static boolean edge(AnnotationNode at) {
		String value = MixinFit.asString(MixinFit.value(at, "value"));
		return value != null && EDGES.contains(value) && MixinFit.value(at, "slice") == null
				&& (!"RETURN".equals(value) || !(MixinFit.value(at, "ordinal") instanceof Integer ordinal) || ordinal < 0);
	}

	/** The one method the census proves a carrier renamed {@code named}'s whole body to, still called; else null. */
	private static MethodNode renamedBody(ClassNode target, MethodNode named, Ecosystem ecosystem) {
		MethodNode found = null;
		for (MethodNode candidate : target.methods) {
			if (candidate == named || !candidate.desc.equals(named.desc)
					|| ((candidate.access ^ named.access) & Opcodes.ACC_STATIC) != 0) continue;
			List<CarrierRenames.Row> rows = CarrierRenames.find(target.name, named.name + named.desc, candidate.name + candidate.desc, ecosystem);
			if (rows.isEmpty() || CarrierRenames.kind(rows) != CarrierRenames.Kind.RENAME || !CarrierRenames.called(rows)
					|| !CarrierRenames.called(target, candidate)) continue;
			if (found != null) return null;
			found = candidate;
		}
		return found;
	}

	/**
	 * Whether {@code named} itself calls {@code renamed}: it is a dispatcher in front of the body, whose runs that go on go
	 * through the body. A method that hands the body to a later continuation instead (NeoForge's
	 * {@code startConfiguration} sends its queries and leaves {@code runConfiguration} to the reply) is not, and an edge of
	 * it keeps meaning the method itself.
	 */
	static boolean forwardsTo(ClassNode target, MethodNode named, MethodNode renamed) {
		if (named.instructions == null) return false;
		for (AbstractInsnNode instruction : named.instructions) {
			if (instruction instanceof MethodInsnNode call && call.owner.equals(target.name) && call.name.equals(renamed.name)
					&& call.desc.equals(renamed.desc)) return true;
		}
		return false;
	}

	/** Whether a method of {@code target} other than {@code named} calls {@code renamed}, or takes a handle to it. */
	static boolean runsWithoutPassingThrough(ClassNode target, MethodNode renamed, MethodNode named) {
		for (MethodNode method : target.methods) {
			if (method == named || method == renamed || method.instructions == null) continue;
			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode call && call.owner.equals(target.name) && call.name.equals(renamed.name)
						&& call.desc.equals(renamed.desc)) return true;
				if (instruction instanceof InvokeDynamicInsnNode dynamic) {
					for (Object argument : dynamic.bsmArgs) {
						if (argument instanceof Handle handle && handle.getOwner().equals(target.name) && handle.getName().equals(renamed.name)
								&& handle.getDesc().equals(renamed.desc)) return true;
					}
				}
			}
		}
		return false;
	}
}
