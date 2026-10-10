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
 *   <li>a {@code @Redirect} at the row's call, its selectors binding the row's method and nothing else, one point (its ordinal mapped by the
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
 *
 * <p>The redirect is read as Mixin reads it, never by its spelling: its selectors by the method they bind (in the class
 * the mod was compiled against, and the same method in the merged one), its point and its slice's start by the member each
 * names in the method it was written for ({@link MixinCallbackShape#member}) — whitespace, a dotted owner, and a target
 * without its owner or descriptor that selects only that member's instructions there are the same redirect.
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

	/** Observations derived on demand; never a matching policy. */
	private static final List<Site> observed = new java.util.concurrent.CopyOnWriteArrayList<>();
	public static final List<Site> SITES = java.util.Collections.unmodifiableList(observed);

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
        return adapt(mixin, targets, NativeGameReferences::reference, log);
    }

    public static int adapt(ClassNode mixin, Function<String, ClassNode> targets,
            java.util.function.BiFunction<Ecosystem, String, ClassNode> references) {
        return adapt(mixin, targets, references, true);
    }

    private static int adapt(ClassNode mixin, Function<String, ClassNode> targets,
            java.util.function.BiFunction<Ecosystem, String, ClassNode> references, boolean log) {
        if (!enabled() || mixin == null || mixin.methods == null || targets == null) return 0;
        Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
        List<String> owners = MixinFit.mixinTargets(mixin);
        if (ecosystem == null || owners.size() != 1) return 0;
        ClassNode target = targets.apply(owners.getFirst()), source = references.apply(ecosystem, owners.getFirst());
        if (target == null || source == null) return 0;
        int moved = 0;
        for (MethodNode handler : List.copyOf(mixin.methods)) {
            AnnotationNode redirect = MixinFit.injectorOf(handler);
            if (redirect == null || !REDIRECT.equals(redirect.desc)) continue;
            Site site = derive(target, source, ecosystem, handler, redirect);
            if (site != null && hostHasTheSiteShape(target, site) && move(mixin, target, handler, redirect, site, log)) {
                observed.add(site); moved++;
            }
        }
        return moved;
    }

    /** A source occurrence must have one guarded, result-consuming counterpart with the same data providers. */
    static Site derive(ClassNode target, ClassNode source, Ecosystem ecosystem, MethodNode handler, AnnotationNode redirect) {
        List<AnnotationNode> points = MixinFit.atNodes(redirect);
        if (points.size() != 1) return null;
        // The method the redirect was written for: the one its selectors bind in the class the mod was compiled against.
        MethodNode original = MixinTargetSelectors.one(handler, source);
        if (original == null) return null;
        AnnotationNode at = points.getFirst(); String wanted = MixinCallbackShape.member(at, original);
        MixinFit.Member member = MixinFit.parseMember(wanted); if (member == null || member.owner() == null || member.desc() == null || !member.desc().startsWith("(")) return null;
        MethodNode current = NativeCallChanges.method(target, original.name + original.desc);
        if (current == null || ((current.access ^ original.access) & Opcodes.ACC_STATIC) != 0) return null;
        List<NativeCallChanges.Site> before = NativeCallChanges.sites(source, original).stream().filter(s -> is(s.call(), member)).toList();
        List<NativeCallChanges.Site> after = NativeCallChanges.sites(target, current);
        if (before.isEmpty() || after.stream().anyMatch(s -> is(s.call(), member))) return null;
        String slice = sourceSlice(redirect, original); if (MixinFit.value(redirect, "slice") != null && slice == null) return null;
        if (slice != null) before = before.stream().filter(s -> readsBefore(original, s.call(), slice)).toList();
        if (before.isEmpty()) return null;
        List<NativeCallChanges.Site> matched = new ArrayList<>(); int[] from = null;
        for (NativeCallChanges.Site old : before) {
            List<NativeCallChanges.Site> counterparts = after.stream().filter(candidate -> {
                MethodInsnNode call=candidate.call();
                if (call.name.equals("<init>") || NativeCallChanges.sites(source, original).stream().anyMatch(s -> NativeCallChanges.member(s.call()).equals(NativeCallChanges.member(call)))) return false;
                Type was = Type.getReturnType(old.call().desc), now = Type.getReturnType(call.desc);
                if(!(was.equals(now) || was.getSort() == Type.OBJECT && now.getSort() == Type.OBJECT && narrowedTo(old.call(), now)))return false;
                int[] projection=NativeCallChanges.carried(old.operands(),candidate.operands());
                if(projection==null||!providerCoverage(old.operands(),candidate.operands()))return false;
                return sameContext(source,original,old,target,current,candidate,projection);
            }).toList();
            NativeCallChanges.Site replacement=counterparts.size()==1?counterparts.getFirst():null;
            if (replacement == null) return null;
            int[] projection = NativeCallChanges.carried(old.operands(), replacement.operands());
            if (projection == null || !providerCoverage(old.operands(), replacement.operands())) return null;
            if (from != null && !java.util.Arrays.equals(from, projection)) return null;
            from = projection; matched.add(replacement);
        }
        String replacement = NativeCallChanges.member(matched.getFirst().call());
        if (matched.stream().anyMatch(s -> !NativeCallChanges.member(s.call()).equals(replacement))) return null;
        int count = (int) after.stream().filter(s -> NativeCallChanges.member(s.call()).equals(replacement)).count();
        int[] ordinals = matched.stream().mapToInt(NativeCallChanges.Site::ordinal).toArray();
        return new Site(target.name, current.name + current.desc, wanted, before.getFirst().call().getOpcode(), replacement,
            matched.getFirst().call().getOpcode(), count, ordinals, List.of(), slice, from, Set.of(ecosystem),
            "the actual native occurrences have unique current CFG/result counterparts and their operand providers are conserved; the wrapper forwards the native operation");
    }
    private static boolean sameContext(ClassNode source,MethodNode original,NativeCallChanges.Site old,ClassNode target,
            MethodNode current,NativeCallChanges.Site replacement,int[] projection){
        Set<NativeCallChanges.Expr> providers=new java.util.HashSet<>(),originalProviders=new java.util.HashSet<>();for(var expression:replacement.operands())collectProviders(expression,providers);for(var expression:old.operands())collectProviders(expression,originalProviders);
        var before=NativeCallChanges.context(source,original,old.call(),e->normalizeOperation(e,NativeCallChanges.member(old.call()),projection,false),originalProviders);
        var after=NativeCallChanges.context(target,current,replacement.call(),e->normalizeOperation(e,NativeCallChanges.member(replacement.call()),projection,true),providers);
        return before!=null&&before.equals(after)&&before.guards()!=null;
    }
    private static void collectProviders(NativeCallChanges.Expr expression,Set<NativeCallChanges.Expr> providers){
        providers.add(expression);expression.inputs().forEach(input->collectProviders(input,providers));
    }
    private static NativeCallChanges.Expr normalizeOperation(NativeCallChanges.Expr expression,String member,int[] projection,boolean current){
        List<NativeCallChanges.Expr> inputs=expression.inputs().stream().map(e->normalizeOperation(e,member,projection,current)).toList();
        if(expression.kind().equals("call")&&expression.symbol().equals(member)){
            List<NativeCallChanges.Expr> kept=new ArrayList<>();for(int p=0;p<projection.length;p++)if(projection[p]>=0){int at=current?projection[p]:p;if(at>=inputs.size())return new NativeCallChanges.Expr("unknown","projection");kept.add(inputs.get(at));}
            return new NativeCallChanges.Expr("replacement-operation","",kept);
        }return new NativeCallChanges.Expr(expression.kind(),expression.symbol(),inputs);
    }
    private static boolean narrowedTo(MethodInsnNode old, Type currentReturn) {
        AbstractInsnNode next = nextReal(old);
        return next instanceof org.objectweb.asm.tree.TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
            && cast.desc.equals(currentReturn.getInternalName());
    }
    private static boolean providerCoverage(List<NativeCallChanges.Expr> old, List<NativeCallChanges.Expr> current) {
        for (NativeCallChanges.Expr expression : old) {
            if (expression.kind().equals("static-field") || expression.kind().equals("constant") || expression.kind().equals("literal")) continue;
            if (!containsProvider(expression, current)) return false;
        }
        return true;
    }
    private static boolean containsProvider(NativeCallChanges.Expr expression, List<NativeCallChanges.Expr> providers) {
        if (providers.contains(expression)) return true;
        for (NativeCallChanges.Expr provider : providers) if (contains(provider, expression)) return true;
        return expression.inputs().stream().anyMatch(input -> containsProvider(input, providers));
    }
    private static boolean contains(NativeCallChanges.Expr expression, NativeCallChanges.Expr part) {
        return expression.equals(part) || expression.inputs().stream().anyMatch(input -> contains(input, part));
    }
    /** The field the redirect's one slice starts from, {@code Lowner;name:desc}, as its {@code from} names it in {@code original}. */
    private static String sourceSlice(AnnotationNode redirect, MethodNode original) {
        Object value = MixinFit.value(redirect, "slice"); if (value == null) return null;
        List<?> slices = value instanceof List<?> list ? list : List.of(value);
        if (slices.size() != 1 || !(slices.getFirst() instanceof AnnotationNode slice)) return null;
        if (!(MixinFit.value(slice, "from") instanceof AnnotationNode from) || !"FIELD".equals(MixinFit.value(from, "value"))) return null;
        String member = MixinCallbackShape.member(from, original);
        return member != null && member.indexOf(':') > 0 ? member : null;
    }
    private static boolean readsBefore(MethodNode method, AbstractInsnNode call, String member) {
        for (AbstractInsnNode i : method.instructions) {
            if (i == call) return false;
            if (i instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                && ("L" + field.owner + ";" + field.name + ":" + field.desc).equals(member)) return true;
        }
        return false;
    }
    private static AbstractInsnNode nextReal(AbstractInsnNode i) { i = i.getNext(); while (i != null && i.getOpcode() < 0) i = i.getNext(); return i; }

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

	private static boolean move(ClassNode mixin, ClassNode target, MethodNode handler, AnnotationNode redirect, Site site, boolean log) {
		if (annotated(handler.visibleAnnotations, GROUP) || annotated(handler.invisibleAnnotations, GROUP)) return false;
		if (parameterAnnotated(handler.visibleParameterAnnotations) || parameterAnnotated(handler.invisibleParameterAnnotations)) return false;
		for (int i = 0; i + 1 < redirect.values.size(); i += 2) if (!INJECTOR_KEYS.contains(redirect.values.get(i))) return false;
		String name = site.method().substring(0, site.method().indexOf('('));
		// Bound, in the merged class, to the row's method itself, however the selectors are written.
		if (!MixinCallbackShape.binds(handler, target, site.method())) return false;
		List<AnnotationNode> points = MixinFit.atNodes(redirect);
		if (points.size() != 1) return false;
		AnnotationNode at = points.getFirst();
		for (int i = 0; i + 1 < at.values.size(); i += 2) if (!AT_KEYS.contains(at.values.get(i))) return false;
		// The row's member is the one the point names in the method it was written for (derive): its spelling may omit
		// the owner or descriptor, never contradict them.
		if (!"INVOKE".equals(MixinFit.value(at, "value")) || !MixinCallbackShape.covers(at, site.vanilla())) return false;
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
		// The point now counts the merged body's calls; no later pass may read it as a native count.
		CurrentBodyOrdinals.mark(handler);
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
		return "FIELD".equals(MixinFit.value(from, "value")) && MixinCallbackShape.covers(from, site.slice())
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
