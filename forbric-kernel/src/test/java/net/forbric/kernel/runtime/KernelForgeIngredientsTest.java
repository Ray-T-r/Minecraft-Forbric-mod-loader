package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/** Source-structure and compiled-shape pins for the Forge ingredient codec wrapper. */
class KernelForgeIngredientsTest {
	private static final Path SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelForgeIngredients.java");
	private static final Path COMPILED = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"),
			"net/forbric/kernel/runtime/KernelForgeIngredients.class");

	private static String source() throws Exception {
		return Files.readString(SOURCE, StandardCharsets.UTF_8);
	}

	@Test
	void theForgeDecodeIsGuardedAndFallsBackToNeoForgeWithoutLatchingTheFailure() throws Exception {
		String s = source();
		int forge = s.indexOf("forge.decode(ops, input)");
		int tryBlock = s.lastIndexOf("try {", forge);
		int catchBlock = s.indexOf("catch (Throwable t)", forge);
		assertTrue(tryBlock >= 0 && tryBlock < forge && catchBlock > forge, "the Forge decode must sit inside a try");
		int fallback = s.indexOf("return neo.decode(ops, input);", catchBlock);
		int encode = s.indexOf("public <T> DataResult<T> encode(", catchBlock);
		assertTrue(fallback > catchBlock && fallback < encode, "the catch must answer with NeoForge's decode of the same value");
		assertFalse(s.contains("forgeFailed") || s.contains("forge = neo") || s.contains("useNeo = true"),
				"a failure must not be cached — the next decode asks Forge again");
	}

	@Test
	void theSwitchIsReadPerCallOnBothPaths() throws Exception {
		String s = source();
		assertFalse(s.contains("static final boolean"), "the switch must be read per call, never cached");
		assertTrue(s.indexOf("System.getProperty(PROPERTY, \"on\")") < s.indexOf("forge.decode(ops, input)"));
		int encode = s.indexOf("public <T> DataResult<T> encode(");
		assertTrue(s.indexOf("System.getProperty(PROPERTY, \"on\")", encode) > 0, "encode reads the switch too");
	}

	@Test
	void onlyForgeBuiltIngredientsEncodeThroughForge() throws Exception {
		String s = source();
		int encode = s.indexOf("public <T> DataResult<T> encode(");
		String body = s.substring(encode, s.indexOf("public String toString()", encode));
		assertTrue(body.contains("value instanceof AbstractIngredient"),
				"Forge's encode path reads the unwritten VANILLA_SERIALIZER for any ingredient NeoForge built");
		assertTrue(body.indexOf("forge.encode") < body.indexOf("return neo.encode"));
	}

	@Test
	void theOnlyForgeCodecConstructorNamedIsTheCarriersOwnComposition() throws Exception {
		assumeTrue(Files.isRegularFile(COMPILED), "runtime helper not compiled");
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(COMPILED)).accept(node, 0);
		List<String> forgeCalls = new ArrayList<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.startsWith("net/minecraftforge/")
						&& !call.owner.equals("net/minecraftforge/registries/ForgeRegistries")
						&& !call.owner.startsWith("net/minecraftforge/registries/")) {
					forgeCalls.add(call.owner + "." + call.name);
				}
			}
		}
		assertEquals(List.of("net/minecraftforge/common/ForgeHooks.ingredientBaseCodec"), forgeCalls,
				"no hand-built dispatch: the carrier composes the either(registry dispatch, base) itself");
	}
}
