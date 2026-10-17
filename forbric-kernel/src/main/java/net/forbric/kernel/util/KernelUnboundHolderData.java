/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.util;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Says so when a data-map lookup is answered "no data" for a value that was still not registered when its registry
 * closed — the one case where the answer {@code UnboundHolderDataInjector} gives can hide a defect instead of fixing one.
 *
 * <p>The merged {@code Holder.Reference.getData} answers {@code null} for an intrusive holder with no key, where native
 * NeoForge throws {@code Trying to access unbound value}. While its registry is still open for registration that is the
 * expected state: the value was constructed and is about to be registered, which is when fabric-api's
 * {@code OxidizableBlocksRegistry.registerNextStage} asks a block for its next stage, and nothing is logged. Once a
 * {@code frozen} flag of the owning registry is set, the registry has been through {@code freeze()}, which throws
 * "Some intrusive holders were not registered" for such a value (setting {@code frozen} first), and native NeoForge
 * would have thrown at this lookup. The value is not necessarily lost for good: both registries named
 * below have an {@code unfreeze()}, and Forbric reopens the registries for the Fabric client entrypoints, so a value
 * registered there was only asked about early. Either way the lookup still answers "no data", and the first one for
 * each such value is a WARN with the stack that asked, so a value that is never registered is not hidden.
 *
 * <p>The owner is asked for {@code frozen} reflectively, every non-static {@code boolean frozen} its class hierarchy
 * declares: vanilla's {@code MappedRegistry.frozen}, which its {@code freeze()} sets and its {@code validateWrite} reads
 * (registration, intrusive holders, tags), and, on a MinecraftForge {@code NamespacedWrapper}, the wrapper's own, which
 * the wrapper's {@code freeze()} sets and its {@code validateWrite} reads (intrusive holders, tags; its
 * {@code register} is gated by a separate {@code locked}). Any set counts as closed. An owner with no such field, or
 * one it cannot read, is not judged and nothing is warned.
 *
 * <p>Diagnostics only: nothing here changes the answer, and nothing here throws. Like {@link KernelChunkExecutorGuard} it
 * names no game type, so it lives in this parent-loaded package and is called from the repaired game class.
 */
public final class KernelUnboundHolderData {
	/** WARN lines, at most: past this a defect that leaves many values unbound has been reported, and one more line says so. */
	static final int MAX_WARNED = 64;
	/** The reports of this game: one per process, as the repaired class calls {@link #unbound} statically. */
	private static final Reports REPORTS = new Reports();
	private static final ClassValue<List<Field>> FROZEN_FLAGS = new ClassValue<>() {
		@Override protected List<Field> computeValue(Class<?> type) {
			List<Field> flags = new ArrayList<>();
			for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
				for (Field field : c.getDeclaredFields()) {
					if (!field.getName().equals("frozen") || field.getType() != boolean.class || Modifier.isStatic(field.getModifiers())) continue;
					try {
						field.setAccessible(true);
						flags.add(field);
					} catch (RuntimeException unreadable) {
						Caught.warn(unreadable, "[Forbric/DataMaps] could not open %s.frozen; whether its data-map"
								+ " lookups came after it closed is not judged", c.getName());
						return List.of();
					}
				}
			}
			return List.copyOf(flags);
		}
	};

	private KernelUnboundHolderData() {
	}

	/**
	 * Called by the repaired {@code Holder.Reference.getData} just before it answers {@code null} for a holder with no
	 * key: {@code value} is the holder's value, {@code owner} its {@code HolderOwner}.
	 */
	public static void unbound(Object value, Object owner) {
		REPORTS.unbound(value, owner);
	}

	/** What has been reported, by value identity; its own instance in a test, so the cap is measured from zero. */
	static final class Reports {
		/** Values already warned about, by identity. */
		private final Set<Object> warned = Collections.newSetFromMap(new IdentityHashMap<>());
		/** Values already reported open with {@code -Dforbric.debug}, by identity. */
		private final Set<Object> debugged = Collections.newSetFromMap(new IdentityHashMap<>());

		void unbound(Object value, Object owner) {
			try {
				if (value == null) return;
				Boolean closed = closed(owner);
				if (Boolean.TRUE.equals(closed)) {
					int count;
					synchronized (warned) {
						if (warned.size() > MAX_WARNED || !warned.add(value)) return;
						count = warned.size();
					}
					if (count > MAX_WARNED) {
						ForbricLog.warn("[Forbric/DataMaps] more than %d values were asked for NeoForge data-map entries while "
								+ "still unregistered after their registry closed; no more are listed", MAX_WARNED);
						return;
					}
					ForbricLog.warn("[Forbric/DataMaps] a data-map lookup asked about " + describe(value) + ", which was still not "
							+ "registered when its registry " + describe(owner) + " closed. Answered \"no data\"; native NeoForge "
							+ "throws \"Trying to access unbound value\" here. Whatever constructed it during registration had not "
							+ "registered it by then (if the registry is reopened later, as for the Fabric client entrypoints, it may "
							+ "still be registered there and this lookup only came early) — the stack below is the lookup that found it",
							new Throwable("data-map lookup on an unregistered value"));
					return;
				}
				if (!ForbricLog.debugEnabled()) return;
				synchronized (debugged) {
					if (debugged.size() >= MAX_WARNED || !debugged.add(value)) return;
				}
				ForbricLog.debug("[Forbric/DataMaps] data-map lookup on %s before it is registered (registry %s): no data",
						describe(value), closed == null ? "not judged" : "open");
			} catch (Throwable diagnosticsOnly) {
				// The answer is already decided by the caller; a report that fails must not turn it into a throw.
				Caught.warn(diagnosticsOnly, "[Forbric/DataMaps] could not report a data-map lookup on an unregistered"
						+ " value");
			}
		}
	}

	/** {@code TRUE} when the owner refuses writes (a {@code frozen} flag is set), {@code FALSE} when open, null when unknown. */
	static Boolean closed(Object owner) {
		if (owner == null) return null;
		List<Field> flags = FROZEN_FLAGS.get(owner.getClass());
		if (flags.isEmpty()) return null;
		try {
			for (Field flag : flags) if (flag.getBoolean(owner)) return Boolean.TRUE;
			return Boolean.FALSE;
		} catch (IllegalAccessException | RuntimeException unreadable) {
			Caught.warn(unreadable, "[Forbric/DataMaps] could not read the frozen flag of %s; not judged",
					owner.getClass().getName());
			return null;
		}
	}

	private static String describe(Object object) {
		if (object == null) return "null";
		String text;
		try {
			text = String.valueOf(object);
		} catch (Throwable unprintable) {
			Caught.expected(unprintable);
			text = "?";
		}
		return object.getClass().getName() + " '" + text + "'";
	}
}
