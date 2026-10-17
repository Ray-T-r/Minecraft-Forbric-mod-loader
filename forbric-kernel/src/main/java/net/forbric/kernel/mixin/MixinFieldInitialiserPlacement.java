/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ByteScan;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Caught;

/**
 * Puts a mixin's field initialiser back right after the constructor's {@code super()} call, where the mixin's own loader
 * puts it, when the merged constructor made Mixin put it somewhere else.
 *
 * <p>Mixin (default initialiser mode) injects a mixin's field initialisers after the LAST {@code invokespecial <init>} in a
 * {@code super()}-calling constructor whose owner is the target class or its superclass. On a native class that is the
 * {@code super()} call itself. A merged constructor that builds other instances of its own class after {@code super()}
 * — NeoForge's {@code Pack} builds its children with {@code new Pack(...)} in a loop — moves the initialiser after the last
 * of those constructions: inside the loop, so an instance with no children never runs it. fabric-api's pack mixin added a
 * predicate defaulting to "not hidden" that way, and on the merged base every top-level pack (the vanilla data pack among
 * them) read as hidden and was never selected.
 *
 * <p>Recognised by what the woven class shows, never by whose field it is: a field Mixin merged
 * ({@code @MixinMerged}), its initialiser — a self-contained {@code this.field = <expression of this and constants>} —
 * standing right after a construction of the class (or its superclass) on another object, in a constructor whose chain call
 * is {@code super()}; and the class as the mixin's own ecosystem compiled it builds no such instance after {@code super()}
 * in any constructor, so its loader puts the initialiser after {@code super()}. The initialiser moves there, unchanged.
 * {@code -Dforbric.mixinFieldInitialisers=off} leaves every class as Mixin wove it.
 */
public final class MixinFieldInitialiserPlacement {
	public static final String PROPERTY = "forbric.mixinFieldInitialisers";
	private static final String MIXIN_MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
	private static final byte[] MERGED_NEEDLE = ByteScan.needle(MIXIN_MERGED);

	private MixinFieldInitialiserPlacement() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** {@code bytes} (post-Mixin) with each misplaced initialiser moved after {@code super()}; the same array when none is. */
	static byte[] repair(byte[] bytes, BiFunction<Ecosystem, String, ClassNode> natives, Function<String, Ecosystem> ecosystemOf) {
		if (bytes == null || !enabled() || natives == null || !ByteScan.contains(bytes, MERGED_NEEDLE)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		Map<String, String> merged = mergedInstanceFields(node);
		if (merged.isEmpty() || node.superName == null) return bytes;
		List<String> moved = new ArrayList<>();
		for (MethodNode ctor : node.methods) {
			if (!ctor.name.equals("<init>")) continue;
			Shape shape = shape(node, ctor);
			if (shape == null || shape.chain == null || !shape.chain.owner.equals(node.superName) || shape.nested.isEmpty()) continue;
			for (MethodInsnNode construction : shape.nested) {
				for (List<AbstractInsnNode> initialiser = initialiserAfter(node, ctor, construction, merged); initialiser != null;
						initialiser = initialiserAfter(node, ctor, construction, merged)) {
					FieldInsnNode put = (FieldInsnNode) initialiser.getLast();
					String mixin = merged.get(put.name + put.desc);
					if (!nativeInitialisesAfterSuper(node.name, natives, ecosystemOf.apply(mixin))) break;
					AbstractInsnNode anchor = shape.chain;
					for (AbstractInsnNode insn : initialiser) {
						ctor.instructions.remove(insn);
						ctor.instructions.insert(anchor, insn);
						anchor = insn;
					}
					moved.add(put.name + " (" + mixin + ")");
				}
			}
		}
		if (moved.isEmpty()) return bytes;
		ForbricLog.info("[Forbric/Mixin] post-mixin repair: %s's merged constructor builds instances of itself after super(), so "
				+ "Mixin put the initialiser of %s after the last of those constructions; it runs right after super() again, "
				+ "as on the mixin's own loader", node.name.replace('/', '.'), String.join(", ", moved));
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** {@code name + desc} of every instance field Mixin merged into {@code node}, with the mixin (internal name) it came from. */
	static Map<String, String> mergedInstanceFields(ClassNode node) {
		Map<String, String> out = new LinkedHashMap<>();
		if (node.fields == null) return out;
		for (FieldNode field : node.fields) {
			if ((field.access & Opcodes.ACC_STATIC) != 0 || field.visibleAnnotations == null) continue;
			for (AnnotationNode annotation : field.visibleAnnotations) {
				if (!MIXIN_MERGED.equals(annotation.desc)) continue;
				Object mixin = MixinFit.value(annotation, "mixin");
				if (mixin instanceof String name) out.put(field.name + field.desc, name.replace('.', '/'));
			}
		}
		return out;
	}

	/** The constructor's chain call and the constructions of the class or its superclass on other objects after it. */
	record Shape(MethodInsnNode chain, List<MethodInsnNode> nested) {
	}

	static Shape shape(ClassNode node, MethodNode ctor) {
		Frame<SourceValue>[] frames;
		try {
			frames = new Analyzer<>(new SourceInterpreter()).analyze(node.name, ctor);
		} catch (AnalyzerException | RuntimeException unreadable) {
			Caught.expected(unreadable);
			return null;
		}
		MethodInsnNode chain = null;
		List<MethodInsnNode> nested = new ArrayList<>();
		for (int i = 0; i < ctor.instructions.size(); i++) {
			if (!(ctor.instructions.get(i) instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
					|| !call.name.equals("<init>") || frames[i] == null) continue;
			if (!call.owner.equals(node.name) && !call.owner.equals(node.superName)) continue;
			Frame<SourceValue> frame = frames[i];
			int receiver = frame.getStackSize() - 1 - org.objectweb.asm.Type.getArgumentTypes(call.desc).length;
			if (receiver < 0) return null;
			boolean self = true;
			for (AbstractInsnNode source : frame.getStack(receiver).insns) {
				self &= source instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0;
			}
			if (self) {
				if (chain != null) return null;    // two chain calls: not a constructor this reads
				chain = call;
			} else if (chain != null) {
				nested.add(call);
			}
		}
		return new Shape(chain, nested);
	}

	/**
	 * The initialiser standing right after {@code construction}: {@code aload 0}, an expression of constants, statics and
	 * {@code this} that touches no other local and does not branch, and a {@code putfield} of a merged field of the class —
	 * or null.
	 */
	private static List<AbstractInsnNode> initialiserAfter(ClassNode node, MethodNode ctor, MethodInsnNode construction,
			Map<String, String> merged) {
		List<AbstractInsnNode> code = new ArrayList<>();
		AbstractInsnNode insn = construction.getNext();
		while (insn != null && (insn instanceof LabelNode || insn instanceof LineNumberNode || insn instanceof FrameNode)) insn = insn.getNext();
		if (!(insn instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0) return null;
		for (; insn != null; insn = insn.getNext()) {
			// Debug nodes stay where they are: nothing jumps into the middle of a copied initialiser.
			if (insn instanceof LabelNode || insn instanceof LineNumberNode || insn instanceof FrameNode) continue;
			if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode
					|| insn instanceof IincInsnNode) return null;
			if (insn instanceof VarInsnNode local && (local.var != 0 || local.getOpcode() != Opcodes.ALOAD) && !code.isEmpty()) return null;
			int op = insn.getOpcode();
			if (op == Opcodes.ATHROW || op >= Opcodes.IRETURN && op <= Opcodes.RETURN || op == Opcodes.MONITORENTER
					|| op == Opcodes.MONITOREXIT) return null;
			code.add(insn);
			if (insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD) {
				if (!put.owner.equals(node.name) || !merged.containsKey(put.name + put.desc)) return null;
				return balanced(code) ? code : null;
			}
			if (code.size() > 64) return null;
		}
		return null;
	}

	/** Whether {@code code} leaves the stack as it found it: what it pushes, the {@code putfield} at its end pops. */
	private static boolean balanced(List<AbstractInsnNode> code) {
		MethodNode probe = new MethodNode(Opcodes.ACC_PUBLIC, "probe", "()V", null, null);
		Map<LabelNode, LabelNode> none = new IdentityHashMap<>();
		InsnList list = new InsnList();
		for (AbstractInsnNode insn : code) list.add(insn.clone(none));
		list.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
		probe.instructions = list;
		probe.maxLocals = 1;
		probe.maxStack = 64;
		try {
			Frame<SourceValue>[] frames = new Analyzer<>(new SourceInterpreter()).analyze("java/lang/Object", probe);
			Frame<SourceValue> last = frames[frames.length - 1];
			return last != null && last.getStackSize() == 0;
		} catch (AnalyzerException | RuntimeException unreadable) {
			Caught.expected(unreadable);
			return false;
		}
	}

	/** Whether the class as {@code ecosystem} compiled it builds no instance of itself or its superclass after {@code super()}. */
	static boolean nativeInitialisesAfterSuper(String owner, BiFunction<Ecosystem, String, ClassNode> natives, Ecosystem ecosystem) {
		if (ecosystem == null) return false;
		ClassNode original = natives.apply(ecosystem, owner);
		if (original == null) return false;
		boolean any = false;
		for (MethodNode ctor : original.methods) {
			if (!ctor.name.equals("<init>")) continue;
			Shape shape = shape(original, ctor);
			if (shape == null) return false;
			if (shape.chain == null || !shape.chain.owner.equals(original.superName)) continue;
			if (!shape.nested.isEmpty()) return false;
			any = true;
		}
		return any;
	}
}
