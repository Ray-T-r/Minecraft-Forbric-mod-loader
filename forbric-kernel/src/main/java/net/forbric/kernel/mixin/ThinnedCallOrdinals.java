/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Points an injector at the merged body's occurrence of a vanilla call where the carrier makes that call fewer times than
 * vanilla — it replaced some occurrences with calls of its own and kept the others — so an ordinal the mod counted on
 * vanilla's body names the same call on the merged one.
 *
 * <p>ViaFabricPlus' 1.12.2 block placement hooks {@code MultiPlayerGameMode.performUseItemOn} before its third
 * {@code ItemStack.isEmpty()}. Vanilla asks {@code isEmpty} of the main- and off-hand stacks for the sneak check, then of
 * the stack in the hand before its cooldown; NeoForge (and MinecraftForge) ask {@code doesSneakBypassUse} of the two hand
 * stacks instead, so the merged body's only {@code isEmpty} is vanilla's third. Ordinal 2 found nothing there, the
 * required injector bound nowhere, and the strict policy stopped the client as soon as a world loaded.
 *
 * <p>Only along a {@link Site} row, which maps each of vanilla's occurrences to the merged one or to none, says why, and
 * names the call each kept occurrence is followed by; only while the merged method makes the call exactly as often as the
 * row keeps, each occurrence followed by that call; only for a mod of a listed ecosystem (one compiled against vanilla's
 * count); only an {@code @At(INVOKE)} with an ordinal and no slice, in an injector with one selector binding the row's
 * method. Any injector kind: the point lands on the same call, with the same arguments and the same result.
 * {@code -Dforbric.thinnedCallOrdinals=off} leaves every ordinal as compiled.
 */
public final class ThinnedCallOrdinals {
	public static final String PROPERTY = "forbric.thinnedCallOrdinals";

	/**
	 * @param owner      the class the mixins target (internal name)
	 * @param method     the method there, {@code name + descriptor}
	 * @param call       the call, as an {@code @At} target
	 * @param ordinals   for each of vanilla's occurrences, the merged occurrence that is the same call, or -1 for none
	 * @param next       the call the method makes next after each kept occurrence, on both sides, as an {@code @At} target
	 * @param ecosystems the mods compiled against vanilla's count
	 * @param because    why the kept occurrences are vanilla's
	 */
	public record Site(String owner, String method, String call, int[] ordinals, String next, Set<Ecosystem> ecosystems,
			String because) {
		/** How many occurrences the merged method keeps. */
		int kept() {
			int kept = 0;
			for (int ordinal : ordinals) if (ordinal >= 0) kept++;
			return kept;
		}
	}

	public static final List<Site> SITES = List.of(
			new Site("net/minecraft/client/multiplayer/MultiPlayerGameMode",
					"performUseItemOn(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/InteractionHand;"
							+ "Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;",
					"Lnet/minecraft/world/item/ItemStack;isEmpty()Z", new int[] {-1, -1, 0},
					"Lnet/minecraft/client/player/LocalPlayer;getCooldowns()Lnet/minecraft/world/item/ItemCooldowns;",
					Set.of(Ecosystem.FABRIC),
					"vanilla asks isEmpty of the main- and off-hand stacks for the sneak check, then of the stack in the hand "
							+ "before its cooldown; NeoForge and MinecraftForge ask doesSneakBypassUse of the two hand stacks "
							+ "instead and keep the third, the merged body's only one"));

	private ThinnedCallOrdinals() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Re-counts every eligible point of {@code mixin}; returns how many. {@code targets} must return nodes WITH code. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!enabled() || mixin == null || mixin.methods == null || targets == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		List<String> owners = MixinOverloadPin.targetsOf(mixin);
		if (ecosystem == null || owners.size() != 1) return 0;
		int moved = 0;
		for (Site site : SITES) {
			if (!site.owner().equals(owners.getFirst()) || !site.ecosystems().contains(ecosystem)) continue;
			ClassNode target = null;
			for (MethodNode handler : mixin.methods) {
				AnnotationNode injector = MixinFit.injectorOf(handler);
				if (injector == null || MixinFit.value(injector, "slice") != null) continue;
				List<String> selectors = MixinFit.stringList(MixinFit.value(injector, "method"));
				if (selectors.size() != 1) continue;
				if (target == null) target = targets.apply(site.owner());
				if (target == null || target.methods == null) return moved;
				MethodNode bound = MixinStubRebind.bound(target, selectors.getFirst());
				if (bound == null || !site.method().equals(bound.name + bound.desc) || !hostHasTheSiteShape(bound, site)) continue;
				for (AnnotationNode at : MixinFit.atNodes(injector)) {
					if (!"INVOKE".equals(MixinFit.asString(MixinFit.value(at, "value")))
							|| !sameMember(MixinFit.asString(MixinFit.value(at, "target")), site.call())
							|| !(MixinFit.value(at, "ordinal") instanceof Integer ordinal)
							|| ordinal < 0 || ordinal >= site.ordinals().length) continue;
					int merged = site.ordinals()[ordinal];
					if (merged < 0 || merged == ordinal) continue;
					set(at, "ordinal", merged);
					moved++;
					ForbricLog.info("[Forbric/Mixin] %s: %s's point on %s in %s.%s is the merged body's occurrence %d of that "
							+ "call, where vanilla's was %d — %s", mixin.name.replace('/', '.'), handler.name,
							callName(site.call()), site.owner().replace('/', '.'), bound.name, merged,
							ordinal, site.because());
				}
			}
		}
		return moved;
	}

	/** Whether {@code method} makes the row's call exactly as often as the row keeps, each followed by the row's next call. */
	static boolean hostHasTheSiteShape(MethodNode method, Site site) {
		int found = 0;
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof MethodInsnNode call) || !matches(call, site.call())) continue;
			found++;
			MethodInsnNode next = nextCall(insn);
			if (next == null || !matches(next, site.next())) return false;
		}
		return found == site.kept();
	}

	/** {@code ItemStack.isEmpty} for {@code Lnet/minecraft/world/item/ItemStack;isEmpty()Z}. */
	private static String callName(String member) {
		MixinFit.Member call = MixinFit.parseMember(member);
		return call.owner().substring(call.owner().lastIndexOf('/') + 1) + "." + call.name();
	}

	private static MethodInsnNode nextCall(AbstractInsnNode from) {
		for (AbstractInsnNode insn = from.getNext(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call) return call;
		}
		return null;
	}

	private static boolean matches(MethodInsnNode call, String member) {
		MixinFit.Member want = MixinFit.parseMember(member);
		return want != null && call.owner.equals(want.owner()) && call.name.equals(want.name()) && call.desc.equals(want.desc());
	}

	/** Whether an {@code @At} target names the row's call: the same owner, name and descriptor. */
	private static boolean sameMember(String target, String call) {
		if (target == null) return false;
		MixinFit.Member have = MixinFit.parseMember(target), want = MixinFit.parseMember(call);
		return have != null && want != null && want.owner().equals(have.owner()) && want.name().equals(have.name())
				&& want.desc().equals(have.desc());
	}

	private static void set(AnnotationNode at, String key, Object value) {
		if (at.values == null) at.values = new ArrayList<>();
		for (int i = 0; i + 1 < at.values.size(); i += 2) {
			if (key.equals(at.values.get(i))) {
				at.values.set(i + 1, value);
				return;
			}
		}
		at.values.add(key);
		at.values.add(value);
	}
}
