package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * {@link MixinCameraRollAdapter#argumentsWithin}: the proof that an argument-list handler cannot see the arguments a
 * widened call appends. Only constant-index {@code get}/{@code set} below the old count, directly or in a lambda the list
 * is captured into, may touch it; anything else that could read its size or the appended values answers no.
 */
class WidenedCallArgumentsWithinTest {
	static void direct(Args args, float partialTicks) {
		args.set(0, (Float) args.get(0) + partialTicks);
		args.set(1, (Float) args.get(1));
	}

	static void throughLambdas(Args args) {
		Consumer<Float> write = value -> args.set(1, value);
		Supplier<Float> read = () -> args.get(0);
		write.accept(read.get());
	}

	static void appended(Args args) {
		args.set(2, 0.0F);
	}

	static void counted(Args args) {
		if (args.size() > 0) args.set(0, 1.0F);
	}

	static void replacedWhole(Args args) {
		args.setAll(1.0F, 2.0F);
	}

	static void indexFromOutside(Args args, int index) {
		args.set(index, 1.0F);
	}

	static Object escaped;

	static void stored(Args args) {
		escaped = args;
	}

	static void lambdaReadsAppended(Args args) {
		Supplier<Float> read = () -> args.get(2);
		read.get();
	}

	@Test void constantIndicesBelowTheOldCountAreTheOnlyAnswerYes() throws Exception {
		ClassNode node = self();
		assertTrue(within(node, "direct"));
		assertTrue(within(node, "throughLambdas"));
		assertFalse(within(node, "appended"));
		assertFalse(within(node, "counted"));
		assertFalse(within(node, "replacedWhole"));
		assertFalse(within(node, "indexFromOutside"));
		assertFalse(within(node, "stored"));
		assertFalse(within(node, "lambdaReadsAppended"));
	}

	private static boolean within(ClassNode node, String name) {
		MethodNode method = node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
		return MixinCameraRollAdapter.argumentsWithin(node, method, 0, 2);
	}

	private static ClassNode self() throws Exception {
		try (InputStream in = WidenedCallArgumentsWithinTest.class.getResourceAsStream("WidenedCallArgumentsWithinTest.class")) {
			ClassNode node = new ClassNode();
			new ClassReader(in).accept(node, 0);
			return node;
		}
	}
}
