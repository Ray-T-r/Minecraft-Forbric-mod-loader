package net.forbric.kernel.metadata.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** The first consumer {@code modLoader} has ever had. */
class LanguageProvidersTest {

	@Test void aManifestWithNoModLoaderIsAJavaMod() {
		assertEquals(LanguageProviders.JAVA, LanguageProviders.of(toml(null)));
		assertEquals(LanguageProviders.JAVA, LanguageProviders.of(toml("  ")));
		assertEquals(LanguageProviders.JAVA, LanguageProviders.of(null));
	}

	@Test void theDeclaredProviderIsNormalisedNotGuessedAt() {
		assertEquals(LanguageProviders.KOTLIN, LanguageProviders.of(toml(" KotlinForForge ")));
		assertEquals(LanguageProviders.LOW_CODE, LanguageProviders.of(toml("lowcodefml")));
		// Not remapped to something known: an unrecognised provider has to stay unrecognised, because the whole
		// value of this field is that it names a gap by its own name.
		assertEquals("somethingelse", LanguageProviders.of(toml("SomethingElse")));
	}

	@Test void onlyTheThreeThisKernelHasAnAnswerForAreSupported() {
		assertTrue(LanguageProviders.isSupported(LanguageProviders.JAVA));
		assertTrue(LanguageProviders.isSupported(LanguageProviders.KOTLIN));
		assertTrue(LanguageProviders.isSupported(LanguageProviders.LOW_CODE));
		assertFalse(LanguageProviders.isSupported("somethingelse"));
	}

	@Test void auditingNeverThrowsOnAnythingItIsHanded() {
		// It runs inside discovery, per jar. A manifest that makes it throw would cost the whole jar.
		LanguageProviders.audit(null, "null.jar");
		LanguageProviders.audit(toml(null), "java.jar");
		LanguageProviders.audit(toml("kotlinforforge"), "kotlin.jar");
		LanguageProviders.audit(toml("lowcodefml"), "data.jar");
		LanguageProviders.audit(toml("whatever"), "unknown.jar");
	}

	private static ForgeModsToml toml(String modLoader) {
		return new ForgeModsToml(modLoader, "[1,)", List.of(), List.of());
	}
}
