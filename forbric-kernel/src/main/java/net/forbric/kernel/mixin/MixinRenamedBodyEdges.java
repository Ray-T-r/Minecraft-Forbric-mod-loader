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
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Edges a body a carrier renamed on every path the merged game runs it by: a guest's edge injector — its method's start,
 * its end — stays on the method it names, and each method that runs the renamed body without passing through it, by
 * only forwarding to it, gets the same edge.
 *
 * <p>NeoForge's {@code PersistentEntitySectionManager.addEntity} posts the entity-join event and hands the rest to
 * {@code addEntityWithoutEvent}, vanilla's body renamed ({@link CarrierRenames}: a whole RENAME, called). Its new
 * {@code addNewEntityWithoutEvent} calls the renamed body directly, and {@code ServerLevel.addPlayer} adds every player
 * through it, having posted the event itself. fabric-lifecycle's {@code ServerEntityEvents.ALLOW_LOAD} is a cancellable
 * {@code HEAD} of vanilla's {@code addEntity}: left on the dispatcher alone it never saw a player. Moved onto the renamed
 * body it saw every run — but after the dispatcher's event: every non-player entity was offered to NeoForge's join
 * listeners first and could then still be refused by Fabric's veto, which on the dispatcher decides before anything else
 * runs. So the edge stays where it was written, and a forwarder — a method of the class whose whole body hands its own
 * arguments and constants to the renamed body and returns what it returns, as {@code addNewEntityWithoutEvent} does —
 * gets a copy of the injector at the same points, whose handler passes the forwarder's values on to the guest's handler.
 * Each run of the body then meets the edge once: through the dispatcher at its start, through a forwarder at its start.
 *
 * <p>Kept exactly: the census rows for the mod's ecosystem make the pair a whole RENAME the class still calls, of the same
 * descriptor and static-ness; the named method still calls the renamed body (a dispatcher in front of it, never one that
 * leaves the body to a later continuation); something in the class other than the named method calls the renamed one
 * (otherwise every run of the body starts at the named method, and nothing is added — NeoForge's {@code ServerLevel.getSeed}
 * answers through a lambda only it creates); every point is a {@code HEAD}, a {@code TAIL} or an unnumbered
 * {@code RETURN}; no slice, group or captured locals; the selector binds the named method alone; the injector is an
 * {@code @Inject} whose handler takes exactly the method's arguments and its callback. The guest's handler is moved aside
 * under a wrapper of its name that keeps its annotation, so the forwarder's copy calls a plain method. A forwarder the
 * dispatcher itself calls gets nothing (the edge would run twice), and a method that runs the body any other way — with
 * more code around the call, through a method handle — is reported, not edged.
 * {@code -Dforbric.renamedBodyEdges=off} leaves every injector as written.
 */
public final class MixinRenamedBodyEdges {
	public static final String PROPERTY = "forbric.renamedBodyEdges";
	private static final Set<String> EDGES = Set.of("HEAD", "TAIL", "RETURN");
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String CALLBACK_INFO = "org/spongepowered/asm/mixin/injection/callback/CallbackInfo";
	private static final String CALLBACK_INFO_RETURNABLE = "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable";
	static final String INNER_SUFFIX = "$forbricedge";
	static final String EDGE_SUFFIX = "$forbricedge$";

	private MixinRenamedBodyEdges() {
	}

	/** Adds the forwarders' copies of every eligible injector of {@code mixin}; returns how many copies. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if ("off".equalsIgnoreCase(net.forbric.kernel.util.ForbricSwitches.get(PROPERTY, "on")) || mixin == null || mixin.methods == null
				|| targets == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		List<String> owners = MixinOverloadPin.targetsOf(mixin);
		if (ecosystem == null || owners.size() != 1) return 0;
		ClassNode target = null;
		int added = 0;
		for (MethodNode handler : new ArrayList<>(mixin.methods)) {
			AnnotationNode injector = MixinFit.injectorOf(handler);
			if (injector == null || MixinFit.value(injector, "slice") != null || MixinFit.value(injector, "locals") != null
					|| MixinFit.groupOf(handler) != null || handler.name.endsWith(INNER_SUFFIX) || handler.name.contains(EDGE_SUFFIX)) continue;
			List<AnnotationNode> points = MixinFit.atNodes(injector);
			if (points.isEmpty() || !points.stream().allMatch(MixinRenamedBodyEdges::edge)) continue;
			List<String> selectors = MixinFit.stringList(MixinFit.value(injector, "method"));
			if (selectors.size() != 1) continue;
			if (target == null) {
				target = targets.apply(owners.getFirst());
				if (target == null) return added;
			}
			MethodNode named = MixinTargetSelectors.one(handler, target);
			if (named == null) continue;
			MethodNode renamed = renamedBody(target, named, ecosystem);
			if (renamed == null || !forwardsTo(target, named, renamed) || !runsWithoutPassingThrough(target, renamed, named)) continue;
			List<MethodNode> forwarders = new ArrayList<>();
			List<String> unedged = new ArrayList<>();
			for (MethodNode other : target.methods) {
				if (other == named || other == renamed || !reaches(target, other, renamed)) continue;
				int[] passed = forwarder(target, other, renamed);
				if (passed == null || calls(target, named, other)) unedged.add(other.name + other.desc);
				else forwarders.add(other);
			}
			if (!INJECT.equals(injector.desc) || !takesExactly(handler, renamed)) {
				ForbricLog.warn("[Forbric/Mixin] %s: %s's edge of %s.%s%s is not met when the merged game runs that body through %s — a carrier "
						+ "renamed it to %s%s, and this injector's kind or handler shape cannot be given to those callers", mixin.name.replace('/', '.'),
						handler.name, target.name.replace('/', '.'), named.name, named.desc, describe(forwarders, unedged), renamed.name, renamed.desc);
				continue;
			}
			if (!unedged.isEmpty()) {
				ForbricLog.warn("[Forbric/Mixin] %s: %s's edge of %s.%s%s is not met when the merged game runs %s%s through %s, which do more than "
						+ "forward to it", mixin.name.replace('/', '.'), handler.name, target.name.replace('/', '.'), named.name, named.desc,
						renamed.name, renamed.desc, unedged);
			}
			if (forwarders.isEmpty()) continue;
			String written = handler.name, inner = aside(mixin, handler, injector);
			for (MethodNode forwarder : forwarders) {
				mixin.methods.add(copy(mixin, handler, written, injector, inner, forwarder, forwarder(target, forwarder, renamed)));
				added++;
			}
			ForbricLog.info("[Forbric/Mixin] %s: %s's edge of %s.%s%s stays there and also edges %s, which run%s the renamed body %s%s "
					+ "without passing through it (carrier-renames.txt)", mixin.name.replace('/', '.'), written, target.name.replace('/', '.'),
					named.name, named.desc, forwarders.stream().map(f -> f.name + f.desc).toList(), forwarders.size() == 1 ? "s" : "",
					renamed.name, renamed.desc);
		}
		return added;
	}

	private static String describe(List<MethodNode> forwarders, List<String> unedged) {
		List<String> all = new ArrayList<>();
		for (MethodNode f : forwarders) all.add(f.name + f.desc);
		all.addAll(unedged);
		return all.toString();
	}

	/** The handler takes the renamed method's arguments, then its callback ({@code CallbackInfoReturnable} for a value), and nothing else. */
	private static boolean takesExactly(MethodNode handler, MethodNode renamed) {
		Type[] wanted = Type.getArgumentTypes(handler.desc), arguments = Type.getArgumentTypes(renamed.desc);
		if (wanted.length != arguments.length + 1 || Type.getReturnType(handler.desc).getSort() != Type.VOID) return false;
		for (int i = 0; i < arguments.length; i++) if (!wanted[i].equals(arguments[i])) return false;
		String callback = Type.getReturnType(renamed.desc).getSort() == Type.VOID ? CALLBACK_INFO : CALLBACK_INFO_RETURNABLE;
		if (!wanted[arguments.length].getDescriptor().equals("L" + callback + ";")) return false;
		if (((handler.access & Opcodes.ACC_STATIC) != 0) != ((renamed.access & Opcodes.ACC_STATIC) != 0)) return false;
		for (List<AnnotationNode>[] all : List.of(handler.visibleParameterAnnotations == null ? new List[0] : handler.visibleParameterAnnotations,
				handler.invisibleParameterAnnotations == null ? new List[0] : handler.invisibleParameterAnnotations)) {
			for (List<AnnotationNode> sugar : all) if (sugar != null && !sugar.isEmpty()) return false;
		}
		return true;
	}

	/**
	 * For a method whose whole body is: {@code this} (when not static), then each of {@code renamed}'s arguments as one of
	 * its own parameters or a constant, the call to {@code renamed}, and the return of what it returns — for each argument
	 * of {@code renamed}, the slot of the parameter it passes, or {@code -1 - i} for the constant instruction {@code i}
	 * of its body. Null for anything else.
	 */
	static int[] forwarder(ClassNode target, MethodNode method, MethodNode renamed) {
		if (method.instructions == null || !method.tryCatchBlocks.isEmpty()) return null;
		boolean isStatic = (renamed.access & Opcodes.ACC_STATIC) != 0;
		if (((method.access & Opcodes.ACC_STATIC) != 0) != isStatic) return null;
		if (!Type.getReturnType(method.desc).equals(Type.getReturnType(renamed.desc))) return null;
		List<AbstractInsnNode> real = new ArrayList<>();
		for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) real.add(instruction);
		Type[] arguments = Type.getArgumentTypes(renamed.desc);
		int at = 0;
		if (!isStatic) {
			if (real.isEmpty() || !(real.getFirst() instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0) return null;
			at = 1;
		}
		if (real.size() != at + arguments.length + 2) return null;
		java.util.Map<Integer, Type> parameters = new java.util.HashMap<>();
		int slot = isStatic ? 0 : 1;
		for (Type parameter : Type.getArgumentTypes(method.desc)) { parameters.put(slot, parameter); slot += parameter.getSize(); }
		int[] passed = new int[arguments.length];
		for (int i = 0; i < arguments.length; i++) {
			AbstractInsnNode value = real.get(at + i);
			if (value instanceof VarInsnNode load && load.getOpcode() == arguments[i].getOpcode(Opcodes.ILOAD)
					&& arguments[i].equals(parameters.get(load.var))) passed[i] = load.var;
			else if (constant(value, arguments[i])) passed[i] = -1 - method.instructions.indexOf(value);
			else return null;
		}
		if (!(real.get(at + arguments.length) instanceof MethodInsnNode call) || !call.owner.equals(target.name) || !call.name.equals(renamed.name)
				|| !call.desc.equals(renamed.desc)) return null;
		return real.getLast().getOpcode() == Type.getReturnType(renamed.desc).getOpcode(Opcodes.IRETURN) ? passed : null;
	}

	/** A constant of {@code type}'s kind: the int family's {@code ICONST}/{@code BIPUSH}/{@code SIPUSH}, a typed constant, null, an {@code LDC}. */
	private static boolean constant(AbstractInsnNode value, Type type) {
		int opcode = value.getOpcode();
		return switch (type.getSort()) {
			case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5
					|| opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH || value instanceof LdcInsnNode ldc && ldc.cst instanceof Integer;
			case Type.LONG -> opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1 || value instanceof LdcInsnNode ldc && ldc.cst instanceof Long;
			case Type.FLOAT -> opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2 || value instanceof LdcInsnNode ldc && ldc.cst instanceof Float;
			case Type.DOUBLE -> opcode == Opcodes.DCONST_0 || opcode == Opcodes.DCONST_1 || value instanceof LdcInsnNode ldc && ldc.cst instanceof Double;
			default -> opcode == Opcodes.ACONST_NULL || value instanceof LdcInsnNode ldc && (ldc.cst instanceof String || ldc.cst instanceof Type);
		};
	}

	/** Moves the guest's handler body aside under a plain name, and puts a wrapper of the handler's name, with its annotation, in its place. */
	private static String aside(ClassNode mixin, MethodNode handler, AnnotationNode injector) {
		String inner = MixinHandlerShim.asideName(mixin.name, handler.name, INNER_SUFFIX);
		boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		MethodNode outer = new MethodNode(Opcodes.ASM9, handler.access, handler.name, handler.desc, handler.signature,
				handler.exceptions == null ? null : handler.exceptions.toArray(String[]::new));
		outer.visibleAnnotations = handler.visibleAnnotations;
		outer.invisibleAnnotations = handler.invisibleAnnotations;
		Type[] arguments = Type.getArgumentTypes(handler.desc);
		int slot = isStatic ? 0 : 1;
		if (!isStatic) outer.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		for (Type argument : arguments) { outer.instructions.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot)); slot += argument.getSize(); }
		outer.instructions.add(MixinHandlerShim.callOwn(mixin, isStatic, inner, handler.desc));
		outer.instructions.add(new InsnNode(Opcodes.RETURN));
		outer.maxStack = slot;
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
	 * The forwarder's copy of the injector: the same points on {@code forwarder}, whose handler takes the forwarder's
	 * arguments and callback and calls the guest's (now {@code inner}) with what the forwarder hands the renamed body.
	 */
	private static MethodNode copy(ClassNode mixin, MethodNode handler, String written, AnnotationNode injector, String inner,
			MethodNode forwarder, int[] passed) {
		boolean isStatic = (handler.access & Opcodes.ACC_STATIC) != 0;
		Type[] own = Type.getArgumentTypes(forwarder.desc), wanted = Type.getArgumentTypes(handler.desc);
		Type callback = wanted[wanted.length - 1];
		Type[] shape = java.util.Arrays.copyOf(own, own.length + 1);
		shape[own.length] = callback;
		// A deep copy: an adapter after this one that rewrites a point of one must not rewrite the other's.
		AnnotationNode whole = new AnnotationNode(INJECT);
		injector.accept(whole);
		AnnotationNode inject = new AnnotationNode(INJECT);
		inject.values = new ArrayList<>();
		for (int i = 0; whole.values != null && i + 1 < whole.values.size(); i += 2) {
			String key = (String) whole.values.get(i);
			if (key.equals("method") || key.equals("id") || key.equals("target")) continue;
			inject.values.add(key);
			inject.values.add(whole.values.get(i + 1));
		}
		inject.values.add("method");
		inject.values.add(new ArrayList<>(List.of(forwarder.name + forwarder.desc)));
		MethodNode edge = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | (isStatic ? Opcodes.ACC_STATIC : 0),
				written + EDGE_SUFFIX + forwarder.name, Type.getMethodDescriptor(Type.VOID_TYPE, shape), null, null);
		edge.visibleAnnotations = new ArrayList<>(List.of(inject));
		int stack = 0;
		if (!isStatic) { edge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); stack++; }
		for (int i = 0; i < passed.length; i++) {
			if (passed[i] >= 0) edge.instructions.add(new VarInsnNode(wanted[i].getOpcode(Opcodes.ILOAD), passed[i]));
			else edge.instructions.add(forwarder.instructions.get(-1 - passed[i]).clone(java.util.Map.of()));
			stack += wanted[i].getSize();
		}
		int slot = isStatic ? 0 : 1;
		for (Type type : own) slot += type.getSize();
		edge.instructions.add(new VarInsnNode(Opcodes.ALOAD, slot));
		stack++;
		edge.instructions.add(MixinHandlerShim.callOwn(mixin, isStatic, inner, handler.desc));
		edge.instructions.add(new InsnNode(Opcodes.RETURN));
		edge.maxStack = stack;
		edge.maxLocals = slot + 1;
		return edge;
	}

	/** Whether {@code method} calls {@code callee} of {@code target}. */
	private static boolean calls(ClassNode target, MethodNode method, MethodNode callee) {
		if (method.instructions == null) return false;
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof MethodInsnNode call && call.owner.equals(target.name) && call.name.equals(callee.name)
					&& call.desc.equals(callee.desc)) return true;
		}
		return false;
	}

	/** Whether {@code method} calls {@code renamed} or takes a handle to it. */
	private static boolean reaches(ClassNode target, MethodNode method, MethodNode renamed) {
		if (method.instructions == null) return false;
		if (calls(target, method, renamed)) return true;
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction instanceof InvokeDynamicInsnNode dynamic) {
				for (Object argument : dynamic.bsmArgs) {
					if (argument instanceof Handle handle && handle.getOwner().equals(target.name) && handle.getName().equals(renamed.name)
							&& handle.getDesc().equals(renamed.desc)) return true;
				}
			}
		}
		return false;
	}

	private static boolean edge(AnnotationNode at) {
		String value = MixinFit.asString(MixinFit.value(at, "value"));
		return value != null && EDGES.contains(value) && MixinFit.value(at, "slice") == null
				&& (!"RETURN".equals(value) || !(MixinFit.value(at, "ordinal") instanceof Integer ordinal) || ordinal < 0);
	}

	/** The one method the census proves a carrier renamed {@code named}'s whole body to, still called; else null. */
	private static MethodNode renamedBody(ClassNode target, MethodNode named, Ecosystem ecosystem) {
		MethodNode found = null;
		for (MethodNode candidate : target.methods) {
			if (candidate == named || !candidate.desc.equals(named.desc)
					|| ((candidate.access ^ named.access) & Opcodes.ACC_STATIC) != 0) continue;
			List<CarrierRenames.Row> rows = CarrierRenames.find(target.name, named.name + named.desc, candidate.name + candidate.desc, ecosystem);
			if (rows.isEmpty() || CarrierRenames.kind(rows) != CarrierRenames.Kind.RENAME || !CarrierRenames.called(rows)
					|| !CarrierRenames.called(target, candidate)) continue;
			if (found != null) return null;
			found = candidate;
		}
		return found;
	}

	/**
	 * Whether {@code named} itself calls {@code renamed}: it is a dispatcher in front of the body, whose runs that go on go
	 * through the body. A method that hands the body to a later continuation instead (NeoForge's
	 * {@code startConfiguration} sends its queries and leaves {@code runConfiguration} to the reply) is not, and an edge of
	 * it keeps meaning the method itself.
	 */
	static boolean forwardsTo(ClassNode target, MethodNode named, MethodNode renamed) {
		if (named.instructions == null) return false;
		for (AbstractInsnNode instruction : named.instructions) {
			if (instruction instanceof MethodInsnNode call && call.owner.equals(target.name) && call.name.equals(renamed.name)
					&& call.desc.equals(renamed.desc)) return true;
		}
		return false;
	}

	/** Whether a method of {@code target} other than {@code named} calls {@code renamed}, or takes a handle to it. */
	static boolean runsWithoutPassingThrough(ClassNode target, MethodNode renamed, MethodNode named) {
		for (MethodNode method : target.methods) {
			if (method == named || method == renamed || method.instructions == null) continue;
			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode call && call.owner.equals(target.name) && call.name.equals(renamed.name)
						&& call.desc.equals(renamed.desc)) return true;
				if (instruction instanceof InvokeDynamicInsnNode dynamic) {
					for (Object argument : dynamic.bsmArgs) {
						if (argument instanceof Handle handle && handle.getOwner().equals(target.name) && handle.getName().equals(renamed.name)
								&& handle.getDesc().equals(renamed.desc)) return true;
					}
				}
			}
		}
		return false;
	}
}
