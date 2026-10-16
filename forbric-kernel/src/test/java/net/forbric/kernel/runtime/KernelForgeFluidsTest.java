package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Source-structure pins for the fluid model/tint funnel — a chunk-mesh hot path. */
class KernelForgeFluidsTest {
	private static final Path SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelForgeFluids.java");

	private static String model() throws Exception {
		String s = Files.readString(SOURCE, StandardCharsets.UTF_8);
		return s.substring(s.indexOf("public static FluidModel model("), s.indexOf("public static int tintColor("));
	}

	@Test
	void theDefaultExtensionShortCircuitsToTheModelByIdentity() throws Exception {
		String m = model();
		int shortCircuit = m.indexOf("if (extensions == IClientFluidTypeExtensions.DEFAULT || KernelFluidTypes.forgeTypeIsStandIn(fluid)) {");
		assertTrue(shortCircuit > 0, "vanilla fluids must not pay for getModel, and must render byte-for-byte as before; a fluid holding"
				+ " the kernel's stand-in type keeps its own model (ForeignFluidAppearanceExecutionTest runs it)");
		assertTrue(m.substring(shortCircuit, m.indexOf("}", shortCircuit)).contains("return model;"));
		assertTrue(shortCircuit < m.indexOf("extensions.getModel("));
		assertTrue(m.indexOf("ASKED.add(fluid)") < shortCircuit, "a vanilla fluid in view is counted, so a gate can see the funnel");
	}

	@Test
	void theCountLineIsGatedByAContainsCheckBeforeTheAdd() throws Exception {
		String m = model();
		assertTrue(m.contains("!ASKED.contains(fluid) && ASKED.add(fluid)"),
				"contains-check first: this runs once per fluid tesselation");
	}

	@Test
	void theSwitchAndAnyFailureFallBackToVanillaOnBothSites() throws Exception {
		String s = Files.readString(SOURCE, StandardCharsets.UTF_8);
		String m = model();
		assertTrue(m.indexOf("return model;") < m.indexOf("try {"), "off-switch answers the model before any Forge call");
		assertTrue(m.contains("catch (Throwable t)") && m.substring(m.indexOf("catch (Throwable t)")).contains("return model;"));
		// The entry the merged body's own Forge ask is routed through holds the same switch and the same fallback.
		String routed = s.substring(s.indexOf("public static FluidModel modelFromExtensions("), s.indexOf("private static FluidModel ask("));
		assertTrue(routed.indexOf("return model;") > 0 && routed.indexOf("return model;") < routed.indexOf("try {"),
				"off-switch answers the model before the routed Forge ask");
		assertTrue(routed.contains("catch (Throwable t)") && routed.substring(routed.indexOf("catch (Throwable t)")).contains("failed(t, model)"));
		assertTrue(s.substring(s.indexOf("private static FluidModel failed(")).contains("return model;"), "a failure answers the model");
		String tint = s.substring(s.indexOf("public static int tintColor("));
		assertTrue(tint.contains("return -1;"));
		assertTrue(tint.indexOf("return -1;") < tint.indexOf("try {"));
		assertTrue(tint.contains("catch (Throwable t)"));
		assertFalse(s.contains("static final boolean"), "the switch is read per call");
	}
}
