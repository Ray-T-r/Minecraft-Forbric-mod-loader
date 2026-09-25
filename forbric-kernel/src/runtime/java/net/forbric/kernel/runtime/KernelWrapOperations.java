/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

/**
 * The {@code Operation} a wrapped {@code @WrapOperation} handler is handed when the surviving carrier reordered or widened
 * its call (MixinWrapOperationShim).
 *
 * <p>The handler calls it with the arguments it was written for, in its own order; this puts each where the merged call
 * takes it, fills the merged call's other arguments with the values the call site had, and calls the real operation.
 * So what the handler changes is changed, and a call it skips is skipped.
 */
public final class KernelWrapOperations {
	private KernelWrapOperations() {
	}

	/**
	 * @param original the merged call's operation
	 * @param receiver whether the first argument is the call's receiver (an instance call)
	 * @param map      for each argument the handler passes, its position in the merged call, comma-separated
	 * @param extras   the merged call's arguments in its own order; only the positions no handler argument maps to are read
	 */
	public static <R> Operation<R> reordered(Operation<R> original, boolean receiver, String map, Object[] extras) {
		String[] parts = map.isEmpty() ? new String[0] : map.split(",");
		int[] positions = new int[parts.length];
		for (int i = 0; i < parts.length; i++) positions[i] = Integer.parseInt(parts[i]);
		int offset = receiver ? 1 : 0;
		return args -> {
			Object[] full = new Object[offset + extras.length];
			if (receiver) full[0] = args[0];
			System.arraycopy(extras, 0, full, offset, extras.length);
			for (int i = 0; i < positions.length; i++) full[offset + positions[i]] = args[offset + i];
			return original.call(full);
		};
	}
}
