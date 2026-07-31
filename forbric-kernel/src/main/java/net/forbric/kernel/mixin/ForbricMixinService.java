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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.ContainerHandleURI;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.IAdviceProvider;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IFeatureValidator;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.IMixinInternal;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.ITransformer;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.util.ReEntranceLock;

import net.fabricmc.api.EnvType;

import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.util.ForbricLog;

/**
 * The kernel IS the Mixin service — the sovereign successor to Fabric Loader's {@code MixinServiceKnot} and to
 * ModLauncher's service, with no loader beneath it.
 *
 * <p>Mixin discovers this through {@code META-INF/services/org.spongepowered.asm.service.IMixinService} on the
 * BOOT classpath, so this class (and the whole {@code net.forbric.kernel.mixin} package) is parent-loaded, while
 * every class it hands Mixin comes from the one {@link ForbricClassLoader}. Bytecode is always served
 * <em>pre</em>-mixin: serving woven bytes would make the weaver re-weave its own output.
 *
 * <p>The two config-rewriting mechanisms below are carried over from the old weld's substrate patches (0007/0008),
 * where they were proven necessary. They exist because guest mixins are written against <i>vanilla</i> bytecode
 * while the merged base is vanilla+Forge+NeoForge byte-merged, so an anchor a mixin expects may have moved. They
 * are first-class kernel logic here rather than a patch on someone else's loader — and both are off unless the
 * corresponding system property names a config, so the default is strict, unrelaxed Mixin behaviour.
 */
public final class ForbricMixinService
		implements IMixinService, IClassProvider, IClassBytecodeProvider, ITransformerProvider, IClassTracker {
	/**
	 * Report every misfitting injection instead of silently skipping it. Read once: Mixin reads configs during
	 * bootstrap, and a mid-run flip would give an inconsistent picture.
	 */
	private static final boolean DIAGNOSTICS = Boolean.getBoolean("forbric.mixinDiagnostics");

	private static volatile ForbricClassLoader gameLoader;
	private static volatile EnvType envType = EnvType.SERVER;
	private static volatile IMixinTransformer transformer;

	private final ReEntranceLock lock = new ReEntranceLock(1);

	/** Points the service at the transforming loader + side. Must be called before {@code MixinBootstrap.init()}. */
	public static void bind(ForbricClassLoader loader, EnvType side) {
		gameLoader = loader;
		envType = side;
	}

	/** The weaver Mixin handed us via {@link #offer}, or {@code null} before bootstrap. */
	public static IMixinTransformer getTransformer() {
		return transformer;
	}

	private static ForbricClassLoader loader() {
		ForbricClassLoader l = gameLoader;
		if (l == null) throw new IllegalStateException("ForbricMixinService used before bind()");

		return l;
	}

	// --- IMixinService ---

	@Override
	public String getName() {
		return "Forbric";
	}

	@Override
	public boolean isValid() {
		return true;
	}

	@Override
	public void prepare() {
	}

	@Override
	public MixinEnvironment.Phase getInitialPhase() {
		return MixinEnvironment.Phase.PREINIT;
	}

	@Override
	public void offer(IMixinInternal internal) {
		if (internal instanceof IMixinTransformerFactory) {
			transformer = ((IMixinTransformerFactory) internal).createTransformer();
		}
	}

	@Override
	public void init() {
	}

	@Override
	public void beginPhase() {
	}

	@Override
	public void checkEnv(Object bootSource) {
	}

	@Override
	public ReEntranceLock getReEntranceLock() {
		return lock;
	}

	@Override
	public IClassProvider getClassProvider() {
		return this;
	}

	@Override
	public IClassBytecodeProvider getBytecodeProvider() {
		return this;
	}

	@Override
	public ITransformerProvider getTransformerProvider() {
		return this;
	}

	@Override
	public IClassTracker getClassTracker() {
		return this;
	}

	@Override
	public IMixinAuditTrail getAuditTrail() {
		return null;
	}

	@Override
	public IFeatureValidator getFeatureValidator() {
		return IFeatureValidator.ALLOW_ALL;
	}

	@Override
	public IAdviceProvider getAdviceProvider() {
		return IAdviceProvider.GENERIC;
	}

	@Override
	public Collection<String> getPlatformAgents() {
		return Collections.singletonList("org.spongepowered.asm.launch.platform.MixinPlatformAgentDefault");
	}

	@Override
	public IContainerHandle getPrimaryContainer() {
		try {
			URL source = ForbricMixinService.class.getProtectionDomain().getCodeSource().getLocation();
			return new ContainerHandleURI(source.toURI());
		} catch (Throwable t) {
			throw new IllegalStateException("cannot resolve the kernel's own code source for Mixin", t);
		}
	}

	@Override
	public Collection<IContainerHandle> getMixinContainers() {
		return Collections.emptyList();
	}

	@Override
	public String getSideName() {
		return envType.name();
	}

	@Override
	public MixinEnvironment.CompatibilityLevel getMinCompatibilityLevel() {
		return MixinEnvironment.CompatibilityLevel.JAVA_8;
	}

	@Override
	public MixinEnvironment.CompatibilityLevel getMaxCompatibilityLevel() {
		return MixinEnvironment.CompatibilityLevel.JAVA_25;
	}

	@Override
	public ILogger getLogger(String name) {
		return new ForbricMixinLogger(name);
	}

	// --- IClassProvider ---

	@Override
	public URL[] getClassPath() {
		// Mixin only used this to find itself, and the kernel reports a correct CodeSource in getPrimaryContainer.
		return new URL[0];
	}

	@Override
	public Class<?> findClass(String name) throws ClassNotFoundException {
		return loader().loadClass(name);
	}

	@Override
	public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
		return Class.forName(name, initialize, loader());
	}

	@Override
	public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
		return Class.forName(name, initialize, ForbricMixinService.class.getClassLoader());
	}

	// --- IClassBytecodeProvider ---

	/** @deprecated Mixin's legacy accessor; it declares only IOException, so a miss returns null rather than throwing. */
	@Deprecated
	public byte[] getClassBytes(String name, String transformedName) throws IOException {
		return loader().getPreMixinClassBytes(name);
	}

	public byte[] getClassBytes(String name, boolean runTransformers) throws ClassNotFoundException, IOException {
		byte[] bytes = loader().getPreMixinClassBytes(name);
		if (bytes == null) throw new ClassNotFoundException(name);

		return bytes;
	}

	@Override
	public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
		return getClassNode(name, true);
	}

	@Override
	public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException {
		return getClassNode(name, runTransformers, 0);
	}

	@Override
	public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags)
			throws ClassNotFoundException, IOException {
		ClassNode node = new ClassNode();
		new ClassReader(getClassBytes(name, runTransformers)).accept(node, readerFlags);

		return node;
	}

	// --- IClassTracker ---

	@Override
	public void registerInvalidClass(String className) {
	}

	@Override
	public boolean isClassLoaded(String className) {
		return loader().isClassLoadedByName(className);
	}

	@Override
	public String getClassRestrictions(String className) {
		return "";
	}

	// --- ITransformerProvider (the kernel owns its pipeline; Mixin delegates nothing) ---

	@Override
	public Collection<ITransformer> getTransformers() {
		return Collections.emptyList();
	}

	@Override
	public Collection<ITransformer> getDelegatedTransformers() {
		return Collections.emptyList();
	}

	@Override
	public void addTransformerExclusion(String name) {
	}

	// --- resources: mixin config JSON, with the merged-base compatibility rewrites ---

	@Override
	public InputStream getResourceAsStream(String name) {
		InputStream in = loader().getGameResourceAsStream(name);
		if (in == null) return null;

		boolean relax = isRelaxedConfig(name);
		List<String> drop = new ArrayList<>(suppressedMixinsFor(name));
		// The general guest-mixin adapter needs the config's own bytes to enumerate its mixins, so it runs below
		// after the JSON is read (only for things that look like mixin configs — not every resource on the path).
		boolean scanForOwned = isMixinConfigName(name) && KernelGuestMixinAdapter.enabled();
		if (!relax && drop.isEmpty() && !scanForOwned) return in;

		try (InputStream source = in) {
			byte[] bytes = source.readAllBytes();
			String json = new String(bytes, StandardCharsets.UTF_8);

			if (scanForOwned) {
				// Derive, from THIS config, the mixins that target a Forge/NeoForge-owned merged class — the general
				// form of MergedBaseMixinCompat's hand-listed renderer/pipeline entries. Each mixin class is a game
				// resource resolvable through the same loader, so no separate mod-jar inventory is needed.
				for (String owned : KernelGuestMixinAdapter.unfitMixins(name, bytes,
						r -> readAdapterClass(r))) {
					if (!drop.contains(owned)) drop.add(owned);
				}
			}

			if (!relax && drop.isEmpty()) return new ByteArrayInputStream(bytes);

			if (relax) {
				// Three independent relaxations, each for a different way a guest mixin meets the merged base:
				//
				//   injectors.defaultRequire -> 0   an injection point whose anchor the byte-merge removed
				//                                   (Bootstrap.bootStrap no longer calls wrapStreams()) soft-skips.
				//   overwrites.requireAnnotations -> false   an @Overwrite byte-identical to a Forge-added method.
				//   required -> false               a mixin that cannot APPLY at all — a callback whose descriptor
				//                                   no longer matches, an @Accessor for a retyped field — is
				//                                   dropped with a warning instead of aborting the launch. This is
				//                                   the only lever for apply-time failures: defaultRequire governs
				//                                   the injection *check*, which never runs if apply throws first.
				//
				// Per-injection require/expect annotations still win, so a mixin that declares its own hard
				// requirement still fails loudly. The mixin ADAPTER (M7) is the real fix; this is v1 parity.
				//
				// DIAGNOSTICS (-Dforbric.mixinDiagnostics): keep the injection requirements STRICT so every
				// misfitting injection is reported, while still setting required=false so the launch survives to
				// collect them all. Relaxing defaultRequire makes a non-matching injection SILENT, which is how a
				// half-applied mixin (fabric-registry-sync's ScopedValue re-bind) hid until it crashed at runtime.
				if (!DIAGNOSTICS) {
					json = json.replaceAll("(\"requireAnnotations\"\\s*:\\s*)true", "$1false")
							.replaceAll("(\"defaultRequire\"\\s*:\\s*)\\d+", "$10");
				}

				json = json.replaceAll("(\"required\"\\s*:\\s*)true", "$1false");
				ForbricLog.debug("[Forbric/Mixin] relaxed %s for merged-base compatibility", name);
			}

			for (String mixin : drop) {
				// Remove one named mixin from the config's mixins/client/server arrays, leaving the mod's other
				// mixins to apply. Needed for a mixin that applies cleanly but breaks at RUNTIME on the merged base.
				String token = Pattern.quote("\"" + mixin + "\"");
				json = json.replaceAll(",\\s*" + token, "")
						.replaceAll(token + "\\s*,", "")
						.replaceAll(token, "");
				ForbricLog.info("[Forbric/Mixin] suppressed mixin %s from %s", mixin, name);
			}

			return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new RuntimeException("Forbric: failed rewriting mixin config " + name, e);
		}
	}

	/** Reads a game resource ({@code some/pkg/Name.class}) to its bytes, or null. */
	private static byte[] readGameResource(String resourcePath) {
		try (InputStream in = loader().getGameResourceAsStream(resourcePath)) {
			return in == null ? null : in.readAllBytes();
		} catch (IOException e) {
			return null;
		}
	}

	/** Cache for {@link #readAdapterClass}: ~70 configs re-request the same merged targets. */
	private static final java.util.Map<String, byte[]> ADAPTER_CLASS_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
	private static final byte[] NOT_FOUND = new byte[0];

	/**
	 * The class bytes {@link KernelGuestMixinAdapter} resolves mixin anchors against.
	 *
	 * <p>This MUST serve post-transform-chain bytes, not raw jar bytes: the chain both ADDS members (
	 * {@code ForbricMergedBaseCompatTransformer.addMissingForgeKeyMappingLookupInitializer} installs the
	 * {@code PUTSTATIC} for {@code KeyMapping.MAP}, which {@code merge-conflicts.txt} lists as "read but never
	 * initialized") and REMOVES them ({@code dropInterfaceDefaultShadowingOverrides} deletes methods across
	 * {@code net/minecraft/client/gui/**}). Resolving against the raw jar would judge the mixin against bytecode
	 * that never reaches Mixin — the orphaned-field check in particular would report a false hazard on
	 * {@code KeyMapping.MAP}.
	 *
	 * <p>Falls back to the raw resource for a MIXIN's own class, which is not a game class and so is not transformed.
	 */
	private static byte[] readAdapterClass(String resourcePath) {
		byte[] cached = ADAPTER_CLASS_CACHE.get(resourcePath);
		if (cached != null) return cached == NOT_FOUND ? null : cached;

		byte[] bytes = null;
		if (resourcePath.endsWith(".class")) {
			String className = resourcePath.substring(0, resourcePath.length() - ".class".length()).replace('/', '.');
			try {
				bytes = loader().getPreMixinClassBytes(className);
			} catch (Throwable notAGameClass) {
				bytes = null;
			}
		}
		if (bytes == null) bytes = readGameResource(resourcePath);

		ADAPTER_CLASS_CACHE.put(resourcePath, bytes == null ? NOT_FOUND : bytes);
		return bytes;
	}

	/** Whether {@code name} looks like a mixin config file — the only resources the owned-target scan should read. */
	private static boolean isMixinConfigName(String name) {
		int slash = name.lastIndexOf('/');
		String file = slash >= 0 ? name.substring(slash + 1) : name;
		return file.endsWith(".mixins.json")
				|| file.endsWith(".mixin.json")
				|| (file.startsWith("mixins.") && file.endsWith(".json"));
	}

	/**
	 * Mixin entries to drop from config {@code configName}: the built-in {@link MergedBaseMixinCompat} list plus
	 * anything named by {@code -Dforbric.suppressMixins} (csv of {@code configName:MixinEntry}). Neither a config
	 * name nor a mixin entry contains a colon.
	 */
	private static List<String> suppressedMixinsFor(String configName) {
		List<String> out = new ArrayList<>();

		if (MergedBaseMixinCompat.enabled()) {
			collectSuppressed(MergedBaseMixinCompat.SUPPRESSED_MIXINS, configName, out);
		}

		String csv = System.getProperty("forbric.suppressMixins");
		if (csv != null && !csv.isEmpty()) {
			collectSuppressed(List.of(csv.split(",")), configName, out);
		}

		return out.isEmpty() ? Collections.emptyList() : out;
	}

	private static void collectSuppressed(List<String> entries, String configName, List<String> out) {
		for (String raw : entries) {
			String entry = raw.trim();
			if (entry.isEmpty()) continue;

			int colon = entry.indexOf(':');
			if (colon <= 0 || colon >= entry.length() - 1) continue;

			if (entry.substring(0, colon).trim().equals(configName)) {
				String mixin = entry.substring(colon + 1).trim();
				if (!out.contains(mixin)) out.add(mixin);
			}
		}
	}

	/**
	 * Every mixin config that came from a discovered GUEST mod — relaxed by default.
	 *
	 * <p>Populated by {@link KernelMixinBootstrap} from the exact config list it registers. The kernel authors no
	 * mixins of its own, so every registered config belongs to a guest mod; infrastructure names are excluded
	 * anyway so a future kernel-owned config would still fail loudly.
	 */
	private static volatile java.util.Set<String> guestConfigs = java.util.Set.of();

	/**
	 * Records the guest mixin configs to relax. Called once, before any config is read.
	 *
	 * <p>Relaxing only {@code fabric-*} (the old hardcoded launch-script glob) meant a single unpatchable injector
	 * in ANY other real mod aborted the whole launch: {@code collective} ships a {@code collective_fabric.mixins.json}
	 * whose {@code PlayerMixin} descriptor no longer matches the merged base, and because the name does not start
	 * with {@code fabric-} it was a fatal {@code MixinApplyError} rather than a soft skip. Guest mixins are written
	 * against vanilla bytecode while the base is byte-merged, so ANY guest config can meet a moved anchor — the
	 * relaxation belongs to "is this a guest mod's config", not to one naming convention. (This mirrors the
	 * differential oracle, which relaxes every discovered guest mod's configs and excludes only infrastructure.)
	 */
	public static void setGuestConfigs(Collection<String> configs) {
		if (configs == null || "off".equalsIgnoreCase(System.getProperty("forbric.relaxGuestMixins", "on"))) {
			guestConfigs = java.util.Set.of();
			return;
		}
		java.util.Set<String> guests = new java.util.LinkedHashSet<>();
		for (String config : configs) {
			if (config == null || config.isEmpty()) continue;
			if (isInfrastructureConfig(config)) continue;
			guests.add(config);
		}
		guestConfigs = java.util.Set.copyOf(guests);
	}

	/**
	 * Kernel configs, never relaxed — a genuine failure in our own code must crash loudly.
	 *
	 * <p>This used to also exclude {@code forge.}, {@code neoforge.} and {@code minecraft.}, on the theory that they
	 * name the ecosystem runtimes' own configs. They do not reach here: the runtimes arrive via {@code --runtimeJar}
	 * (see {@code KernelBoot}), which contributes nothing to mixin discovery, and discovery only scans
	 * {@code <gameDir>/mods}. Meanwhile the moment Forge/NeoForge GUEST configs joined the registered set, those
	 * prefixes became a live hazard: a guest config legitimately named {@code forge.mixins.json} or
	 * {@code neoforge.mixins.json} would silently not be relaxed, so one unpatchable injector in it becomes a fatal
	 * {@code MixinApplyError} instead of the soft skip that general relaxation exists to provide.
	 *
	 * <p>Keep {@code forbric}: the kernel authors no mixins today, but if it ever does, that one must fail loudly.
	 */
	static boolean isInfrastructureConfig(String config) {
		return config.startsWith("forbric");
	}

	/**
	 * Whether this config is relaxed: it came from a guest mod, or {@code -Dforbric.relaxMixinOverwrites} names it
	 * (trailing {@code *} = prefix glob). {@code -Dforbric.relaxGuestMixins=off} restores strict behaviour.
	 *
	 * <p>Package-private rather than private so a test can pin which names relax without booting Mixin — the
	 * distinction is invisible at runtime until exactly one injector fails, at which point it decides between a soft
	 * skip and a fatal apply error.
	 */
	static boolean isRelaxedConfig(String name) {
		if (guestConfigs.contains(name)) return true;

		String csv = System.getProperty("forbric.relaxMixinOverwrites");
		if (csv == null || csv.isEmpty()) return false;

		for (String raw : csv.split(",")) {
			String entry = raw.trim();
			if (entry.isEmpty()) continue;

			if (entry.endsWith("*")) {
				if (name.startsWith(entry.substring(0, entry.length() - 1))) return true;
			} else if (name.equals(entry)) {
				return true;
			}
		}

		return false;
	}
}
