package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;

import net.forbric.api.Ecosystem;
import net.forbric.api.GameEventBridge;
import net.forbric.api.EventBridges;
import net.forbric.api.ForeignType;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Redirect one post call without changing the event object, descriptor or surrounding instructions. */
public final class ForgeBlockTintInjector implements ClassTransformer {
	static final String TARGET = "net.minecraft.client.color.block.BlockColors";
	static final String HOOK = "net/forbric/kernel/runtime/KernelForgeBlockColors";
	static final String POST = "(Lnet/neoforged/bus/api/Event;)V";

	@Override public String name() { return "forbric-forge-block-tints"; }
	@Override public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"MinecraftForge block tint registrations would never populate the live block colors"));
	}

	@Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
		if (!TARGET.equals(name) || "off".equalsIgnoreCase(System.getProperty("forbric.forgeClientInit", "on"))) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		List<MethodInsnNode> candidates = new ArrayList<>();
		int repaired = 0;
		for (var method : node.methods) {
			if (!method.name.equals("createDefault") || !method.desc.equals("()Lnet/minecraft/client/color/block/BlockColors;")) continue;
			for (var instruction : method.instructions) {
				if (!(instruction instanceof MethodInsnNode call)) continue;
				if (call.owner.equals(HOOK) && call.name.equals("postBlockTintSources") && call.desc.equals(POST)) repaired++;
				if (call.getOpcode() != Opcodes.INVOKESTATIC
						|| !call.owner.equals(ForeignType.FML_MOD_LOADER.internal(Ecosystem.NEOFORGE))
						|| !call.name.equals("postEvent") || !call.desc.equals(POST)) continue;
				AbstractInsnNode previous = call.getPrevious();
				while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
				if (!(previous instanceof MethodInsnNode constructor)
						|| constructor.getOpcode() != Opcodes.INVOKESPECIAL || !constructor.name.equals("<init>")
						|| !constructor.owner.equals(ForeignType.BLOCK_TINT_EVENT.internal(Ecosystem.NEOFORGE))
						|| !constructor.desc.equals("(Lnet/minecraft/client/color/block/BlockColors;)V")) return bytes;
				candidates.add(call);
			}
		}
		if (repaired != 0 || candidates.size() != 1) return bytes;
		MethodInsnNode target = candidates.getFirst();
		target.owner = HOOK;
		target.name = "postBlockTintSources";
		EventBridges.installed(GameEventBridge.BLOCK_TINT_SOURCES);
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
}
