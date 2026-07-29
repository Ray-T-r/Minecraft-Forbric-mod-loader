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

package net.forbric.kernel.boot;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.util.ForbricLog;

/**
 * The kernel's minimal mod-container factory: manufactures a concrete {@code net.neoforged.fml.ModContainer}
 * for an ecosystem baseline / mod, so a {@code @Mod} class (which the container's config registration and event
 * bus flow through) can be constructed without running the genuine loader's discovery + module machinery.
 *
 * <p>{@code ModContainer} is abstract (ctor takes {@code IModInfo}; {@code getEventBus()} is abstract). This
 * generates a tiny concrete subclass with ASM — {@code super(modInfo); getEventBus()->bus} — defined by the
 * transforming loader (so it can extend the game-side {@code ModContainer}), backed by a {@link Proxy}
 * {@code IModInfo} answering just the fields the container path reads ({@code getModId} etc.). This is the M3
 * container keystone, reused for every constructed mod, not just NeoForge's baseline.
 */
public final class KernelModContainerFactory {
	private static final String GEN = "net/forbric/kernel/runtime/KernelModContainer";
	private static volatile Class<?> generated;

	private KernelModContainerFactory() {
	}

	/** Builds a {@code ModContainer} for {@code modId} whose {@code getEventBus()} returns {@code bus}. */
	public static Object create(ForbricClassLoader loader, ClassLoader cl, String modId, Object bus) throws Exception {
		Class<?> iModInfo = Class.forName("net.neoforged.neoforgespi.language.IModInfo", false, cl);
		Class<?> iEventBus = Class.forName("net.neoforged.bus.api.IEventBus", false, cl);
		Class<?> modContainer = Class.forName("net.neoforged.fml.ModContainer", false, cl);

		Object modInfo = proxyModInfo(iModInfo, modId);

		Object genuine = tryGenuineFmlContainer(cl, modId, bus, modInfo, modContainer);
		if (genuine != null) return genuine;

		Class<?> containerClass = ensureGenerated(loader, modContainer, iModInfo, iEventBus);

		Constructor<?> ctor = containerClass.getConstructor(iModInfo, iEventBus);
		Object container = ctor.newInstance(modInfo, bus);
		ForbricLog.debug("[Forbric/Container] built ModContainer for '%s'", modId);
		return container;
	}

	/**
	 * Allocates a genuine {@code net.neoforged.fml.javafmlmod.FMLModContainer} and fills only the fields the
	 * mod-facing API reads — the NeoForge twin of {@link KernelForgeModContext}'s traditional-Forge container.
	 *
	 * <p>A kernel-generated {@code ModContainer} subclass satisfies every abstract-typed call, but NOT an
	 * {@code instanceof}. Real NeoForge library mods resolve their own bus with
	 * {@code ModList.get().getModContainerById(id)} and then narrow to {@code FMLModContainer} — Bookshelf does
	 * exactly this and threw {@code IllegalStateException: Mod 'bookshelf' is not an FML mod!} against the generated
	 * type, aborting its construction. So the container has to BE that class.
	 *
	 * <p>Its only constructor is the loader-facing 4-arg one, which would run genuine FancyModLoader mod-class
	 * discovery, so the instance is allocated without a constructor (Forge's {@code UnsafeHacks}, already the
	 * kernel's technique on the Forge side) and the {@code final} fields are set directly: {@code eventBus} backs
	 * {@code getEventBus()}, and {@code ModContainer}'s {@code modId}/{@code namespace}/{@code modInfo}/
	 * {@code extensionPoints} are what its ctor would have set. {@code scanResults}/{@code modClasses}/{@code layer}
	 * stay null — they only feed the genuine loader's own construction path, which never runs here.
	 *
	 * <p>Returns null (caller falls back to the generated subclass) if anything is missing, so a runtime without
	 * javafmlmod still boots.
	 */
	private static Object tryGenuineFmlContainer(ClassLoader cl, String modId, Object bus, Object modInfo,
			Class<?> modContainer) {
		try {
			Class<?> fmlContainer = Class.forName("net.neoforged.fml.javafmlmod.FMLModContainer", false, cl);
			Class<?> unsafe = Class.forName("net.minecraftforge.unsafe.UnsafeHacks", false, cl);
			Method newInstance = unsafe.getMethod("newInstance", Class.class);
			Method setField = unsafe.getMethod("setField", java.lang.reflect.Field.class, Object.class, Object.class);

			Object container = newInstance.invoke(null, fmlContainer);
			set(setField, fmlContainer, "eventBus", container, bus);
			set(setField, modContainer, "modId", container, modId);
			set(setField, modContainer, "namespace", container, modId);
			set(setField, modContainer, "modInfo", container, modInfo);
			set(setField, modContainer, "extensionPoints", container, new java.util.HashMap<>());

			ForbricLog.debug("[Forbric/Container] built genuine FMLModContainer for '%s'", modId);
			return container;
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Container] no genuine FMLModContainer for '%s' (%s) — using generated subclass",
					modId, String.valueOf(t));
			return null;
		}
	}

	private static void set(Method setField, Class<?> owner, String name, Object target, Object value)
			throws Exception {
		java.lang.reflect.Field field = owner.getDeclaredField(name);
		setField.invoke(null, field, target, value);
	}

	private static synchronized Class<?> ensureGenerated(ForbricClassLoader loader, Class<?> modContainer,
			Class<?> iModInfo, Class<?> iEventBus) {
		if (generated != null) return generated;
		byte[] bytes = generate(
				modContainer.getName().replace('.', '/'),
				iModInfo.getName().replace('.', '/'),
				iEventBus.getName().replace('.', '/'));
		generated = loader.defineRuntimeClass(GEN.replace('/', '.'), bytes);
		return generated;
	}

	// class KernelModContainer extends ModContainer { final IEventBus bus;
	//   KernelModContainer(IModInfo i, IEventBus b){ super(i); this.bus=b; } IEventBus getEventBus(){ return bus; } }
	private static byte[] generate(String superName, String modInfoName, String busName) {
		String busDesc = "L" + busName + ";";
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, GEN, null, superName, null);
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "bus", busDesc, null, null).visitEnd();

		String ctorDesc = "(L" + modInfoName + ";" + busDesc + ")V";
		MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", ctorDesc, null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitVarInsn(Opcodes.ALOAD, 1);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "(L" + modInfoName + ";)V", false);
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitVarInsn(Opcodes.ALOAD, 2);
		ctor.visitFieldInsn(Opcodes.PUTFIELD, GEN, "bus", busDesc);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(0, 0);
		ctor.visitEnd();

		MethodVisitor geb = cw.visitMethod(Opcodes.ACC_PUBLIC, "getEventBus", "()" + busDesc, null, null);
		geb.visitCode();
		geb.visitVarInsn(Opcodes.ALOAD, 0);
		geb.visitFieldInsn(Opcodes.GETFIELD, GEN, "bus", busDesc);
		geb.visitInsn(Opcodes.ARETURN);
		geb.visitMaxs(0, 0);
		geb.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A minimal {@code IModInfo} answering the few fields the container/config path reads. */
	private static Object proxyModInfo(Class<?> iModInfo, String modId) {
		// A non-null owning-file chain: NeoForge's error path ModContainer.acceptEvent → ModLoadingIssue
		// .withAffectedMod dereferences getOwningFile().getFile().getFilePath() when ANY mod-bus event listener
		// throws. With a null owning file that path NPEs and MASKS the real listener error (caught empirically on
		// the client's RegisterKeyMappingsEvent). Provide the chain so real errors surface with their cause.
		// self[0] is back-filled with the IModInfo below so the owning-file proxy's getMods() can return [it]
		// (NeoForge's title-screen version check does getModFileById(id).getMods().get(0)).
		Object[] self = new Object[1];
		Object owningFile = owningFileProxy(iModInfo.getClassLoader(), modId, self);
		Object version = defaultArtifactVersion(iModInfo.getClassLoader());
		Object config = configurableProxy(iModInfo.getClassLoader(), modId);
		InvocationHandler h = (proxy, method, args) -> switch (method.getName()) {
			case "getModId", "getNamespace" -> modId;
			case "getDisplayName" -> modId;
			case "getDescription" -> "";
			// getVersion() must be non-null: NeoForge's ModListScreen.init renders each mod's version via
			// MavenVersionTranslator.artifactVersionToString(getVersion()), which does version.toString() unguarded —
			// a null crashes the Mods screen the instant it opens (then its tick NPEs on the half-built modList).
			case "getVersion" -> version;
			// getConfig() must be non-null: ModListScreen.updateCache — which runs from the screen's TICK, so it
			// fires on every frame the Mods list is open — does getConfig().getConfigElement(...) unguarded. A null
			// crashed the client the moment the list was shown again, which is what closing a mod's config screen
			// with Done does (Done pops back to the Mods list).
			case "getConfig" -> config;
			case "getModProperties" -> Map.of();
			case "getDependencies", "getForgeFeatures" -> List.of();
			case "getUpdateURL", "getModURL", "getLogoFile" -> Optional.empty();
			case "getLogoBlur" -> Boolean.FALSE;
			case "getOwningFile" -> owningFile;
			case "toString" -> "KernelModInfo[" + modId + "]";
			case "hashCode" -> System.identityHashCode(proxy);
			case "equals" -> proxy == (args == null ? null : args[0]);
			default -> defaultReturn(method);
		};
		Object modInfo = Proxy.newProxyInstance(iModInfo.getClassLoader(), new Class<?>[] {iModInfo}, h);
		self[0] = modInfo;
		return modInfo;
	}

	/**
	 * A minimal {@code IModFileInfo} (→ {@code IModFile} → a placeholder file path) so NeoForge's mod-loading
	 * error/reporting paths that walk {@code getOwningFile().getFile().getFilePath()} do not NPE on the kernel's
	 * synthetic containers. Returns null (the old behavior) if the SPI types are absent — never fails the caller.
	 */
	private static Object owningFileProxy(ClassLoader cl, String modId, Object[] modInfoHolder) {
		try {
			Class<?> iModFileInfo = Class.forName("net.neoforged.neoforgespi.language.IModFileInfo", false, cl);
			Class<?> iModFile = Class.forName("net.neoforged.neoforgespi.locating.IModFile", false, cl);
			java.nio.file.Path path = java.nio.file.Path.of("forbric-kernel", modId + ".jar");
			Object modFile = Proxy.newProxyInstance(cl, new Class<?>[] {iModFile}, (p, m, a) -> switch (m.getName()) {
				case "getFilePath" -> path;
				case "getModFileInfo" -> null; // set below via the enclosing IModFileInfo when asked
				case "toString" -> "KernelModFile[" + modId + "]";
				case "hashCode" -> System.identityHashCode(p);
				case "equals" -> p == (a == null ? null : a[0]);
				default -> defaultReturn(m);
			});
			return Proxy.newProxyInstance(cl, new Class<?>[] {iModFileInfo}, (p, m, a) -> switch (m.getName()) {
				case "getFile" -> modFile;
				// NeoForge's title-screen version check reads getModFileById(id).getMods().get(0) — return [modInfo].
				case "getMods" -> modInfoHolder[0] != null ? List.of(modInfoHolder[0]) : List.of();
				// ModListScreen.updateCache reads the license straight into the info pane; keep it a real String.
				case "getLicense" -> "";
				case "toString" -> "KernelModFileInfo[" + modId + "]";
				case "hashCode" -> System.identityHashCode(p);
				case "equals" -> p == (a == null ? null : a[0]);
				default -> defaultReturn(m);
			});
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Container] no IModFileInfo SPI — owning-file chain left null: %s",
					String.valueOf(t));
			return null;
		}
	}

	/**
	 * A non-null {@code IConfigurable} for the proxy's {@code getConfig()}, reporting "this mod declares nothing".
	 *
	 * <p>Genuine NeoForge backs this with the parsed {@code neoforge.mods.toml} section; the kernel constructs its
	 * containers directly and has no such section, but the value may not be null — {@code ModListScreen.updateCache}
	 * dereferences it on every tick the Mods list is open. Both interface methods are answered by
	 * {@link #defaultReturn}: {@code getConfigElement} → {@code Optional.empty()}, {@code getConfigList} →
	 * {@code List.of()}, i.e. every lookup simply finds nothing, which is the correct answer here.
	 */
	private static Object configurableProxy(ClassLoader cl, String modId) {
		try {
			Class<?> iConfigurable = Class.forName("net.neoforged.neoforgespi.language.IConfigurable", false, cl);
			return Proxy.newProxyInstance(cl, new Class<?>[] {iConfigurable}, (p, m, a) -> switch (m.getName()) {
				case "toString" -> "KernelModConfig[" + modId + "]";
				case "hashCode" -> System.identityHashCode(p);
				case "equals" -> p == (a == null ? null : a[0]);
				default -> defaultReturn(m);
			});
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Container] no IConfigurable SPI — getConfig() stays null: %s",
					String.valueOf(t));
			return null;
		}
	}

	private static Object defaultReturn(Method method) {
		Class<?> r = method.getReturnType();
		if (r == boolean.class) return Boolean.FALSE;
		if (r == int.class) return 0;
		if (r == Optional.class) return Optional.empty();
		if (r == List.class) return List.of();
		if (r == Map.class) return Map.of();
		return null;
	}

	/**
	 * A non-null {@code ArtifactVersion} for the proxy's {@code getVersion()}, constructed reflectively (the kernel
	 * is clean-room and carries no compile dep on maven-artifact). {@code "0.0"} is the conventional unknown-version
	 * placeholder. Null if the type is somehow absent — the caller's field stays null, the old behaviour.
	 */
	private static Object defaultArtifactVersion(ClassLoader cl) {
		try {
			return Class.forName("org.apache.maven.artifact.versioning.DefaultArtifactVersion", true, cl)
					.getConstructor(String.class).newInstance("0.0");
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Container] no DefaultArtifactVersion — getVersion() stays null: %s",
					String.valueOf(t));
			return null;
		}
	}
}
