/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;

import org.objectweb.asm.Attribute;
import org.objectweb.asm.ByteVector;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.MethodNode;

/**
 * The hand-off between the passes that re-count an injection point. A guest mixin's ordinal counts occurrences in the
 * body its mod was compiled against (the native body); several passes translate it into a count over the current
 * merged body. A translated ordinal must never be translated again: read as a native count, a current-body ordinal
 * names a different occurrence, and a second translation silently lands the handler on another call.
 *
 * <p>The contract: a pass that leaves a handler's injection point counted over the current body marks the handler;
 * a pass that reads an ordinal as a native count leaves a marked handler alone. The mark is a non-executable class-file
 * attribute, so it survives the node being written and read again. It marks the whole handler, which is exact for the
 * single-point injectors these passes translate.
 */
final class CurrentBodyOrdinals {
	static final String ATTRIBUTE = "ForbricCurrentOrdinals";

	private CurrentBodyOrdinals() {
	}

	/** Records that {@code handler}'s injection point now counts occurrences in the current body. */
	static void mark(MethodNode handler) {
		if (handler == null || counted(handler)) return;
		if (handler.attrs == null) handler.attrs = new ArrayList<>();
		handler.attrs.add(new Marker());
	}

	/** Whether a pass already counted {@code handler}'s injection point over the current body. */
	static boolean counted(MethodNode handler) {
		return handler != null && handler.attrs != null && handler.attrs.stream().anyMatch(a -> ATTRIBUTE.equals(a.type));
	}

	private static final class Marker extends Attribute {
		Marker() {
			super(ATTRIBUTE);
		}

		@Override
		protected ByteVector write(ClassWriter writer, byte[] code, int length, int maxStack, int maxLocals) {
			return new ByteVector().putByte(1);
		}
	}
}
