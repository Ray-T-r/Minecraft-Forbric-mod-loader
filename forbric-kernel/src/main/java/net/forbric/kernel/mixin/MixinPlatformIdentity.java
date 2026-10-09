/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.Iterator;

import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.ForbricSwitches;

/**
 * What a guest's Mixin code reads of the platform it runs on: the Mixin service's name, and the field its own loader
 * keeps the weaver in.
 *
 * <h2>The service name</h2>
 *
 * <p>A mod built for more than one loader often picks its platform half from
 * {@code MixinService.getService().getName()}: Fabric's Knot service reports {@code "Knot/Fabric"}, MinecraftForge's
 * ModLauncher service {@code "ModLauncher"}, NeoForge's FML service {@code "FML"}. The kernel's service said
 * {@code "Forbric"}, a name no mod was written against, so every such mod took its "unknown platform" branch, whatever
 * shape its check had — Controlify's switch threw "Unknown mixin service: Forbric", a one-sided
 * {@code "Knot/Fabric".equals(...)} quietly picked its Forge half on a Fabric build.
 *
 * <p>So {@link ForbricMixinService#getName} answers the code that asks: the nearest frame past it whose class the game
 * loader defined from a mod jar it attributes to one ecosystem, and the answer is that ecosystem's native name, as its
 * own loader would give it. The JDK's frames are passed over on the way, and so is game-side code no single ecosystem
 * owns — a library bundled without a loader manifest, say, whose plugin base class reads the name in its constructor,
 * the merged base, a carrier, a jar two ecosystems claim — since such code has no platform of its own and runs on its
 * caller's. With no owned frame before the walk leaves the game loader — Mixin asking, the kernel asking, either of
 * them calling such unowned code directly — the answer is {@code "Forbric"}, as before. Because the answer is made
 * where the name is read, it holds however the mod asks: a switch or a single comparison, a helper or a base class in
 * its jar or a library's, a value kept in a static, a method reference handed to a JDK API, reflection.
 *
 * <h2>Knot's weaver field</h2>
 *
 * <p>A Fabric mod that decorates the weaver walks Knot by reflection: the class loader's {@code delegate} field, then
 * that object's {@code mixinTransformer}, which it reads, wraps and writes back. On {@code ForbricClassLoader} the walk
 * found no {@code delegate} and the mod failed with {@code NoSuchFieldException}, however it reached the loader.
 * {@code ForbricClassLoader} now has that field, holding a {@link KnotDelegate}: a class declaring
 * {@code mixinTransformer} with Knot's name and type, seeded with the weaver that weaves now and registered with
 * {@link MixinWeaverSlot}, so what a mod writes there weaves every class after it — and a mod that walks FML's view
 * after it finds its wrapper there, and the other way round.
 *
 * <p>{@code -Dforbric.mixinPluginPlatforms=off} (once {@code forbric.compatPluginPlatforms}): every caller is told
 * {@code "Forbric"}, and Knot's field stays empty and unread, so a mod that writes its decorator there changes nothing.
 */
public final class MixinPlatformIdentity {
	/** {@code -Dforbric.mixinPluginPlatforms=off}: the kernel's own service name to everyone, and no Knot weaver field. */
	public static final String PROPERTY = "forbric.mixinPluginPlatforms";
	/** What the kernel's Mixin service is called, and what every caller without a platform of its own is told. */
	public static final String KERNEL_SERVICE = "Forbric";

	private static final StackWalker FRAMES = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

	private MixinPlatformIdentity() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(ForbricSwitches.get(PROPERTY, "on"));
	}

	/** What {@link ForbricMixinService#getName} answers its caller. Call only from there: the caller is found by frame. */
	static String serviceName() {
		if (!enabled()) return KERNEL_SERVICE;
		String platform = nativeServiceName(askingEcosystem());
		return platform != null ? platform : KERNEL_SERVICE;
	}

	/** The name {@code ecosystem}'s own Mixin service reports, or null for none. */
	public static String nativeServiceName(Ecosystem ecosystem) {
		if (ecosystem == null) return null;
		return switch (ecosystem) {
			case FABRIC -> "Knot/Fabric";        // MixinServiceKnot.getName under Knot
			case FORGE -> "ModLauncher";         // MixinServiceModLauncher.getName
			case NEOFORGE -> "FML";              // FMLMixinService.getName
		};
	}

	/**
	 * The ecosystem of the code that called {@link ForbricMixinService#getName}, or null when that code has none.
	 *
	 * <p>Walks out from the call, frame by frame:
	 * <ul>
	 *   <li>The JDK's frames are passed over. Reflection and lambda frames are not even shown to a walker, so a method
	 *       reference handed to {@code Optional.map} or a {@code Method.invoke} is answered for the code that set it up.</li>
	 *   <li>So is code the game loader defined that no single ecosystem owns ({@link ForbricClassLoader#ecosystemOfClass}
	 *       is null): a library a mod bundles without a loader manifest, the merged base, a runtime carrier. It has no
	 *       platform of its own; natively it is loaded into the one platform there is, and here it runs on the platform
	 *       of whoever drives it. A plugin whose base class lives in such a library is answered for the plugin.</li>
	 *   <li>The first game-side frame that is owned answers.</li>
	 *   <li>A frame from outside the game loader ends the walk unanswered: the loader's own machinery — Mixin, the kernel
	 *       — runs on nobody's behalf, so a library it calls directly is told the kernel's name, as before.</li>
	 * </ul>
	 */
	static Ecosystem askingEcosystem() {
		return FRAMES.walk(frames -> {
			Iterator<Class<?>> callers = frames
					.dropWhile(frame -> !(frame.getDeclaringClass() == ForbricMixinService.class && "getName".equals(frame.getMethodName())))
					.skip(1)
					.<Class<?>>map(StackWalker.StackFrame::getDeclaringClass)
					.filter(type -> !jdk(type))
					.iterator();
			while (callers.hasNext()) {
				Class<?> caller = callers.next();
				if (!(caller.getClassLoader() instanceof ForbricClassLoader game)) return null;
				Ecosystem owner = game.ecosystemOfClass(caller);
				if (owner != null) return owner;
			}
			return null;
		});
	}

	private static boolean jdk(Class<?> type) {
		ClassLoader loader = type.getClassLoader();
		return loader == null || loader == ClassLoader.getPlatformClassLoader();
	}

	/**
	 * What {@code ForbricClassLoader.delegate} holds: Knot's {@code KnotClassDelegate} as far as a mod reflecting on it
	 * can see — a declared {@code mixinTransformer} field of type {@code IMixinTransformer}, and the private
	 * {@code getMixinTransformer()} beside it. Empty, as Knot's is, until Mixin is up.
	 */
	public static final class KnotDelegate {
		/** Knot's field, by name and type: the weaver that weaves now, and where a mod puts its decorator. */
		private volatile IMixinTransformer mixinTransformer;

		/** Knot's own accessor of the field, for a mod that calls it rather than reading the field. */
		@SuppressWarnings("unused")
		private IMixinTransformer getMixinTransformer() {
			return mixinTransformer;
		}

		/**
		 * Seeds the field with the weaver that weaves now and makes it one of {@link MixinWeaverSlot}'s views. Called
		 * once Mixin's transformer is installed in the slot, before any guest plugin is constructed.
		 */
		public void attach() {
			if (!enabled()) {
				ForbricLog.info("[Forbric/Weaver] -D%s=off -- a Fabric mod that decorates the Mixin weaver through "
						+ "Knot's delegate.mixinTransformer finds it empty, and what it writes there weaves nothing", PROPERTY);
				return;
			}
			mixinTransformer = MixinWeaverSlot.currentOr(MixinWeaverSlot.original());
			MixinWeaverSlot.watch("Knot's KnotClassLoader.delegate.mixinTransformer", () -> mixinTransformer,
					weaver -> mixinTransformer = weaver);
		}
	}
}
