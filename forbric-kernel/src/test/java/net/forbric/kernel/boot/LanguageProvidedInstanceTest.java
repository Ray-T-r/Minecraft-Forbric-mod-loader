package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/**
 * The shape a language provider hands back instead of a constructor.
 *
 * <p>{@code mods.toml}'s {@code modLoader} field is parsed, exposed, and — until this — read by nothing in
 * {@code src/main}. The Fabric side has had a full {@code KernelLanguageAdapters} for exactly this reason, with
 * a javadoc saying every Kotlin mod died on "language adapter 'kotlin' is not supported yet" without it; the
 * Forge side reflects the widest public constructor and has no notion that a mod might not be written in Java.
 *
 * <p>A Kotlin {@code object} has a private constructor and one public static final INSTANCE, so it failed as
 * "no public constructor" — a true sentence about a mod that is not broken.
 */
class LanguageProvidedInstanceTest {

	/** What Kotlin's {@code object} compiles to, in the only two respects that matter here. */
	public static final class KotlinObjectShaped {
		public static final KotlinObjectShaped INSTANCE = new KotlinObjectShaped();

		private KotlinObjectShaped() {
		}
	}

	public static final class OrdinaryJavaMod {
		public OrdinaryJavaMod() {
		}
	}

	/** An unrelated static named INSTANCE. Picking this up would hand the loader an object that is not the mod. */
	public static final class DecoyInstanceField {
		public static final String INSTANCE = "not the mod";
	}

	public static final class NonFinalInstance {
		public static KotlinObjectShaped INSTANCE = KotlinObjectShaped.INSTANCE;
	}

	@Test void aKotlinObjectIsTakenRatherThanConstructed() {
		Object found = KernelModLoader.languageProvidedInstance(KotlinObjectShaped.class);
		assertNotNull(found, "a Kotlin object's INSTANCE is what kotlinforforge itself reads");
		assertSame(KotlinObjectShaped.INSTANCE, found);
	}

	@Test void anOrdinaryModIsLeftToTheConstructorPath() {
		assertNull(KernelModLoader.languageProvidedInstance(OrdinaryJavaMod.class));
	}

	@Test void aStaticNamedInstanceIsNotEnough() {
		// Narrow on purpose: public, static, final, and typed as the mod class itself.
		assertNull(KernelModLoader.languageProvidedInstance(DecoyInstanceField.class),
				"a String named INSTANCE is not a mod instance");
		assertNull(KernelModLoader.languageProvidedInstance(NonFinalInstance.class),
				"a non-final INSTANCE is not the Kotlin object shape");
	}
}
