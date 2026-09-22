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
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.boot.KernelServerTicks;
import net.forbric.kernel.util.ForbricLog;

/**
 * Gives {@link KernelServerTicks} a tick to measure.
 *
 * <p>A COREMOD transform rather than a mixin, for the reason {@code ClientSmokeTickInjector} gives: the chain
 * already owns this, so there is no mixin config to register and nothing new in the config list a gate asserts
 * on. Injected at the HEAD of {@code tickServer}, because the interval between tick STARTS is what a tick rate
 * is and the method has several returns.
 */
public final class ServerTickSamplerInjector implements ClassTransformer {
	private static final String SERVER = "net.minecraft.server.MinecraftServer";
	private static final String TICK = "tickServer";

	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelServerTicks";
	private static final String HOOK_NAME = "onServerTick";
	private static final String HOOK_DESC = "()V";

	private boolean announced;

	@Override
	public String name() {
		return "forbric-server-tick-sampler";
	}

	@Override
	public AnchorSet anchors() {
		if (!KernelServerTicks.enabled()) return AnchorSet.scanned("the tick sampler is switched off");
		return AnchorSet.of(new AnchorSet.Anchor(SERVER, AnchorSet.Severity.REQUIRED,
				"nothing would measure how long a tick takes, which is the only performance number this loader "
						+ "has, and a slowness report would again have nothing to agree or disagree with"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!SERVER.equals(className) || !KernelServerTicks.enabled()) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		MethodNode tick = null;
		for (MethodNode m : node.methods) {
			// One name, whatever its descriptor: tickServer takes a BooleanSupplier today and has changed shape
			// before. Binding to the descriptor would make this silently stop measuring on the next bump.
			if (TICK.equals(m.name)) {
				tick = m;
				break;
			}
		}
		if (tick == null) {
			ForbricLog.warn("[Forbric/Tick] no MinecraftServer.%s to hook — this run reports no tick times, and a "
					+ "slowness report from it will have nothing to stand on", TICK);
			return classBytes;
		}

		InsnList prologue = new InsnList();
		prologue.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
		tick.instructions.insert(prologue);
		tick.maxStack = Math.max(tick.maxStack, 1);

		if (!announced) {
			announced = true;
			ForbricLog.info("[Forbric/Tick] sampling MinecraftServer.%s%s — a line every %d ticks",
					TICK, tick.desc, KernelServerTicks.REPORT_EVERY);
		}

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
}
