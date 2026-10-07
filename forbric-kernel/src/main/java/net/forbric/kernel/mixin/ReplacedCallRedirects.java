/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Moves a mod's {@code @Redirect} of a vanilla call that the surviving carrier REPLACED, at the same place, with a call of
 * its own onto that call — when the handler only puts a condition around the vanilla call: it forwards it, once, with the
 * very values it was handed. The forwarding becomes the carrier's call. Where the mod lets the call through, the game
 * does what the carrier does there; where it does not, what the mod does.
 *
 * <p>A redirect of a call that is gone binds nothing, and Mixin's {@code require} turns that into a lost required
 * injector. Rewriting the redirect onto the carrier's call is not sound in general — the handler is shaped by the call
 * and replaces it whole — so this moves one only along a {@link Site} row, which says where the carrier's call stands
 * for vanilla's and why, and only a handler whose own work is the condition:
 * <ul>
 *   <li>a {@code @Redirect} at the row's call in the row's method, one selector, one point (its ordinal mapped by the
 *       row, a slice only where the row names it), no {@code @Group}, no parameter annotations, the handler's descriptor
 *       the vanilla call's;</li>
 *   <li>exactly one call of the vanilla member in the handler, taking every parameter, unchanged and in order;</li>
 *   <li>a parameter the carrier's call does not hand over (row {@code from} -1) used nowhere else; one it does may be
 *       read anywhere, and keeps its meaning — the handler gets the value vanilla passed in that place;</li>
 *   <li>when the carrier's call returns another type, every return of the handler returns null or the forwarded value;
 *       </li>
 *   <li>the merged method calls the vanilla member nowhere and the carrier's as many times as the row says, each after
 *       the row's marker.</li>
 * </ul>
 * The handler's descriptor becomes the carrier's call's ({@code -Dforbric.replacedCallRedirects=off} leaves every one as
 * compiled).
 */
public final class ReplacedCallRedirects {
	public static final String PROPERTY = "forbric.replacedCallRedirects";
	private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
	private static final String GROUP = "Lorg/spongepowered/asm/mixin/injection/Group;";
	private static final Set<String> INJECTOR_KEYS = Set.of("method", "at", "slice", "remap", "require", "expect", "allow");
	private static final Set<String> AT_KEYS = Set.of("value", "target", "ordinal", "remap");

	/**
	 * @param owner      the class the mixins target (internal name)
	 * @param method     the method there, {@code name + descriptor}
	 * @param vanilla    the call the listed ecosystems' mods redirect, as an {@code @At} target
	 * @param vanillaOpcode how vanilla invokes it (whether the redirect handler takes a receiver)
	 * @param merged     the carrier's call in its place, as an {@code @At} target
	 * @param opcode     how the merged body invokes it
	 * @param count      how many times the merged method makes that call
	 * @param ordinals   for each ordinal of the vanilla call (within the slice), the merged call's
	 * @param markers    for each merged call in order, a field the method reads before it and after the call before;
	 *                   shorter than {@code count} when only the first ones are told apart
	 * @param slice      the {@code @At(FIELD)} target a slice may start from (then dropped), or null when none may
	 * @param from       for each operand of the vanilla call (receiver first), the merged operand carrying the same
	 *                   value, or -1 when the merged call has none
	 * @param ecosystems the mods compiled against {@code vanilla} in this method
	 * @param because    why the merged call's place is the vanilla one's
	 */
	public record Site(String owner, String method, String vanilla, int vanillaOpcode, String merged, int opcode, int count,
			int[] ordinals,
			List<String> markers, String slice, int[] from, Set<Ecosystem> ecosystems, String because) {
	}

	private static final String ITEM_STACK = "Lnet/minecraft/world/item/ItemStack;";
	public static final List<Site> SITES = List.of(
			new Site("net/minecraft/client/gui/screens/inventory/AbstractContainerScreen",
					"checkHotbarKeyPressed(Lnet/minecraft/client/input/KeyEvent;)Z",
					"Lnet/minecraft/client/KeyMapping;matches(Lnet/minecraft/client/input/KeyEvent;)Z", Opcodes.INVOKEVIRTUAL,
					"Lnet/minecraft/client/KeyMapping;isActiveAndMatches(Lcom/mojang/blaze3d/platform/InputConstants$Key;)Z",
					Opcodes.INVOKEVIRTUAL, 2, new int[] { 0, 1 },
					List.of("Lnet/minecraft/client/Options;keySwapOffhand:Lnet/minecraft/client/KeyMapping;",
							"Lnet/minecraft/client/Options;keyHotbarSlots:[Lnet/minecraft/client/KeyMapping;"),
					null, new int[] { 0, -1 }, Set.of(Ecosystem.FABRIC, Ecosystem.FORGE),
					"NeoForge's checkHotbarKeyPressed reads the key once and asks each mapping isActiveAndMatches(key) where "
							+ "vanilla asked matches(event): the same key comparison plus NeoForge's key-conflict context and "
							+ "modifier, for the off-hand swap and then each hotbar slot, in vanilla's order"),
			new Site("net/minecraft/world/entity/LivingEntity", "updatingUsingItem()V",
					ITEM_STACK + "isSameItem(" + ITEM_STACK + ITEM_STACK + ")Z", Opcodes.INVOKESTATIC,
					"Lnet/neoforged/neoforge/common/CommonHooks;canContinueUsing(" + ITEM_STACK + ITEM_STACK + ")Z",
					Opcodes.INVOKESTATIC, 1, new int[] { 0 }, List.of(), null, new int[] { 1, 0 }, Set.of(Ecosystem.FABRIC),
					"vanilla keeps using an item while isSameItem(the stack in hand, the stack in use); NeoForge asks "
							+ "canContinueUsing(the stack in use, the stack in hand) — the item decides, by default whether they "
							+ "are the same item — and keeps using it only while the stack in hand is the one in use. The "
							+ "handler still sees the stacks in vanilla's order and forwards NeoForge's question"),
			new Site("net/minecraft/world/item/ShovelItem",
					"useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;",
					"Ljava/util/Map;get(Ljava/lang/Object;)Ljava/lang/Object;", Opcodes.INVOKEINTERFACE,
					"Lnet/minecraft/world/level/block/state/BlockState;getToolModifiedState("
							+ "Lnet/minecraft/world/item/context/UseOnContext;Lnet/neoforged/neoforge/common/ItemAbility;Z)"
							+ "Lnet/minecraft/world/level/block/state/BlockState;",
					Opcodes.INVOKEVIRTUAL, 2, new int[] { 0 },
					List.of("Lnet/neoforged/neoforge/common/ItemAbilities;SHOVEL_FLATTEN:Lnet/neoforged/neoforge/common/ItemAbility;"),
					"Lnet/minecraft/world/item/ShovelItem;FLATTENABLES:Ljava/util/Map;", new int[] { -1, -1 },
					Set.of(Ecosystem.FABRIC),
					"vanilla's useOn looks the clicked block up in FLATTENABLES, null meaning no path; NeoForge's asks the state "
							+ "for its SHOVEL_FLATTEN modification, which for a block that does not override it is that same "
							+ "lookup (ShovelItem.getShovelPathingState) behind NeoForge's tool-modification event, and null "
							+ "still means no path"));

	private ReplacedCallRedirects() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/** Moves every eligible redirect of {@code mixin}; returns how many. {@code targets} must return nodes WITH code. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		return adapt(mixin, targets, true);
	}

	private static int adapt(ClassNode mixin, Function<String, ClassNode> targets, boolean log) {
		if (!enabled() || mixin == null || mixin.methods == null || targets == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		List<String> owners = MixinFit.mixinTargets(mixin);
		if (ecosystem == null || owners.size() != 1) return 0;
		int moved = 0;
		for (Site site : SITES) {
			if (!site.owner().equals(owners.getFirst()) || !site.ecosystems().contains(ecosystem)) continue;
			ClassNode target = null;
			for (MethodNode handler : List.copyOf(mixin.methods)) {
				AnnotationNode redirect = MixinFit.injectorOf(handler);
				if (redirect == null || !REDIRECT.equals(redirect.desc)) continue;
				if (target == null) target = targets.apply(site.owner());
				if (target == null || !hostHasTheSiteShape(target, site)) break;
				if (move(mixin, handler, redirect, site, log)) moved++;
			}
		}
		return moved;
	}

	/**
	 * {@code bytes} as {@link #adapt} hands it to Mixin, or {@code bytes} itself when nothing changes: the census reads
	 * the mixin before the adapters run, and a redirect this moves would read as a missing anchor there.
	 */
	public static byte[] asLoaded(byte[] bytes, Function<String, byte[]> resource) {
		if (!enabled() || bytes == null) return bytes;
		try {
			ClassNode mixin = new ClassNode();
			new ClassReader(bytes).accept(mixin, 0);
			Function<String, ClassNode> targets = name -> {
				byte[] b = resource.apply(name + ".class");
				if (b == null) return null;
				ClassNode t = new ClassNode();
				new ClassReader(b).accept(t, 0);
				return t;
			};
			if (adapt(mixin, targets, false) == 0) return bytes;
			ClassWriter out = new ClassWriter(0);
			mixin.accept(out);
			return out.toByteArray();
		} catch (RuntimeException unreadable) {
			return bytes;
		}
	}

	/** The merged method makes the carrier's call {@code count} times, after each marker, and the vanilla one never. */
	static boolean hostHasTheSiteShape(ClassNode target, Site site) {
		MethodNode host = null;
		for (MethodNode m : target.methods) if ((m.name + m.desc).equals(site.method())) host = m;
		if (host == null || host.instructions == null) return false;
		MixinFit.Member vanilla = MixinFit.parseMember(site.vanilla()), merged = MixinFit.parseMember(site.merged());
		int calls = 0, marker = 0;
		boolean marked = site.markers().isEmpty();
		for (AbstractInsnNode insn : host.instructions) {
			if (insn instanceof MethodInsnNode call) {
				if (is(call, vanilla)) return false;
				if (is(call, merged)) {
					if (call.getOpcode() != site.opcode() || (calls < site.markers().size() && !marked)) return false;
					calls++;
					marked = calls >= site.markers().size();
				}
			} else if (insn instanceof FieldInsnNode field && calls < site.markers().size()
					&& ("L" + field.owner + ";" + field.name + ":" + field.desc).equals(site.markers().get(calls))) {
				marked = true;
				marker++;
			}
		}
		return calls == site.count() && marker >= site.markers().size();
	}

	private static boolean is(MethodInsnNode call, MixinFit.Member member) {
		return call.owner.equals(member.owner()) && call.name.equals(member.name()) && call.desc.equals(member.desc());
	}

	private static boolean move(ClassNode mixin, MethodNode handler, AnnotationNode redirect, Site site, boolean log) {
		if (annotated(handler.visibleAnnotations, GROUP) || annotated(handler.invisibleAnnotations, GROUP)) return false;
		if (parameterAnnotated(handler.visibleParameterAnnotations) || parameterAnnotated(handler.invisibleParameterAnnotations)) return false;
		for (int i = 0; i + 1 < redirect.values.size(); i += 2) if (!INJECTOR_KEYS.contains(redirect.values.get(i))) return false;
		List<String> selectors = MixinFit.stringList(MixinFit.value(redirect, "method"));
		String name = site.method().substring(0, site.method().indexOf('('));
		if (selectors.size() != 1 || !(selectors.getFirst().equals(name) || selectors.getFirst().equals(site.method()))) return false;
		List<AnnotationNode> points = MixinFit.atNodes(redirect);
		if (points.size() != 1) return false;
		AnnotationNode at = points.getFirst();
		for (int i = 0; i + 1 < at.values.size(); i += 2) if (!AT_KEYS.contains(at.values.get(i))) return false;
		if (!"INVOKE".equals(MixinFit.value(at, "value")) || !site.vanilla().equals(MixinFit.value(at, "target"))) return false;
		Object slice = MixinFit.value(redirect, "slice");
		if (slice != null && !theRowsSlice(slice, site)) return false;
		Object ordinal = MixinFit.value(at, "ordinal");
		Integer mergedOrdinal;
		if (ordinal == null || Integer.valueOf(-1).equals(ordinal)) {
			// Every vanilla call: every merged one, when the rows pair them all, in order; the one, when there is one.
			if (site.ordinals().length == site.count() && identity(site.ordinals())) mergedOrdinal = null;
			else if (site.ordinals().length == 1) mergedOrdinal = site.ordinals()[0];
			else return false;
		} else if (ordinal instanceof Integer n && n >= 0 && n < site.ordinals().length) {
			mergedOrdinal = site.ordinals()[n];
		} else {
			return false;
		}

		MixinFit.Member vanilla = MixinFit.parseMember(site.vanilla()), merged = MixinFit.parseMember(site.merged());
		List<Type> oldParams = operands(vanilla, site.vanillaOpcode());
		List<Type> newParams = operands(merged, site.opcode());
		Type oldReturn = Type.getReturnType(vanilla.desc()), newReturn = Type.getReturnType(merged.desc());
		if (!handler.desc.equals(Type.getMethodDescriptor(oldReturn, oldParams.toArray(Type[]::new)))) return false;
		if (site.from().length != oldParams.size()) return false;
		for (int i = 0; i < oldParams.size(); i++) {
			if (site.from()[i] >= 0 && !oldParams.get(i).equals(newParams.get(site.from()[i]))) return false;
		}
		boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		if (isStatic) return false;   // the rows' methods are instance methods; Mixin wants an instance handler there
		int base = 1;
		int[] oldSlot = slots(oldParams, base), newSlot = slots(newParams, base);
		int oldEnd = base + size(oldParams), newEnd = base + size(newParams), delta = newEnd - oldEnd;
		// A full frame that does not list every parameter could not be given the new ones in their places.
		for (AbstractInsnNode insn : handler.instructions) {
			if (insn instanceof FrameNode frame && (frame.type == Opcodes.F_NEW || frame.type == Opcodes.F_FULL)
					&& (frame.local == null || frame.local.size() < 1 + oldParams.size())) return false;
		}

		// The forwarding: the one call of the vanilla member, fed by the loads of every parameter in order.
		MethodInsnNode forward = null;
		for (AbstractInsnNode insn : handler.instructions) {
			if (insn instanceof MethodInsnNode call && is(call, vanilla)) {
				if (forward != null) return false;
				forward = call;
			}
		}
		if (forward == null) return false;
		List<VarInsnNode> feed = new ArrayList<>();
		AbstractInsnNode back = forward.getPrevious();
		for (int i = oldParams.size() - 1; i >= 0; i--) {
			while (back != null && back.getOpcode() < 0) back = back.getPrevious();
			if (!(back instanceof VarInsnNode load) || load.var != oldSlot[i] || load.getOpcode() != oldParams.get(i).getOpcode(Opcodes.ILOAD)) return false;
			feed.addFirst(load);
			back = back.getPrevious();
		}
		// Elsewhere: a dropped parameter not at all, a carried one only read.
		for (AbstractInsnNode insn : handler.instructions) {
			int var = insn instanceof VarInsnNode v ? v.var : insn instanceof IincInsnNode inc ? inc.var : -1;
			if (var < base || var >= oldEnd || feed.contains(insn)) continue;
			int parameter = parameterAt(oldSlot, oldParams, var);
			if (parameter < 0 || site.from()[parameter] < 0) return false;
			if (!(insn instanceof VarInsnNode v) || v.getOpcode() != oldParams.get(parameter).getOpcode(Opcodes.ILOAD)) return false;
		}
		if (!oldReturn.equals(newReturn)) {
			if (oldReturn.getSort() != Type.OBJECT || newReturn.getSort() != Type.OBJECT) return false;
			for (AbstractInsnNode insn : handler.instructions) {
				if (insn.getOpcode() != Opcodes.ARETURN) continue;
				AbstractInsnNode value = insn.getPrevious();
				while (value != null && value.getOpcode() < 0) value = value.getPrevious();
				if (value != forward && (value == null || value.getOpcode() != Opcodes.ACONST_NULL)) return false;
			}
		}

		// Rewrite: the carried parameters' reads and every later local move to their new slots, then the forwarding.
		for (AbstractInsnNode insn : handler.instructions) {
			if (feed.contains(insn)) continue;
			if (insn instanceof VarInsnNode v && v.var >= base) {
				v.var = v.var < oldEnd ? newSlot[site.from()[parameterAt(oldSlot, oldParams, v.var)]] : v.var + delta;
			} else if (insn instanceof IincInsnNode inc && inc.var >= oldEnd) {
				inc.var += delta;
			} else if (insn instanceof FrameNode frame && (frame.type == Opcodes.F_NEW || frame.type == Opcodes.F_FULL)) {
				List<Object> locals = new ArrayList<>();
				locals.add(frame.local.getFirst());
				for (Type t : newParams) locals.add(frameType(t));
				locals.addAll(frame.local.subList(1 + oldParams.size(), frame.local.size()));
				frame.local = locals;
			}
		}
		for (VarInsnNode load : feed) handler.instructions.remove(load);
		for (int i = 0; i < newParams.size(); i++) {
			handler.instructions.insertBefore(forward, new VarInsnNode(newParams.get(i).getOpcode(Opcodes.ILOAD), newSlot[i]));
		}
		handler.instructions.set(forward, new MethodInsnNode(site.opcode(), merged.owner(), merged.name(), merged.desc(),
				site.opcode() == Opcodes.INVOKEINTERFACE));
		relabel(handler, oldParams, newParams, oldSlot, newSlot, oldEnd, delta, site.from());
		handler.desc = Type.getMethodDescriptor(newReturn, newParams.toArray(Type[]::new));
		handler.signature = null;
		handler.parameters = null;
		handler.visibleParameterAnnotations = null;
		handler.invisibleParameterAnnotations = null;
		handler.visibleAnnotableParameterCount = 0;
		handler.invisibleAnnotableParameterCount = 0;
		handler.maxLocals = Math.max(handler.maxLocals + delta, newEnd);
		handler.maxStack = handler.maxStack + Math.max(0, size(newParams) - size(oldParams));

		set(at, "target", site.merged());
		if (mergedOrdinal == null) remove(at, "ordinal");
		else set(at, "ordinal", mergedOrdinal);
		remove(redirect, "slice");
		if (log) {
			ForbricLog.info("[Forbric/Mixin] %s: %s redirects %s in %s.%s where the merged body calls it instead of %s — %s",
					mixin.name.replace('/', '.'), handler.name, merged.name(), site.owner().replace('/', '.'), name,
					vanilla.name(), site.because());
		}
		return true;
	}

	private static List<Type> operands(MixinFit.Member member, int opcode) {
		List<Type> out = new ArrayList<>();
		if (opcode != Opcodes.INVOKESTATIC) out.add(Type.getObjectType(member.owner()));
		out.addAll(List.of(Type.getArgumentTypes(member.desc())));
		return out;
	}

	private static int[] slots(List<Type> params, int base) {
		int[] out = new int[params.size()];
		int slot = base;
		for (int i = 0; i < params.size(); i++) {
			out[i] = slot;
			slot += params.get(i).getSize();
		}
		return out;
	}

	private static int size(List<Type> params) {
		int n = 0;
		for (Type t : params) n += t.getSize();
		return n;
	}

	private static int parameterAt(int[] slots, List<Type> params, int var) {
		for (int i = 0; i < slots.length; i++) if (var == slots[i]) return i;
		return -1;
	}

	private static Object frameType(Type t) {
		return switch (t.getSort()) {
			case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.INTEGER;
			case Type.FLOAT -> Opcodes.FLOAT;
			case Type.LONG -> Opcodes.LONG;
			case Type.DOUBLE -> Opcodes.DOUBLE;
			default -> t.getInternalName();
		};
	}

	/** The local variable table: carried parameters keep their names, the others are named for the call, later locals move. */
	private static void relabel(MethodNode handler, List<Type> oldParams, List<Type> newParams, int[] oldSlot, int[] newSlot,
			int oldEnd, int delta, int[] from) {
		if (handler.localVariables == null) return;
		LabelNode start = null, end = null;
		String[] names = new String[newParams.size()];
		List<LocalVariableNode> kept = new ArrayList<>();
		for (LocalVariableNode local : handler.localVariables) {
			if (local.index == 0) {
				start = local.start;
				end = local.end;
				kept.add(local);
			} else if (local.index < oldEnd) {
				int parameter = parameterAt(oldSlot, oldParams, local.index);
				if (parameter >= 0 && from[parameter] >= 0) names[from[parameter]] = local.name;
			} else {
				local.index += delta;
				kept.add(local);
			}
		}
		if (start == null) {
			handler.localVariables = null;
			return;
		}
		for (int i = 0; i < newParams.size(); i++) {
			kept.add(new LocalVariableNode(names[i] != null ? names[i] : "forbric$operand" + i,
					newParams.get(i).getDescriptor(), null, start, end, newSlot[i]));
		}
		handler.localVariables = kept;
	}

	private static boolean theRowsSlice(Object slice, Site site) {
		if (site.slice() == null) return false;
		List<?> slices = slice instanceof List<?> list ? list : List.of(slice);
		if (slices.size() != 1 || !(slices.getFirst() instanceof AnnotationNode s)) return false;
		for (int i = 0; i + 1 < s.values.size(); i += 2) if (!"from".equals(s.values.get(i))) return false;
		if (!(MixinFit.value(s, "from") instanceof AnnotationNode from)) return false;
		for (int i = 0; i + 1 < from.values.size(); i += 2) {
			Object key = from.values.get(i);
			if (!"value".equals(key) && !"target".equals(key) && !"opcode".equals(key)) return false;
		}
		Object opcode = MixinFit.value(from, "opcode");
		return "FIELD".equals(MixinFit.value(from, "value")) && site.slice().equals(MixinFit.value(from, "target"))
				&& (opcode == null || Integer.valueOf(Opcodes.GETSTATIC).equals(opcode));
	}

	private static boolean identity(int[] ordinals) {
		for (int i = 0; i < ordinals.length; i++) if (ordinals[i] != i) return false;
		return true;
	}

	private static boolean annotated(List<AnnotationNode> annotations, String desc) {
		return annotations != null && annotations.stream().anyMatch(a -> desc.equals(a.desc));
	}

	private static boolean parameterAnnotated(List<AnnotationNode>[] parameters) {
		if (parameters != null) for (List<AnnotationNode> annotations : parameters) {
			if (annotations != null && !annotations.isEmpty()) return true;
		}
		return false;
	}

	private static void set(AnnotationNode annotation, String key, Object value) {
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			if (key.equals(annotation.values.get(i))) {
				annotation.values.set(i + 1, value);
				return;
			}
		}
		annotation.values.add(key);
		annotation.values.add(value);
	}

	private static void remove(AnnotationNode annotation, String key) {
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			if (key.equals(annotation.values.get(i))) {
				annotation.values.remove(i + 1);
				annotation.values.remove(i);
				return;
			}
		}
	}
}
