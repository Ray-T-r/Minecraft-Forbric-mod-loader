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

package net.forbric.kernel.mixin;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import net.forbric.kernel.util.ForbricLog;

/**
 * The one place the class pipeline asks which Mixin transformer to weave with — so a guest that REPLACES that
 * transformer, the way its own platform lets it, is honoured.
 *
 * <h2>Why a slot at all</h2>
 *
 * <p>On NeoForge the live weaver sits in a field: {@code FMLMixinClassProcessor.transformer}, which the processor
 * reads again for every class it handles. LibJF's ASM layer depends on exactly that — its mixin plugin walks
 * {@code TransformingClassLoader} to that field, wraps what it finds in its own {@code AsmTransformer}, and writes
 * the wrapper back, so every class after that goes through Mixin and then LibJF's patches. Fabric's Knot keeps it in
 * a field too, {@code KnotClassLoader.delegate.mixinTransformer}, and a Fabric mod decorates it the same way. The
 * kernel captured its transformer once, in a local the define lambda closed over, so there was nothing a guest could
 * replace: even with the rest of that object graph in place, the wrapper would have been written into a field nobody
 * reads.
 *
 * <h2>Views</h2>
 *
 * <p>Each such field the kernel hands a guest is a <em>view</em> registered with {@link #watch}: a reader of the field
 * and, where the kernel can write it, a writer. {@code KernelFmlTransformerView}, game-side, is FML's; the
 * {@code delegate} object on {@code ForbricClassLoader} ({@link MixinPlatformIdentity.KnotDelegate}) is Knot's. The
 * transformer a view was last seen changing to is what weaves, and it is written into every other view, so a guest
 * that walks any view after another guest wrote one finds that guest's wrapper there and wraps it in turn — as it
 * would find it in the single field of its own platform. A view is seeded with whatever weaves when it is registered,
 * so its first look is a change only when something else was written into it by then.
 *
 * <h2>What it costs everyone else</h2>
 *
 * <p>Nothing that shows: until a view is registered {@link #currentOr} returns the transformer it was given, and
 * after that it is one read of each view's field per class. {@code MixinEnvironment.setActiveTransformer}, which
 * LibJF also calls, is deliberately NOT a way in: it does not reroute weaving on NeoForge either.
 *
 * <p>{@code -Dforbric.fmlTransformerView=off} hands out no FML view, so a NeoForge guest fails its cast exactly as
 * before; {@code -Dforbric.mixinPluginPlatforms=off} leaves Knot's view out of the slot.
 */
public final class MixinWeaverSlot {
	/** {@code -Dforbric.fmlTransformerView=off}: no FML view, so LibJF's ASM layer fails to start as before. */
	public static final String SWITCH = "forbric.fmlTransformerView";

	/** What a view has been seen holding before its first read: nothing it could hold. */
	private static final Object UNSEEN = new Object();

	private static volatile IMixinTransformer original;
	/** The transformer a view was last seen changing to; null while none has held one. */
	private static volatile IMixinTransformer chosen;
	private static final List<View> views = new CopyOnWriteArrayList<>();

	/** One field a guest can read the weaver from, and replace it in. */
	private static final class View {
		final String name;
		final Supplier<?> reader;
		final Consumer<? super IMixinTransformer> writer;
		volatile Object seen = UNSEEN;

		View(String name, Supplier<?> reader, Consumer<? super IMixinTransformer> writer) {
			this.name = name;
			this.reader = reader;
			this.writer = writer;
		}
	}

	private MixinWeaverSlot() {
	}

	/** Whether a guest may be handed a view of the weaver through FML's {@code TransformingClassLoader}. */
	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	/**
	 * Records the transformer Mixin gave the kernel, before anything can ask for a view of it. Views left from an
	 * earlier boot in this process are forgotten: they read graphs built around that boot's weaver.
	 */
	static void install(IMixinTransformer weaver) {
		original = weaver;
		views.clear();
		chosen = null;
	}

	/** The transformer the kernel weaves with when no guest has replaced it; null before Mixin is up. */
	public static IMixinTransformer original() {
		return original;
	}

	/**
	 * Registers {@code reader} as a view the kernel cannot write: what it yields, when it changes to a transformer,
	 * weaves from then on; anything but a transformer is ignored.
	 */
	public static void watch(Supplier<?> reader) {
		watch("a guest's view of the weaver", reader, null);
	}

	/**
	 * Registers a view: {@code reader} reads its field, and {@code writer}, when not null, writes into it the
	 * transformer another view changed to.
	 *
	 * @param name what the view is, for the one log line written when a guest replaces the weaver through it
	 */
	public static void watch(String name, Supplier<?> reader, Consumer<? super IMixinTransformer> writer) {
		views.add(new View(name, reader, writer));
	}

	/**
	 * The transformer to weave this class with: whatever a guest last put into a view, else {@code fallback}.
	 *
	 * <p>Not guarded against a wrapper that throws. On NeoForge a throwing wrapper fails the class it was handed,
	 * and swallowing it here would weave a class the guest meant to patch without the patch, silently.
	 */
	public static IMixinTransformer currentOr(IMixinTransformer fallback) {
		if (!views.isEmpty()) {
			for (View view : views) {
				if (view.reader.get() != view.seen) {
					settle();
					break;
				}
			}
		}
		IMixinTransformer current = chosen;
		return current != null ? current : fallback;
	}

	/**
	 * Adopts what a view changed to, and writes it into every other view.
	 *
	 * <p>A view's first look is a change only when it holds something other than what weaves: a view is seeded with
	 * what weaves, and a seed must never outvote a wrapper a guest wrote into another view before anything looked.
	 * A view that changed after it was seen outranks one seen for the first time; among equals the one registered
	 * last wins, as the single watched view of old did.
	 */
	private static synchronized void settle() {
		IMixinTransformer before = chosen != null ? chosen : original;
		View changed = null;
		IMixinTransformer value = null;
		int best = 0;
		for (View view : views) {
			Object now = view.reader.get();
			if (now == view.seen) continue;
			boolean firstLook = view.seen == UNSEEN;
			view.seen = now;
			if (!(now instanceof IMixinTransformer transformer)) continue;
			int rank = !firstLook ? 2 : now != before ? 1 : 0;
			if (rank > 0 && rank >= best) {
				best = rank;
				changed = view;
				value = transformer;
			}
		}
		if (changed == null) return;
		chosen = value;
		for (View view : views) {
			if (view == changed || view.writer == null) continue;
			view.writer.accept(value);
			view.seen = view.reader.get();
		}
		if (value != before) {
			ForbricLog.info("[Forbric/Weaver] %s now holds %s; it weaves every class from here on", changed.name,
					value.getClass().getName());
		}
	}

	/** Forgets the views and the transformer — for tests, and so a relaunch in one process starts clean. */
	public static void reset() {
		original = null;
		chosen = null;
		views.clear();
	}
}
