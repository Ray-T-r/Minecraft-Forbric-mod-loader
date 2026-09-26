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

package net.forbric.kernel.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/**
 * Redirects the genuine-loader lifecycle trigger the merged base's byte-merge left woven into a vanilla entry
 * point ({@code Main.main}) to the kernel's own native lifecycle hook — so the kernel, not FancyModLoader / FML,
 * owns the lifecycle.
 *
 * <p><b>The merged-base facts.</b> NeoForge won the byte-merge of both entries:
 * <ul>
 *   <li><b>Server</b> — {@code net.minecraft.server.Main.main} calls
 *       {@code net.neoforged.neoforge.server.loading.ServerModLoader.load(Z)V} between {@code Bootstrap.bootStrap()}
 *       and {@code new DedicatedServerSettings(...)}.</li>
 *   <li><b>Client</b> — {@code net.minecraft.client.main.Main.main} calls
 *       {@code net.neoforged.neoforge.client.loading.ClientModLoader.begin()V} at bc 814, after
 *       {@code Bootstrap.validate()} (and after {@code BackgroundWaiter.runAndTick} ran the bootstrap lambda that
 *       froze the registries), and before {@code new Minecraft} → {@code Minecraft.<init>} → the
 *       {@code ClientHooks.initClientHooks} that fires the client mod-bus events (reload listeners, renderers) the
 *       kernel must have populated the {@code ModList} for. This is the exact client analogue of the server's
 *       {@code ServerModLoader.load} site — a plain owner+name swap (no args).</li>
 * </ul>
 * Invoking either would run the genuine mod-loading lifecycle (discovery, module layer, {@code LoadingModList},
 * RegisterEvent dispatch) — the sovereign machinery the kernel replaces.
 *
 * <p><b>The redirect.</b> Each such {@code invokestatic} is retargeted (owner + name) to the matching
 * {@code net.forbric.kernel.boot.KernelLifecycle} hook, keeping the descriptor so the argument on the stack is
 * preserved. The kernel drives its native registration there, at exactly the point the genuine loader would have.
 * The other client mod-loader calls ({@code ClientModLoader.finish/completeModLoading/setupModResourcePacks}) are
 * stubbed separately (see {@code KernelClientLaunch}'s {@code MethodBodyNeuter} targets).
 *
 * <p><b>Fail-loud.</b> If the targeted entry is transformed but no known trigger is found (the merged base moved
 * the call), {@link #missedRequiredExcision()} reports it so the kernel refuses to boot rather than silently
 * firing a genuine lifecycle or leaving the entry un-hooked.
 *
 * <p><b>Fabric's own marker.</b> On the server, the redirected call is followed by
 * {@code Hooks.startServer(null, null)} — the call Fabric Loader patches into {@code Main.main} and mods anchor on
 * as "every mod has initialised" (owo freezes its channels right after it). It goes after the kernel's window, not at
 * Fabric's literal spot after {@code Bootstrap.validate}, because only here is that post-condition true in the
 * kernel; see {@code net.fabricmc.loader.impl.game.minecraft.Hooks}. {@code -Dforbric.fabricHooks=off} leaves it out.
 */
public final class LifecycleHookInjector implements ClassTransformer {

	/** Merged-base dedicated-server entry. */
	public static final String SERVER_MAIN = "net.minecraft.server.Main";
	/** Merged-base client entry. */
	public static final String CLIENT_MAIN = "net.minecraft.client.main.Main";

	private static final String KERNEL_HOOK_OWNER = "net/forbric/kernel/boot/KernelLifecycle";

	/** Fabric Loader's hook class, whose calls mods anchor on. Shipped by the kernel, parent-loaded. */
	static final String FABRIC_HOOKS = "net/fabricmc/loader/impl/game/minecraft/Hooks";
	/** {@code Hooks.startServer} / {@code Hooks.startClient}: {@code (File runDir, Object gameInstance)}. */
	static final String FABRIC_HOOK_DESC = "(Ljava/io/File;Ljava/lang/Object;)V";
	/** {@code -Dforbric.fabricHooks=off}: emit neither Fabric hook call, exactly the bytecode from before they existed. */
	public static final String FABRIC_HOOKS_SWITCH = "forbric.fabricHooks";

	/** Whether the entries carry Fabric's {@code Hooks.startServer}/{@code startClient} calls. Read per transform. */
	static boolean fabricHooksEnabled() {
		return !"off".equalsIgnoreCase(System.getProperty(FABRIC_HOOKS_SWITCH, "on"));
	}

	/**
	 * A genuine mod-loading trigger the byte-merge can leave in an entry, and the kernel hook it redirects to.
	 *
	 * <p>{@code popSlots} = how many argument slots to POP before the (no-arg) kernel hook, when the trigger's
	 * descriptor carries arguments the hook does not want. When {@code popSlots == 0} the descriptor is kept and it
	 * is a plain owner+name swap (the argument stays on the stack for a matching hook signature).
	 */
	private record Trigger(String owner, String name, String desc, String hookName, int popSlots) {}

	// Server triggers: NeoForge won the entry (load(Z)V); the Forge no-arg form is kept in case a base flips it.
	// Same descriptor as the hook — keep the boolean on the stack, plain owner+name swap.
	private static final Trigger[] SERVER_TRIGGERS = {
			new Trigger(ForeignType.SERVER_MOD_LOADER.internal(Ecosystem.NEOFORGE), "load", "(Z)V", "onServerModLoading", 0),
			new Trigger(ForeignType.SERVER_MOD_LOADER.internal(Ecosystem.FORGE), "load", "()V", "onServerModLoadingNoArg", 0),
	};

	// Client trigger: ClientModLoader.begin()V in net.minecraft.client.main.Main.main — the EXACT client analogue of
	// the server's ServerModLoader.load redirect in the dedicated-server Main.main. bc 814, after Bootstrap.validate
	// (811) and after BackgroundWaiter.runAndTick (796) has already run the bootstrap lambda (registries frozen), and
	// BEFORE `new Minecraft` (1613) → Minecraft.<init> → ClientHooks.initClientHooks (which fires the client mod-bus
	// events onClientModLoading must have populated the ModList for). No-arg swap (popSlots 0). Class name differs by
	// family (Neo/Forge).
	//
	// (History: an earlier M5 attempt redirected setupModResourcePacks(PackRepository) in Minecraft.<init> instead,
	// with begin() neutered — that HANGS before Minecraft.<init>, because neutering begin() defers onClientModLoading
	// to a point never reached. Redirecting begin() here mirrors the proven server path and reaches the window.)
	private static final String BEGIN = "begin";
	private static final Trigger[] CLIENT_TRIGGERS = {
			new Trigger(ForeignType.CLIENT_MOD_LOADER.internal(Ecosystem.NEOFORGE), BEGIN, "()V", "onClientModLoading", 0),
			new Trigger(ForeignType.CLIENT_MOD_LOADER.internal(Ecosystem.FORGE), BEGIN, "()V", "onClientModLoading", 0),
	};

	// The class + method carrying the trigger to rewrite. Server: Main.main. Client: Main.main (client entry), where
	// ClientModLoader.begin() is woven — the exact analogue of the server's ServerModLoader.load site.
	private final String transformClass;
	private final String transformMethod;
	private final Trigger[] triggers;
	/**
	 * Whether a redirected trigger is followed by Fabric's {@code Hooks.startServer}. Server only: the client's
	 * equivalent, {@code Hooks.startClient}, belongs in {@code Minecraft.<init>} and is emitted there by
	 * {@code ClientEntrypointHookInjector}.
	 */
	private final boolean fabricServerHook;

	private volatile boolean transformedRequiredEntry;
	private volatile boolean redirectedAtRequiredEntry;

	private LifecycleHookInjector(String transformClass, String transformMethod, Trigger[] triggers,
			boolean fabricServerHook) {
		this.transformClass = transformClass;
		this.transformMethod = transformMethod;
		this.triggers = triggers;
		this.fabricServerHook = fabricServerHook;
	}

	/** The injector for the dedicated-server entry ({@code Main.main}). */
	public static LifecycleHookInjector forServer() {
		return new LifecycleHookInjector(SERVER_MAIN, "main", SERVER_TRIGGERS, true);
	}

	/** The injector for the client ({@code net.minecraft.client.main.Main.main}). */
	public static LifecycleHookInjector forClient() {
		return new LifecycleHookInjector(CLIENT_MAIN, "main", CLIENT_TRIGGERS, false);
	}

	/** Backwards-compatible default: the server entry (existing callers/tests). */
	public LifecycleHookInjector() {
		this(SERVER_MAIN, "main", SERVER_TRIGGERS, true);
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!transformClass.equals(className)) return classBytes;
		transformedRequiredEntry = true;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		int redirected = 0;
		boolean fabricHooks = fabricServerHook && fabricHooksEnabled();
		for (MethodNode m : node.methods) {
			if (!m.name.equals(transformMethod)) continue;
			for (var insn : m.instructions.toArray()) {
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC) continue;
				Trigger t = matchTrigger(call);
				if (t == null) continue;

				if (t.popSlots() == 0) {
					// Same descriptor: keep the argument on the stack, just retarget owner+name.
					call.owner = KERNEL_HOOK_OWNER;
					call.name = t.hookName();
				} else {
					// The trigger has arguments the no-arg hook does not want: POP them, then call the hook. (Every
					// arg the client trigger carries is a single-slot object reference, so one POP per slot.)
					for (int i = 0; i < t.popSlots(); i++) {
						m.instructions.insertBefore(call, new InsnNode(Opcodes.POP));
					}
					call.owner = KERNEL_HOOK_OWNER;
					call.name = t.hookName();
					call.desc = "()V";
				}

				redirected++;
				ForbricLog.info("[Forbric/Lifecycle] redirected genuine loader trigger %s.%s to %s.%s from %s.%s "
						+ "— kernel owns the lifecycle", t.owner(), t.name(), KERNEL_HOOK_OWNER, t.hookName(),
						transformClass, transformMethod);

				if (fabricHooks) {
					// Right after the window, on the same path: the NeoForge base only loads mods when the launch
					// is not --initSettings, and neither may the marker claim they were. Fabric passes (null, null).
					InsnList marker = new InsnList();
					marker.add(new InsnNode(Opcodes.ACONST_NULL));
					marker.add(new InsnNode(Opcodes.ACONST_NULL));
					marker.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FABRIC_HOOKS, "startServer", FABRIC_HOOK_DESC,
							false));
					m.instructions.insert(call, marker);
					// Two references on top of whatever the void call left, which is nothing at this statement
					// boundary; the frames are untouched (no branch in, no branch out, stack balanced).
					m.maxStack += 2;
					ForbricLog.info("[Forbric/Fabric] emitted Fabric Loader's Hooks.startServer after the server "
							+ "mod-loading window — mods anchored on it (owo's freeze) see every mod initialised, "
							+ "as on Fabric (-D%s=off to go back)", FABRIC_HOOKS_SWITCH);
				}
			}
		}

		if (redirected == 0) {
			ForbricLog.error("[Forbric/Lifecycle] %s.%s contained NO known genuine-loader trigger — the merged "
					+ "base's entry shape changed; refusing to boot on an un-hooked lifecycle", transformClass,
					transformMethod);
			return classBytes;
		}
		redirectedAtRequiredEntry = true;

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		byte[] out = writer.toByteArray();

		return out;
	}

	private Trigger matchTrigger(MethodInsnNode call) {
		for (Trigger t : triggers) {
			if (t.owner().equals(call.owner) && t.name().equals(call.name) && t.desc().equals(call.desc)) return t;
		}
		return null;
	}

	/**
	 * True iff the required entry was loaded+transformed but its genuine-loader lifecycle trigger was NOT
	 * redirected (moved/renamed). The kernel checks this after the game class loads and aborts.
	 */
	public boolean missedRequiredExcision() {
		return transformedRequiredEntry && !redirectedAtRequiredEntry;
	}

	/** True once the targeted entry has been transformed (whether or not a trigger was found). */
	public boolean transformedServerEntry() {
		return transformedRequiredEntry;
	}

	@Override
	public String name() {
		return "forbric:lifecycle-hook-injector";
	}

	@Override
	public AnchorSet anchors() {
		// The one FATAL in the tree, and not a new policy: KernelBoot already refuses to boot when this seam is
		// missed. Declaring it puts the same fact in the books so the summary and the build-time audit can see it
		// too.
		return AnchorSet.of(new AnchorSet.Anchor(transformClass, AnchorSet.Severity.FATAL,
				"the genuine loader's own mod-loading lifecycle would run alongside the kernel's, which is the "
						+ "one thing this architecture cannot survive"));
	}
}
