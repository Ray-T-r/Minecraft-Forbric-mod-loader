/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Mixin reads a config with Gson's lenient reader, so a commented config is one Mixin applies. Essential's
 * {@code mixins.essential.json} keeps its preprocessor's {@code //#if} lines; the kernel's strict parse failed on it and
 * every reader of the config gave up without a word — no ecosystem noted for the config's mixins, so the shims that
 * read the native game of the mod's platform (Essential's screenshot capture on a widened lambda, its server-status hook
 * on a field the carrier reads once more) declined it.
 */
class MixinConfigJsonTest {
	/** The shape of Essential's config: preprocessor comments around a key, a block comment, a plugin. */
	private static final String COMMENTED = """
			{
			  "required": true,
			  "minVersion": "0.8",
			  //#if MC>=11700
			  "compatibilityLevel": "JAVA_16",
			  //#else
			  //$$ "compatibilityLevel": "JAVA_8",
			  //#endif
			  "package": "spry.mixins.transformers", /* the root of every entry */
			  "plugin": "spry.mixins.Plugin",
			  # hash comments are Gson's too
			  "refmap": "spry//refmap.json",
			  "mixins": ["client.Snap", 'unused']
			}
			""".replace("'unused'", "\"it's \\\"quoted\\\" // here\"");

	@AfterEach void forget() {
		MixinStubRebind.forget();
		MixinConfigOwners.reset();
	}

	@Test void aCommentedConfigReadsAsMixinReadsIt() {
		var config = MixinConfigJson.parse(COMMENTED.getBytes(StandardCharsets.UTF_8));
		assertEquals("spry.mixins.transformers", config.get("package"));
		assertEquals("JAVA_16", config.get("compatibilityLevel"), "the line the preprocessor left active");
		assertEquals("spry//refmap.json", config.get("refmap"), "a // inside a string is the string's");
		assertEquals(List.of("client.Snap", "it's \"quoted\" // here"), config.get("mixins"));
	}

	@Test void blankingKeepsEveryLineAndNothingOutsideComments() {
		String stripped = MixinConfigJson.withoutComments(COMMENTED);
		assertEquals(COMMENTED.length(), stripped.length());
		assertEquals(COMMENTED.lines().count(), stripped.lines().count());
		assertEquals("{\"a\":\"/*x*/\"}", MixinConfigJson.withoutComments("{\"a\":\"/*x*/\"}"));
		assertEquals("{\"a\":1     \n}", MixinConfigJson.withoutComments("{\"a\":1 // c\n}"));
		assertEquals("{\"a\":1     \n  }", MixinConfigJson.withoutComments("{\"a\":1 /* c\n*/}"));
	}

	@Test void somethingThatIsNotJsonIsStillRefused() {
		assertThrows(RuntimeException.class, () -> MixinConfigJson.parse("not json at all".getBytes(StandardCharsets.UTF_8)));
		assertThrows(RuntimeException.class, () -> MixinConfigJson.parse("{\"a\": unquoted}".getBytes(StandardCharsets.UTF_8)),
				"only comments are forgiven, not the rest of the lenient grammar");
	}

	/** The adapter reads the commented config: its package is noted with the owner's ecosystem, as for any other. */
	@Test void theGuestAdapterNotesACommentedConfigsMixinsWithTheirEcosystem() {
		MixinConfigOwners.claimLate("spry.mixins.json", "spry", Ecosystem.FABRIC);
		KernelGuestMixinAdapter.unfitMixins("spry.mixins.json", COMMENTED.getBytes(StandardCharsets.UTF_8), resource -> null);
		assertEquals(Ecosystem.FABRIC, MixinStubRebind.ecosystemOf("spry/mixins/transformers/util/AddedByThePlugin"));
	}
}
