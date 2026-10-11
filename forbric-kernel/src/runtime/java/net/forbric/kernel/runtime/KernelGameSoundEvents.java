/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;

/**
 * MinecraftForge's {@code PlayLevelSoundEvent}, forwarded from NeoForge's.
 *
 * <p>Every place the merged game plays a sound — both {@code playSeededSound} overloads of {@code ServerLevel} and of
 * {@code ClientLevel}, and {@code LocalPlayer.playSound} — asks NeoForge's {@code EventHooks} and plays what the event
 * ends with: nothing when it is cancelled or its sound is null, otherwise its sound, source, volume and pitch. The two
 * families rewrite the same vanilla lines there and the merge keeps NeoForge's, so MinecraftForge's hook is never
 * called and a MinecraftForge mod that changes or silences sounds is loaded, registered and inert.
 *
 * <p>The two events carry the same state under the same names, so the forward is a translation rather than an
 * approximation. It runs at LOWEST, after NeoForge's listeners, and hands a MinecraftForge mod what they left: their
 * sound, source, volume and pitch, with the volume and pitch the game started from as the originals. What it writes
 * back reaches the event the game reads — a cancel one way, and each of the four values only where it differs from
 * what the mod was handed, so a mod that only looks overwrites nothing. A sound NeoForge's listeners already silenced
 * (cancelled, or set to null) is not offered: the game will not play it whatever a MinecraftForge mod says.
 *
 * <p>Where the merged game asks MinecraftForge natively on the same path as NeoForge, the forward stands down for
 * that event ({@code GameEventMultiplexer}'s guard), so a mod is never asked twice about one sound.
 */
public final class KernelGameSoundEvents {
	private KernelGameSoundEvents() {
	}

	/** A sound played at an entity: footsteps, a mob's voice, the local player's own sounds. */
	public static void installAtEntity(Object neoBus) {
		KernelGameServerEvents.forward((IEventBus) neoBus, PlayLevelSoundEvent.AtEntity.class, "PlayLevelSoundEvent.AtEntity",
				event -> {
					if (event.getSound() == null) return;
					var forge = new net.minecraftforge.event.PlayLevelSoundEvent.AtEntity(event.getLevel(), event.getEntity(),
							event.getSound(), event.getSource(), event.getOriginalVolume(), event.getOriginalPitch());
					carry(event, forge, net.minecraftforge.event.PlayLevelSoundEvent.AtEntity.BUS.post(seeded(event, forge)));
				});
	}

	/** A sound played at a position: blocks, explosions, anything not tied to an entity. */
	public static void installAtPosition(Object neoBus) {
		KernelGameServerEvents.forward((IEventBus) neoBus, PlayLevelSoundEvent.AtPosition.class, "PlayLevelSoundEvent.AtPosition",
				event -> {
					if (event.getSound() == null) return;
					var forge = new net.minecraftforge.event.PlayLevelSoundEvent.AtPosition(event.getLevel(), event.getPosition(),
							event.getSound(), event.getSource(), event.getOriginalVolume(), event.getOriginalPitch());
					carry(event, forge, net.minecraftforge.event.PlayLevelSoundEvent.AtPosition.BUS.post(seeded(event, forge)));
				});
	}

	/** The MinecraftForge event as NeoForge's listeners left the sound: their volume and pitch, over the originals. */
	static <F extends net.minecraftforge.event.PlayLevelSoundEvent> F seeded(PlayLevelSoundEvent neo, F forge) {
		forge.setNewVolume(neo.getNewVolume());
		forge.setNewPitch(neo.getNewPitch());
		return forge;
	}

	/**
	 * What a MinecraftForge mod decided, onto the event the game reads.
	 *
	 * <p>A cancel is carried and nothing else is then worth writing. Otherwise each value is written only where the
	 * mod changed it from what it was handed: writing the same value back is harmless today, but a write that is not a
	 * decision is how a later change to either event would start overruling NeoForge's listeners.
	 */
	static void carry(PlayLevelSoundEvent neo, net.minecraftforge.event.PlayLevelSoundEvent forge, boolean cancelled) {
		if (cancelled) {
			neo.setCanceled(true);
			return;
		}
		if (forge.getSound() != neo.getSound()) neo.setSound(forge.getSound());
		if (forge.getSource() != neo.getSource()) neo.setSource(forge.getSource());
		if (Float.compare(forge.getNewVolume(), neo.getNewVolume()) != 0) neo.setNewVolume(forge.getNewVolume());
		if (Float.compare(forge.getNewPitch(), neo.getNewPitch()) != 0) neo.setNewPitch(forge.getNewPitch());
	}
}
