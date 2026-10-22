/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.transform.VanillaEarlyReturns;
import net.forbric.kernel.util.ForbricLog;

/**
 * Edges a body a carrier split into pieces the merged game runs one by one: a guest's {@code @Inject} at the start or the
 * end of the method it names lands at the start of the first piece and the end of the last, because one run of the pieces is
 * what one run of the method was.
 *
 * <p>NeoForge turned vanilla's {@code Hud.extractPlayerHealth} into {@code extractHealthLevel; extractArmorLevel;
 * extractFoodLevel; extractAirLevel} ({@link CarrierHelpers} {@code SPLIT}) and draws each piece as a HUD layer of its own;
 * nothing in the merged game calls {@code extractPlayerHealth} any more ({@link MergedBaseUncalledMethods}). Balm's
 * {@code RENDER_GUI_HEALTH_PRE}/{@code POST} are a cancellable {@code HEAD} and a {@code TAIL} of it: bound there they bind
 * and never run, so every listener of either event heard nothing, frame after frame.
 *
 * <p>Moved, they mean what they meant: the start of the first piece is where the native method started, before any of its
 * work; the end of the last is where it ended, after all of it — the last piece's own early return for a null player is the
 * native method's guard, given back by {@link VanillaEarlyReturns} (keyed against the native method), so a {@code TAIL}
 * there runs only when the native {@code TAIL} would. A cancel at the start returned from the native method and skipped all
 * of its work: so the first piece's handler marks the run ({@code SplitBodyRuns}) when its callback is cancelled, and every
 * later piece is entered by a guard of the same mixin that cancels it at once while its run is marked. The pieces are told
 * apart by order: one run is the first piece, then the later ones in the dispatcher's order.
 *
 * <p>Kept exactly: the method the selector binds is, in the merged class, a pure dispatcher to two or more same-shaped pieces
 * ({@link CarrierHelpers#dispatchedHelpers}), and each piece does work the native method (the class the mod was compiled
 * against) did and the dispatcher no longer does — a call or field access the native method makes once, made once in that
 * piece and in no other (the census's own {@code SPLIT} rule, read on the bytes at hand); the merged game never calls the
 * method ({@link MergedBaseUncalledMethods}); every piece is reached from exactly one other method of the class — through a
 * lambda or thunk that only forwards to it, standing for the place that registers it — the same one for all, in the
 * dispatcher's order (the carrier registers them, one after another, where it used to make the one call), and that place
 * runs; every point is a {@code HEAD}, a {@code TAIL} or an unnumbered {@code RETURN}, unshifted; no slice, group or captured
 * locals; the injector is an {@code @Inject} whose handler takes exactly the method's arguments and its callback, with no
 * sugar; and a {@code TAIL}'s native method returns early only where the last piece's tail was split the same way. Anything
 * else stays where it was written, and MixinFit reports it as never running. {@code -Dforbric.splitBodyEdges=off} leaves
 * every injector as written.
 */
public final class MixinSplitBodyEdges {
	public static final String PROPERTY = "forbric.splitBodyEdges";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String CALLBACK_INFO = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
	private static final String RUNS = "net/forbric/kernel/interop/SplitBodyRuns";
	static final String INNER_SUFFIX = "$forbricsplit";
	static final String GUARD_SUFFIX = "$forbricsplit$";

	private MixinSplitBodyEdges() {
	}

	/** Moves every eligible edge injector of {@code mixin} onto the pieces; returns how many it moved. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		return adapt(mixin, targets, VanillaEarlyReturns::splitOf, NativeGameReferences::reference);
	}

	/** Splits recorded for {@code owner#name+desc}, as {@link VanillaEarlyReturns#splitOf}. */
	interface Splits {
		VanillaEarlyReturns.Split of(String owner, String name, String desc);
	}

	static int adapt(ClassNode mixin, Function<String, ClassNode> targets, Splits splits,
			java.util.function.BiFunction<Ecosystem, String, ClassNode> references) {
		if (!enabled() || mixin == null || mixin.methods == null || targets == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		List<String> owners = MixinOverloadPin.targetsOf(mixin);
		if (ecosystem == null || owners.size() != 1) return 0;
		ClassNode target = null;
		int moved = 0;
		for (MethodNode handler : new ArrayList<>(mixin.methods)) {
			if (handler.name.endsWith(INNER_SUFFIX) || handler.name.contains(GUARD_SUFFIX)) continue;
			AnnotationNode injector = MixinFit.injectorOf(handler);
			if (shape(handler, injector) == null) continue;
			if (target == null) {
				target = targets.apply(owners.getFirst());
				if (target == null) return moved;
			}
			Move move = move(mixin, handler, injector, target, ecosystem, splits, references, true);
			if (move == null) continue;
			MethodNode piece = move.piece();
			boolean cancels = move.head() && Boolean.TRUE.equals(MixinFit.value(injector, "cancellable"));
			String written = handler.name;
			if (cancels) {
				String key = mixin.name.replace('/', '.') + "#" + written;
				String inner = aside(mixin, handler, injector, key);
				for (int i = 1; i < move.pieces().size(); i++) mixin.methods.add(guard(mixin, handler, written, injector, move.pieces().get(i), i, key));
				ForbricLog.debug("[Forbric/Mixin] %s: %s moved aside to %s; its cancel skips the later pieces", mixin.name, written, inner);
			}
			MixinPlayerWorldCallbackAdapter.set(injector, "method", new ArrayList<>(List.of(piece.name + piece.desc)));
			moved++;
			ForbricLog.info("[Forbric/Mixin] %s: %s's %s of %s.%s%s moves to the %s of %s%s — a carrier split that body into %s, which the "
					+ "merged game runs one by one and never through the method%s", mixin.name.replace('/', '.'), written,
					move.head() ? "start" : "end", target.name.replace('/', '.'), move.named().name, move.named().desc,
					move.head() ? "start" : "end", piece.name, piece.desc, move.pieces().stream().map(p -> p.name).toList(),
					cancels ? "; a cancel there skips the later pieces of that run" : "");
		}
		return moved;
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY, "on"));
	}

	/** Where the injector of {@code handler} lands, as MixinFit judges it: the piece, or null when it stays. */
	static MethodNode destination(ClassNode mixin, MethodNode handler, ClassNode target,
			java.util.function.BiFunction<Ecosystem, String, ClassNode> references) {
		if (!enabled() || mixin == null || target == null || MixinOverloadPin.targetsOf(mixin).size() != 1) return null;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		AnnotationNode injector = MixinFit.injectorOf(handler);
		if (ecosystem == null || shape(handler, injector) == null) return null;
		// Judged before the class is defined, so the early returns it will be given back are read off the shipped rows.
		Move move = move(mixin, handler, injector, target, ecosystem,
				(owner, name, desc) -> VanillaEarlyReturns.restores(owner, name, desc) ? new VanillaEarlyReturns.Split(0, 1) : null, references, false);
		return move == null ? null : move.piece();
	}

	/** Which end an eligible injector names ({@code true} the start), or null when its shape is not one this rule moves. */
	private static Boolean shape(MethodNode handler, AnnotationNode injector) {
		if (injector == null || !INJECT.equals(injector.desc) || MixinFit.value(injector, "slice") != null
				|| MixinFit.value(injector, "locals") != null || MixinFit.groupOf(handler) != null) return null;
		List<AnnotationNode> points = MixinFit.atNodes(injector);
		if (points.isEmpty() || !points.stream().allMatch(MixinSplitBodyEdges::edge)) return null;
		boolean head = points.stream().allMatch(at -> "HEAD".equals(MixinFit.asString(MixinFit.value(at, "value"))));
		boolean end = points.stream().noneMatch(at -> "HEAD".equals(MixinFit.asString(MixinFit.value(at, "value"))));
		if (!head && !end) return null;    // both ends in one injector: no single piece carries them
		if (MixinFit.stringList(MixinFit.value(injector, "method")).size() != 1) return null;
		return head;
	}

	private record Move(MethodNode named, List<MethodNode> pieces, MethodNode piece, boolean head) {
	}

	private static Move move(ClassNode mixin, MethodNode handler, AnnotationNode injector, ClassNode target, Ecosystem ecosystem,
			Splits splits, java.util.function.BiFunction<Ecosystem, String, ClassNode> references, boolean report) {
		boolean head = shape(handler, injector);
		MethodNode named = MixinTargetSelectors.one(handler, target);
		if (named == null || !Type.getReturnType(named.desc).equals(Type.VOID_TYPE)) return null;
		List<MethodNode> pieces = pieces(target, named, ecosystem, references);
		if (pieces == null || !takesExactly(handler, named)) return null;
		MethodNode last = pieces.getLast();
		if (!head && MixinFit.atNodes(injector).stream().anyMatch(at -> "TAIL".equals(MixinFit.asString(MixinFit.value(at, "value"))))
				&& !tailMatches(target, named, last, ecosystem, splits, references)) {
			if (report) ForbricLog.warn("[Forbric/Mixin] %s: %s's end of %s.%s%s is not moved to %s%s, the last piece the carrier split "
					+ "that body into: its native method returns early, and that piece's tail was not given the same early return",
					mixin.name.replace('/', '.'), handler.name, target.name.replace('/', '.'), named.name, named.desc, last.name, last.desc);
			return null;
		}
		return new Move(named, pieces, head ? pieces.getFirst() : last, head);
	}

	private static boolean edge(AnnotationNode at) {
		String value = MixinFit.asString(MixinFit.value(at, "value"));
		return value != null && Set.of("HEAD", "TAIL", "RETURN").contains(value) && MixinFit.value(at, "slice") == null
				&& MixinFit.value(at, "shift") == null && MixinFit.value(at, "by") == null
				&& (!"RETURN".equals(value) || !(MixinFit.value(at, "ordinal") instanceof Integer ordinal) || ordinal < 0);
	}

	/**
	 * The pieces of {@code named}, in its order, when it is a census-proven split nothing in the merged game calls and the
	 * merged game reaches every piece from one other method of the class, the same for all, in that order; else null.
	 */
	static List<MethodNode> pieces(ClassNode target, MethodNode named, Ecosystem ecosystem,
			java.util.function.BiFunction<Ecosystem, String, ClassNode> references) {
		List<MethodInsnNode> calls = CarrierHelpers.dispatchedHelpers(target, named);
		if (calls == null || calls.size() < 2) return null;
		ClassNode reference = references == null ? null : references.apply(ecosystem, target.name);
		MethodNode original = reference == null ? null : CarrierHelpers.declared(reference, named.name, named.desc);
		if (original == null || original.instructions == null) return null;
		List<MethodNode> pieces = new ArrayList<>();
		for (MethodInsnNode call : calls) {
			MethodNode piece = CarrierHelpers.declared(target, call.name, call.desc);
			if (piece == null || pieces.contains(piece)) return null;
			pieces.add(piece);
		}
		for (MethodNode piece : pieces) if (!carriesNativeWork(original, named, piece, pieces)) return null;
		if (MergedBaseUncalledMethods.neverRuns(target, named, ecosystem) == null) return null;
		MethodNode site = null;
		int previous = -1;
		for (MethodNode piece : pieces) {
			List<Reference> refs = references(target, piece, named);
			if (refs.size() != 1) return null;
			Reference ref = refs.getFirst();
			if (site != null && ref.method() != site) return null;
			site = ref.method();
			if (ref.index() <= previous) return null;
			previous = ref.index();
		}
		// …and that one place runs.
		if (site == null || MergedBaseUncalledMethods.neverRuns(target, site, ecosystem) != null) return null;
		return pieces;
	}

	/**
	 * Whether {@code piece} does work the native method did and its merged dispatcher no longer does: some call or field
	 * access the native method makes exactly once, made exactly once in this piece and in no other piece or the dispatcher
	 * (the census's own rule for a {@code SPLIT} piece, carrier-helpers.txt, read here on the bytes at hand).
	 */
	private static boolean carriesNativeWork(MethodNode original, MethodNode dispatcher, MethodNode piece, List<MethodNode> pieces) {
		for (AbstractInsnNode insn : piece.instructions) {
			String member = CarrierHelpers.member(insn);
			if (member == null || CarrierHelpers.occurrences(piece, member) != 1 || CarrierHelpers.occurrences(original, member) != 1
					|| CarrierHelpers.occurrences(dispatcher, member) != 0) continue;
			boolean elsewhere = false;
			for (MethodNode other : pieces) if (other != piece && CarrierHelpers.occurrences(other, member) > 0) elsewhere = true;
			if (!elsewhere) return true;
		}
		return false;
	}

	private record Reference(MethodNode method, int index) {
	}

	/** Every call of {@code piece}, or handle to it, in a method of {@code target} other than the piece and {@code skip}. */
	private static List<Reference> references(ClassNode target, MethodNode piece, MethodNode skip) {
		List<Reference> out = new ArrayList<>();
		for (MethodNode method : target.methods) {
			if (method == piece || method == skip || method.instructions == null) continue;
			int index = 0;
			for (AbstractInsnNode insn : method.instructions) {
				index++;
				if (insn instanceof MethodInsnNode call && call.owner.equals(target.name) && call.name.equals(piece.name)
						&& call.desc.equals(piece.desc)) out.add(new Reference(method, index));
				else if (insn instanceof InvokeDynamicInsnNode indy) {
					for (Object argument : indy.bsmArgs) {
						if (argument instanceof Handle h && h.getOwner().equals(target.name) && h.getName().equals(piece.name)
								&& h.getDesc().equals(piece.desc)) out.add(new Reference(method, index));
					}
				} else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Handle h && h.getOwner().equals(target.name)
						&& h.getName().equals(piece.name) && h.getDesc().equals(piece.desc)) out.add(new Reference(method, index));
			}
		}
		// A method that does nothing but hand its own values to the piece — a lambda the carrier registers in its place, or a
		// thunk the kernel gave a method reference — stands for the place that references it, not for a second place.
		List<Reference> outer = new ArrayList<>();
		for (Reference ref : out) {
			if (forwards(target, ref.method(), piece)) {
				List<Reference> through = references(target, ref.method(), skip);
				if (through.size() != 1) return out;
				outer.add(through.getFirst());
			} else {
				outer.add(ref);
			}
		}
		return outer;
	}

	/** Whether {@code method}'s whole body is loads of its own locals, the one call to {@code piece}, and a return. */
	private static boolean forwards(ClassNode target, MethodNode method, MethodNode piece) {
		if (method.instructions == null || (method.tryCatchBlocks != null && !method.tryCatchBlocks.isEmpty())) return false;
		boolean called = false;
		for (AbstractInsnNode insn : method.instructions) {
			int op = insn.getOpcode();
			if (op < 0) continue;
			if (called) return op >= Opcodes.IRETURN && op <= Opcodes.RETURN && nextReal(insn) == null;
			if (insn instanceof VarInsnNode load && op >= Opcodes.ILOAD && op <= Opcodes.ALOAD) continue;
			if (insn instanceof MethodInsnNode call && call.owner.equals(target.name) && call.name.equals(piece.name)
					&& call.desc.equals(piece.desc)) { called = true; continue; }
			return false;
		}
		return false;
	}

	private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
		for (AbstractInsnNode next = insn.getNext(); next != null; next = next.getNext()) if (next.getOpcode() >= 0) return next;
		return null;
	}

	/**
	 * Whether a {@code TAIL} at the end of {@code last} runs exactly when one at the end of {@code named} runs natively:
	 * the native method has no early return, or {@code last}'s tail was split the way vanilla's was.
	 */
	private static boolean tailMatches(ClassNode target, MethodNode named, MethodNode last, Ecosystem ecosystem, Splits splits,
			java.util.function.BiFunction<Ecosystem, String, ClassNode> references) {
		ClassNode reference = references == null ? null : references.apply(ecosystem, target.name);
		MethodNode original = reference == null ? null : CarrierHelpers.declared(reference, named.name, named.desc);
		if (original == null) return false;
		int returns = 0;
		for (AbstractInsnNode insn : original.instructions) {
			int op = insn.getOpcode();
			if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN) returns++;
		}
		if (returns <= 1) return true;
		return splits != null && splits.of(target.name, last.name, last.desc) != null;
	}

	/** The handler takes the method's arguments, then its {@code CallbackInfo}, and nothing else; no sugar. */
	static boolean takesExactly(MethodNode handler, MethodNode named) {
		Type[] wanted = Type.getArgumentTypes(handler.desc), arguments = Type.getArgumentTypes(named.desc);
		if (wanted.length != arguments.length + 1 || Type.getReturnType(handler.desc).getSort() != Type.VOID) return false;
		for (int i = 0; i < arguments.length; i++) if (!wanted[i].equals(arguments[i])) return false;
		if (!wanted[arguments.length].getDescriptor().equals("L" + CALLBACK_INFO + ";")) return false;
		if (((handler.access & Opcodes.ACC_STATIC) != 0) != ((named.access & Opcodes.ACC_STATIC) != 0)) return false;
		for (List<AnnotationNode>[] all : List.of(handler.visibleParameterAnnotations == null ? new List[0] : handler.visibleParameterAnnotations,
				handler.invisibleParameterAnnotations == null ? new List[0] : handler.invisibleParameterAnnotations)) {
			for (List<AnnotationNode> sugar : all) if (sugar != null && !sugar.isEmpty()) return false;
		}
		return true;
	}

	/**
	 * Moves the guest's handler body aside under a plain name, and puts a wrapper of the handler's name, with its annotation,
	 * in its place: it starts the run, calls the guest's body with the same values, and marks the run when the callback came
	 * back cancelled.
	 */
	private static String aside(ClassNode mixin, MethodNode handler, AnnotationNode injector, String key) {
		String inner = MixinHandlerShim.asideName(mixin.name, handler.name, INNER_SUFFIX);
		boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		MethodNode outer = new MethodNode(Opcodes.ASM9, handler.access, handler.name, handler.desc, handler.signature,
				handler.exceptions == null ? null : handler.exceptions.toArray(String[]::new));
		outer.visibleAnnotations = handler.visibleAnnotations;
		outer.invisibleAnnotations = handler.invisibleAnnotations;
		Type[] arguments = Type.getArgumentTypes(handler.desc);
		outer.instructions.add(new LdcInsnNode(key));
		outer.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNS, "started", "(Ljava/lang/String;)V", false));
		int slot = isStatic ? 0 : 1;
		if (!isStatic) outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		int callback = -1;
		for (Type argument : arguments) {
			outer.instructions.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
			callback = slot;
			slot += argument.getSize();
		}
		outer.instructions.add(MixinHandlerShim.callOwn(mixin, isStatic, inner, handler.desc));
		LabelNode done = new LabelNode();
		outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, callback));
		outer.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CALLBACK_INFO, "isCancelled", "()Z", false));
		outer.instructions.add(new JumpInsnNode(Opcodes.IFEQ, done));
		outer.instructions.add(new LdcInsnNode(key));
		outer.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNS, "cancelled", "(Ljava/lang/String;)V", false));
		outer.instructions.add(done);
		outer.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		outer.instructions.add(new InsnNode(Opcodes.RETURN));
		outer.maxStack = Math.max(slot, 1);
		outer.maxLocals = slot;
		handler.name = inner;
		handler.visibleAnnotations = without(handler.visibleAnnotations, injector);
		handler.invisibleAnnotations = without(handler.invisibleAnnotations, injector);
		handler.access = (handler.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE;
		mixin.methods.add(mixin.methods.indexOf(handler), outer);
		return inner;
	}

	private static List<AnnotationNode> without(List<AnnotationNode> annotations, AnnotationNode injector) {
		if (annotations == null) return null;
		List<AnnotationNode> kept = new ArrayList<>(annotations);
		kept.remove(injector);
		return kept;
	}

	/**
	 * A cancellable {@code @Inject} at the start of the later piece {@code index}, which cancels it while the run it belongs
	 * to was cancelled at its start. Takes the piece's arguments and callback, as the guest's own handler does.
	 */
	private static MethodNode guard(ClassNode mixin, MethodNode handler, String written, AnnotationNode injector, MethodNode piece,
			int index, String key) {
		boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		AnnotationNode inject = new AnnotationNode(INJECT);
		inject.values = new ArrayList<>();
		inject.values.add("method");
		inject.values.add(new ArrayList<>(List.of(piece.name + piece.desc)));
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "HEAD"));
		inject.values.add("at");
		inject.values.add(new ArrayList<>(List.of(at)));
		inject.values.add("cancellable");
		inject.values.add(Boolean.TRUE);
		Object require = MixinFit.value(injector, "require");
		if (require != null) { inject.values.add("require"); inject.values.add(require); }
		MethodNode guard = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | (isStatic ? Opcodes.ACC_STATIC : 0),
				written + GUARD_SUFFIX + index, handler.desc, null, null);
		guard.visibleAnnotations = new ArrayList<>(List.of(inject));
		int slot = isStatic ? 0 : 1;
		int callback = -1;
		for (Type argument : Type.getArgumentTypes(handler.desc)) { callback = slot; slot += argument.getSize(); }
		LabelNode done = new LabelNode();
		guard.instructions.add(new LdcInsnNode(key));
		guard.instructions.add(new LdcInsnNode(index));
		guard.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNS, "skips", "(Ljava/lang/String;I)Z", false));
		guard.instructions.add(new JumpInsnNode(Opcodes.IFEQ, done));
		guard.instructions.add(new VarInsnNode(Opcodes.ALOAD, callback));
		guard.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CALLBACK_INFO, "cancel", "()V", false));
		guard.instructions.add(done);
		guard.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		guard.instructions.add(new InsnNode(Opcodes.RETURN));
		guard.maxStack = 2;
		guard.maxLocals = slot;
		return guard;
	}
}
