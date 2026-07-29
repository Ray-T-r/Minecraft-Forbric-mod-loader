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
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

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
 */
public final class LifecycleHookInjector implements ClassTransformer {

	/** Merged-base dedicated-server entry. */
	public static final String SERVER_MAIN = "net.minecraft.server.Main";
	/** Merged-base client entry. */
	public static final String CLIENT_MAIN = "net.minecraft.client.main.Main";

	private static final String KERNEL_HOOK_OWNER = "net/forbric/kernel/boot/KernelLifecycle";

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
			new Trigger("net/neoforged/neoforge/server/loading/ServerModLoader", "load", "(Z)V", "onServerModLoading", 0),
			new Trigger("net/minecraftforge/server/loading/ServerModLoader", "load", "()V", "onServerModLoadingNoArg", 0),
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
			new Trigger("net/neoforged/neoforge/client/loading/ClientModLoader", BEGIN, "()V", "onClientModLoading", 0),
			new Trigger("net/minecraftforge/client/loading/ClientModLoader", BEGIN, "()V", "onClientModLoading", 0),
	};

	// The class + method carrying the trigger to rewrite. Server: Main.main. Client: Main.main (client entry), where
	// ClientModLoader.begin() is woven — the exact analogue of the server's ServerModLoader.load site.
	private final String transformClass;
	private final String transformMethod;
	private final Trigger[] triggers;

	private volatile boolean transformedRequiredEntry;
	private volatile boolean redirectedAtRequiredEntry;

	private LifecycleHookInjector(String transformClass, String transformMethod, Trigger[] triggers) {
		this.transformClass = transformClass;
		this.transformMethod = transformMethod;
		this.triggers = triggers;
	}

	/** The injector for the dedicated-server entry ({@code Main.main}). */
	public static LifecycleHookInjector forServer() {
		return new LifecycleHookInjector(SERVER_MAIN, "main", SERVER_TRIGGERS);
	}

	/** The injector for the client ({@code net.minecraft.client.main.Main.main}). */
	public static LifecycleHookInjector forClient() {
		return new LifecycleHookInjector(CLIENT_MAIN, "main", CLIENT_TRIGGERS);
	}

	/** Backwards-compatible default: the server entry (existing callers/tests). */
	public LifecycleHookInjector() {
		this(SERVER_MAIN, "main", SERVER_TRIGGERS);
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!transformClass.equals(className)) return classBytes;
		transformedRequiredEntry = true;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		int redirected = 0;
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
}
