/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.Caught;

/**
 * Vanilla method bodies a carrier moved, whole or in pieces, into a method it added under another name with the same
 * descriptor, and which calls and field accesses moved with them — the shipped table {@code carrier-renames.txt}, which
 * CarrierRenameCensusTest re-derives from the staged jars and pins. MixinRetarget's R3 moves an injector's selector
 * only along rows, and only when every anchor it names is one of the calls that moved.
 *
 * <p>A pair {@code method -> renamed} is in the table when: in each listed ecosystem's own jar the class declares
 * {@code method} and not {@code renamed}; the merged base declares both, with the same descriptor and static-ness;
 * {@code renamed} is made of that jar's {@code method} — of the calls and field accesses of whichever of the two makes
 * fewer, at least half and at least two are the other's too — and makes some of them that the merged {@code method}
 * no longer makes; and the merged class still calls {@code renamed}, from {@code method} itself (a call, or the method
 * handle of a lambda it creates) or from another of its methods — or, marked {@link #UNCALLED}, {@code renamed} is
 * private and nothing in its class or its nest calls it. NeoForge's
 * {@code PersistentEntitySectionManager.addEntity} posts its event and calls vanilla's body as
 * {@code addEntityWithoutEvent}; its {@code ServerConfigurationPacketListenerImpl.startConfiguration} first asks the
 * client what it speaks, and {@code handlePong} runs vanilla's body as {@code runConfiguration}. What would make a move
 * a guess is left out: a renamed method made of two of the reference's methods, two of one method's that are copies,
 * and, of several renamed methods of one method, any it does not call itself (the pieces of a split, such as
 * NeoForge's four HUD layers out of {@code Hud.extractPlayerHealth}, are what it dispatches to).
 *
 * <p>Each pair has one row per call or field access that moved: one the reference's {@code method} makes, the merged
 * {@code method} makes less often, and {@code renamed} makes. An anchor that is not among them was not in vanilla's
 * body where the mod looked — on the reference the mod misses too — and a row is no reason to move it. A row also says
 * how often the reference's {@code method} makes its call: where {@code renamed} makes it more often, the carrier added
 * one, and an anchor that binds every match would bind that one too. And each pair is a {@link Kind#RENAME} or a
 * {@link Kind#PIECE}: whether {@code renamed} makes every call and field access of the reference's {@code method}, as
 * often, or only part of them; a piece whose result the merged method returns whenever it is an {@code Either} left is
 * marked {@link Exit#LEFT} ({@link #returnsLefts}).
 *
 * <p>The table exists because the bytes alone were wrong in seven of the 15 moves an audit of R3 over 655 mod jars
 * found. text_styles' {@code Style.withColor(I)} went to {@code withShadowColor(I)}, ViaFabricPlus'
 * {@code checkHotbarKeyPressed} to {@code keyPressed} and its {@code updatingUsingItem} to {@code releaseUsingItem},
 * goldenpotions' {@code CreativeModeTabs.lambda$bootstrap$21} to {@code lambda$bootstrap$24}: each a method of the same
 * shape that happened to carry the anchor and that vanilla itself declares, so no carrier renamed anything. malilib's
 * and trinkets' {@code ItemStack.addDetailsToTooltip} went to {@code addDetailsToTooltipComponents}: NeoForge's renamed
 * body, but nothing in the merged game calls it — NeoForge draws tooltips from its own appenders — so the injection
 * bound, never ran, and read as fitting. That pair is now a row marked {@link #UNCALLED}: the move binds the injector
 * where the body is and says it never runs, as MixinFit and the final-class check report it ({@link #neverRuns}), so
 * malilib's required tooltip hook is a reported loss that marks its row and does not stop a strict launch.
 *
 * <p>The rows are re-checked against the live bytes ({@link #called}, {@link #carries}): a transform that drops the
 * last call to a renamed body leaves nothing to move to, and one that adds a call to an UNCALLED one makes it no longer
 * dead.
 */
public final class CarrierRenames {
	/** The shipped table; CarrierRenameCensusTest pins it to the staged artifacts. */
	static final String TABLE = "/net/forbric/kernel/mixin/carrier-renames.txt";

	/** How much of the reference's body the renamed method carries. */
	enum Kind {
		/**
		 * All of it: every call and field access the reference's method makes, as often. The merged method may do more
		 * around the call, but the body a handler was written against is whole in the renamed one.
		 */
		RENAME,
		/**
		 * Part of it: the rest stayed in the merged method or went elsewhere. NeoForge's {@code addDetailsToTooltipTail}
		 * is only the advanced tail of vanilla's tooltip; {@code lambda$startSleepInBed$0} is the checks before the
		 * sleep, while {@code super.startSleepInBed} and {@code canSleepThroughNights} stayed in the method.
		 */
		PIECE
	}

	/** What the merged method does with a piece's result, when the piece cancels where vanilla returned from the method. */
	enum Exit {
		/** Nothing known: a cancel returns from the piece and the method goes on. */
		NONE,
		/**
		 * The method returns the piece's result whenever it is an {@code Either} left, after any static hook that takes it
		 * ({@link #returnsLefts}): NeoForge's {@code startSleepInBed} hands its lambda's answer to
		 * {@code EventHooks.canPlayerStartSleeping} and returns it when it names a problem. A left a handler cancels the
		 * piece with is then what one of vanilla's own checks in it returns.
		 */
		LEFT
	}

	/**
	 * @param owner      the class (internal name)
	 * @param method     the method a mod written against the reference names, {@code name + descriptor}
	 * @param renamed    the carrier-added method that carries its body now, {@code name + descriptor}
	 * @param member     one call or field access that moved with it, as an {@code @At} target
	 * @param count      how many times the reference's {@code method} makes it
	 * @param kind       the whole body or a piece of it
	 * @param ecosystems whose own jar declares {@code method} with that body and not {@code renamed}
	 * @param exit       for a piece, whether the merged method returns its lefts
	 * @param called     whether the merged class calls {@code renamed}; false for a body the carrier renamed and nothing
	 *                   in the merged game calls ({@link #UNCALLED})
	 */
	record Row(String owner, String method, String renamed, String member, int count, Kind kind, Set<Ecosystem> ecosystems,
			Exit exit, boolean called) {
		/**
		 * {@code <class>#<method> -> <renamed> : <member> x<count> | <kind> | <ecosystems>[ | LEFT | UNCALLED]}, as the
		 * census writes it; null when not that.
		 */
		static Row parse(String line) {
			String[] columns = line.split(" \\| ");
			if (columns.length != 3 && columns.length != 4) return null;
			int hash = columns[0].indexOf('#'), arrow = columns[0].indexOf(" -> "), colon = columns[0].indexOf(" : ");
			int times = columns[0].lastIndexOf(" x");
			if (hash <= 0 || arrow < hash || colon < arrow || times < colon || columns[0].indexOf('(', arrow) < 0) return null;
			try {
				int count = Integer.parseInt(columns[0].substring(times + 2).trim());
				Kind kind = Kind.valueOf(columns[1].trim());
				Set<Ecosystem> ecosystems = EnumSet.noneOf(Ecosystem.class);
				for (String e : columns[2].trim().split(",")) ecosystems.add(Ecosystem.valueOf(e.trim()));
				String marker = columns.length == 4 ? columns[3].trim() : null;
				boolean called = !UNCALLED.equals(marker);
				Exit exit = marker == null || !called ? Exit.NONE : Exit.valueOf(marker);
				if (count <= 0 || exit == Exit.NONE && called && marker != null) return null;
				return new Row(columns[0].substring(0, hash), columns[0].substring(hash + 1, arrow),
						columns[0].substring(arrow + 4, colon).trim(), columns[0].substring(colon + 3, times).trim(), count, kind,
						Set.copyOf(ecosystems), exit, called);
			} catch (IllegalArgumentException unknown) {
				Caught.warn(unknown, "[Forbric/Mixin] unreadable row in %s, skipped: %s", TABLE, line);
				return null;
			}
		}
	}

	/**
	 * The marker of a pair whose renamed method nothing in the merged class calls: NeoForge's
	 * {@code ItemStack.addDetailsToTooltipComponents}, vanilla's tooltip body under another name, which NeoForge keeps
	 * and never calls (it draws tooltips from its own appenders). An injector moves there only to bind where the body it
	 * was written against is, and is then reported as never running ({@link #neverRuns}).
	 */
	static final String UNCALLED = "UNCALLED";

	private static volatile List<Row> rows;

	private CarrierRenames() {
	}

	static List<Row> rows() {
		List<Row> loaded = rows;
		if (loaded != null) return loaded;
		List<Row> read = new ArrayList<>();
		try (InputStream in = CarrierRenames.class.getResourceAsStream(TABLE)) {
			if (in != null) {
				for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
					if (line.isBlank() || line.startsWith("#")) continue;
					Row row = Row.parse(line.trim());
					if (row != null) read.add(row);
				}
			}
		} catch (IOException unreadable) {
			Caught.warn(unreadable, "[Forbric/Mixin] could not read %s; no injector follows a body a carrier renamed", TABLE);
		}
		rows = List.copyOf(read);
		return rows;
	}

	/** The rows of {@code owner#method -> renamed} whose ecosystems include {@code ecosystem}; empty when none, or unknown. */
	static List<Row> find(String owner, String method, String renamed, Ecosystem ecosystem) {
		if (ecosystem == null) return List.of();
		List<Row> found = new ArrayList<>();
		for (Row row : rows()) {
			if (row.owner().equals(owner) && row.method().equals(method) && row.renamed().equals(renamed)
					&& row.ecosystems().contains(ecosystem)) found.add(row);
		}
		return found;
	}

	/** {@link Kind#PIECE} when any of a pair's rows says so (they agree, as the census writes them), else RENAME. */
	static Kind kind(List<Row> rows) {
		for (Row row : rows) if (row.kind() == Kind.PIECE) return Kind.PIECE;
		return Kind.RENAME;
	}

	/** {@link Exit#LEFT} when every one of a pair's rows says so (the census writes it on all of them), else NONE. */
	static Exit exit(List<Row> rows) {
		if (rows.isEmpty()) return Exit.NONE;
		for (Row row : rows) if (row.exit() != Exit.LEFT) return Exit.NONE;
		return Exit.LEFT;
	}

	/** Whether a pair's rows say the merged class calls its renamed method (the census marks every row of an uncalled one). */
	static boolean called(List<Row> rows) {
		for (Row row : rows) if (!row.called()) return false;
		return true;
	}

	/**
	 * Why {@code method}, declared by {@code owner}, never runs for a mod of {@code ecosystem}: it is the renamed side of
	 * an {@link #UNCALLED} pair for that ecosystem, still private, and no other method of the live class calls it either
	 * (a private method only its nest can call; the census found none in it that does). Null when it may run. MixinFit and
	 * the final-class check read an injector attached only there as one that never runs.
	 */
	static String neverRuns(ClassNode owner, MethodNode method, Ecosystem ecosystem) {
		if (ecosystem == null || owner == null || method == null || (method.access & Opcodes.ACC_PRIVATE) == 0) return null;
		for (Row row : rows()) {
			if (row.called() || !row.owner().equals(owner.name) || !row.renamed().equals(method.name + method.desc)
					|| !row.ecosystems().contains(ecosystem)) continue;
			if (called(owner, method)) return null;
			String original = row.method().substring(0, row.method().indexOf('('));
			return "a carrier moved " + original + "'s vanilla body into it and nothing in the merged game calls it";
		}
		return null;
	}

	/** Whether any {@link #UNCALLED} pair names a method of {@code owner} (internal name). */
	static boolean listsUncalled(String owner) {
		for (Row row : rows()) if (!row.called() && row.owner().equals(owner)) return true;
		return false;
	}

	/**
	 * Whether {@code anchor} (an {@code @At} target as the mod wrote it: owner and descriptor may be left out), at
	 * {@code ordinal} (-1 for every match), binds in {@code renamed} only to calls and field accesses that moved there
	 * along {@code rows}, and only as many as the reference's method made: it names at least one instruction of
	 * {@code renamed}, every one it names makes a row's member, and no member more often than its row says. A loosely
	 * written anchor that would also bind to a call the carrier added does not move, and neither does one that would bind
	 * to a second copy of vanilla's call the carrier added to the body. An ordinal counts the matches in the method, so it
	 * names the same call only when the anchor can match one member and {@code renamed} makes it exactly as often as the
	 * reference's method did.
	 */
	static boolean carries(List<Row> rows, MethodNode renamed, String anchor, int ordinal) {
		MixinFit.Member want = MixinFit.parseMember(anchor);
		if (want == null || renamed.instructions == null) return false;
		Map<String, Integer> made = new HashMap<>();
		for (Row row : rows) made.put(row.member(), row.count());
		Map<String, Integer> bound = new HashMap<>();
		for (AbstractInsnNode insn : renamed.instructions) {
			String member = CarrierHelpers.member(insn);
			if (member == null) continue;
			MixinFit.Member have = MixinFit.parseMember(member);
			if (have == null || !CarrierHelpers.matches(want, have)) continue;
			if (!made.containsKey(member)) return false;
			bound.merge(member, 1, Integer::sum);
		}
		if (bound.isEmpty()) return false;
		for (Map.Entry<String, Integer> member : bound.entrySet()) {
			int reference = made.get(member.getKey());
			if (member.getValue() > reference) return false;
			if (ordinal >= 0 && (member.getValue() != reference || want.owner() == null || want.desc() == null)) return false;
		}
		return true;
	}

	/**
	 * Whether a method of {@code owner} other than {@code renamed} calls it: an invoke of it on {@code owner}, or its
	 * method handle among an {@code invokedynamic}'s arguments (a lambda whose body it is).
	 */
	static boolean called(ClassNode owner, MethodNode renamed) {
		if (owner.methods == null) return false;
		for (MethodNode method : owner.methods) if (method != renamed && calls(owner, method, renamed)) return true;
		return false;
	}

	/** Whether {@code method} of {@code owner} calls {@code renamed}, directly or as the method handle of a lambda. */
	static boolean calls(ClassNode owner, MethodNode method, MethodNode renamed) {
		if (method.instructions == null) return false;
		for (AbstractInsnNode insn : method.instructions) if (reaches(owner, insn, renamed)) return true;
		return false;
	}

	/**
	 * Whether {@code method} hands {@code piece} its own arguments, unchanged: somewhere it calls {@code piece} (or
	 * creates the lambda whose body it is, capturing every parameter) right after loading {@code this}, when not static,
	 * and each of its parameters in order, {@code piece} has its descriptor and static-ness, and nowhere does
	 * {@code method} store into a parameter's slot (a {@code pos = pos.above()} before the call hands the piece another
	 * position under the same load). An argument the handler captures in the piece is then the one it captured in the
	 * method. NeoForge's {@code addDetailsToTooltip} calls {@code addDetailsToTooltipTail} so; {@code handlePong}, not
	 * {@code startConfiguration}, calls {@code runConfiguration}.
	 */
	static boolean handsOwnArguments(ClassNode owner, MethodNode method, MethodNode piece) {
		if (method.instructions == null || !method.desc.equals(piece.desc)
				|| ((method.access ^ piece.access) & Opcodes.ACC_STATIC) != 0) return false;
		boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
		Type[] params = Type.getArgumentTypes(method.desc);
		List<VarInsnNode> expected = new ArrayList<>();
		if (!isStatic) expected.add(new VarInsnNode(Opcodes.ALOAD, 0));
		int slot = isStatic ? 0 : 1;
		for (Type param : params) {
			expected.add(new VarInsnNode(param.getOpcode(Opcodes.ILOAD), slot));
			slot += param.getSize();
		}
		for (AbstractInsnNode insn : method.instructions) {
			boolean store = insn instanceof VarInsnNode var && var.getOpcode() >= Opcodes.ISTORE && var.getOpcode() <= Opcodes.ASTORE
					&& var.var < slot;
			if (store || insn instanceof IincInsnNode iinc && iinc.var < slot) return false;
		}
		for (AbstractInsnNode insn : method.instructions) {
			if (!reaches(owner, insn, piece)) continue;
			if (insn instanceof InvokeDynamicInsnNode indy && Type.getArgumentTypes(indy.desc).length != expected.size()) continue;
			AbstractInsnNode at = insn;
			boolean inPlace = true;
			for (int i = expected.size() - 1; i >= 0 && inPlace; i--) {
				at = previousReal(at);
				inPlace = at instanceof VarInsnNode load && load.getOpcode() == expected.get(i).getOpcode()
						&& load.var == expected.get(i).var;
			}
			if (inPlace) return true;
		}
		return false;
	}

	private static final String EITHER = "com/mojang/datafixers/util/Either";
	private static final String EITHER_DESC = "L" + EITHER + ";";

	/**
	 * Whether {@code method} returns {@code piece}'s result whenever it is an {@code Either} left: it takes the result —
	 * of a call to {@code piece}, or of the no-argument call of the lambda whose body {@code piece} is, made right where
	 * the lambda is created — into a local, maybe through static hooks that take it as their last argument and give an
	 * {@code Either} back into the same local, and then, straight on, returns that local if its {@code left()} is present.
	 * NeoForge's {@code startSleepInBed}:
	 * <pre>
	 *   aload this; aload pos; invokedynamic get -> lambda$startSleepInBed$0; invokeinterface Supplier.get
	 *   checkcast Either; astore r
	 *   aload this; aload pos; aload r; invokestatic EventHooks.canPlayerStartSleeping; astore r
	 *   aload r; invokevirtual Either.left; invokevirtual Optional.isPresent; ifeq L; aload r; areturn
	 * </pre>
	 * The piece's own early returns are vanilla's checks saying no, each an {@code Either.left}; they reach the carrier's
	 * hook and leave the method. A handler that cancels the piece with a left leaves it the same way.
	 */
	static boolean returnsLefts(ClassNode owner, MethodNode method, MethodNode piece) {
		if (method.instructions == null || !Type.getReturnType(method.desc).getDescriptor().equals(EITHER_DESC)
				|| !Type.getReturnType(piece.desc).getDescriptor().equals(EITHER_DESC)) return false;
		List<AbstractInsnNode> real = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) real.add(insn);
		for (int i = 0; i < real.size(); i++) {
			int at = pastResult(owner, real, i, piece);
			if (at < 0) continue;
			if (at < real.size() && real.get(at) instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
					&& cast.desc.equals(EITHER)) at++;
			if (at >= real.size() || !(real.get(at) instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) continue;
			int result = store.var;
			at++;
			while (true) {
				if (leftReturned(real, at, result)) return true;
				int call = at;
				while (call < real.size() && real.get(call) instanceof VarInsnNode load && load.getOpcode() >= Opcodes.ILOAD
						&& load.getOpcode() <= Opcodes.ALOAD) call++;
				if (call == at || call + 1 >= real.size() || !(real.get(call) instanceof MethodInsnNode hook)
						|| hook.getOpcode() != Opcodes.INVOKESTATIC || !load(real.get(call - 1), Opcodes.ALOAD, result)
						|| !load(real.get(call + 1), Opcodes.ASTORE, result)) break;
				Type[] args = Type.getArgumentTypes(hook.desc);
				if (args.length != call - at || !args[args.length - 1].getDescriptor().equals(EITHER_DESC)
						|| !Type.getReturnType(hook.desc).getDescriptor().equals(EITHER_DESC)) break;
				at = call + 2;
			}
		}
		return false;
	}

	/** Past the instructions at {@code i} that leave {@code piece}'s result on the stack, or -1. */
	private static int pastResult(ClassNode owner, List<AbstractInsnNode> real, int i, MethodNode piece) {
		AbstractInsnNode insn = real.get(i);
		if (insn instanceof MethodInsnNode && reaches(owner, insn, piece)) return i + 1;
		if (!(insn instanceof InvokeDynamicInsnNode indy) || !reaches(owner, indy, piece) || i + 1 >= real.size()) return -1;
		Type functional = Type.getReturnType(indy.desc);
		return real.get(i + 1) instanceof MethodInsnNode get && get.getOpcode() == Opcodes.INVOKEINTERFACE
				&& functional.getSort() == Type.OBJECT && get.owner.equals(functional.getInternalName()) && get.name.equals(indy.name)
				&& get.desc.equals("()Ljava/lang/Object;") ? i + 2 : -1;
	}

	/** {@code aload r; Either.left(); Optional.isPresent(); ifeq; aload r; areturn} at {@code at}. */
	private static boolean leftReturned(List<AbstractInsnNode> real, int at, int result) {
		if (at + 5 >= real.size() || !load(real.get(at), Opcodes.ALOAD, result)) return false;
		return real.get(at + 1) instanceof MethodInsnNode left && left.getOpcode() == Opcodes.INVOKEVIRTUAL && left.owner.equals(EITHER)
				&& left.name.equals("left") && left.desc.equals("()Ljava/util/Optional;")
				&& real.get(at + 2) instanceof MethodInsnNode present && present.getOpcode() == Opcodes.INVOKEVIRTUAL
				&& present.owner.equals("java/util/Optional") && present.name.equals("isPresent") && present.desc.equals("()Z")
				&& real.get(at + 3) instanceof JumpInsnNode skip && skip.getOpcode() == Opcodes.IFEQ
				&& load(real.get(at + 4), Opcodes.ALOAD, result) && real.get(at + 5).getOpcode() == Opcodes.ARETURN;
	}

	private static boolean load(AbstractInsnNode insn, int opcode, int var) {
		return insn instanceof VarInsnNode v && v.getOpcode() == opcode && v.var == var;
	}

	private static boolean reaches(ClassNode owner, AbstractInsnNode insn, MethodNode renamed) {
		if (insn instanceof MethodInsnNode call) {
			return call.owner.equals(owner.name) && call.name.equals(renamed.name) && call.desc.equals(renamed.desc);
		}
		if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
			for (Object arg : indy.bsmArgs) {
				if (arg instanceof Handle handle && handle.getOwner().equals(owner.name)
						&& handle.getName().equals(renamed.name) && handle.getDesc().equals(renamed.desc)) return true;
			}
		}
		return false;
	}

	private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
		AbstractInsnNode at = insn.getPrevious();
		while (at != null && at.getOpcode() < 0) at = at.getPrevious();
		return at;
	}
}
