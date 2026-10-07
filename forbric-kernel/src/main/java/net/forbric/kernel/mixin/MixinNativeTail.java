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

package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.transform.VanillaEarlyReturns;
import net.forbric.kernel.util.ForbricLog;

/**
 * Keeps a NeoForge or MinecraftForge mod's {@code @At("TAIL")} on the paths it ran on before
 * {@link VanillaEarlyReturns} gave a method back vanilla's early returns.
 *
 * <p>Those mods were compiled against their carrier's recompiled body, where the guard clause is folded into the last
 * return and TAIL therefore runs on every path — the early-exit ones included. A mod pairing a HEAD push with a TAIL
 * pop is balanced there and would not be once the early paths return on their own. After the split, the returns those
 * paths reach are the blocks the split placed immediately before the tail: returns {@code inline} to
 * {@code inline + blocks}, the tail last. So such a mod's TAIL — and a {@code RETURN} whose ordinal named the old tail —
 * becomes exactly those returns: a plain {@code RETURN} when nothing returned before them, else one {@code RETURN} per
 * ordinal. Fabric mods keep TAIL as it is: vanilla's meaning is the one they were compiled against.
 *
 * <p>Stands down — leaving the vanilla meaning, and saying so — when the injector's {@code at} is a single
 * annotation that cannot carry several ordinals, when the methods one injector selects were split differently (or one
 * of them not at all: the rewrite would land on its returns too), or when ordinals counted on a method named by name
 * alone could land on another method of that name, should another mixin's {@code @Overwrite} reorder them.
 */
public final class MixinNativeTail {
	private static final String AT_DESC = "Lorg/spongepowered/asm/mixin/injection/At;";

	private MixinNativeTail() {
	}

	/** Rewrites eligible injectors of {@code mixin} in place; returns how many. {@code targets} must run the chain. */
	public static int adapt(ClassNode mixin, Function<String, ClassNode> targets) {
		if (!VanillaEarlyReturns.enabled() || mixin == null || mixin.methods == null || targets == null) return 0;
		Ecosystem ecosystem = MixinStubRebind.ecosystemOf(mixin.name);
		if (ecosystem != Ecosystem.NEOFORGE && ecosystem != Ecosystem.FORGE) return 0;
		List<String> owners = MixinFit.mixinTargets(mixin);
		if (owners.isEmpty()) return 0;

		int rewritten = 0;
		for (MethodNode handler : mixin.methods) {
			AnnotationNode injector = MixinFit.injectorOf(handler);
			if (injector == null) continue;
			List<AnnotationNode> ats = MixinFit.atNodes(injector);
			if (ats.stream().noneMatch(MixinNativeTail::mayNameTheOldTail)) continue;

			VanillaEarlyReturns.Split split = null;
			boolean agreed = true, unsplit = false, sibling = false;
			for (String owner : owners) {
				ClassNode target = targets.apply(owner);   // runs the chain, so a split it makes is recorded
				if (target == null) continue;
				for (String selector : MixinFit.stringList(MixinFit.value(injector, "method"))) {
					Bound bound = bind(owner, target, selector);
					if (bound == null) continue;
					if (bound.split() == null) {
						unsplit = true;
						continue;
					}
					if (split != null && !split.equals(bound.split())) agreed = false;
					split = bound.split();
					sibling |= bound.siblings();
				}
			}
			if (split == null) continue;
			String where = mixin.name.replace('/', '.') + "#" + handler.name;
			if (!agreed || unsplit) {
				ForbricLog.warn("[Forbric/EarlyReturns] %s selects methods whose early returns were restored differently; its "
						+ "TAIL keeps vanilla's meaning there", where);
				continue;
			}
			if (sibling && split.inlineReturns() > 0) {
				// Return ordinals counted on the method Mixin binds now would name nothing in its namesake, and an
				// @Overwrite elsewhere can make Mixin bind the namesake instead. A plain RETURN is safe on either.
				ForbricLog.warn("[Forbric/EarlyReturns] %s names its method by name alone, and the class has another of that "
						+ "name; its TAIL keeps vanilla's meaning rather than ordinals that could land on the other", where);
				continue;
			}
			if (rewrite(injector, ats, split, where)) rewritten++;
		}
		return rewritten;
	}

	private static boolean mayNameTheOldTail(AnnotationNode at) {
		String value = MixinFit.asString(MixinFit.value(at, "value"));
		return "TAIL".equals(value) || ("RETURN".equals(value) && MixinFit.value(at, "ordinal") instanceof Integer);
	}

	private static boolean rewrite(AnnotationNode injector, List<AnnotationNode> ats, VanillaEarlyReturns.Split split, String where) {
		Object at = MixinFit.value(injector, "at");
		boolean many = at instanceof List<?>;
		List<Object> out = new ArrayList<>();
		boolean changed = false;
		for (AnnotationNode node : ats) {
			String value = MixinFit.asString(MixinFit.value(node, "value"));
			Object ordinal = MixinFit.value(node, "ordinal");
			boolean oldTail = "TAIL".equals(value) || ("RETURN".equals(value) && Integer.valueOf(split.inlineReturns()).equals(ordinal));
			if (!oldTail) {
				out.add(node);
				continue;
			}
			if (split.inlineReturns() == 0) {
				// Nothing returns before the blocks: every return IS one the old tail stood for.
				AnnotationNode all = copy(node);
				MixinPlayerWorldCallbackAdapter.set(all, "value", "RETURN");
				MixinPlayerWorldCallbackAdapter.remove(all, "ordinal");
				out.add(all);
			} else if (many) {
				for (int k = split.inlineReturns(); k <= split.inlineReturns() + split.blocks(); k++) {
					AnnotationNode one = copy(node);
					MixinPlayerWorldCallbackAdapter.set(one, "value", "RETURN");
					MixinPlayerWorldCallbackAdapter.set(one, "ordinal", k);
					out.add(one);
				}
			} else {
				ForbricLog.warn("[Forbric/EarlyReturns] %s: a single @At cannot name the %d returns its TAIL ran at before "
						+ "vanilla's early returns were restored; it keeps vanilla's meaning", where, split.blocks() + 1);
				out.add(node);
				continue;
			}
			changed = true;
		}
		if (!changed) return false;
		if (many) {
			MixinPlayerWorldCallbackAdapter.set(injector, "at", out);
		} else {
			MixinPlayerWorldCallbackAdapter.set(injector, "at", out.get(0));
		}
		ForbricLog.info("[Forbric/EarlyReturns] %s: its TAIL still runs at all %d return(s) the folded body sent there — "
				+ "the paths vanilla returns early from included, as on its own loader", where, split.blocks() + 1);
		return true;
	}

	private static AnnotationNode copy(AnnotationNode at) {
		AnnotationNode copy = new AnnotationNode(AT_DESC);
		if (at.values != null) copy.values = new ArrayList<>(at.values);
		return copy;
	}

	/** What one selector binds on one target: Mixin's method's split (null when it was never split), and whether it has namesakes. */
	private record Bound(VanillaEarlyReturns.Split split, boolean siblings) {
	}

	/**
	 * What {@code selector} binds on {@code owner}, parsed the way Mixin parses it (whitespace dropped, an owner in
	 * either {@code Lowner;} or dotted form), or null when it binds nothing this class can name. A name-only selector
	 * binds what Mixin binds: without a quantifier it matches one method, the first of that name in declaration order —
	 * the real method, when javac put a bridge of the same name after it ({@code AbstractZombieRenderer.getArmPose}).
	 * A quantified or wildcard selector is not this class's call.
	 */
	private static Bound bind(String owner, ClassNode target, String selector) {
		MixinFit.Member member = MixinFit.parseMember(selector);
		if (member == null || member.name().isEmpty() || !member.name().matches("[^*+{}]+")) return null;
		if (member.owner() != null && !member.owner().equals(owner)) return null;
		MethodNode first = null;
		int named = 0;
		for (MethodNode method : target.methods) {
			if (!method.name.equals(member.name())) continue;
			named++;
			if (first == null && (member.desc() == null || member.desc().equals(method.desc))) first = method;
		}
		if (first == null) return null;
		return new Bound(VanillaEarlyReturns.splitOf(owner, first.name, first.desc), member.desc() == null && named > 1);
	}
}
