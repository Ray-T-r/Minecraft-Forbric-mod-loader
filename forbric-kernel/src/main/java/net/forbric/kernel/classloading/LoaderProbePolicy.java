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

package net.forbric.kernel.classloading;

import java.util.Map;

import net.forbric.kernel.util.ForbricLog;

/**
 * Answers a guest mod's "which loader am I running on?" question with the loader it was actually loaded as.
 *
 * <p>Multi-platform mods pick their platform branch by probing for a marker class:
 *
 * <pre>{@code
 * if (doesClassExist("net.neoforged.fml.loading.FMLLoader"))      use NeoForgeAbstractions;
 * if (doesClassExist("net.minecraftforge.fml.loading.FMLLoader")) use ForgeAbstractions;
 * if (doesClassExist("net.fabricmc.loader.api.FabricLoader"))     use FabricAbstractions;
 * }</pre>
 *
 * <p>On every real loader exactly one of those answers yes. On Forbric all three are live in one instance, so a
 * Fabric mod takes the Forge branch — code its author compiled against a different ABI and, on Fabric, never once
 * executed. Observed: Flashback nests {@code mixinconstraints}, whose {@code ForgeAbstractionsImpl} calls
 * {@code LoadingModList.get()} as a public static on a CLASS. Real MinecraftForge 26.2 declares
 * {@code LoadingModList} as an INTERFACE whose {@code get()} is private, so the JVM refuses the call site with
 * {@code IncompatibleClassChangeError: must be InterfaceMethodref constant}. That throw escaped the mod's mixin
 * config plugin, out through Mixin's {@code select()}, and aborted mixin config preparation for the whole game —
 * one wrong branch, a dozen unrelated-looking symptoms downstream.
 *
 * <p>So the kernel answers the probe honestly: a Fabric-owned class asking whether a Forge-family loader exists is
 * told no, and vice versa. That is the answer the mod would get on its own loader, which is the answer its code was
 * written and tested against.
 *
 * <h2>Why the answer is given at the call site, not in the class loader</h2>
 *
 * <p>Hooking {@link ForbricClassLoader#loadClass} looks like the obvious place and does not work: the kernel itself
 * loads both {@code FMLLoader}s while seeding, and {@code Class.forName} resolves an already-loaded class straight
 * out of the loader's dictionary without ever calling {@code loadClass}. So the rewrite happens where the mod asks
 * — {@link net.forbric.kernel.transform.LoaderProbeRewriter} redirects {@code Class.forName} inside single-family
 * guest classes to {@link #forName}, which applies this policy first.
 *
 * <h2>Only a question is answered, never a reference</h2>
 *
 * <p>Only an explicit {@code Class.forName} is answered this way — a question the mod asked out loud. A hard
 * bytecode reference still resolves normally, so a class that genuinely links against a Forge type keeps working
 * and never degrades into {@code NoClassDefFoundError}.
 *
 * <h2>Universal jars are exempt</h2>
 *
 * <p>A jar carrying more than one loader's manifest is genuinely multi-platform: its probes are how it finds out
 * which half of itself to run, and {@code MultiLoaderArbiter} has already picked its ecosystem. Those, the merged
 * base, the runtime carriers and the kernel's own classes have no family here and probe as before.
 *
 * <p>Escape hatch: {@code -Dforbric.loaderProbes=off} restores the pre-policy behaviour (every probe answers yes).
 */
public final class LoaderProbePolicy {

	/**
	 * Which loader family a guest class belongs to, for probe purposes.
	 *
	 * <p><b>Deliberately not {@link net.forbric.api.Ecosystem}, and deliberately two-valued.</b> When the kernel's
	 * five ecosystem enums were collapsed into one, this one was kept, because it is not the same question.
	 * {@code FORGE_FAMILY} is a GROUPING: a jar declaring {@code neoforge.mods.toml} still probes for
	 * {@code net.minecraftforge.fml.loading.FMLLoader} and must be told yes, because on a real instance a mod of
	 * either Forge family is running on FML. Splitting this into FORGE and NEOFORGE would make
	 * {@link #forName(String, boolean, ClassLoader, String)} start answering "absent" to exactly those probes.
	 *
	 * <p>It is also not free to rename: {@link #name()} is baked into guest bytecode as an {@code LDC} by
	 * {@code LoaderProbeRewriter} and compared back here, so the constant is part of an already-transformed
	 * class's contract for the life of the process.
	 */
	public enum Family {
		/** Loaded from a jar that declares only {@code fabric.mod.json}. */
		FABRIC,
		/** Loaded from a jar that declares only a Forge or NeoForge mods.toml. */
		FORGE_FAMILY
	}

	/**
	 * The marker classes a mod probes to identify its loader, and the family each one proves. Deliberately short:
	 * these are the canonical "am I on X?" entry points. Registry/ModList types are NOT here — a mod that reaches
	 * for those has usually already chosen a branch, so hiding them would change behaviour with no probe to fix.
	 */
	private static final Map<String, Family> PROBES = Map.of(
			"net.fabricmc.loader.api.FabricLoader", Family.FABRIC,
			"net.fabricmc.loader.impl.FabricLoaderImpl", Family.FABRIC,
			"net.minecraftforge.fml.loading.FMLLoader", Family.FORGE_FAMILY,
			"net.neoforged.fml.loading.FMLLoader", Family.FORGE_FAMILY);

	private static final boolean ENABLED = !"off".equalsIgnoreCase(System.getProperty("forbric.loaderProbes", "on"));

	private LoaderProbePolicy() {
	}

	/** Whether the policy is active at all, and so whether the rewriter has anything to do. */
	public static boolean enabled() {
		return ENABLED;
	}

	/** Whether {@code name} is one of the loader marker classes a mod probes for. */
	public static boolean isProbe(String name) {
		return PROBES.containsKey(name);
	}

	/**
	 * The {@code Class.forName} a single-family guest class actually calls after
	 * {@link net.forbric.kernel.transform.LoaderProbeRewriter} has rewritten it.
	 *
	 * @param name        the class the mod asked about
	 * @param initialize  the mod's own {@code initialize} flag, forwarded untouched
	 * @param loader      the mod's own loader argument, forwarded untouched
	 * @param askingFamily {@link Family#name()} of the jar the calling class came from, baked in at transform time
	 * @throws ClassNotFoundException for a foreign loader's marker class, exactly as on the mod's own platform
	 */
	public static Class<?> forName(String name, boolean initialize, ClassLoader loader, String askingFamily)
			throws ClassNotFoundException {
		Family proves = PROBES.get(name);

		if (ENABLED && proves != null && !proves.name().equals(askingFamily)) {
			ForbricLog.debug("[Forbric/Probe] told a %s class that %s does not exist — it sees the loader it was "
					+ "loaded as (-Dforbric.loaderProbes=off to disable)", askingFamily, name);
			throw new ClassNotFoundException(name);
		}
		return Class.forName(name, initialize, loader);
	}

	/**
	 * The single-argument {@code Class.forName(String)} form. Vanilla semantics resolve against the CALLER's loader,
	 * which for a rewritten guest class is always the kernel's own loader — so that is what gets passed on.
	 */
	public static Class<?> forName(String name, String askingFamily) throws ClassNotFoundException {
		return forName(name, true, guestLoader(), askingFamily);
	}

	/** The loader every rewritten guest class was defined by; set once at boot. */
	private static volatile ClassLoader guestLoader;

	/** Records the loader guest classes are defined by, so the one-argument {@code forName} can resolve against it. */
	public static void bindGuestLoader(ClassLoader loader) {
		guestLoader = loader;
	}

	private static ClassLoader guestLoader() {
		ClassLoader bound = guestLoader;
		return bound == null ? Thread.currentThread().getContextClassLoader() : bound;
	}
}
