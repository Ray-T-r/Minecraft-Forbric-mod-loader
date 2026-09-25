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
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.util.ForbricLog;

/**
 * Makes Fabric API's {@code HudElementRegistry} work on a merged base where NeoForge won the HUD.
 *
 * <p>Fabric's HUD API is implemented by {@code fabric-rendering-v1}'s {@code HudMixin}, which injects at each
 * {@code Hud.extractXxx} CALL SITE inside {@code Hud.extractRenderState} and {@code Hud.extractPlayerHealth}.
 * NeoForge replaced both methods: {@code extractRenderState} is now {@code updateContextualBarRenderer();
 * layerManager.render(…)}, and the per-element calls moved into {@code Hud.registerVanillaLayers()} as
 * {@code GuiLayerManager.add(id, layer, gate)} calls whose layers are DIRECT METHOD REFERENCES. A method
 * reference compiles to an {@code invokedynamic} with a method handle — there is no lambda body — so those call
 * sites do not exist as bytecode anywhere in the merged base and no amount of anchor resolution can find them.
 *
 * <p>The result is the worst kind of failure: fifteen of {@code HudMixin}'s anchors quietly do not resolve, the
 * mixin still applies (it is only PARTIAL, not broken), every mod initialises cleanly, and then nothing draws.
 * Xaero's Minimap is how this was found — it registers its renderer, completes every init stage, reports no error
 * and renders nothing, because {@code attachElementAfter} put its element somewhere that is never visited. Any
 * Fabric mod using this API had the same problem; only one of them was noticed.
 *
 * <p>So the dispatch is rebuilt on the layer manager instead. The two ecosystems' element types are the same
 * function — {@code HudElement.extractRenderState(GuiGraphicsExtractor, DeltaTracker)} and
 * {@code GuiLayer.render(GuiGraphicsExtractor, DeltaTracker)} — so one generated class implements both and is its
 * own vanilla-element callback: as a {@code GuiLayer} it hands the Fabric root {@code this}, and as a
 * {@code HudElement} it calls the layer NeoForge originally registered. {@code RootLayer.extractRenderState}
 * walks its list rendering attached-before elements, then the element it was handed, then attached-after — which
 * is exactly Fabric's contract, unchanged.
 *
 * <p>Three entries in the table are deliberately absent, and each one would be a double render:
 *
 * <ul>
 *   <li><b>{@code subtitle_overlay}</b> — Fabric's {@code subtitles} root is dispatched by a SEPARATE mixin,
 *       {@code SubtitleOverlayMixin}, which wraps {@code SubtitleOverlay.extractRenderState}. That class is pure
 *       vanilla and the merge did not touch it, so that root already works. Note that {@code addLast} appends
 *       into {@code subtitles} (and {@code addFirst} prepends into {@code misc_overlays}) — Fabric's
 *       {@code FIRST}/{@code LAST} are aliases of two ordinary roots, not extra ones, so there is nothing to
 *       render "around" the stack either.</li>
 *   <li><b>{@code after_camera_decorations}</b> — {@code extractor.nextStratum()}, a stratum separator with no
 *       elements of its own and no Fabric counterpart.</li>
 *   <li><b>{@code contextual_info_bar}</b> — {@code ContextualBar.extractRenderState}; Fabric's {@code INFO_BAR}
 *       wraps {@code extractBackground}, which is the {@code contextual_info_bar_background} layer instead.</li>
 * </ul>
 *
 * <p>One accepted fidelity gap: vanilla renders the spectator menu and the item hotbar as the two arms of an
 * {@code if (gameMode == SPECTATOR)}, so on stock Fabric only one of those roots draws at a time. NeoForge has a
 * single {@code hotbar} layer, so nesting both roots around it means both always draw. Drawing a HUD element in
 * the wrong game mode is cosmetic; drawing nothing is the bug being fixed. The alternative — bridge only
 * {@code hotbar} and warn when {@code spectator_menu} has attachments — is not obviously better and nothing in
 * the observed mod sets uses that root.
 *
 * <p>A root the game already dispatches through fabric-rendering-v1's own {@code HudMixin} is left to it. The
 * kernel's renamed-body retarget moves the health, armor, food and air handlers from vanilla's
 * {@code extractPlayerHealth} into NeoForge's {@code extractHealthLevel} and its siblings — which the
 * {@code player_health}, {@code armor_level}, {@code food_level} and {@code air_level} layers call — so wrapping those
 * layers as well drew every element attached around those roots twice a frame. The mixin is the better owner: it
 * wraps exactly the vanilla call, leaving NeoForge's {@code leftHeight}/{@code rightHeight} bookkeeping outside a
 * replaced element. Which roots it owns is read off the final {@code Hud} class ({@link #liveRoots}).
 *
 * <p>When {@code fabric-rendering-v1} is absent {@link #wrap} returns the layer BY IDENTITY, so the render path
 * is byte-for-byte what it would be without the kernel and costs nothing per frame. {@code -Dforbric.hudBridge=off}
 * does the same on demand.
 */
public final class KernelHudBridge {
	private static final String PROPERTY = "forbric.hudBridge";

	private static final String REGISTRY_IMPL = "net.fabricmc.fabric.impl.client.rendering.hud.HudElementRegistryImpl";
	private static final String ROOT_LAYER = REGISTRY_IMPL + "$RootLayer";
	private static final String VANILLA_ELEMENTS = "net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements";
	private static final String HUD_ELEMENT = "net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement";
	private static final String GUI_LAYER = "net.neoforged.neoforge.client.gui.GuiLayer";

	private static final String GEN = "net/forbric/kernel/runtime/KernelHudLayer";

	/**
	 * NeoForge {@code VanillaGuiLayers} id -> the {@code VanillaHudElements} constants whose roots wrap it,
	 * OUTERMOST first. Both id sets were read off their classes' {@code <clinit>} string constants; the pairing
	 * comes from which {@code Hud} method each NeoForge layer calls and which call each Fabric root wraps.
	 *
	 * <p>Fabric roots are named by FIELD, not by id string, so the lookup uses the very {@code Identifier}
	 * instances Fabric keyed its own map with. See the class javadoc for the three ids left out on purpose.
	 */
	private static final Map<String, String[]> ROOTS = roots();

	private static Map<String, String[]> roots() {
		Map<String, String[]> map = new LinkedHashMap<>();
		map.put("minecraft:camera_overlays", new String[] {"MISC_OVERLAYS"});
		map.put("minecraft:crosshair", new String[] {"CROSSHAIR"});
		map.put("minecraft:hotbar", new String[] {"SPECTATOR_MENU", "HOTBAR"});
		map.put("minecraft:player_health", new String[] {"HEALTH_BAR"});
		map.put("minecraft:armor_level", new String[] {"ARMOR_BAR"});
		map.put("minecraft:food_level", new String[] {"FOOD_BAR"});
		map.put("minecraft:air_level", new String[] {"AIR_BAR"});
		map.put("minecraft:vehicle_health", new String[] {"MOUNT_HEALTH"});
		map.put("minecraft:contextual_info_bar_background", new String[] {"INFO_BAR"});
		map.put("minecraft:experience_level", new String[] {"EXPERIENCE_LEVEL"});
		map.put("minecraft:selected_item_name", new String[] {"HELD_ITEM_TOOLTIP"});
		map.put("minecraft:spectator_tooltip", new String[] {"SPECTATOR_TOOLTIP"});
		map.put("minecraft:effects", new String[] {"MOB_EFFECTS"});
		map.put("minecraft:boss_overlay", new String[] {"BOSS_BAR"});
		map.put("minecraft:sleep_overlay", new String[] {"SLEEP"});
		map.put("minecraft:demo_overlay", new String[] {"DEMO_TIMER"});
		map.put("minecraft:scoreboard_sidebar", new String[] {"SCOREBOARD"});
		map.put("minecraft:overlay_message", new String[] {"OVERLAY_MESSAGE"});
		map.put("minecraft:title", new String[] {"TITLE_AND_SUBTITLE"});
		map.put("minecraft:chat", new String[] {"CHAT"});
		map.put("minecraft:tab_list", new String[] {"PLAYER_LIST"});
		return Map.copyOf(map);
	}

	private enum State { UNRESOLVED, PRESENT, ABSENT }

	private static volatile ForbricClassLoader guestLoader;
	private static volatile State state = State.UNRESOLVED;

	private static Class<?> generated;
	private static Constructor<?> generatedCtor;
	private static Method getRoot;
	private static Class<?> vanillaElements;
	private static int bridged;
	private static int settled;

	private static final String HUD = "net.minecraft.client.gui.Hud";
	private static final String VANILLA_ELEMENTS_INTERNAL = VANILLA_ELEMENTS.replace('.', '/');
	/** {@code VanillaHudElements} fields some live method of the final {@code Hud} class already dispatches. */
	private static volatile Set<String> mixinOwned = Set.of();

	private KernelHudBridge() {
	}

	/** {@code -Dforbric.hudBridge=off} restores the pre-bridge behaviour: Fabric HUD elements render nothing. */
	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Records the loader guest classes are defined by. Called from {@code KernelBoot} beside the other binds. */
	public static void bind(ForbricClassLoader loader) {
		guestLoader = loader;
		synchronized (KernelHudBridge.class) {
			state = State.UNRESOLVED;
			generated = null;
			generatedCtor = null;
			getRoot = null;
			vanillaElements = null;
			bridged = 0;
			settled = 0;
		}
	}

	/**
	 * Called with every class the kernel loader defines; reads the final {@code Hud} — after Mixin, before its
	 * constructor registers a single layer — for the roots fabric-rendering-v1's own handlers already dispatch.
	 */
	public static void observeDefinition(String name, byte[] bytes) {
		if (!HUD.equals(name) || bytes == null) return;
		try {
			ClassNode hud = new ClassNode();
			new ClassReader(bytes).accept(hud, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
			Set<String> owned = liveRoots(hud);
			mixinOwned = owned;
			if (!owned.isEmpty()) {
				ForbricLog.info("[Forbric/HudBridge] %s already dispatched by fabric-rendering-v1's own HudMixin in "
						+ "live Hud code — the layer bridge leaves those roots to it, so their elements draw once",
						String.join(", ", owned));
			}
		} catch (RuntimeException unreadable) {
			ForbricLog.debug("[Forbric/HudBridge] could not read the final Hud class (%s) — bridging every mapped root",
					String.valueOf(unreadable));
		}
	}

	/**
	 * The {@code VanillaHudElements} fields read by a method reachable from what the game calls: the layers
	 * {@code registerVanillaLayers} registers (method handles) and {@code extractRenderState}, following the class's
	 * own calls and handles. A HudMixin handler bound in orphaned vanilla code — {@code extractHotbarAndDecorations},
	 * which nothing calls on this base — is not reachable and owns nothing.
	 */
	static Set<String> liveRoots(ClassNode hud) {
		Map<String, MethodNode> byKey = new HashMap<>();
		for (MethodNode method : hud.methods) byKey.put(method.name + method.desc, method);
		Deque<MethodNode> work = new ArrayDeque<>();
		Set<MethodNode> seen = new HashSet<>();
		for (MethodNode method : hud.methods) {
			if (method.name.equals("registerVanillaLayers") || method.name.equals("extractRenderState")) {
				if (seen.add(method)) work.add(method);
			}
		}
		Set<String> owned = new TreeSet<>();
		while (!work.isEmpty()) {
			MethodNode method = work.poll();
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
						&& field.owner.equals(VANILLA_ELEMENTS_INTERNAL)) {
					owned.add(field.name);
				} else if (insn instanceof MethodInsnNode call && call.owner.equals(hud.name)) {
					MethodNode next = byKey.get(call.name + call.desc);
					if (next != null && seen.add(next)) work.add(next);
				} else if (insn instanceof InvokeDynamicInsnNode indy) {
					for (Object argument : indy.bsmArgs) {
						if (argument instanceof Handle handle && handle.getOwner().equals(hud.name)) {
							MethodNode next = byKey.get(handle.getName() + handle.getDesc());
							if (next != null && seen.add(next)) work.add(next);
						}
					}
				}
			}
		}
		return java.util.Collections.unmodifiableSet(owned);
	}

	/**
	 * The hook the transformed {@code GuiLayerManager.initModdedLayers} calls at its end.
	 *
	 * <p>Boot-side, so it cannot name {@code GuiLayerManager}; the work is game-side in
	 * {@code KernelForgeOverlayLayers}, which builds MinecraftForge's overlay stack and registers it here — the
	 * one moment where every mod is loaded and the HUD has not yet drawn a frame.
	 *
	 * <p>A failure costs only MinecraftForge's overlays, which is the state this replaces, so it never propagates
	 * into the game's own HUD initialisation.
	 */
	public static void addForgeOverlayLayers(Object layerManager) {
		if (layerManager == null) return;
		try {
			Class.forName("net.forbric.kernel.runtime.KernelForgeOverlayLayers", true,
					layerManager.getClass().getClassLoader())
					.getMethod("install", Object.class).invoke(null, layerManager);
		} catch (ClassNotFoundException single) {
			ForbricLog.debug("[Forbric/HudBridge] no MinecraftForge overlay adapter on this loader");
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/HudBridge] could not install MinecraftForge's overlay layers — a mod's HUD "
					+ "overlay will render nothing", t);
		}
	}

	/**
	 * The hook the transformed {@code GuiLayerManager.add} calls.
	 *
	 * <p>Returns {@code layer} unchanged — by identity, so nothing is allocated and the render path is unchanged —
	 * unless fabric-rendering-v1 is installed AND this layer id has Fabric roots attached to it.
	 *
	 * <p>Typed {@code Object} on both sides because this class is boot-side and cannot name {@code Identifier} or
	 * {@code GuiLayer} at compile time; the transform inserts a {@code CHECKCAST} back to the declared type.
	 */
	public static Object wrap(Object identifier, Object layer) {
		if (!enabled() || identifier == null || layer == null) return layer;
		// add(GuiLayerManager, BooleanSupplier) re-adds a sub-manager's layers through the same overload, so a layer
		// can arrive here twice. This is the only reason the check exists.
		if (layer.getClass() == generated) return layer;

		String[] rootNames = ROOTS.get(identifier.toString());
		if (rootNames == null) return layer;
		if (!resolve()) return layer;

		Object wrapped = layer;
		Set<String> owned = mixinOwned;
		try {
			// Innermost first: the LAST name in the array ends up closest to the vanilla layer.
			for (int i = rootNames.length - 1; i >= 0; i--) {
				if (owned.contains(rootNames[i])) continue;    // HudMixin already dispatches it: one owner per root
				Object root = getRoot.invoke(null, vanillaElements.getField(rootNames[i]).get(null));
				if (root == null) continue;
				wrapped = generatedCtor.newInstance(root, wrapped);
			}
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/HudBridge] could not bridge HUD layer " + identifier + " — Fabric HUD elements "
					+ "attached to it will not render", t);
			return layer;
		}
		synchronized (KernelHudBridge.class) {
			if (wrapped != layer) bridged++;
			if (++settled == ROOTS.size()) {
				ForbricLog.info("[Forbric/HudBridge] bridged %d of %d vanilla HUD layers to Fabric's "
						+ "HudElementRegistry — NeoForge won Hud.extractRenderState, so fabric-rendering-v1's element "
						+ "dispatch had nothing to hook and every Fabric HUD element drew nothing%s",
						bridged, ROOTS.size(), bridged == ROOTS.size() ? ""
								: " (the other " + (ROOTS.size() - bridged) + " are dispatched by HudMixin itself)");
			}
		}
		return wrapped;
	}

	/** Resolves fabric-rendering-v1 once. False (permanently) when it is not installed. */
	private static synchronized boolean resolve() {
		if (state == State.PRESENT) return true;
		if (state == State.ABSENT) return false;

		ClassLoader loader = guestLoader;
		if (loader == null) {
			state = State.ABSENT;
			return false;
		}
		try {
			Class<?> registry = Class.forName(REGISTRY_IMPL, false, loader);
			Class<?> rootLayer = Class.forName(ROOT_LAYER, false, loader);
			Class<?> hudElement = Class.forName(HUD_ELEMENT, false, loader);
			Class<?> guiLayer = Class.forName(GUI_LAYER, false, loader);
			vanillaElements = Class.forName(VANILLA_ELEMENTS, false, loader);

			Method extract = single(hudElement);
			Method render = single(guiLayer);
			Method rootExtract = rootLayer.getMethod(extract.getName(), extract.getParameterTypes()[0],
					extract.getParameterTypes()[1], hudElement);

			getRoot = getRootOf(registry);
			generated = guestLoader.defineRuntimeClass(GEN.replace('/', '.'),
					generate(rootLayer, guiLayer, hudElement, render, extract, rootExtract));
			generatedCtor = generated.getDeclaredConstructor(rootLayer, guiLayer);

			state = State.PRESENT;
			return true;
		} catch (Throwable absent) {
			// A normal instance without fabric-rendering-v1 — not a defect, so debug rather than warn.
			ForbricLog.debug("[Forbric/HudBridge] Fabric HudElementRegistry not present (%s) — leaving the NeoForge "
					+ "layer manager exactly as it is", String.valueOf(absent));
			state = State.ABSENT;
			return false;
		}
	}

	/** {@code getRoot(Identifier)}, found by shape so {@code Identifier} is never named boot-side. */
	private static Method getRootOf(Class<?> registry) throws NoSuchMethodException {
		for (Method m : registry.getMethods()) {
			if ("getRoot".equals(m.getName()) && m.getParameterCount() == 1) return m;
		}
		throw new NoSuchMethodException("HudElementRegistryImpl.getRoot");
	}

	/** The single abstract method of a functional interface. */
	private static Method single(Class<?> iface) throws NoSuchMethodException {
		for (Method m : iface.getMethods()) {
			if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) return m;
		}
		throw new NoSuchMethodException(iface.getName() + " has no abstract method");
	}

	/**
	 * Generates {@code KernelHudLayer implements GuiLayer, HudElement}.
	 *
	 * <p>{@code render} hands the Fabric root {@code this} as the vanilla element; {@code extractRenderState} —
	 * what the root calls back when it reaches the vanilla entry in its list — calls the layer NeoForge registered.
	 * Neither method branches, so no {@code StackMapTable} is needed and {@code COMPUTE_MAXS} suffices;
	 * {@code COMPUTE_FRAMES} would have to resolve game types through a loader this code does not have.
	 */
	private static byte[] generate(Class<?> rootLayer, Class<?> guiLayer, Class<?> hudElement,
			Method render, Method extract, Method rootExtract) {
		String rootName = Type.getInternalName(rootLayer);
		String layerName = Type.getInternalName(guiLayer);
		String elementName = Type.getInternalName(hudElement);
		String rootDesc = "L" + rootName + ";";
		String layerDesc = "L" + layerName + ";";
		String frameDesc = Type.getMethodDescriptor(render); // (GuiGraphicsExtractor, DeltaTracker)V — both interfaces

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, GEN, null, "java/lang/Object",
				new String[] {layerName, elementName});
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "root", rootDesc, null, null).visitEnd();
		cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "delegate", layerDesc, null, null).visitEnd();

		MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + rootDesc + layerDesc + ")V",
				null, null);
		ctor.visitCode();
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitVarInsn(Opcodes.ALOAD, 1);
		ctor.visitFieldInsn(Opcodes.PUTFIELD, GEN, "root", rootDesc);
		ctor.visitVarInsn(Opcodes.ALOAD, 0);
		ctor.visitVarInsn(Opcodes.ALOAD, 2);
		ctor.visitFieldInsn(Opcodes.PUTFIELD, GEN, "delegate", layerDesc);
		ctor.visitInsn(Opcodes.RETURN);
		ctor.visitMaxs(0, 0);
		ctor.visitEnd();

		// GuiLayer: root.extractRenderState(extractor, tracker, this)
		MethodVisitor draw = cw.visitMethod(Opcodes.ACC_PUBLIC, render.getName(), frameDesc, null, null);
		draw.visitCode();
		draw.visitVarInsn(Opcodes.ALOAD, 0);
		draw.visitFieldInsn(Opcodes.GETFIELD, GEN, "root", rootDesc);
		draw.visitVarInsn(Opcodes.ALOAD, 1);
		draw.visitVarInsn(Opcodes.ALOAD, 2);
		draw.visitVarInsn(Opcodes.ALOAD, 0);
		draw.visitMethodInsn(Opcodes.INVOKEVIRTUAL, rootName, rootExtract.getName(),
				Type.getMethodDescriptor(rootExtract), false);
		draw.visitInsn(Opcodes.RETURN);
		draw.visitMaxs(0, 0);
		draw.visitEnd();

		// HudElement: delegate.render(extractor, tracker) — the vanilla entry in the root's list calls this back.
		MethodVisitor vanilla = cw.visitMethod(Opcodes.ACC_PUBLIC, extract.getName(),
				Type.getMethodDescriptor(extract), null, null);
		vanilla.visitCode();
		vanilla.visitVarInsn(Opcodes.ALOAD, 0);
		vanilla.visitFieldInsn(Opcodes.GETFIELD, GEN, "delegate", layerDesc);
		vanilla.visitVarInsn(Opcodes.ALOAD, 1);
		vanilla.visitVarInsn(Opcodes.ALOAD, 2);
		vanilla.visitMethodInsn(Opcodes.INVOKEINTERFACE, layerName, render.getName(), frameDesc, true);
		vanilla.visitInsn(Opcodes.RETURN);
		vanilla.visitMaxs(0, 0);
		vanilla.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	/** The bridging table, for tests. */
	static Map<String, String[]> mapping() {
		return ROOTS;
	}

	/** How many vanilla layers have been bridged, for the boot summary. */
	public static int bridgedLayers() {
		return bridged;
	}
}
