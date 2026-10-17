/*
 * Copyright 2026 The Forbric Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package net.forbric.kernel.transform;

import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.GameEventBridge;
import net.forbric.api.EventBridges;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Caught;

/**
 * Preserves NeoForge's table construction/writeback and redirects only the intervening event post.
 *
 * <p>A merge that keeps both sides' edits writes MinecraftForge's own registration into the same body ahead of
 * NeoForge's — seed, post on MinecraftForge's bus, write back — and then the table already serves MinecraftForge
 * first: routing NeoForge's post through the bridge as well would post MinecraftForge's event a second time. Such a
 * body is left as it is (the deferred client half still runs: it does not depend on the redirect). The outcome is
 * judged by {@link #CLAIM}: MinecraftForge's registration reaches the table, through the bridge or natively.
 */
public final class ForgeSpawnPlacementsInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.forgeSpawnPlacements";
	private static final String TARGET = "net.minecraft.world.entity.SpawnPlacements";
	private static final String POST_DESC = "(Lnet/neoforged/bus/api/Event;)V";
	private static final String RUNTIME = "net/forbric/kernel/runtime/KernelForgeSpawnPlacements";
	private static final String LOADER = ForeignType.FML_MOD_LOADER.internal(Ecosystem.NEOFORGE);
	private static final String EVENT = ForeignType.SPAWN_PLACEMENT_EVENT.internal(Ecosystem.NEOFORGE);
	private static final String FORGE_EVENT = ForeignType.SPAWN_PLACEMENT_EVENT.internal(Ecosystem.FORGE);
	static final String CLAIM = "forbric-forge-spawn-placements#minecraftForgeRegistrationReachesTheTable";

	@Override public String name() { return "forbric-forge-spawn-placements"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("switched off by -D" + PROPERTY);
		return AnchorSet.scanned("SpawnPlacements, judged by the " + CLAIM + " claim");
	}

	@Override public List<Claim> claims() {
		if (!enabled()) return List.of();
		return List.of(new Claim(CLAIM, AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"MinecraftForge spawn placement registrations never reach the game's spawn table"))));
	}

	private static boolean enabled() { return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on")); }

	@Override public byte[] transform(String className, byte[] classBytes, TransformContext context, ClaimReporter reporter) {
		byte[] result = transform(className, classBytes, context);
		if (enabled() && TARGET.equals(className) && result != null && result.length > 0 && servesMinecraftForge(result)) reporter.hit(CLAIM);
		return result;
	}

	/** Whether {@code fireSpawnPlacementEvent} posts MinecraftForge's registration: through the bridge, or itself. */
	static boolean servesMinecraftForge(byte[] bytes) {
		ClassNode node = new ClassNode();
		try { new ClassReader(bytes).accept(node, 0); } catch (RuntimeException unreadable) { Caught.warn(unreadable, "[Forbric/SpawnPlacements] could not read a class to check fireSpawnPlacementEvent; taken as not serving MinecraftForge"); return false; }
		for (MethodNode method : node.methods) {
			if (!method.name.equals("fireSpawnPlacementEvent") || !method.desc.equals("()V")) continue;
			for (var instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode call && call.owner.equals(RUNTIME) && call.name.equals("postBothFamilies")) return true;
			}
			return postsMinecraftForgesEvent(method);
		}
		return false;
	}

	/** MinecraftForge's own registration in the body: its event constructed and handed straight to a post. */
	static boolean postsMinecraftForgesEvent(MethodNode method) {
		for (var instruction : method.instructions) {
			if (instruction instanceof MethodInsnNode constructor && constructor.getOpcode() == Opcodes.INVOKESPECIAL
					&& constructor.owner.equals(FORGE_EVENT) && constructor.name.equals("<init>")
					&& nextReal(constructor) instanceof MethodInsnNode post && post.name.startsWith("post")) return true;
		}
		return false;
	}

	@Override public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!enabled() || !TARGET.equals(className) || classBytes == null || classBytes.length == 0) return classBytes;
		ClassNode node = new ClassNode(); new ClassReader(classBytes).accept(node, 0);
		if (!TARGET.replace('.', '/').equals(node.name)) return classBytes;
		for (MethodNode method : node.methods) {
			if (method.name.equals("fireSpawnPlacementEvent") && method.desc.equals("()V") && postsMinecraftForgesEvent(method)) {
				// The delivery the bridge would make is the body's own; record it so the registration-pass audit
				// does not name the bridge missing.
				EventBridges.installed(GameEventBridge.SPAWN_PLACEMENTS);
				ForbricLog.info("[Forbric/SpawnPlacements] the merged spawn placement registration already posts MinecraftForge's event "
						+ "before NeoForge's; left as it is");
				return classBytes;
			}
		}
		MethodNode target = null, caller = null;
		MethodInsnNode post = null, constructor = null;
		int methods = 0, posts = 0, constructors = 0, creations = 0;
		for (MethodNode method : node.methods) {
			boolean host = method.name.equals("fireSpawnPlacementEvent") && method.desc.equals("()V");
			if (host) { target = method; methods++; }
			for (var instruction : method.instructions) {
				if (host && instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
						&& type.desc.equals(EVENT)) creations++;
				if (!(instruction instanceof MethodInsnNode call)) continue;
				if (call.owner.equals(RUNTIME) && call.name.equals("postBothFamilies")) return classBytes;
				if (call.owner.equals(LOADER) && call.name.equals("postEvent")) { posts++; caller = method; post = call; }
				if (host && call.owner.equals(EVENT) && call.name.equals("<init>")) { constructors++; constructor = call; }
			}
		}
		if (methods != 1 || posts != 1 || caller != target || creations != 1 || constructors != 1
				|| (target.access & Opcodes.ACC_STATIC) == 0 || (target.access & (Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0
				|| post.getOpcode() != Opcodes.INVOKESTATIC || post.itf || !post.desc.equals(POST_DESC)
				|| constructor.getOpcode() != Opcodes.INVOKESPECIAL || constructor.itf || !constructor.desc.equals("(Ljava/util/Map;)V")
				|| previousReal(post) != constructor) return classBytes;
		post.owner = RUNTIME; post.name = "postBothFamilies";
		EventBridges.installed(GameEventBridge.SPAWN_PLACEMENTS);
		ClassWriter writer = new ClassWriter(0); node.accept(writer);
		ForbricLog.info("[Forbric/SpawnPlacements] spawn placement registration now serves MinecraftForge before NeoForge");
		return writer.toByteArray();
	}

	private static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
		AbstractInsnNode next = instruction.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		return next;
	}

	private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
		AbstractInsnNode previous = instruction.getPrevious();
		while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
		return previous;
	}
}
