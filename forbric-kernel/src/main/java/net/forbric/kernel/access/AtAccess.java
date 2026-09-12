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

package net.forbric.kernel.access;

import org.objectweb.asm.Opcodes;

/**
 * A Java access level, ordered by visibility so an Access Transformer can <em>widen</em> monotonically
 * (never narrow). {@link #rank()} gives the ordering; {@link #apply(int)} rewrites an ASM access flag set
 * to at least this visibility.
 */
public enum AtAccess {
	PRIVATE(0),
	PACKAGE(1),
	PROTECTED(2),
	PUBLIC(3);

	private static final int VISIBILITY_MASK = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE;

	private final int rank;

	AtAccess(int rank) {
		this.rank = rank;
	}

	public int rank() {
		return rank;
	}

	/** Parses a cfg modifier keyword (already stripped of any {@code +f}/{@code -f} suffix). */
	public static AtAccess parse(String keyword) {
		switch (keyword) {
			case "public": return PUBLIC;
			case "protected": return PROTECTED;
			case "private": return PRIVATE;
			case "default": // package-private
			case "package": return PACKAGE;
			default: throw new IllegalArgumentException("unknown access modifier: " + keyword);
		}
	}

	/** The visibility currently encoded in an ASM access flag set. */
	public static AtAccess of(int access) {
		if ((access & Opcodes.ACC_PUBLIC) != 0) return PUBLIC;
		if ((access & Opcodes.ACC_PROTECTED) != 0) return PROTECTED;
		if ((access & Opcodes.ACC_PRIVATE) != 0) return PRIVATE;
		return PACKAGE;
	}

	/**
	 * Returns {@code access} widened to at least this visibility (monotonic: an already-more-visible member
	 * is left unchanged).
	 */
	public int apply(int access) {
		AtAccess current = of(access);
		AtAccess target = current.rank >= this.rank ? current : this;

		int cleared = access & ~VISIBILITY_MASK;
		switch (target) {
			case PUBLIC: return cleared | Opcodes.ACC_PUBLIC;
			case PROTECTED: return cleared | Opcodes.ACC_PROTECTED;
			case PRIVATE: return cleared | Opcodes.ACC_PRIVATE;
			case PACKAGE:
			default: return cleared; // package-private = no visibility bit
		}
	}
}
