package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.MixinStubRebind;

/**
 * The stub rebind's pinned {@code @Local(index)} through the real weave, real MixinExtras resolving the index — no
 * third-party jar. A stand-in {@code ServerExplosion} has the shipped carrier-stubs.txt row's shape: vanilla's
 * {@code hurtEntities()} is a stub nothing calls, forwarding to the carrier's {@code hurtEntities(List)}, whose new
 * argument is a {@code List} as the entity list is. A Fabric mod injects at the entity list's iterator, taking the list
 * by type: on the body two {@code List}s are in scope, and MixinExtras' implicit pick refuses both.
 * <ul>
 *   <li>constant — the stub hands the new argument {@code List.of()}, a value of constants alone, which vanilla's body
 *       cannot have held in a local: set aside, the entity list is the one left, the handler moves with
 *       {@code @Local(index = 2)} and sees the entities;</li>
 *   <li>constant, typed locals off — the control: the handler stays on the stub, which nothing calls, and never runs;</li>
 *   <li>computed — the stub hands it {@code this.affected()}, a value worked out the way vanilla's body could have held
 *       it in a local the carrier hoisted into the stub: nothing is set aside, nothing is guessed, the handler stays as
 *       compiled. Setting every value the stub did not pass through aside (the rule before) pinned the other {@code List}
 *       here.</li>
 * </ul>
 */
class MixinStubRebindPinWeaveTest {
	private static final String OWNER = "net/minecraft/world/level/ServerExplosion";
	private static final String CONFIG = "pin.mixins.json";
	private static final String SAW_ENTITIES = "[Pin] seen=[handler:[zombie, cow], hit:zombie, hit:cow]";
	private static final String NEVER_RAN = "[Pin] seen=[hit:zombie, hit:cow]";
	private static final String PINNED = "reads slot 2 — the one of its type there that is not an argument the carrier added";
	private static final String MOVED = "now targets net.minecraft.world.level.ServerExplosion.hurtEntities(Ljava/util/List;)V";

	@TempDir static Path work;
	private static Path constantFixture, computedFixture;
	private static WeaveHarness.Result constant, constantOff, computed;

	@BeforeAll static void weave() throws Exception {
		constantFixture = fixture("constant", "hurtEntities(List.of());");
		computedFixture = fixture("computed", "hurtEntities(this.affected());");
		constant = run("constant", constantFixture, Map.of());
		constantOff = run("constant-off", constantFixture, Map.of(MixinStubRebind.TYPED_LOCAL_PROPERTY, "off"));
		computed = run("computed", computedFixture, Map.of());
	}

	@Test void aConstantBuiltArgumentIsSetAsideAndTheIndexIsResolvedByMixinExtras() throws Exception {
		assertTrue(constant.printed(SAW_ENTITIES), "the handler did not see the entity list — " + constant.describe());
		assertTrue(constant.printed(PINNED), constant.describe());
		assertTrue(constant.printed(MOVED), constant.describe());
		WeaveHarness.assertWovenAndVerified(constant, OWNER, constantFixture);
	}

	@Test void switchedOffTheHandlerStaysOnTheStubNothingCalls() throws Exception {
		assertTrue(constantOff.printed(NEVER_RAN), constantOff.describe());
		assertFalse(constantOff.printed(MOVED), constantOff.describe());
	}

	@Test void aComputedArgumentIsNotSetAsideAndNothingIsPinned() throws Exception {
		assertTrue(computed.printed(NEVER_RAN), "a guessed slot was pinned — " + computed.describe());
		assertFalse(computed.printed(PINNED), computed.describe());
		assertFalse(computed.printed(MOVED), computed.describe());
		assertFalse(computed.printed("handler:[zombie, cow, stone]"), computed.describe());
	}

	private static Path fixture(String name, String forward) throws Exception {
		Path explosion = source(name + "/ServerExplosion.java", """
				package net.minecraft.world.level;
				import java.util.*;
				public class ServerExplosion {
				 public static final List<String> SEEN = new ArrayList<>();
				 private final List<String> nearby = List.of("zombie", "cow");
				 public void explode() { hurtEntities(new ArrayList<>(List.of("stone"))); }
				 private List<String> affected() { return new ArrayList<>(List.of("stone")); }
				 private void hurtEntities() { %s }
				 private void hurtEntities(List<String> blocks) {
				  List<String> entities = new ArrayList<>(nearby);
				  entities.removeAll(blocks);
				  for (String entity : entities) SEEN.add("hit:" + entity);
				 }
				}
				""".formatted(forward));
		Path probe = source(name + "/Probe.java", "package fixture.pin;public class Probe{public static void probe(){"
				+ "new net.minecraft.world.level.ServerExplosion().explode();System.out.println(\"[Pin] seen=\"+net.minecraft.world.level.ServerExplosion.SEEN);}}");
		Path mixin = source(name + "/ExplosionMixin.java", """
				package fixture.pin.mixin;
				import java.util.List;
				import net.minecraft.world.level.ServerExplosion;
				import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;
				import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
				import com.llamalad7.mixinextras.sugar.Local;
				@Mixin(ServerExplosion.class)public class ExplosionMixin {
				 @Inject(method="hurtEntities",at=@At(value="INVOKE",target="Ljava/util/List;iterator()Ljava/util/Iterator;"),require=0)
				 private void onHurt(CallbackInfo ci, @Local List<String> entities) { ServerExplosion.SEEN.add("handler:" + entities); }
				}
				""");
		Path json = source(name + "/" + CONFIG, "{\"required\":false,\"package\":\"fixture.pin.mixin\",\"mixins\":[\"ExplosionMixin\"],\"injectors\":{\"defaultRequire\":0}}");
		return WeaveHarness.fixture(work, name, List.of(explosion, probe, mixin), Map.of(CONFIG, json), List.of("-g"));
	}

	private static WeaveHarness.Result run(String label, Path fixture, Map<String, String> properties) throws Exception {
		return WeaveHarness.run(work, label, fixture, CONFIG, "pinprobe", Ecosystem.FABRIC, EnvType.SERVER, "fixture.pin.Probe", "probe", properties);
	}

	private static Path source(String name, String text) throws Exception {
		Path file = work.resolve("src").resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, text);
		return file;
	}
}
