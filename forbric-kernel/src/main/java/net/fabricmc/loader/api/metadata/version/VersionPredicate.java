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

package net.fabricmc.loader.api.metadata.version;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;

/**
 * A parsed Fabric version requirement, as mods call it.
 *
 * <p><b>Why this exists, when {@code ModDependency} deliberately omits {@code getVersionRequirements}.</b> That
 * omission was argued on the grounds that nothing the kernel drives reads a dependency's requirement objects —
 * which is true, and is about the INSTANCE side. It missed the STATIC side: {@code VersionPredicate.parse} is a
 * public entry point mods call directly, with no dependency involved at all. ShoulderSurfing-Fabric's
 * {@code Platform.parseVersionPredicateSilent} is an {@code invokestatic} straight to it; conditional-mixin's
 * {@code versionPredicates} condition is another. Under the kernel that class was simply absent, so the call
 * site raised {@code NoClassDefFoundError} — and the catch blocks around those calls catch {@code Exception},
 * which an {@code Error} walks straight past.
 *
 * <p>The parsing and comparison are the kernel's own ({@code net.forbric.api.VersionPredicate}); what is
 * reproduced here is the API shape mods compile against.
 */
public interface VersionPredicate extends Predicate<Version> {
	/** The individual comparisons this predicate is built from. */
	Collection<? extends PredicateTerm> getTerms();

	/** The version range this predicate admits, or {@code null} when it cannot be expressed as one. */
	VersionInterval getInterval();

	/** One comparison: an operator and the version it compares against. */
	interface PredicateTerm {
		VersionComparisonOperator getOperator();

		Version getReferenceVersion();
	}

	/**
	 * Parses one requirement, e.g. {@code ">=6.0.0"}, {@code "~1.2"}, {@code "*"}.
	 *
	 * @throws VersionParsingException if it cannot be read as a requirement at all
	 */
	static VersionPredicate parse(String predicate) throws VersionParsingException {
		if (predicate == null) throw new VersionParsingException("no version predicate");
		String trimmed = predicate.trim();
		if (trimmed.isEmpty()) throw new VersionParsingException("empty version predicate");
		return new Parsed(trimmed);
	}

	/** Parses several, as {@code fabric.mod.json} allows an array of them. */
	static Collection<VersionPredicate> parse(Collection<String> predicates) throws VersionParsingException {
		List<VersionPredicate> out = new ArrayList<>();
		if (predicates == null) return out;
		for (String predicate : predicates) out.add(parse(predicate));
		return out;
	}

	/**
	 * The one implementation, holding the requirement as written.
	 *
	 * <p>The string is kept rather than decomposed because the kernel's matcher takes the string: decomposing it
	 * here and reassembling it there would be two parsers to keep in agreement, which is exactly the drift
	 * {@code net.forbric.api.VersionPredicate}'s own javadoc records having paid for once already.
	 */
	final class Parsed implements VersionPredicate {
		private final String predicate;

		Parsed(String predicate) {
			this.predicate = predicate;
		}

		@Override
		public boolean test(Version version) {
			return version != null
					&& net.forbric.api.VersionPredicate.matches(predicate, version.getFriendlyString());
		}

		@Override
		public Collection<? extends PredicateTerm> getTerms() {
			List<PredicateTerm> terms = new ArrayList<>();
			for (String part : predicate.split("\\s+")) {
				PredicateTerm term = term(part.trim());
				if (term != null) terms.add(term);
			}
			return terms;
		}

		/**
		 * Null for the parts that carry no comparison — {@code *} admits everything and has no reference — and for
		 * a reference the kernel's version parser rejects.
		 *
		 * <p>Dropping an unparseable term rather than throwing is deliberate: {@link #test} does not go through
		 * these at all, so a term that cannot be described must not be able to fail a predicate that WORKS. A
		 * caller enumerating the terms sees one fewer; a caller testing a version sees the right answer.
		 */
		private static PredicateTerm term(String part) {
			if (part.isEmpty() || "*".equals(part)) return null;
			VersionComparisonOperator operator = VersionComparisonOperator.EQUAL;
			String rest = part;
			for (VersionComparisonOperator candidate : VersionComparisonOperator.values()) {
				if (part.startsWith(candidate.getSerialized())
						&& candidate.getSerialized().length() > (operator == VersionComparisonOperator.EQUAL
								&& !part.startsWith("=") ? 0 : operator.getSerialized().length())) {
					operator = candidate;
					rest = part.substring(candidate.getSerialized().length());
				}
			}
			VersionComparisonOperator chosen = operator;
			Version reference;
			try {
				reference = Version.parse(rest.trim());
			} catch (VersionParsingException unreadable) {
				return null;
			}
			return new PredicateTerm() {
				@Override
				public VersionComparisonOperator getOperator() {
					return chosen;
				}

				@Override
				public Version getReferenceVersion() {
					return reference;
				}
			};
		}

		/**
		 * {@code null}, honestly.
		 *
		 * <p>An interval is a different shape from a predicate — it is a bounded range, and a predicate like
		 * {@code ">=1.0 <2.0 !=1.5"} has no single one. Fabric's own returns null where it cannot express one;
		 * the kernel returns null throughout rather than inventing a bound a caller would trust.
		 */
		@Override
		public VersionInterval getInterval() {
			return null;
		}

		@Override
		public String toString() {
			return predicate;
		}
	}
}
