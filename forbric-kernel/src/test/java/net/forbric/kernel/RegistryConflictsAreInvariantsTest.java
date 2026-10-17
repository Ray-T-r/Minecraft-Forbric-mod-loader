/*
 * Copyright 2026 The Forbric Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package net.forbric.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * A registry that finds a different value already under the key it is given throws {@code Caught.Invariant}.
 *
 * <p>The kernel's contract registries key what they hold by what it was derived from, so a second, different value
 * under one key is the kernel contradicting itself. Their callers register from inside the catch that turns "not
 * this shape" into "decline", where an ordinary {@code IllegalStateException} was taken for exactly that: the
 * repair stood down and nothing said so. {@code Caught.expected} reports an {@code Invariant} as the failure it is;
 * this keeps the next registry from throwing anything else.
 *
 * <p>The shape looked for: a {@code throw} whose guard compares with {@code equals} what a {@code putIfAbsent} or a
 * {@code get} on the same registry just returned, within the few statements before it.
 */
class RegistryConflictsAreInvariantsTest {
	private static final Path PROJECT = Path.of(System.getProperty("user.dir"));
	private static final List<String> ROOTS = List.of("src/main/java", "src/runtime/java");
	private static final Pattern THROW = Pattern.compile("\\bthrow\\s+new\\s+([\\w.]+)\\s*\\(");
	private static final Pattern LOOKUP = Pattern.compile("\\.(?:putIfAbsent|get)\\s*\\(");
	private static final int WINDOW = 400;

	@Test
	void everyRegistryConflictThrowsAnInvariant() throws Exception {
		List<String> ordinary = new ArrayList<>();
		for (Path source : sources()) {
			String file = PROJECT.relativize(source).toString().replace('\\', '/');
			for (String where : ordinaryConflicts(Files.readString(source, StandardCharsets.UTF_8))) ordinary.add(file + ":" + where);
		}
		assertTrue(ordinary.isEmpty(), "a registry conflict thrown as an ordinary exception is declined as silently as "
				+ "a shape that did not match, wherever a repair registers from inside its catch. Throw "
				+ "Caught.Invariant instead:\n" + String.join("\n", ordinary));
	}

	@Test
	void whatCountsAsARegistryConflict() {
		assertEquals(List.of(), ordinaryConflicts(
				"Schema old=SCHEMAS.putIfAbsent(key,schema);if(old!=null&&!old.equals(schema))throw new Caught.Invariant(\"x\");"));
		assertEquals(List.of(), ordinaryConflicts(
				"Installed old=PLANS.get(key);if(old!=null&&!old.source.equals(source))throw new net.forbric.kernel.util.Caught.Invariant(\"x\");"));
		assertEquals(1, ordinaryConflicts(
				"Schema old=SCHEMAS.putIfAbsent(key,schema);if(old!=null&&!old.equals(schema))throw new IllegalStateException(\"x\");").size());
		assertEquals(1, ordinaryConflicts(
				"Contract old=CONTRACTS.putIfAbsent(id,c);\n\tif(old!=null&&!old.equals(c))throw new IllegalArgumentException(\"x\");").size());
		// Not a conflict: a lookup that came back empty, and a comparison that is not against what the lookup held.
		assertEquals(List.of(), ordinaryConflicts("Schema s=SCHEMAS.get(key);if(s==null)throw new IllegalStateException(\"x\");"));
		assertEquals(List.of(), ordinaryConflicts("if(!name.equals(other))throw new IllegalArgumentException(\"x\");"));
	}

	/** "line N: Type" for each throw in {@code java} that guards a registry conflict with anything but an Invariant. */
	static List<String> ordinaryConflicts(String java) {
		String code = NoRawAssumptionsTest.codeOnly(java);
		List<String> found = new ArrayList<>();
		Matcher thrown = THROW.matcher(code);
		while (thrown.find()) {
			String before = code.substring(Math.max(0, thrown.start() - WINDOW), thrown.start());
			int guard = Math.max(before.lastIndexOf(';'), before.lastIndexOf('{'));
			String condition = before.substring(guard + 1);
			if (!condition.contains(".equals(") || !condition.contains("null")) continue;
			if (!LOOKUP.matcher(before.substring(0, Math.max(0, guard))).find()) continue;
			String type = thrown.group(1);
			if (type.equals("Caught.Invariant") || type.equals("net.forbric.kernel.util.Caught.Invariant")) continue;
			int line = 1;
			for (int i = 0; i < thrown.start(); i++) if (code.charAt(i) == '\n') line++;
			found.add("line " + line + ": " + type);
		}
		return found;
	}

	private static List<Path> sources() throws IOException {
		List<Path> sources = new ArrayList<>();
		for (String root : ROOTS) {
			try (Stream<Path> walk = Files.walk(PROJECT.resolve(root))) {
				walk.filter(p -> p.toString().endsWith(".java")).sorted().forEach(sources::add);
			}
		}
		return sources;
	}
}
