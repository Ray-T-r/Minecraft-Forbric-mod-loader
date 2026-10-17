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
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import org.spongepowered.asm.mixin.FabricUtil;

import net.fabricmc.loader.api.metadata.ModDependency;

import net.forbric.kernel.fabric.KernelMetadataSupport;
import net.forbric.kernel.util.Caught;

/**
 * The Mixin compatibility level Fabric Loader gives a mod's mixin configs, worked out the way its
 * {@code FabricMixinBootstrap.MixinConfigDecorator.getMixinCompat} works it out.
 *
 * <p>Fabric's Mixin changes behaviour between releases — the order initialisers and injectors are applied in, how
 * a local variable is picked out, where a shifted injection point lands — and keeps the old behaviour for mods built
 * before the change. Which one a mod gets is read from the lowest Fabric Loader version it accepts: a mod declaring
 * {@code "fabricloader": ">=0.14.21"} was written against the Mixin of that era and gets that era's behaviour, every
 * injector of every config it declares. The kernel never told Mixin, so every Fabric mod ran with the newest
 * behaviour, which is not the one it was tested against on Fabric.
 *
 * <p>The table is Fabric Loader 0.19.3's ({@code FabricMixinVersions}), the version the kernel reports itself as, and
 * it tops out at the bundled Mixin's own latest level — a level that Mixin does not know would be read as its latest
 * anyway. A newer Fabric Loader adds a row with a newer Mixin; both move together.
 *
 * <p>The lower bound is computed with Fabric's own interval rules: each {@code depends} on {@code fabricloader}
 * narrows the accepted range, each {@code breaks} on it removes a range, and the bound is the start of what is left.
 * Versions are compared in Fabric Loader's own order ({@link #compare}).
 */
public final class FabricMixinCompat {
	/** Fabric Loader's table, newest first: the lowest loader version of each era, and that era's Mixin level. */
	private static final String[] LOADER_VERSIONS = {"0.19.0", "0.18.4", "0.17.3", "0.16.0", "0.12.0-"};
	private static final int[] LEVELS = {FabricUtil.COMPATIBILITY_0_17_1, FabricUtil.COMPATIBILITY_0_17_0,
			FabricUtil.COMPATIBILITY_0_16_5, FabricUtil.COMPATIBILITY_0_14_0, FabricUtil.COMPATIBILITY_0_10_0};

	private FabricMixinCompat() {
	}

	/** The highest level the table gives, for checking it against the bundled Mixin's own latest. */
	static int newestLevel() {
		return LEVELS[0];
	}

	/** The level a mod with these dependencies gets on Fabric. */
	public static int levelFor(Collection<? extends ModDependency> dependencies) {
		String floor = lowestAcceptedLoader(dependencies);
		if (floor == null) return FabricUtil.COMPATIBILITY_0_9_2;

		for (int i = 0; i < LOADER_VERSIONS.length; i++) {
			if (compare(floor, LOADER_VERSIONS[i]) >= 0) return LEVELS[i];
		}
		return FabricUtil.COMPATIBILITY_0_9_2;
	}

	/**
	 * The lowest Fabric Loader version these dependencies accept, or {@code null} when they accept versions without
	 * end downwards — no {@code fabricloader} dependency, or one like {@code "*"} or {@code "<0.20"}. A mod that accepts
	 * no loader version at all, which Fabric refuses to load, gets {@code null} too.
	 */
	static String lowestAcceptedLoader(Collection<? extends ModDependency> dependencies) {
		List<Interval> accepted = List.of(Interval.ALL);
		if (dependencies != null) {
			for (ModDependency dependency : dependencies) {
				String id = dependency.getModId();
				if (!"fabricloader".equals(id) && !"fabric-loader".equals(id)) continue;

				if (dependency.getKind() == ModDependency.Kind.DEPENDS) {
					accepted = and(accepted, intervalsOf(dependency));
				} else if (dependency.getKind() == ModDependency.Kind.BREAKS) {
					accepted = and(accepted, not(intervalsOf(dependency)));
				}
			}
		}
		if (accepted.isEmpty()) return null;
		return normalize(accepted).get(0).min();
	}

	/** One dependency's versions: its predicates OR-joined, each one's terms AND-joined. */
	private static List<Interval> intervalsOf(ModDependency dependency) {
		if (!(dependency instanceof KernelMetadataSupport.SimpleModDependency simple)) return List.of(Interval.ALL);

		List<String> constraints = simple.getConstraints();
		if (constraints.isEmpty()) return List.of(Interval.ALL);

		List<Interval> union = new ArrayList<>();
		for (String constraint : constraints) {
			// "||" is the kernel's matcher's spelling of OR inside one predicate; Fabric's is the array form above.
			for (String alternative : constraint.split("\\|\\|")) {
				Interval interval = Interval.ALL;
				for (String term : alternative.trim().split(" ")) {
					interval = interval == null ? null : interval.intersect(termInterval(term.trim()));
				}
				if (interval != null) union.add(interval);
			}
		}
		return union;
	}

	/** Fabric's {@code VersionPredicateParser}: one term's interval. Anything it would refuse admits everything. */
	static Interval termInterval(String term) {
		if (term.isEmpty() || term.equals("*")) return Interval.ALL;

		String operator = "=";
		for (String candidate : new String[] {">=", "<=", ">", "<", "=", "~", "^"}) {
			if (term.startsWith(candidate)) {
				operator = candidate;
				term = term.substring(candidate.length());
				break;
			}
		}
		if (term.isEmpty()) return Interval.ALL;

		// "1.2.x" is "~1.2-" and "1.x" is "^1-", and only without an operator: Fabric's own rewriting.
		String[] parts = core(term).split("\\.");
		String last = parts[parts.length - 1];
		if (last.equalsIgnoreCase("x") || last.equals("*")) {
			if (!operator.equals("=") || (parts.length != 2 && parts.length != 3)) return Interval.ALL;
			StringBuilder prefix = new StringBuilder();
			for (int i = 0; i < parts.length - 1; i++) {
				if (i > 0) prefix.append('.');
				prefix.append(parts[i]);
			}
			term = prefix.append('-').toString();
			operator = parts.length == 2 ? "^" : "~";
		}

		return switch (operator) {
			case ">=" -> new Interval(term, true, null, false);
			case "<=" -> new Interval(null, false, term, true);
			case ">" -> new Interval(term, false, null, false);
			case "<" -> new Interval(null, false, term, false);
			case "~" -> nextUp(term, 2);
			case "^" -> nextUp(term, 1);
			default -> new Interval(term, true, term, true);
		};
	}

	/** {@code ~} and {@code ^}: from {@code version} up to, not including, the next minor or major release. */
	private static Interval nextUp(String version, int pinned) {
		String[] parts = core(version).split("\\.");
		long[] components = new long[pinned];
		for (int i = 0; i < pinned; i++) {
			if (i >= parts.length) break;
			try {
				components[i] = Long.parseLong(parts[i]);
			} catch (NumberFormatException notSemantic) {
				Caught.expected(notSemantic);
				return new Interval(version, true, version, true);
			}
		}
		components[pinned - 1]++;
		StringBuilder max = new StringBuilder();
		for (int i = 0; i < pinned; i++) {
			if (i > 0) max.append('.');
			max.append(components[i]);
		}
		return new Interval(version, true, max.append('-').toString(), false);
	}

	/** The version without its pre-release and build suffixes. */
	private static String core(String version) {
		int end = version.length();
		int dash = version.indexOf('-');
		int plus = version.indexOf('+');
		if (dash >= 0) end = Math.min(end, dash);
		if (plus >= 0) end = Math.min(end, plus);
		return version.substring(0, end);
	}

	private static List<Interval> and(List<Interval> a, List<Interval> b) {
		List<Interval> out = new ArrayList<>();
		for (Interval x : a) {
			for (Interval y : b) {
				Interval both = x.intersect(y);
				if (both != null) out.add(both);
			}
		}
		return normalize(out);
	}

	/** Everything outside {@code union}. */
	private static List<Interval> not(List<Interval> union) {
		List<Interval> sorted = normalize(union);
		if (sorted.isEmpty()) return List.of(Interval.ALL);

		List<Interval> gaps = new ArrayList<>();
		String from = null;
		boolean fromIn = false;
		boolean open = true; // whether the gap below the next interval starts at minus infinity or at 'from'
		for (Interval interval : sorted) {
			if (interval.min() != null) {
				Interval gap = open
						? new Interval(null, false, interval.min(), !interval.minIn())
						: new Interval(from, fromIn, interval.min(), !interval.minIn());
				if (gap.isNonEmpty()) gaps.add(gap);
			}
			if (interval.max() == null) return gaps;
			from = interval.max();
			fromIn = !interval.maxIn();
			open = false;
		}
		gaps.add(new Interval(from, fromIn, null, false));
		return gaps;
	}

	/** Sorted by lower bound, overlapping intervals merged. */
	private static List<Interval> normalize(List<Interval> intervals) {
		List<Interval> sorted = new ArrayList<>(intervals);
		sorted.sort(Comparator.comparing(Interval::min, Comparator.nullsFirst(FabricMixinCompat::compare)));
		List<Interval> merged = new ArrayList<>();
		for (Interval next : sorted) {
			if (merged.isEmpty()) {
				merged.add(next);
				continue;
			}
			Interval last = merged.get(merged.size() - 1);
			if (last.max() == null || next.min() == null || compare(next.min(), last.max()) < 0
					|| (compare(next.min(), last.max()) == 0 && (next.minIn() || last.maxIn()))) {
				merged.set(merged.size() - 1, last.span(next));
			} else {
				merged.add(next);
			}
		}
		return merged;
	}

	/**
	 * Fabric Loader's version order: {@code SemanticVersionImpl.compareTo} for two semantic versions, plain string order
	 * otherwise (its {@code StringVersion}). The floor is compared against the table's rows with this, so it has to be
	 * Fabric's order to the letter: a pre-release sorts below its release ({@code 0.16.0-0 < 0.16.0}), a shorter core is
	 * padded with zeros before the pre-release is looked at ({@code 0.12- == 0.12.0-}), and pre-release identifiers
	 * compare numeric-below-alphanumeric. The kernel's {@code VersionPredicate.compare}, which matches dependencies,
	 * decides those three cases differently, and each one moved a mod to a neighbouring level.
	 */
	static int compare(String a, String b) {
		Semantic x = Semantic.parse(a);
		Semantic y = Semantic.parse(b);
		if (x == null || y == null) return a.compareTo(b);

		for (int i = 0; i < Math.max(x.core.length, y.core.length); i++) {
			int first = x.component(i);
			int second = y.component(i);
			if (first == Semantic.WILDCARD || second == Semantic.WILDCARD) continue;
			int c = Integer.compare(first, second);
			if (c != 0) return c;
		}

		if (x.pre == null && y.pre == null) return 0;
		if (x.pre == null) return x.wildcard() ? 0 : 1;
		if (y.pre == null) return y.wildcard() ? 0 : -1;

		java.util.StringTokenizer left = new java.util.StringTokenizer(x.pre, ".");
		java.util.StringTokenizer right = new java.util.StringTokenizer(y.pre, ".");
		while (left.hasMoreTokens()) {
			if (!right.hasMoreTokens()) return 1;
			String partA = left.nextToken();
			String partB = right.nextToken();
			boolean numericA = UNSIGNED_INTEGER.matcher(partA).matches();
			boolean numericB = UNSIGNED_INTEGER.matcher(partB).matches();
			if (numericA && numericB) {
				int c = Integer.compare(partA.length(), partB.length());
				if (c != 0) return c;
			} else if (numericA) {
				return -1;
			} else if (numericB) {
				return 1;
			}
			int c = partA.compareTo(partB);
			if (c != 0) return c;
		}
		return right.hasMoreTokens() ? -1 : 0;
	}

	private static final java.util.regex.Pattern UNSIGNED_INTEGER = java.util.regex.Pattern.compile("0|[1-9][0-9]*");
	private static final java.util.regex.Pattern DOT_SEPARATED_ID =
			java.util.regex.Pattern.compile("|[-0-9A-Za-z]+(\\.[-0-9A-Za-z]+)*");

	/** Fabric's {@code SemanticVersionImpl} as it parses with wildcards allowed; {@code null} where it would refuse. */
	private record Semantic(int[] core, String pre) {
		static final int WILDCARD = Integer.MIN_VALUE;

		static Semantic parse(String version) {
			int build = version.indexOf('+');
			if (build >= 0) version = version.substring(0, build);
			String pre = null;
			int dash = version.indexOf('-');
			if (dash >= 0) {
				pre = version.substring(dash + 1);
				version = version.substring(0, dash);
			}
			if (pre != null && !DOT_SEPARATED_ID.matcher(pre).matches()) return null;
			if (version.isEmpty() || version.startsWith(".") || version.endsWith(".")) return null;

			String[] parts = version.split("\\.");
			int[] core = new int[parts.length];
			int firstWildcard = -1;
			for (int i = 0; i < parts.length; i++) {
				String part = parts[i];
				if (part.equals("x") || part.equals("X") || part.equals("*")) {
					if (pre != null) return null;
					core[i] = WILDCARD;
					if (firstWildcard < 0) firstWildcard = i;
					continue;
				}
				if (i > 0 && core[i - 1] == WILDCARD) return null;
				try {
					core[i] = Integer.parseInt(part.trim());
				} catch (NumberFormatException notANumber) {
					Caught.expected(notANumber);
					return null;
				}
				if (part.trim().isEmpty() || core[i] < 0) return null;
			}
			if (core.length == 1 && core[0] == WILDCARD) return null;
			if (firstWildcard > 0 && core.length > firstWildcard + 1) core = java.util.Arrays.copyOf(core, firstWildcard + 1);
			return new Semantic(core, pre);
		}

		/** Past the end: the wildcard repeats, otherwise 0. */
		int component(int i) {
			if (i < core.length) return core[i];
			return core[core.length - 1] == WILDCARD ? WILDCARD : 0;
		}

		boolean wildcard() {
			for (int c : core) {
				if (c == WILDCARD) return true;
			}
			return false;
		}
	}

	/** A version range; a {@code null} bound is unbounded on that side. */
	record Interval(String min, boolean minIn, String max, boolean maxIn) {
		static final Interval ALL = new Interval(null, false, null, false);

		/** The overlap, or {@code null} when there is none. */
		Interval intersect(Interval other) {
			String lo;
			boolean loIn;
			if (min == null || (other.min != null && compare(other.min, min) > 0)) {
				lo = other.min;
				loIn = other.minIn;
			} else if (other.min == null || compare(min, other.min) > 0) {
				lo = min;
				loIn = minIn;
			} else {
				lo = min;
				loIn = minIn && other.minIn;
			}

			String hi;
			boolean hiIn;
			if (max == null || (other.max != null && compare(other.max, max) < 0)) {
				hi = other.max;
				hiIn = other.maxIn;
			} else if (other.max == null || compare(max, other.max) < 0) {
				hi = max;
				hiIn = maxIn;
			} else {
				hi = max;
				hiIn = maxIn && other.maxIn;
			}

			Interval both = new Interval(lo, loIn, hi, hiIn);
			return both.isNonEmpty() ? both : null;
		}

		/** The smallest interval covering both, for two that overlap or touch; {@code this} starts no later. */
		Interval span(Interval other) {
			boolean loIn = minIn;
			if (min != null && other.min != null && compare(min, other.min) == 0) {
				loIn = minIn || other.minIn;
			}

			String hi;
			boolean hiIn;
			if (max == null || other.max == null) {
				hi = null;
				hiIn = false;
			} else {
				int c = compare(max, other.max);
				hi = c >= 0 ? max : other.max;
				hiIn = c > 0 ? maxIn : c < 0 ? other.maxIn : maxIn || other.maxIn;
			}
			return new Interval(min, loIn, hi, hiIn);
		}

		boolean isNonEmpty() {
			if (min == null || max == null) return true;
			int c = compare(min, max);
			return c < 0 || (c == 0 && minIn && maxIn);
		}
	}
}
