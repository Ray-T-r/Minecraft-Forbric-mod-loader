/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Every key name the game hands {@code InputConstants.getKey} has its {@code ":MODIFIER"} suffix off already, on NeoForge's
 * base and carrier. This is the standing check of a repair deleted in Stage 4 C5 (D73, {@code dropTheKeyModifierSuffix}).
 *
 * <p>A modified key binding is saved as {@code key_key.jei.quickMove:key.mouse.left:SHIFT}, by NeoForge's {@code Options}
 * and by the merged base of Forbric 0.4 and earlier alike. On the merged base MinecraftForge's
 * {@code Options.processOptionsKeysOnly} handed that whole value to {@code getKey}, vanilla's {@code Integer.parseInt}
 * threw, and {@code Options.load} (one try/catch around the whole file) cost the player every setting; the kernel split the
 * name at {@code getKey}'s entry. NeoForge's own {@code Options.processOptions} asks {@code getKey} twice: with
 * {@code value.split(":")[0]} when the value holds a ':', and with the whole value only on the branch where
 * {@code value.indexOf(':') == -1}. So no suffixed name reaches the parse, and native NeoForge has no such repair. A base
 * change that brings back a call fed a value with no such split or guard is named here (the merged base had one:
 * {@code processOptionsKeysOnly}, measured in C5 wave 2b with the same criterion).
 *
 * <p>Mods call {@code getKey} with names from their own configs (REI, kuma-api, WTHIT in the local corpora); native
 * NeoForge and native Fabric hand those to vanilla's parse unchanged, and so does Forbric now.
 */
class KeyModifierSplitStagedTest {
	static final String INPUT_CONSTANTS = "com/mojang/blaze3d/platform/InputConstants";
	static final String GET_KEY_DESC = "(Ljava/lang/String;)L" + INPUT_CONSTANTS + "$Key;";

	@Test void everyGetKeyCallInTheBaseAndCarrierIsHandedANameWithoutItsModifier() throws Exception {
		List<String> split = new ArrayList<>(), guarded = new ArrayList<>(), whole = new ArrayList<>();
		int classes = 0;
		for (Path jar : StagedGameClasses.jars()) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : Collections.list(zip.entries())) {
					String name = entry.getName();
					if (!name.endsWith(".class") || name.startsWith("META-INF/")) continue;
					byte[] bytes;
					try (InputStream in = zip.getInputStream(entry)) { bytes = in.readAllBytes(); }
					ClassNode node = new ClassNode();
					new ClassReader(bytes).accept(node, 0);
					classes++;
					for (MethodNode method : node.methods) {
						for (String[] call : getKeyCalls(node.name, method)) {
							(switch (call[1]) { case SPLIT -> split; case GUARDED -> guarded; default -> whole; }).add(call[0]);
						}
					}
				}
			}
		}
		assertTrue(classes > 10_000, "premise: the base and carrier were read (" + classes + " classes)");
		assertTrue(!split.isEmpty() && !guarded.isEmpty(),
				"premise: the game reads a saved binding both ways, split and guarded (split " + split + ", guarded " + guarded + ")");
		assertEquals(List.of(), whole, "getKey handed a whole saved value that may still carry \":MODIFIER\"");
	}

	/** The criterion has teeth: MinecraftForge's whole-value call is named; NeoForge's split and guarded calls are not. */
	@Test void theCriterionNamesOnlyAWholeValueWithNoGuard() {
		assertEquals(List.of(SPLIT), kinds(neoForgeSplit()));
		assertEquals(List.of(GUARDED), kinds(neoForgeGuarded()));
		assertEquals(List.of(WHOLE), kinds(minecraftForgeWhole()));
		// A guard whose branch another jump also reaches guards nothing.
		assertEquals(List.of(WHOLE), kinds(guardedButJoined()));
	}

	// --- the criterion -----------------------------------------------------------------------------------------------

	static final String SPLIT = "split";
	static final String GUARDED = "guarded";
	static final String WHOLE = "whole";

	/** {location, kind} for each {@code getKey(String)} call in {@code method}. */
	static List<String[]> getKeyCalls(String owner, MethodNode method) {
		List<String[]> out = new ArrayList<>();
		if (method.instructions == null) return out;
		int index = 0;
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext(), index++) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC && INPUT_CONSTANTS.equals(call.owner)
					&& "getKey".equals(call.name) && GET_KEY_DESC.equals(call.desc)) {
				out.add(new String[] {owner + "." + method.name + method.desc + " @" + index, kind(method, call)});
			}
		}
		return out;
	}

	private static List<String> kinds(MethodNode method) {
		List<String> out = new ArrayList<>();
		for (String[] call : getKeyCalls("fixture/Options", method)) out.add(call[1]);
		return out;
	}

	/**
	 * SPLIT: the argument is {@code parts[0]} of an array stored from {@code value.split(":")}. GUARDED: the argument is a
	 * local read on a straight path from the target of {@code if (v.indexOf(':') == -1)} on that same local, a label no
	 * other jump, switch or handler reaches. WHOLE: anything else.
	 */
	static String kind(MethodNode method, MethodInsnNode call) {
		AbstractInsnNode a = real(call.getPrevious());
		if (a != null && a.getOpcode() == Opcodes.AALOAD) {
			AbstractInsnNode zero = real(a.getPrevious());
			AbstractInsnNode array = zero == null ? null : real(zero.getPrevious());
			if (zero != null && zero.getOpcode() == Opcodes.ICONST_0 && array instanceof VarInsnNode load
					&& load.getOpcode() == Opcodes.ALOAD && storesAColonSplit(method, load.var)) return SPLIT;
			return WHOLE;
		}
		if (a instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && reachedOnlyFromAColonFreeTest(method, call, load.var)) {
			return GUARDED;
		}
		return WHOLE;
	}

	private static boolean storesAColonSplit(MethodNode method, int var) {
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE && store.var == var
					&& real(store.getPrevious()) instanceof MethodInsnNode split && isSplit(split)
					&& real(split.getPrevious()) instanceof LdcInsnNode ldc && ":".equals(ldc.cst)) return true;
		}
		return false;
	}

	private static boolean reachedOnlyFromAColonFreeTest(MethodNode method, AbstractInsnNode call, int var) {
		Set<LabelNode> targets = jumpTargets(method);
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IF_ICMPEQ) continue;
			AbstractInsnNode m1 = real(jump.getPrevious());
			AbstractInsnNode indexOf = m1 == null ? null : real(m1.getPrevious());
			AbstractInsnNode colon = indexOf == null ? null : real(indexOf.getPrevious());
			AbstractInsnNode value = colon == null ? null : real(colon.getPrevious());
			if (m1 == null || m1.getOpcode() != Opcodes.ICONST_M1 || !(indexOf instanceof MethodInsnNode find)
					|| !"java/lang/String".equals(find.owner) || !"indexOf".equals(find.name) || !"(I)I".equals(find.desc)
					|| !(colon instanceof IntInsnNode c) || c.operand != ':'
					|| !(value instanceof VarInsnNode v) || v.getOpcode() != Opcodes.ALOAD || v.var != var) continue;
			if (straightFrom(jump.label, call, targets)) return true;
		}
		return false;
	}

	/** Whether {@code to} follows {@code from} with no other way in and no way out between them. */
	private static boolean straightFrom(LabelNode from, AbstractInsnNode to, Set<LabelNode> targets) {
		for (AbstractInsnNode insn = from.getNext(); insn != null; insn = insn.getNext()) {
			if (insn == to) return true;
			if (insn instanceof LabelNode label && targets.contains(label)) return false;
			if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) return false;
			int op = insn.getOpcode();
			if (op == Opcodes.ATHROW || op >= Opcodes.IRETURN && op <= Opcodes.RETURN) return false;
		}
		return false;
	}

	private static Set<LabelNode> jumpTargets(MethodNode method) {
		Set<LabelNode> targets = new HashSet<>();
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof JumpInsnNode jump) targets.add(jump.label);
			if (insn instanceof TableSwitchInsnNode table) { targets.add(table.dflt); targets.addAll(table.labels); }
			if (insn instanceof LookupSwitchInsnNode lookup) { targets.add(lookup.dflt); targets.addAll(lookup.labels); }
		}
		if (method.tryCatchBlocks != null) for (TryCatchBlockNode block : method.tryCatchBlocks) targets.add(block.handler);
		return targets;
	}

	private static boolean isSplit(MethodInsnNode call) {
		return "java/lang/String".equals(call.owner) && "split".equals(call.name) && "(Ljava/lang/String;)[Ljava/lang/String;".equals(call.desc);
	}

	private static AbstractInsnNode real(AbstractInsnNode insn) {
		while (insn != null && insn.getOpcode() < 0) insn = insn.getPrevious();
		return insn;
	}

	// --- fixtures, in the shapes javap shows (NeoForge 26.2.0.93 Options.processOptions; the merged base's
	// processOptionsKeysOnly) ---------------------------------------------------------------------------------------

	private static MethodNode method() {
		return new MethodNode(Opcodes.ACC_STATIC, "processOptions", "(Ljava/lang/String;)V", null, null);
	}

	private static MethodInsnNode getKey() {
		return new MethodInsnNode(Opcodes.INVOKESTATIC, INPUT_CONSTANTS, "getKey", GET_KEY_DESC, false);
	}

	private static void testColonFree(MethodNode m, LabelNode branch) {
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		m.instructions.add(new IntInsnNode(Opcodes.BIPUSH, ':'));
		m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(I)I", false));
		m.instructions.add(new InsnNode(Opcodes.ICONST_M1));
		m.instructions.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, branch));
	}

	private static MethodNode neoForgeSplit() {
		MethodNode m = method();
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		m.instructions.add(new LdcInsnNode(":"));
		m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "split", "(Ljava/lang/String;)[Ljava/lang/String;", false));
		m.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		m.instructions.add(new InsnNode(Opcodes.ICONST_0));
		m.instructions.add(new InsnNode(Opcodes.AALOAD));
		m.instructions.add(getKey());
		m.instructions.add(new InsnNode(Opcodes.POP));
		m.instructions.add(new InsnNode(Opcodes.RETURN));
		return m;
	}

	private static MethodNode neoForgeGuarded() {
		MethodNode m = method();
		LabelNode noColon = new LabelNode();
		testColonFree(m, noColon);
		m.instructions.add(new InsnNode(Opcodes.RETURN));
		m.instructions.add(noColon);
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		m.instructions.add(getKey());
		m.instructions.add(new InsnNode(Opcodes.POP));
		m.instructions.add(new InsnNode(Opcodes.RETURN));
		return m;
	}

	private static MethodNode minecraftForgeWhole() {
		MethodNode m = method();
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		m.instructions.add(getKey());
		m.instructions.add(new InsnNode(Opcodes.POP));
		m.instructions.add(new InsnNode(Opcodes.RETURN));
		return m;
	}

	private static MethodNode guardedButJoined() {
		MethodNode m = method();
		LabelNode noColon = new LabelNode(), join = new LabelNode();
		testColonFree(m, noColon);
		m.instructions.add(new JumpInsnNode(Opcodes.GOTO, join));
		m.instructions.add(noColon);
		m.instructions.add(new InsnNode(Opcodes.NOP));
		m.instructions.add(join);
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		m.instructions.add(getKey());
		m.instructions.add(new InsnNode(Opcodes.POP));
		m.instructions.add(new InsnNode(Opcodes.RETURN));
		return m;
	}
}
