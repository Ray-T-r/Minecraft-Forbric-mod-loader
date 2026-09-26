/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.List;
import java.util.Set;

import net.forbric.api.Ecosystem;

/**
 * Calls a vanilla method made itself that the surviving carrier absorbed into a static hook of its own, which the
 * method now calls in their place — reviewed one by one, because the hook does more than the call.
 *
 * <p>{@link CarrierHelpers}' census rows are provable from the bytes: the call is the helper's first or last act, so
 * before or after the helper call IS before or after the call. Here it is not. NeoForge replaced
 * {@code FogRenderer.computeFogColor}'s final {@code dest.set(r, g, b, 1)} with {@code ClientHooks.getFogColor(...)},
 * which sets {@code dest} the same way and then lets fluid types and {@code ViewportEvent.ComputeFogColor} listeners
 * change it. An {@code @Inject} after the set can follow the hook call only on an argument about what the hook does
 * afterwards, and that argument is written down in {@code because}. MixinRetarget's R5 reads these rows for an
 * {@code @Inject} AFTER the call only; the census in MergedBaseAbsorbedCallsTest pins each row to the staged jars.
 * {@code -Dforbric.mixinAbsorbedCall=off} leaves every such point as compiled.
 */
public final class MergedBaseAbsorbedCalls {
	/**
	 * @param owner      the class the mixin targets (internal name)
	 * @param method     the target method, {@code name + descriptor}
	 * @param member     the call the listed ecosystems' jars make as the method's last act, as an {@code @At} target
	 * @param hook       the carrier's call that replaced it, as an {@code @At} target
	 * @param ecosystems the mods compiled against the call in the method
	 * @param because    why AFTER the hook is AFTER the call for those mods
	 */
	public record Absorbed(String owner, String method, String member, String hook, Set<Ecosystem> ecosystems, String because) {
	}

	public static final String PROPERTY = "forbric.mixinAbsorbedCall";

	public static final List<Absorbed> KNOWN = List.of(
			new Absorbed("net/minecraft/client/renderer/fog/FogRenderer",
					"computeFogColor(Lnet/minecraft/client/Camera;FLnet/minecraft/client/multiplayer/ClientLevel;IFLorg/joml/Vector4f;)V",
					"Lorg/joml/Vector4f;set(FFFF)Lorg/joml/Vector4f;",
					"Lnet/neoforged/neoforge/client/ClientHooks;getFogColor(Lnet/minecraft/client/Camera;FLnet/minecraft/client/multiplayer/"
							+ "ClientLevel;IFFFFLorg/joml/Vector4f;)V",
					Set.of(Ecosystem.FABRIC, Ecosystem.FORGE),
					"the hook first does dest.set(r, g, b, 1) exactly as vanilla's last act did, then applies a fluid type's "
							+ "modifyFogColor (only with the camera inside the fluid) and posts ViewportEvent.ComputeFogColor; after "
							+ "it, dest holds vanilla's colour with NeoForge's changes, so puzzleslib's FogEvents.Color listeners "
							+ "run after NeoForge's instead of on the bare colour -- an ordering choice, not a loss. Restoring a "
							+ "dest.set before the hook would be discarded by the hook's own set. AFTER the hook is also the "
							+ "method's return, where TAIL injectors (nuit's) land, so their order against it follows Mixin's "
							+ "application order where vanilla ran AFTER-set first. MinecraftForge's own jar sets dest after its "
							+ "ForgeHooksClient.getFogColor the same way, so its mods follow the hook too"));

	private MergedBaseAbsorbedCalls() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** The row for {@code anchor} (as the mod wrote it) in {@code owner#method}, for a mod of {@code ecosystem}; null when none. */
	static Absorbed find(String owner, String method, String anchor, Ecosystem ecosystem) {
		if (!enabled() || ecosystem == null) return null;
		MixinFit.Member want = MixinFit.parseMember(anchor);
		if (want == null) return null;
		for (Absorbed row : KNOWN) {
			if (!row.owner().equals(owner) || !row.method().equals(method) || !row.ecosystems().contains(ecosystem)) continue;
			MixinFit.Member have = MixinFit.parseMember(row.member());
			if (want.name().equals(have.name()) && (want.owner() == null || want.owner().equals(have.owner()))
					&& (want.desc() == null || want.desc().equals(have.desc()))) return row;
		}
		return null;
	}
}
