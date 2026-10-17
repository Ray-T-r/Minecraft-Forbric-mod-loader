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
import org.objectweb.asm.tree.MethodNode;
import net.forbric.kernel.util.Caught;

/**
 * Redirect one post call without changing the event object, descriptor or surrounding instructions.
 *
 * <p>A {@code createDefault} that already hands its BlockColors to MinecraftForge itself — a merge that keeps both sides'
 * edits writes {@code ForgeHooksClient.onBlockColorsInit(colors)} right before NeoForge's post — is left alone: the funnel
 * would register every MinecraftForge mod's block tints a second time. That call counts only when it is handed the very
 * BlockColors NeoForge's event is built with and runs on the same path as NeoForge's post ({@link NativeCallPairing}); a
 * call handed another object, or one on another branch, is not MinecraftForge's registration of the live colors. The
 * outcome is judged by {@link #CLAIM}: the live BlockColors reaches MinecraftForge's registration, through the funnel or
 * natively.
 */
public final class ForgeBlockTintInjector implements ClassTransformer {
	static final String TARGET = "net.minecraft.client.color.block.BlockColors";
	static final String HOOK = "net/forbric/kernel/runtime/KernelForgeBlockColors";
	static final String POST = "(Lnet/neoforged/bus/api/Event;)V";
	static final String CLAIM = "forbric-forge-block-tints#minecraftForgeRegistersBlockTints";

	@Override public String name() { return "forbric-forge-block-tints"; }
	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("MinecraftForge client init switched off by -Dforbric.forgeClientInit=off");
		return AnchorSet.scanned("BlockColors, judged by the " + CLAIM + " claim");
	}

	@Override public List<Claim> claims() {
		if (!enabled()) return List.of();
		return List.of(new Claim(CLAIM, AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"MinecraftForge block tint registrations would never populate the live block colors"))));
	}

	private static boolean enabled() { return !"off".equalsIgnoreCase(System.getProperty("forbric.forgeClientInit", "on")); }

	@Override public byte[] transform(String name, byte[] bytes, TransformContext context, ClaimReporter reporter) {
		byte[] result = transform(name, bytes, context);
		if (enabled() && TARGET.equals(name) && result != null && result.length > 0 && reachesMinecraftForge(result)) reporter.hit(CLAIM);
		return result;
	}

	/** Whether {@code createDefault} hands its BlockColors to MinecraftForge: through the funnel, or itself. */
	static boolean reachesMinecraftForge(byte[] bytes) {
		ClassNode node = new ClassNode();
		try { new ClassReader(bytes).accept(node, 0); } catch (RuntimeException unreadable) { Caught.warn(unreadable, "[Forbric/BlockTint] could not read BlockColors; taken as not handing its colors to MinecraftForge"); return false; }
		for (var method : node.methods) {
			if (!method.name.equals("createDefault")) continue;
			if (registersNatively(node.name, method)) return true;
			for (var instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode call && call.owner.equals(HOOK) && call.name.equals("postBlockTintSources")) return true;
			}
		}
		return false;
	}

	private static boolean isForgeRegistration(MethodInsnNode call) {
		return call.owner.equals(ForeignType.CLIENT_HOOKS.internal(Ecosystem.FORGE)) && call.name.equals("onBlockColorsInit");
	}

	/**
	 * Whether {@code method} hands MinecraftForge the BlockColors NeoForge's event carries, on the path of NeoForge's post:
	 * a MinecraftForge registration paired ({@link NativeCallPairing}) with NeoForge's post of a BlockTintEvent built
	 * from those colors.
	 */
	static boolean registersNatively(String owner, MethodNode method) {
		List<MethodInsnNode> forge = new ArrayList<>();
		for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && isForgeRegistration(call)) forge.add(call);
		if (forge.isEmpty()) return false;
		for (var instruction : method.instructions) {
			if (!(instruction instanceof MethodInsnNode post) || post.getOpcode() != Opcodes.INVOKESTATIC || !post.desc.equals(POST)
					|| !post.name.equals("postEvent") && !post.name.equals("postBlockTintSources")) continue;
			MethodInsnNode constructor = eventConstructor(post);
			if (constructor == null) continue;
			for (MethodInsnNode call : forge) if (NativeCallPairing.pairs(owner, method, call, constructor, 0, post)) return true;
		}
		return false;
	}

	/** The BlockTintEvent constructor right before a post, or null. */
	private static MethodInsnNode eventConstructor(MethodInsnNode post) {
		AbstractInsnNode previous = post.getPrevious();
		while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
		return previous instanceof MethodInsnNode constructor && constructor.getOpcode() == Opcodes.INVOKESPECIAL && constructor.name.equals("<init>")
				&& constructor.owner.equals(ForeignType.BLOCK_TINT_EVENT.internal(Ecosystem.NEOFORGE))
				&& constructor.desc.equals("(Lnet/minecraft/client/color/block/BlockColors;)V") ? constructor : null;
	}

	@Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
		if (!TARGET.equals(name) || !enabled()) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		for (var method : node.methods) {
			if (!method.name.equals("createDefault") || !registersNatively(node.name, method)) continue;
			// The body delivers what the bridge stands for; record it so the client-init audit does not name it missing.
			EventBridges.installed(GameEventBridge.BLOCK_TINT_SOURCES);
			return bytes;
		}
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
