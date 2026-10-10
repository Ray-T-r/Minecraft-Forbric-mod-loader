/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.selectors.ElementNode;
import org.spongepowered.asm.mixin.injection.selectors.ITargetSelector;
import org.spongepowered.asm.mixin.injection.selectors.MemberMatcher;
import org.spongepowered.asm.mixin.injection.struct.MemberInfo;

/**
 * Which methods of a target class an injector's {@code method} selectors bind, decided the way Mixin decides it, never
 * by comparing how the selector is spelled. {@code "baseTick"}, {@code "baseTick()V"},
 * {@code "Lnet/minecraft/world/entity/LivingEntity;baseTick()V"}, {@code "net.minecraft.world.entity.LivingEntity.baseTick()V"},
 * {@code "baseTick*"} and {@code "/^baseTick$/"} are one target wherever Mixin binds each of them to that one method;
 * {@code "setBlock"} is not {@code setBlock(BlockPos, BlockState, int, int)} in a class that declares the three-argument
 * overload first, because Mixin binds a bare name to the first method of that name.
 *
 * <p>Each selector is parsed, validated and matched by Mixin's own selector classes ({@link MemberInfo}, and
 * {@link MemberMatcher} for a selector ending in {@code /}), dispatched as {@code TargetSelector.parse} dispatches; the
 * loop over the class's declared methods is Mixin 0.8.7's {@code TargetSelectors.findRootTargets}: a selector naming an
 * owner other than the target is refused ({@code MemberInfo.attach}); a selector without a quantifier is configured to
 * match ONE method, the first declared; only exact matches count (the case-insensitive near miss is Mixin's permissive
 * pass, which runs only under refmap remapping); a selector allowed several matches skips static methods for an
 * instance handler and methods another mixin merged in, though they still count toward its maximum; and a selector
 * matching fewer than its minimum fails the injector. Selectors that bind the same method bind it once.
 *
 * <p>Null where Mixin would refuse the injector's selectors, and for what this does not model: a dynamic
 * {@code @Desc}/{@code @selector} target and a {@code ->} nested selection.
 */
final class MixinTargetSelectors {
	private static final String MERGED = "Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;";
	/** MemberMatcher's own pattern for the {@code owner=/…/ name=/…/ desc=/…/} parts of a pattern selector. */
	private static final Pattern REGEX = Pattern.compile("((owner|name|desc)\\s*=\\s*)?/(.*?)(?<!\\\\)/");

	private MixinTargetSelectors() { }

	/** The selectors the injector carries in {@code method}, as Mixin reads them (blank entries included); null for a dynamic {@code target}. */
	static List<String> selectors(MethodNode handler) {
		AnnotationNode injector = handler == null ? null : MixinFit.injectorOf(handler);
		if (injector == null || MixinFit.value(injector, "target") != null) return null;
		Object value = MixinFit.value(injector, "method");
		List<String> selectors = new ArrayList<>();
		if (value instanceof String single) selectors.add(single);
		else if (value instanceof List<?> list) for (Object element : list) if (element instanceof String selector) selectors.add(selector);
		return selectors;
	}

	/** The methods of {@code target} the handler's injector binds, in Mixin's order; null as described on the class. */
	static List<MethodNode> bound(MethodNode handler, ClassNode target) {
		List<String> selectors = selectors(handler);
		return selectors == null ? null : bound(selectors, target, (handler.access & Opcodes.ACC_STATIC) != 0);
	}

	/** The methods of {@code target} that {@code selectors} bind for a handler of the given staticness. */
	static List<MethodNode> bound(List<String> selectors, ClassNode target, boolean staticHandler) {
		if (selectors == null || selectors.isEmpty() || target == null || target.methods == null) return null;
		Set<ITargetSelector> parsed = new LinkedHashSet<>();
		for (String selector : selectors) {
			ITargetSelector one = parse(selector, target);
			if (one == null) return null;
			parsed.add(one);
		}
		Set<MethodNode> bound = new LinkedHashSet<>();
		for (ITargetSelector selector : parsed) {
			ITargetSelector member = selector.configure(ITargetSelector.Configure.SELECT_MEMBER);
			int matches = 0, max = member.getMaxMatchCount();
			for (MethodNode method : target.methods) {
				if (!member.match(ElementNode.of(target, method)).isExactMatch()) continue;
				matches++;
				if (max <= 1 || (staticHandler || (method.access & Opcodes.ACC_STATIC) == 0) && !merged(method)) bound.add(method);
				if (matches >= max) break;
			}
			if (matches < member.getMinMatchCount()) return null;
		}
		return List.copyOf(bound);
	}

	/** The one method of {@code target} the handler's injector binds; null when it binds none, several, or cannot be judged. */
	static MethodNode one(MethodNode handler, ClassNode target) {
		List<MethodNode> bound = bound(handler, target);
		return bound != null && bound.size() == 1 ? bound.getFirst() : null;
	}

	/** Whether the handler's injector binds the method {@code nameAndDesc} of {@code target}, and nothing else. */
	static boolean bindsOnly(MethodNode handler, ClassNode target, String nameAndDesc) {
		MethodNode bound = one(handler, target);
		return bound != null && (bound.name + bound.desc).equals(nameAndDesc);
	}

	/**
	 * What one selector can reach in a class: every declared method it matches exactly (before its quantifier picks among
	 * them), whether it binds at most one — Mixin's default for a selector without a quantifier, which then binds the
	 * FIRST declared match, so which method that is depends on declaration order — and the {@code name + desc} it spells
	 * when it pins both (null otherwise), which is what it names in a class that does not declare it.
	 */
	record Reach(List<MethodNode> matched, boolean single, String spelled) { }

	/** Each selector's {@link Reach} in {@code target}, in the injector's order; null as {@link #bound} is. */
	static List<Reach> reach(MethodNode handler, ClassNode target) {
		List<String> selectors = selectors(handler);
		if (selectors == null || selectors.isEmpty() || target == null || target.methods == null) return null;
		List<Reach> out = new ArrayList<>();
		for (String selector : selectors) {
			ITargetSelector one = parse(selector, target);
			if (one == null) return null;
			ITargetSelector member = one.configure(ITargetSelector.Configure.SELECT_MEMBER);
			List<MethodNode> matched = new ArrayList<>();
			for (MethodNode method : target.methods) if (member.match(ElementNode.of(target, method)).isExactMatch()) matched.add(method);
			String spelled = one instanceof MemberInfo info && member.getMaxMatchCount() <= 1 && info.getName() != null
					&& info.getDesc() != null && info.getDesc().startsWith("(") ? info.getName() + info.getDesc() : null;
			out.add(new Reach(List.copyOf(matched), member.getMaxMatchCount() <= 1, spelled));
		}
		return out;
	}

	/**
	 * The one method the handler binds in {@code target} where that does not hang on declaration order: no selector that
	 * binds one method could match another of the class. A bare name shared by overloads binds whichever is declared first,
	 * which is the merge's order, not necessarily the one the mod was compiled against; null then, and as {@link #one}.
	 */
	static MethodNode unambiguous(MethodNode handler, ClassNode target) {
		List<Reach> reach = reach(handler, target);
		if (reach == null) return null;
		for (Reach r : reach) if (r.single() && r.matched().size() > 1) return null;
		return one(handler, target);
	}

	/**
	 * For a caller that has no class to bind against: whether every selector of the handler, as Mixin parses it, can only
	 * name {@code nameAndDesc} of {@code owner} — the same name, the owner and the descriptor wherever it gives them, and
	 * no quantifier or pattern. Whitespace, a dotted owner or an owner prefix are then the same selector; a bare name is
	 * read as the method of that name, which a class at hand would decide ({@link #bindsOnly}).
	 */
	static boolean spellsOnly(MethodNode handler, String owner, String nameAndDesc) {
		List<String> selectors = selectors(handler);
		int paren = nameAndDesc.indexOf('(');
		if (selectors == null || selectors.isEmpty() || paren < 0) return false;
		String name = nameAndDesc.substring(0, paren), desc = nameAndDesc.substring(paren);
		for (String selector : selectors) {
			if (!(parse(selector, null) instanceof MemberInfo info) || info.configure(ITargetSelector.Configure.SELECT_MEMBER).getMaxMatchCount() > 1
					|| !name.equals(info.getName())
					|| info.getOwner() != null && !info.getOwner().equals(owner) || info.getDesc() != null && !info.getDesc().equals(desc)) return false;
		}
		return true;
	}

	/**
	 * The member ({@code name + desc}) the handler was written for in a class the merged game may no longer declare it in:
	 * the one method its injector binds in {@code nativeClass}, the class the mod was compiled against. Where that class is
	 * missing or binds nothing, only a selector pinning the descriptor decides it — every selector Mixin accepts, its owner
	 * (if any) the target, spelling one name and one method descriptor — since which overload a bare name, a wildcard or a
	 * pattern binds depends on the declaring class. Null when neither decides it.
	 */
	static String nativeMember(MethodNode handler, ClassNode nativeClass, String owner) {
		if (nativeClass != null) {
			MethodNode bound = one(handler, nativeClass);
			if (bound != null) return bound.name + bound.desc;
		}
		return spelled(handler, owner);
	}

	/** The one member every selector of the handler spells by name and method descriptor; null otherwise. */
	private static String spelled(MethodNode handler, String owner) {
		List<String> selectors = selectors(handler);
		if (selectors == null || selectors.isEmpty()) return null;
		String member = null;
		for (String selector : selectors) {
			ITargetSelector parsed = parse(selector, null);
			if (!(parsed instanceof MemberInfo info) || info.getName() == null || info.getDesc() == null || !info.getDesc().startsWith("(")
					|| info.getOwner() != null && !info.getOwner().equals(owner)) return null;
			String spelled = info.getName() + info.getDesc();
			if (member != null && !member.equals(spelled)) return null;
			member = spelled;
		}
		return member;
	}

	/**
	 * One selector as Mixin parses and validates it for {@code target}; null when Mixin rejects it (malformed, or an owner
	 * other than the target) or it is a form this does not model. {@code target} null skips the owner check.
	 */
	private static ITargetSelector parse(String selector, ClassNode target) {
		String trimmed = selector.trim();
		ITargetSelector parsed;
		try {
			if (trimmed.endsWith("/")) {
				// MemberMatcher prints a malformed pattern's stack trace while parsing; Mixin reports it when it applies.
				Matcher patterns = REGEX.matcher(trimmed);
				while (patterns.find()) Pattern.compile(patterns.group(3));
				parsed = MemberMatcher.parse(trimmed, null);
			}
			else if (trimmed.startsWith("@")) return null;
			else parsed = MemberInfo.parse(trimmed, null);
			parsed = parsed.validate();
		} catch (Exception invalid) {
			return null;
		}
		if (parsed instanceof MemberInfo info) {
			if (info.next() != null) return null;
			if (target != null && info.getOwner() != null && !info.getOwner().equals(target.name)) return null;
		}
		return parsed;
	}

	private static boolean merged(MethodNode method) {
		return method.visibleAnnotations != null && method.visibleAnnotations.stream().anyMatch(a -> MERGED.equals(a.desc));
	}
}
