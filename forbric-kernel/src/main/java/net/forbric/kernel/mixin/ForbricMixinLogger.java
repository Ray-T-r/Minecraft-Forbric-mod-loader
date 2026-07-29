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

import org.spongepowered.asm.logging.Level;
import org.spongepowered.asm.logging.LoggerAdapterAbstract;

import net.forbric.kernel.util.ForbricLog;

/**
 * Routes Mixin's logging into the kernel's logger, so mixin apply failures land in the same server log the gates
 * grep. {@code LoggerAdapterAbstract} supplies the level-specific overloads; only the sinks are implemented here.
 *
 * <p>Mixin formats with {@code {}} placeholders (slf4j style) while {@link ForbricLog} uses {@code String.format},
 * so the message is rendered here rather than passed through as a format string.
 */
final class ForbricMixinLogger extends LoggerAdapterAbstract {
	ForbricMixinLogger(String name) {
		super(name);
	}

	@Override
	public String getType() {
		return "Forbric Mixin Logger";
	}

	@Override
	public void catching(Level level, Throwable t) {
		log(level, "Caught " + t.getClass().getName(), t);
	}

	@Override
	public <T extends Throwable> T throwing(T t) {
		catching(Level.WARN, t);
		return t;
	}

	@Override
	public void log(Level level, String message, Object... params) {
		String rendered = format(message, params);
		Throwable trailing = trailingThrowable(params);

		if (trailing != null) {
			log(level, rendered, trailing);
			return;
		}

		switch (level) {
			case FATAL, ERROR -> ForbricLog.error(prefix(rendered));
			case WARN -> ForbricLog.warn(prefix(rendered));
			case INFO -> ForbricLog.info(prefix(rendered));
			default -> ForbricLog.debug(prefix(rendered));
		}
	}

	@Override
	public void log(Level level, String message, Throwable t) {
		String rendered = prefix(message);

		switch (level) {
			case FATAL, ERROR -> ForbricLog.error(rendered, t);
			case WARN -> ForbricLog.warn(rendered, t);
			// Mixin logs a great deal at INFO/DEBUG with throwables during normal operation; keep them off the
			// default console but retain the stack trace under -Dforbric.debug.
			default -> ForbricLog.debug(rendered + ": " + t);
		}
	}

	private String prefix(String message) {
		return "[Mixin/" + getId() + "] " + message;
	}

	/** slf4j-style {@code {}} substitution; surplus params are appended, matching Mixin's own expectations. */
	private static String format(String message, Object... params) {
		if (message == null) return "null";
		if (params == null || params.length == 0) return message;

		StringBuilder sb = new StringBuilder(message.length() + 32);
		int param = 0;
		int i = 0;

		while (i < message.length()) {
			int brace = message.indexOf("{}", i);

			if (brace < 0 || param >= params.length) {
				sb.append(message, i, message.length());
				break;
			}

			sb.append(message, i, brace).append(params[param++]);
			i = brace + 2;
		}

		return sb.toString();
	}

	/** Mixin passes a Throwable as the last vararg in some call sites; surface it instead of printing toString. */
	private static Throwable trailingThrowable(Object... params) {
		if (params == null || params.length == 0) return null;

		Object last = params[params.length - 1];
		return last instanceof Throwable ? (Throwable) last : null;
	}
}
