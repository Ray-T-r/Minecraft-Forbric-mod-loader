package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.transform.ClassTransformer;
import net.forbric.kernel.transform.TransformContext;

/**
 * A fixture class as the base has it after one of the kernel's own COREMOD transformers ran: the production
 * transformer applied to the compiled stand-in, in the fixture jar, before any run reads it.
 *
 * <p>For a transformer {@link KernelBootChain} cannot register the way a boot does — KernelBoot registers Create's
 * breathing, sound and HUD injectors only behind a probe for Create's own class, which the chain refuses to guess
 * at. The class the scenario weaves is still exactly what Mixin would be shown, because a pre-Mixin transformer's
 * output is all Mixin ever sees of a class.
 */
final class PreMixinFixture {
	private PreMixinFixture() {
	}

	/** Rewrites {@code binaryName} in {@code jar} through {@code transformer}; fails when the transformer changes nothing. */
	static void transform(Path jar, String binaryName, ClassTransformer transformer, EnvType side) throws IOException {
		String entryName = binaryName.replace('.', '/') + ".class";
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (JarInputStream in = new JarInputStream(Files.newInputStream(jar))) {
			for (JarEntry entry; (entry = in.getNextJarEntry()) != null;) entries.put(entry.getName(), in.readAllBytes());
		}
		byte[] before = entries.get(entryName);
		if (before == null) throw new AssertionError(entryName + " is not in the fixture " + jar);
		byte[] after = transformer.transform(binaryName, before, new TransformContext(side, false, "named"));
		assertFalse(after == null || Arrays.equals(before, after), transformer.getClass().getSimpleName()
				+ " left the fixture's " + binaryName + " unchanged, so the scenario would weave a class no boot has");
		entries.put(entryName, after);
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
			for (var entry : entries.entrySet()) {
				out.putNextEntry(new JarEntry(entry.getKey()));
				out.write(entry.getValue());
				out.closeEntry();
			}
		}
	}
}
