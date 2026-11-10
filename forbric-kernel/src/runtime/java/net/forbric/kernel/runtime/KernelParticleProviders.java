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

package net.forbric.kernel.runtime;

import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;

import it.unimi.dsi.fastutil.ints.AbstractInt2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.resources.Identifier;

/**
 * Makes {@code ParticleResources}' vanilla-typed {@code providers} map a live view of the NeoForge-typed one.
 *
 * <p>Vanilla 26.2 declares {@code providers} as {@code Int2ObjectMap<ParticleProvider<?>>} keyed by the particle
 * type's registry id. NeoForge 26.2.0.88 RE-TYPES that same field to {@code Map<Identifier, ParticleProvider<?>>}.
 * Same name, different descriptor is legal in the JVM, so VanillaMemberTwinInjector declares vanilla's beside it —
 * and NeoForge's {@code <init>} writes only its own. The vanilla-typed one is null forever.
 *
 * <p>That is not a dormant curiosity. fabric-api's {@code DirectParticleProviderRegistry.register} reads the field
 * DIRECTLY — a raw {@code getfield providers:Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;}, then
 * {@code BuiltInRegistries.PARTICLE_TYPE.getId(type)}, then {@code put(int, provider)}. It never goes through
 * {@code getProviders()}, so no amount of rewriting the accessors reaches it. Any mod using the Fabric particle
 * API therefore NPEs inside {@code Minecraft.<init>}; here it was {@code particle-effects}.
 *
 * <p>So the field is given a value, and the value has to be a VIEW rather than a second map: the two halves of the
 * mechanism have to stay one map. A write through the int-keyed side must be visible to
 * {@code ParticleEngine.makeParticle}, which reads the {@code Identifier}-keyed one, and vice versa. The key
 * adaptation is the registry itself, which is the same thing each side already does — fabric-api spells
 * {@code getId(type)} and NeoForge's {@code register} spells {@code getKey(type)}.
 *
 * <p>The view holds the live map BY REFERENCE, which is sound because {@code providers:Ljava/util/Map;} is final
 * and has exactly one {@code PUTFIELD} in the whole class, in {@code <init>}; nothing, reload included, replaces
 * it. The transformer that installs this refuses to run if that ever stops being true.
 *
 * <p><b>Why fabric-registry-sync's remap tracker is not attached to it ({@link #followsTheRegistry}).</b> On native
 * Fabric this field is a real {@code Int2ObjectOpenHashMap}, and fabric-registry-sync's client
 * {@code ParticleResourcesMixin} hands it to {@code Int2ObjectMapTracker.register} at the end of {@code <init>} so a
 * registry remap (a server's ids on join, the client's own again on disconnect) re-keys it: the tracker copies the
 * map, {@code clear()}s it, and puts every value back under {@code getRawIdChangeMap().get(oldKey)}. That protocol
 * assumes the map's keys are STALE when the remap event fires — still the old ids — and they are on native Fabric,
 * because {@code MappedRegistry.remap} rebuilds {@code byId}/{@code toId} first and fires
 * {@code RegistryIdRemapCallback} after (fabric-registry-sync 7.1.0, {@code MappedRegistryMixin.remap}). This view
 * has no stored int keys: every key is the particle registry's id for the type AT THE TIME OF THE CALL, so by the
 * time the event fires the view already answers the new ids. The tracker's copy would read new ids as old ones and
 * re-key them a second time, moving providers onto other particle types wherever an id changed — so the right
 * {@code clear()} for it does not exist, and the old one threw ({@code UnsupportedOperationException}, "Registry
 * remapping failed!", the client never joined a server whose mods register particle types). What a native remap
 * achieves — every provider reachable under the id the remapped registry now gives its type, NeoForge's map
 * untouched as native NeoForge leaves it — is what this view already is, with no work; so the tracker has nothing
 * to do and is not attached ({@code ParticleViewTrackerInjector}). NeoForge's own registry sync needs no tracker for
 * the same reason: its map is keyed by name.
 */
public final class KernelParticleProviders {
	private static volatile boolean announcedDetach;

	private KernelParticleProviders() {
	}

	/**
	 * Wraps the {@code Identifier}-keyed provider map as an {@code Int2ObjectMap}.
	 *
	 * <p>Declared as {@code Object} on purpose. Every game-side seam in {@link net.forbric.kernel.runtime} is
	 * expressed in JDK types so {@code KernelRuntimeClasses} can state its signature without the boot side
	 * needing the game's libraries on its classpath; the caller is generated bytecode, which supplies its own
	 * {@code CHECKCAST}.
	 */
	public static Object intKeyedView(Map<?, ?> byName) {
		return intKeyedView(byName, KernelParticleProviders::particleKey, KernelParticleProviders::particleId);
	}

	/**
	 * The same view over another id table: {@code keyOf} answers the name an int id stands for now (null for none),
	 * {@code idOf} the id a name has now (negative for none). The particle registry in the game; a table a test can
	 * remap without a bootstrapped game.
	 */
	static Object intKeyedView(Map<?, ?> byName, IntFunction<Identifier> keyOf, ToIntFunction<Identifier> idOf) {
		@SuppressWarnings("unchecked")
		Map<Identifier, Object> live = (Map<Identifier, Object>) byName;
		return new IntKeyedView(live, keyOf, idOf);
	}

	/**
	 * Asked at the head of fabric-registry-sync's {@code Int2ObjectMapTracker.register(Registry, String,
	 * Int2ObjectMap)} ({@code ParticleViewTrackerInjector}): {@code true} when {@code map} is one of these views, whose
	 * int keys are the registry's current ids by construction, so a remap tracker would re-key it twice (see the class
	 * comment). The register then returns without attaching anything. Any other map — a raw-id map as on native
	 * Fabric — answers {@code false} and is tracked exactly as fabric-registry-sync tracks it.
	 */
	public static boolean followsTheRegistry(Object map, String trackerName) {
		if (!(map instanceof IntKeyedView)) return false;
		if (!announcedDetach) {
			announcedDetach = true;
			System.out.println("[Forbric/Particles] fabric-registry-sync's remap tracker for " + trackerName + " is not "
					+ "attached: that map is the kernel's view of NeoForge's Identifier-keyed provider map, whose int keys "
					+ "are the particle registry's ids at every read, so a remap leaves every provider under its type's new "
					+ "id with nothing to re-key");
		}
		return true;
	}

	private static Identifier particleKey(int id) {
		ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.byId(id);
		return type == null ? null : BuiltInRegistries.PARTICLE_TYPE.getKey(type);
	}

	private static int particleId(Identifier key) {
		ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.getValue(key);
		return type == null ? -1 : BuiltInRegistries.PARTICLE_TYPE.getId(type);
	}

	/**
	 * An {@code Int2ObjectMap} face on an {@code Identifier}-keyed map, translating through the particle registry.
	 *
	 * <p>Deliberately not spelled with its nested name anywhere in {@code src/main}: the registry test scans for
	 * {@code net.forbric.kernel.runtime.<Name>} and would then demand its own entry for this class.
	 *
	 * <p>{@code clear()} stays unsupported: emptying this face would empty NeoForge's own provider map, which nothing
	 * on either side asks for, and the one caller that did — the remap tracker — must not be attached (class comment).
	 */
	static final class IntKeyedView extends AbstractInt2ObjectMap<Object> {
		private static final long serialVersionUID = 1L;

		private final Map<Identifier, Object> byName;
		private final transient IntFunction<Identifier> keyOf;
		private final transient ToIntFunction<Identifier> idOf;
		/**
		 * Entries whose int key names no registered particle type. They cannot be translated, and dropping them
		 * would lose a registration silently; they live here instead, readable through this face only.
		 */
		private final Int2ObjectOpenHashMap<Object> untranslatable = new Int2ObjectOpenHashMap<>();
		private boolean warned;

		IntKeyedView(Map<Identifier, Object> byName, IntFunction<Identifier> keyOf, ToIntFunction<Identifier> idOf) {
			this.byName = byName;
			this.keyOf = keyOf;
			this.idOf = idOf;
		}

		private Identifier key(int id) {
			return keyOf.apply(id);
		}

		@Override
		public Object get(int id) {
			Identifier key = key(id);
			return key == null ? untranslatable.get(id) : byName.get(key);
		}

		@Override
		public Object put(int id, Object provider) {
			Identifier key = key(id);
			if (key == null) {
				// byId answers null for an id no particle type holds. Keeping the entry beats losing it: the
				// caller would see a successful put either way, and a lost provider is a particle that never
				// renders with nothing said about it.
				if (!warned) {
					warned = true;
					System.out.println("[Forbric/Particles] a provider was registered under particle id " + id
							+ ", which no registered particle type holds — kept, but nothing reading the "
							+ "Identifier-keyed map will see it");
				}
				return untranslatable.put(id, provider);
			}
			return byName.put(key, provider);
		}

		@Override
		public boolean containsKey(int id) {
			Identifier key = key(id);
			return key == null ? untranslatable.containsKey(id) : byName.containsKey(key);
		}

		@Override
		public Object remove(int id) {
			Identifier key = key(id);
			return key == null ? untranslatable.remove(id) : byName.remove(key);
		}

		@Override
		public int size() {
			return byName.size() + untranslatable.size();
		}

		/**
		 * Iteration translates back the other way, through the registry's ids as they are now; an entry whose
		 * {@code Identifier} is no longer registered is skipped rather than reported as id 0.
		 */
		@Override
		public ObjectSet<Int2ObjectMap.Entry<Object>> int2ObjectEntrySet() {
			ObjectSet<Int2ObjectMap.Entry<Object>> out = new ObjectOpenHashSet<>();
			for (Map.Entry<Identifier, Object> e : byName.entrySet()) {
				int id = idOf.applyAsInt(e.getKey());
				if (id < 0) continue;
				out.add(new AbstractInt2ObjectMap.BasicEntry<>(id, e.getValue()));
			}
			for (Int2ObjectMap.Entry<Object> e : untranslatable.int2ObjectEntrySet()) {
				out.add(new AbstractInt2ObjectMap.BasicEntry<>(e.getIntKey(), e.getValue()));
			}
			return out;
		}
	}
}
