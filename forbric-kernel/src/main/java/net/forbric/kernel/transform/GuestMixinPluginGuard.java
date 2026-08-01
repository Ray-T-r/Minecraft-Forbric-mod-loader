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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Contains a guest {@code IMixinConfigPlugin} that throws, so one mod's plugin cannot abort mixin configuration
 * for every other mod in the instance.
 *
 * <p>Mixin guards plugin INSTANTIATION — a plugin whose constructor blows up is reported as "may be out of date"
 * and skipped. It does not guard the calls. {@code MixinProcessor.prepareConfigs} invokes {@code acceptTargets}
 * and {@code shouldApplyMixin} bare, so a throw propagates out of {@code select()} and takes down config
 * preparation for the WHOLE game — every config after the throwing one silently never prepares, and the failure
 * lands somewhere with no connection to the mod that caused it.
 *
 * <p>Both mods in this instance that ship a plugin hit it, for unrelated reasons:
 *
 * <ul>
 *   <li>{@code mixinconstraints} (nested in Flashback) probed the wrong platform and died on a Forge ABI —
 *       {@code IncompatibleClassChangeError} inside {@code shouldApplyMixin}. That aborted preparation, so
 *       {@code ModList} was never seeded and the client died in {@code Options.<init>} seconds later with an
 *       unrelated NPE. Sodium lost a mixin accessor in the same sweep.</li>
 *   <li>CustomSkinLoader's plugin resolves {@code SkinManager$3} from {@code acceptTargets}; anonymous inner
 *       classes renumber in the merged base, so it threw {@code Could not resolve target ClassInfo} and killed
 *       the boot outright — {@code Exception in thread "main"}, no crash report, no clue.</li>
 * </ul>
 *
 * <p>Each guarded method keeps its body under a private alias and gets a wrapper that catches {@code Throwable}
 * and returns the value that means "this plugin had no opinion": {@code true} for {@code shouldApplyMixin} (apply
 * the mixin, i.e. behave as if the plugin were absent), {@code null} for the list/string queries, nothing for the
 * void hooks. Failing open is the conservative direction — the alternative is silently dropping mixins the plugin
 * would have allowed.
 *
 * <p>The failure is still reported, once per plugin and method, naming the mod's class. That line is the whole
 * point: it says which plugin misbehaved, at the moment it did.
 */
public final class GuestMixinPluginGuard implements ClassTransformer {

	private static final String PLUGIN_INTERFACE = "org/spongepowered/asm/mixin/extensibility/IMixinConfigPlugin";
	private static final String GUARD = "net/forbric/kernel/transform/GuestMixinPluginGuard";
	private static final String ALIAS_PREFIX = "forbric$unguarded$";

	/** The interface's methods, all of which Mixin calls without a guard of its own. */
	private static final Set<String> GUARDED = Set.of(
			"onLoad", "getRefMapperConfig", "shouldApplyMixin", "acceptTargets", "getMixins", "preApply", "postApply");

	private static final Map<String, Boolean> REPORTED = new ConcurrentHashMap<>();

	private int guarded;

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		if (node.interfaces == null || !node.interfaces.contains(PLUGIN_INTERFACE)) return classBytes;

		int wrapped = 0;
		for (MethodNode method : node.methods.toArray(new MethodNode[0])) {
			if (!GUARDED.contains(method.name) || (method.access & Opcodes.ACC_ABSTRACT) != 0) continue;
			if (method.name.startsWith(ALIAS_PREFIX)) continue;

			node.methods.add(wrap(node, method));
			wrapped++;
		}
		if (wrapped == 0) return classBytes;

		guarded++;
		ForbricLog.info("[Forbric/Mixin] guarded %d method(s) of guest mixin config plugin %s — a plugin that throws "
				+ "would otherwise abort config preparation for every mod", wrapped, className);

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * Renames {@code method}'s body to a private alias and returns a replacement with the original name that calls
	 * it inside a {@code try}/{@code catch (Throwable)}.
	 *
	 * <p>Frames are written by hand rather than computed: the wrapper is a straight call with one handler, so the
	 * handler's frame is exactly "the locals this method started with, one {@code Throwable} on the stack".
	 * {@code COMPUTE_FRAMES} would need to resolve guest types through a loader this transformer does not have.
	 */
	private static MethodNode wrap(ClassNode owner, MethodNode method) {
		String original = method.name;
		method.name = ALIAS_PREFIX + original;
		method.access = (method.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE;

		MethodNode wrapper = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC, original, method.desc, method.signature,
				method.exceptions == null ? null : method.exceptions.toArray(new String[0]));

		Type returnType = Type.getReturnType(method.desc);
		Type[] args = Type.getArgumentTypes(method.desc);
		LabelNode start = new LabelNode(new Label());
		LabelNode end = new LabelNode(new Label());
		LabelNode handler = new LabelNode(new Label());
		InsnList code = wrapper.instructions;

		wrapper.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, "java/lang/Throwable"));

		code.add(start);
		code.add(new VarInsnNode(Opcodes.ALOAD, 0));
		int slot = 1;
		for (Type arg : args) {
			code.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), slot));
			slot += arg.getSize();
		}
		// The alias is private, so invokespecial is both correct and immune to a subclass overriding it.
		code.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, owner.name, method.name, method.desc, false));
		code.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
		code.add(end);

		code.add(handler);
		code.add(new FrameNode(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Throwable"}));
		code.add(new VarInsnNode(Opcodes.ASTORE, slot));
		code.add(new LdcInsnNode(owner.name.replace('/', '.')));
		code.add(new LdcInsnNode(original));
		code.add(new VarInsnNode(Opcodes.ALOAD, slot));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUARD, "report",
				"(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Throwable;)V", false));
		code.add(fallback(returnType));
		code.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));

		return wrapper;
	}

	/** The value that means "this plugin had no opinion", by return type. */
	private static InsnNode fallback(Type returnType) {
		return switch (returnType.getSort()) {
			// shouldApplyMixin: apply it. A plugin that cannot answer must not be read as a veto.
			case Type.BOOLEAN -> new InsnNode(Opcodes.ICONST_1);
			case Type.VOID -> new InsnNode(Opcodes.NOP);
			case Type.OBJECT, Type.ARRAY -> new InsnNode(Opcodes.ACONST_NULL);
			case Type.LONG -> new InsnNode(Opcodes.LCONST_0);
			case Type.FLOAT -> new InsnNode(Opcodes.FCONST_0);
			case Type.DOUBLE -> new InsnNode(Opcodes.DCONST_0);
			default -> new InsnNode(Opcodes.ICONST_0);
		};
	}

	/** Called from the generated handler. Reports each plugin/method pair once, however often it throws. */
	public static void report(String plugin, String method, Throwable t) {
		if (REPORTED.putIfAbsent(plugin + "#" + method, Boolean.TRUE) != null) return;

		ForbricLog.warn("[Forbric/Mixin] mixin config plugin " + plugin + "." + method + " threw — treated as no "
				+ "opinion so the other mods' configs still prepare (unguarded this aborts ALL of them)", t);
	}

	/** How many guest plugins have been guarded, for the boot summary. */
	public int guardedPlugins() {
		return guarded;
	}

	@Override
	public String name() {
		return "guest-mixin-plugin-guard";
	}
}
