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

/**
 * One parsed Access Transformer line: widen (and optionally change {@code final} on) a class, a field, a
 * method, or every field/method of a class. Names are stored as <em>internal</em> names (slash-separated)
 * in the runtime (intermediary) namespace after {@link AccessTransformerParser#parseAndRemap remapping}.
 */
public final class AtDirective {
	/** What to do with the {@code final} flag. */
	public enum FinalOp {
		LEAVE,
		STRIP,
		MAKE
	}

	/** Wildcard member name meaning "every field of the class". */
	public static final String ALL_FIELDS = "*";
	/** Wildcard member name meaning "every method of the class". */
	public static final String ALL_METHODS = "*()";

	public final String className;   // internal name, e.g. net/minecraft/class_2248
	public final String memberName;  // null = class entry; ALL_FIELDS / ALL_METHODS = wildcard; else field/method name
	public final String memberDesc;  // method descriptor (intermediary) or null
	public final boolean method;     // true if this targets a method (or ALL_METHODS)
	public final AtAccess access;
	public final FinalOp finalOp;

	public AtDirective(String className, String memberName, String memberDesc, boolean method,
			AtAccess access, FinalOp finalOp) {
		this.className = className;
		this.memberName = memberName;
		this.memberDesc = memberDesc;
		this.method = method;
		this.access = access;
		this.finalOp = finalOp;
	}

	public boolean isClass() {
		return memberName == null;
	}

	public boolean isAllFields() {
		return ALL_FIELDS.equals(memberName);
	}

	public boolean isAllMethods() {
		return ALL_METHODS.equals(memberName);
	}

	@Override
	public String toString() {
		String target = isClass() ? className : className + " " + memberName + (memberDesc == null ? "" : memberDesc);
		return access + (finalOp == FinalOp.LEAVE ? "" : finalOp == FinalOp.STRIP ? "-f" : "+f") + " " + target;
	}
}
