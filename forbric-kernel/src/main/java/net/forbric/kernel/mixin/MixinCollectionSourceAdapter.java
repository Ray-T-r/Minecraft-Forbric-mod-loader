/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import net.forbric.kernel.util.ForbricLog;

/** Migrates a static List field redirect to a proved copy-prefix/concat Stream getter.
 * Prefix replacement is structural, never chosen by names, return types alone or a guessed union of entries. */
public final class MixinCollectionSourceAdapter {
	public static final String PROPERTY = "forbric.mixinCollectionSources";
	private static final String LIST = "Ljava/util/List;", STREAM = "Ljava/util/stream/Stream;";
	private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
	private static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
	private static final String OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
	private static final String GROUP = "Lorg/spongepowered/asm/mixin/injection/Group;";
	private static final String RUNTIME = "net/forbric/kernel/boot/KernelCollectionSources";
	private static final Handle LAMBDA = new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory", "metafactory",
			"(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false);
	private record Field(String owner, String name, String desc) { }
	private record StreamSources(List<Field> fields) { }
	private enum Origin { DIRECT, COPY, OTHER, UNKNOWN }
	private record Plan(Field original, MethodInsnNode getter, String proof) { }
	private MixinCollectionSourceAdapter() { }

	public static boolean enabled() { return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on")); }

	/** Providers must include the getter holder's complete instructions and its class initializer. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> classes) {
		if (!enabled() || mixin == null) return 0;
		List<String> targets = MixinFit.mixinTargets(mixin);
		if (targets.size() != 1) return 0;
		ClassNode target = classes.apply(targets.getFirst());
		if (target == null) return 0;
		List<MethodNode> added = new ArrayList<>();
		for (MethodNode handler : new ArrayList<>(mixin.methods)) {
			AnnotationNode injector = MixinFit.injectorOf(handler);
			Plan plan = plan(handler, injector, target, classes);
			if (plan == null) continue;
			added.add(wrap(mixin, handler, injector, plan));
			ForbricLog.info("[Forbric/Mixin] %s.%s follows a proved collection copy-prefix into %s.%s; the original redirect replaces only that prefix and native suffixes remain",
					mixin.name.replace('/', '.'), added.getLast().name, plan.getter().owner.replace('/', '.'), plan.getter().name);
		}
		mixin.methods.addAll(added);
		return added.size();
	}

	private static Plan plan(MethodNode handler, AnnotationNode injector, ClassNode target, Function<String, ClassNode> classes) {
		if (injector == null || !REDIRECT.equals(injector.desc) || !handler.desc.equals("()" + LIST)
				|| (handler.access & Opcodes.ACC_STATIC) == 0
				|| (handler.access & (Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0
				|| grouped(handler) || injectionAnnotations(handler) != 1 || MixinFit.value(injector, "slice") != null) return null;
		List<AnnotationNode> ats = MixinFit.atNodes(injector);
		if (ats.size() != 1 || !"FIELD".equals(MixinFit.value(ats.getFirst(), "value"))) return null;
		AnnotationNode at = ats.getFirst();
		Object opcode = MixinFit.value(at, "opcode"), ordinal = MixinFit.value(at, "ordinal");
		if (opcode instanceof Number n && n.intValue() != -1 && n.intValue() != Opcodes.GETSTATIC) return null;
		if (ordinal instanceof Number n && n.intValue() > 0 || MixinFit.value(at, "shift") != null) return null;
		Field original = field(MixinFit.asString(MixinFit.value(at, "target")));
		if (original == null || !LIST.equals(original.desc())) return null;
		ClassNode originalOwner = classes.apply(original.owner());
		if (originalOwner == null || originalOwner.fields.stream().noneMatch(f -> f.name.equals(original.name())
				&& f.desc.equals(original.desc()) && (f.access & Opcodes.ACC_STATIC) != 0)) return null;
		List<String> selectors = MixinFit.stringList(MixinFit.value(injector, "method"));
		if (selectors.size() != 1) return null;
		List<MethodNode> bodies = target.methods.stream().filter(m -> selectors.getFirst().equals(m.name)
				|| selectors.getFirst().equals(m.name + m.desc)).toList();
		if (bodies.size() != 1) return null;
		List<Plan> candidates = new ArrayList<>();
		for (var insn : bodies.getFirst().instructions) {
			if (insn instanceof FieldInsnNode f && same(f, original)) return null;
			if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESTATIC || !call.desc.equals("()" + STREAM)) continue;
			ClassNode owner = classes.apply(call.owner);
			if (owner == null) continue;
			MethodNode getter = owner.methods.stream().filter(m -> m.name.equals(call.name) && m.desc.equals(call.desc)).findFirst().orElse(null);
			List<Field> sources = getter == null ? null : streamSources(getter);
			if (sources == null || sources.isEmpty()) continue;
			Provenance provenance = new Provenance(original, classes);
			if (provenance.origin(sources.getFirst()) != Origin.COPY) continue;
			boolean unique = true;
			Set<Field> seen = new HashSet<>(); seen.add(sources.getFirst());
			for (Field source : sources.subList(1, sources.size())) {
				Origin origin = provenance.origin(source);
				if (!seen.add(source) || origin != Origin.OTHER) { unique = false; break; }
			}
			if (unique) candidates.add(new Plan(original, call, "proved original copy prefix and " + (sources.size() - 1) + " independent List stream source(s)"));
		}
		return candidates.size() == 1 ? candidates.getFirst() : null;
	}

	/** A small closed grammar, interpreted without executing the getter. */
	private static List<Field> streamSources(MethodNode getter) {
		if ((getter.access & Opcodes.ACC_STATIC) == 0 || (getter.access & (Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0
				|| !getter.tryCatchBlocks.isEmpty()) return null;
		List<Object> stack = new ArrayList<>(); Map<Integer,Object> locals = new HashMap<>();
		try {
			for (var insn : getter.instructions) {
				if (insn.getOpcode() < 0) continue;
				if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && LIST.equals(f.desc)) {
					stack.add(new Field(f.owner, f.name, f.desc));
				} else if (insn instanceof VarInsnNode v && v.getOpcode() == Opcodes.ASTORE) {
					locals.put(v.var, pop(stack));
				} else if (insn instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && locals.containsKey(v.var)) {
					stack.add(locals.get(v.var));
				} else if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEINTERFACE
						&& Set.of("java/util/List", "java/util/Collection").contains(call.owner) && call.name.equals("stream") && call.desc.equals("()" + STREAM)) {
					Object receiver = pop(stack); if (!(receiver instanceof Field f)) return null;
					stack.add(new StreamSources(List.of(f)));
				} else if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
						&& call.owner.equals("java/util/stream/Stream") && call.name.equals("concat") && call.desc.equals("(" + STREAM + STREAM + ")" + STREAM)) {
					Object right = pop(stack), left = pop(stack); if (!(left instanceof StreamSources a) || !(right instanceof StreamSources b)) return null;
					List<Field> fields = new ArrayList<>(a.fields()); fields.addAll(b.fields()); stack.add(new StreamSources(List.copyOf(fields)));
				} else if (insn.getOpcode() == Opcodes.ARETURN) {
					Object returned = pop(stack); if (!stack.isEmpty() || !(returned instanceof StreamSources s)) return null;
					// No code, return or effect after the one return.
					for (var after = insn.getNext(); after != null; after = after.getNext()) if (after.getOpcode() >= 0) return null;
					return s.fields();
				} else return null;
			}
		} catch (IndexOutOfBoundsException malformed) { return null; }
		return null;
	}
	private static Object pop(List<Object> stack) { return stack.removeLast(); }

	private static final class StableSources extends SourceInterpreter {
		StableSources() { super(Opcodes.ASM9); }
		@Override public SourceValue copyOperation(AbstractInsnNode instruction, SourceValue value) { return value; }
	}
	private static final class Provenance {
		private final Field original;
		private final Function<String, ClassNode> classes;
		private final Map<Field, Origin> cached = new HashMap<>();
		private final Set<Field> visiting = new HashSet<>();
		Provenance(Field original, Function<String, ClassNode> classes) { this.original = original; this.classes = classes; }
		Origin origin(Field source) {
			if (source.equals(original)) return Origin.DIRECT;
			if (cached.containsKey(source)) return cached.get(source);
			if (!visiting.add(source)) return Origin.UNKNOWN;
			Origin result = Origin.UNKNOWN;
			try {
				ClassNode owner = classes.apply(source.owner());
				if (owner == null) return Origin.UNKNOWN;
				MethodNode initializer = owner.methods.stream().filter(m -> m.name.equals("<clinit>")).findFirst().orElse(null);
				if (initializer == null || !initializer.tryCatchBlocks.isEmpty()) return Origin.UNKNOWN;
				for (var insn : initializer.instructions) if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) return Origin.UNKNOWN;
				Frame<SourceValue>[] frames = new Analyzer<>(new StableSources()).analyze(owner.name, initializer);
				List<FieldInsnNode> writers = new ArrayList<>();
				for (var insn : initializer.instructions) if (insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTSTATIC && same(f, source)) writers.add(f);
				if (writers.size() != 1) return Origin.UNKNOWN;
				Frame<SourceValue> frame = frames[initializer.instructions.indexOf(writers.getFirst())];
				result = value(frame.getStack(frame.getStackSize() - 1), initializer, frames, new HashSet<>());
				cached.put(source, result); return result;
			} catch (AnalyzerException | RuntimeException invalid) { return Origin.UNKNOWN; }
			finally { visiting.remove(source); }
		}
		private Origin value(SourceValue source, MethodNode method, Frame<SourceValue>[] frames, Set<AbstractInsnNode> active) {
			if (source == null || source.insns.size() != 1) return Origin.UNKNOWN;
			AbstractInsnNode instruction = source.insns.iterator().next();
			if (!active.add(instruction)) return Origin.UNKNOWN;
			try {
				if (instruction instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && LIST.equals(f.desc)) return origin(new Field(f.owner, f.name, f.desc));
				if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC) {
					if (call.owner.equals("java/util/Collections") && call.name.equals("unmodifiableList") && call.desc.equals("(" + LIST + ")" + LIST)) {
						Frame<SourceValue> before = frames[method.instructions.indexOf(call)];
						return value(before.getStack(before.getStackSize() - 1), method, frames, active);
					}
					// List.of creates a different list of its arguments; it never flattens a source collection.
					// This only proves an independent suffix source, never a prefix replacement.
					if (call.owner.equals("java/util/List") && call.name.equals("of") && Type.getReturnType(call.desc).getDescriptor().equals(LIST)) return Origin.OTHER;
				}
				if (instruction instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW && allocation.desc.equals("java/util/ArrayList")) {
					List<MethodInsnNode> constructors = new ArrayList<>();
					for (var insn : method.instructions) if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
							&& call.owner.equals(allocation.desc) && call.name.equals("<init>")) {
						Frame<SourceValue> before = frames[method.instructions.indexOf(call)];
						int receiver = before.getStackSize() - Type.getArgumentTypes(call.desc).length - 1;
						if (receiver >= 0 && before.getStack(receiver).insns.equals(Set.of(allocation))) constructors.add(call);
					}
					if (constructors.size() != 1) return Origin.UNKNOWN;
					MethodInsnNode constructor = constructors.getFirst();
					if (constructor.desc.equals("()V")) return Origin.OTHER;
					if (!constructor.desc.equals("(Ljava/util/Collection;)V")) return Origin.UNKNOWN;
					Frame<SourceValue> before = frames[method.instructions.indexOf(constructor)];
					Origin from = value(before.getStack(before.getStackSize() - 1), method, frames, active);
					return from == Origin.DIRECT || from == Origin.COPY ? Origin.COPY : from == Origin.OTHER ? Origin.OTHER : Origin.UNKNOWN;
				}
				return Origin.UNKNOWN;
			} finally { active.remove(instruction); }
		}
	}

	private static MethodNode wrap(ClassNode mixin, MethodNode handler, AnnotationNode injector, Plan plan) {
		String originalName = handler.name, aside = MixinHandlerShim.asideName(mixin.name, handler.name, "$forbriccollection");
		MethodNode wrapper = new MethodNode(handler.access, originalName, "(L" + OPERATION + ";)" + STREAM, null, handler.exceptions.toArray(String[]::new));
		AnnotationNode wrapped = new AnnotationNode(WRAP); wrapped.values = new ArrayList<>(injector.values);
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(MixinFit.atNodes(injector).getFirst().values);
		set(at, "value", "INVOKE"); set(at, "target", "L" + plan.getter().owner + ";" + plan.getter().name + plan.getter().desc);
		if (MixinFit.value(at, "opcode") != null) set(at, "opcode", Opcodes.INVOKESTATIC);
		set(wrapped, "at", at);
		wrapper.visibleAnnotations = replace(handler.visibleAnnotations, injector, wrapped);
		wrapper.invisibleAnnotations = replace(handler.invisibleAnnotations, injector, wrapped);
		wrapper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		wrapper.instructions.add(new InsnNode(Opcodes.ICONST_0)); wrapper.instructions.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
		wrapper.instructions.add(new InvokeDynamicInsnNode("get", "(L" + OPERATION + ";[Ljava/lang/Object;)Ljava/util/function/Supplier;", LAMBDA,
				Type.getMethodType("()Ljava/lang/Object;"), new Handle(Opcodes.H_INVOKEINTERFACE, OPERATION, "call", "([Ljava/lang/Object;)Ljava/lang/Object;", true), Type.getMethodType("()" + STREAM)));
		wrapper.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, plan.original().owner(), plan.original().name(), plan.original().desc()));
		wrapper.instructions.add(new InvokeDynamicInsnNode("get", "()Ljava/util/function/Supplier;", LAMBDA,
				Type.getMethodType("()Ljava/lang/Object;"), new Handle(Opcodes.H_INVOKESTATIC, mixin.name, aside, handler.desc, (mixin.access & Opcodes.ACC_INTERFACE) != 0), Type.getMethodType("()" + LIST)));
		wrapper.instructions.add(new LdcInsnNode(mixin.name.replace('/', '.') + "#" + originalName + ": " + plan.proof()));
		wrapper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "replacePrefix", "(Ljava/util/function/Supplier;" + LIST + "Ljava/util/function/Supplier;Ljava/lang/String;)" + STREAM, false));
		wrapper.instructions.add(new InsnNode(Opcodes.ARETURN)); wrapper.maxLocals = 1; wrapper.maxStack = 6;
		handler.name = aside; handler.visibleAnnotations = without(handler.visibleAnnotations, injector); handler.invisibleAnnotations = without(handler.invisibleAnnotations, injector);
		return wrapper;
	}
	private static List<AnnotationNode> replace(List<AnnotationNode> annotations, AnnotationNode old, AnnotationNode replacement) {
		if (annotations == null) return null; List<AnnotationNode> copy = new ArrayList<>();
		for (AnnotationNode annotation : annotations) copy.add(annotation == old ? replacement : annotation); return copy;
	}
	private static List<AnnotationNode> without(List<AnnotationNode> annotations, AnnotationNode old) {
		return annotations == null ? null : annotations.stream().filter(a -> a != old).toList();
	}
	private static boolean grouped(MethodNode handler) {
		return handler.visibleAnnotations != null && handler.visibleAnnotations.stream().anyMatch(a -> GROUP.equals(a.desc))
				|| handler.invisibleAnnotations != null && handler.invisibleAnnotations.stream().anyMatch(a -> GROUP.equals(a.desc));
	}
	private static long injectionAnnotations(MethodNode handler) {
		List<AnnotationNode> annotations = new ArrayList<>();
		if (handler.visibleAnnotations != null) annotations.addAll(handler.visibleAnnotations);
		if (handler.invisibleAnnotations != null) annotations.addAll(handler.invisibleAnnotations);
		return annotations.stream().filter(a -> a.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")
				|| a.desc.startsWith("Lcom/llamalad7/mixinextras/injector/")).count();
	}
	private static boolean same(FieldInsnNode instruction, Field field) {
		return instruction.owner.equals(field.owner()) && instruction.name.equals(field.name()) && instruction.desc.equals(field.desc());
	}
	private static Field field(String selector) {
		if (selector == null || !selector.startsWith("L")) return null;
		int semicolon = selector.indexOf(';'), colon = selector.indexOf(':', semicolon);
		return semicolon > 1 && colon > semicolon + 1 ? new Field(selector.substring(1, semicolon), selector.substring(semicolon + 1, colon), selector.substring(colon + 1)) : null;
	}
	private static void set(AnnotationNode annotation, String key, Object value) {
		if (annotation.values == null) annotation.values = new ArrayList<>();
		for (int i = 0; i < annotation.values.size(); i += 2) if (key.equals(annotation.values.get(i))) { annotation.values.set(i + 1, value); return; }
		annotation.values.add(key); annotation.values.add(value);
	}
}
