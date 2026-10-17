/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.zip.ZipFile;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;

/**
 * {@link CarrierDecisions} on unmodified upstream fabric-api mixins against the staged merged base and the native references
 * it carries: what fabric-api's mining, enchanting and effect handlers become, read as Mixin will read them, and the mining
 * handler pair executed. The mechanism is given no name: the same handlers renamed, with one more instruction, still move;
 * a {@code @Group} or the switch leaves them as compiled.
 */
@ResourceLock("system-properties")
class CarrierDecisionsStagedTest {
	private static final String STACK = "net/minecraft/world/item/ItemStack";
	@TempDir Path root;

	@AfterEach void reset() {
		System.clearProperty(CarrierDecisions.PROPERTY);
	}

	/** The merged class with its local variable tables, as the mixin service hands it to the adapters. */
	private static ClassNode game(String owner) throws Exception {
		Path jar = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
		TestFixtures.require(TestFixtures.Fixture.STAGED, Files.isRegularFile(jar), "actual game required");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ClassNode node = new ClassNode();
			new org.objectweb.asm.ClassReader(zip.getInputStream(zip.getEntry(owner + ".class")).readAllBytes()).accept(node, 0);
			return node;
		}
	}

	private static BiFunction<Ecosystem, String, ClassNode> natives() {
		Path jar = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
		TestFixtures.require(TestFixtures.Fixture.STAGED, Files.isRegularFile(jar), "actual game required");
		NativeGameReferences references = new NativeGameReferences(path -> {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				var entry = zip.getEntry(path);
				return entry == null ? null : zip.getInputStream(entry).readAllBytes();
			} catch (Exception unreadable) {
				return null;
			}
		});
		return references::get;
	}

	private static ClassNode fabric(String module, String name) throws Exception {
		ClassNode mixin = StagedFabricMixinFixture.mixin(module, name);
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		return mixin;
	}

	private static int adapt(ClassNode mixin, String target) throws Exception {
		ClassNode merged = game(target);
		return CarrierDecisions.adapt(mixin, owner -> owner.equals(target) ? merged : null, natives());
	}

	private static AnnotationNode at(MethodNode handler) {
		return MixinFit.atNodes(MixinFit.injectorOf(handler)).getFirst();
	}

	@Test void theMiningRedirectMovesOntoTheCarriersOppositeQuestionAndItsOwnCallIsTurnedBack() throws Exception {
		ClassNode mixin = fabric("fabric-item-api-v1", "net/fabricmc/fabric/mixin/item/client/MultiPlayerGameModeMixin");
		assertEquals(1, adapt(mixin, "net/minecraft/client/multiplayer/MultiPlayerGameMode"));
		MethodNode wrapper = StagedFabricMixinFixture.method(mixin, "fabricItemContinueBlockBreakingInject");
		assertEquals("L" + STACK + ";shouldCauseBlockBreakReset(L" + STACK + ";)Z", MixinFit.value(at(wrapper), "target"));
		assertEquals("(L" + STACK + ";L" + STACK + ";)Z", wrapper.desc);
		MethodNode original = StagedFabricMixinFixture.method(mixin, "fabricItemContinueBlockBreakingInject" + CarrierDecisions.SUFFIX);
		assertNull(MixinFit.injectorOf(original));
		assertEquals(1, calls(original, "shouldCauseBlockBreakReset"), "its own vanilla question is the carrier's there");
		assertEquals(0, calls(original, "isSameItemSameComponents"));
		assertEquals(1, calls(original, "allowContinuingBlockBreaking"), "the rest of its body is as written");
		verify(mixin, wrapper);
		verify(mixin, original);
		assertEquals(0, adapt(mixin, "net/minecraft/client/multiplayer/MultiPlayerGameMode"), "a second pass changes nothing");
	}

	/**
	 * The pair runs on small stand-ins: the carrier's answer decides, and fabric's per-item override keeps a same-item break,
	 * asked with the old and new stacks and the player, exactly as fabric-api asks it.
	 */
	@Test void theMovedMiningPairDecidesWithTheCarrierAndFabricsOverride() throws Exception {
		Map<String, String> sources = Map.of(
				"net/minecraft/world/entity/player/Player.java", "package net.minecraft.world.entity.player; public class Player {}",
				"net/minecraft/client/player/LocalPlayer.java", "package net.minecraft.client.player; public class LocalPlayer extends net.minecraft.world.entity.player.Player {"
						+ " public net.minecraft.world.item.ItemStack hand; public net.minecraft.world.item.ItemStack getMainHandItem(){return hand;} }",
				"net/minecraft/client/Minecraft.java", "package net.minecraft.client; public class Minecraft { public net.minecraft.client.player.LocalPlayer player=new net.minecraft.client.player.LocalPlayer(); }",
				"net/minecraft/world/item/Item.java", "package net.minecraft.world.item; public class Item { public boolean reset,allow; public int nativeCalls,fabricCalls; public Object player,old,next;"
						+ " public boolean allowContinuingBlockBreaking(net.minecraft.world.entity.player.Player p,ItemStack a,ItemStack b){fabricCalls++;player=p;old=a;next=b;return allow;} }",
				"net/minecraft/world/item/ItemStack.java", "package net.minecraft.world.item; public class ItemStack {public final Item item;public ItemStack(Item item){this.item=item;}public Item getItem(){return item;}"
						+ " public boolean is(Object o){return o==item;} public boolean shouldCauseBlockBreakReset(ItemStack next){item.nativeCalls++;return item.reset;} }",
				"net/fabricmc/fabric/mixin/item/client/MultiPlayerGameModeMixin.java", "package net.fabricmc.fabric.mixin.item.client; public class MultiPlayerGameModeMixin {"
						+ " private net.minecraft.client.Minecraft minecraft; private net.minecraft.world.item.ItemStack destroyingItem;"
						+ " public MultiPlayerGameModeMixin(net.minecraft.client.Minecraft m, net.minecraft.world.item.ItemStack d){minecraft=m;destroyingItem=d;} }");
		List<String> args = new ArrayList<>(List.of("--release", "21", "-d", root.toString()));
		for (var source : sources.entrySet()) {
			Path file = root.resolve(source.getKey());
			Files.createDirectories(file.getParent());
			Files.writeString(file, source.getValue());
			args.add(file.toString());
		}
		assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
		String name = "net/fabricmc/fabric/mixin/item/client/MultiPlayerGameModeMixin";
		ClassNode mixin = fabric("fabric-item-api-v1", name);
		assertEquals(1, adapt(mixin, "net/minecraft/client/multiplayer/MultiPlayerGameMode"));
		Path shell = root.resolve(name + ".class");
		ClassNode output = MixinFit.parse(Files.readAllBytes(shell));
		for (String handler : List.of("fabricItemContinueBlockBreakingInject", "fabricItemContinueBlockBreakingInject" + CarrierDecisions.SUFFIX)) {
			MethodNode method = StagedFabricMixinFixture.method(mixin, handler);
			method.access = Opcodes.ACC_PUBLIC;
			method.visibleAnnotations = null;
			method.invisibleAnnotations = null;
			method.invisibleParameterAnnotations = null;
			method.attrs = null;
			output.methods.add(method);
		}
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_FRAMES) {
			@Override protected String getCommonSuperClass(String a, String b) {
				return "java/lang/Object";
			}
		};
		output.accept(writer);
		Files.write(shell, writer.toByteArray());
		try (URLClassLoader loader = new URLClassLoader(new URL[] {root.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
			Class<?> item = loader.loadClass("net.minecraft.world.item.Item"), stack = loader.loadClass("net.minecraft.world.item.ItemStack"),
					minecraft = loader.loadClass("net.minecraft.client.Minecraft"), controller = loader.loadClass(name.replace('/', '.'));
			// {carrier says reset, fabric allows, same item, expected reset}
			for (boolean[] shape : List.of(new boolean[] {false, false, true, false}, new boolean[] {true, false, true, true},
					new boolean[] {true, true, true, false}, new boolean[] {true, true, false, true})) {
				Object a = item.getConstructor().newInstance(), b = shape[2] ? a : item.getConstructor().newInstance();
				item.getField("reset").setBoolean(a, shape[0]);
				item.getField("allow").setBoolean(a, shape[1]);
				Object destroying = stack.getConstructor(item).newInstance(a), held = stack.getConstructor(item).newInstance(b);
				Object mc = minecraft.getConstructor().newInstance();
				Object player = minecraft.getField("player").get(mc);
				player.getClass().getField("hand").set(player, held);
				Object host = controller.getConstructor(minecraft, stack).newInstance(mc, destroying);
				// The carrier's call as merged: destroying.shouldCauseBlockBreakReset(held) — the wrapper's receiver, then argument.
				assertEquals(shape[3], controller.getMethod("fabricItemContinueBlockBreakingInject", stack, stack).invoke(host, destroying, held));
				assertEquals(1, item.getField("nativeCalls").getInt(a), "the carrier is asked once");
				int asked = shape[0] && shape[2] ? 1 : 0;
				assertEquals(asked, item.getField("fabricCalls").getInt(a));
				if (asked == 1) {
					assertSame(destroying, item.getField("old").get(a));
					assertSame(held, item.getField("next").get(a));
					assertSame(player, item.getField("player").get(a));
				}
			}
		}
	}

	@Test void threeEnchantingRedirectsMoveOntoTheCarriersItemQuestionWithTheNativeValues() throws Exception {
		Map<String, String> routes = Map.of(
				"EnchantCommandMixin", "net/minecraft/server/commands/EnchantCommand",
				"EnchantRandomlyFunctionMixin", "net/minecraft/world/level/storage/loot/functions/EnchantRandomlyFunction",
				"AnvilMenuMixin", "net/minecraft/world/inventory/AnvilMenu");
		for (var route : routes.entrySet()) {
			ClassNode mixin = fabric("fabric-item-api-v1", "net/fabricmc/fabric/mixin/item/" + route.getKey());
			assertEquals(1, adapt(mixin, route.getValue()), route.getKey());
			MethodNode wrapper = StagedFabricMixinFixture.method(mixin, "callAllowEnchantingEvent");
			assertEquals("L" + STACK + ";supportsEnchantment(Lnet/minecraft/core/Holder;)Z", MixinFit.value(at(wrapper), "target"), route.getKey());
			assertEquals(Type.getType("L" + STACK + ";"), Type.getArgumentTypes(wrapper.desc)[0], "the carrier's receiver first");
			MethodNode original = StagedFabricMixinFixture.method(mixin, "callAllowEnchantingEvent" + CarrierDecisions.SUFFIX);
			assertEquals(1, calls(original, "canBeEnchantedWith"), "fabric-api's own handler body, unchanged");
			verify(mixin, wrapper);
		}
		// The anvil's body moved into the carrier's helper: the redirect names it.
		ClassNode anvil = fabric("fabric-item-api-v1", "net/fabricmc/fabric/mixin/item/AnvilMenuMixin");
		adapt(anvil, "net/minecraft/world/inventory/AnvilMenu");
		assertEquals(List.of("createResultInternal()V"), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(StagedFabricMixinFixture.method(anvil, "callAllowEnchantingEvent")), "method")));
	}

	/**
	 * The enchanting table's primary-item question is not moved: natively the lambda answers {@code isPrimaryItem || isBook}
	 * with the book flag captured from its caller; the carrier's method reference folds the book check into its own
	 * question, so the two ways on of the native decision are not the carrier's.
	 */
	@Test void thePrimaryItemLambdaIsNotMovedBecauseTheCarrierFoldedTheBookCheckIntoItsQuestion() throws Exception {
		ClassNode mixin = fabric("fabric-item-api-v1", "net/fabricmc/fabric/mixin/item/EnchantmentHelperMixin");
		assertEquals(0, adapt(mixin, "net/minecraft/world/item/enchantment/EnchantmentHelper"));
	}

	@Test void theEffectInjectorsGoToTheCarriersApplicabilityQuestionAndItsCopy() throws Exception {
		ClassNode mixin = fabric("fabric-entity-events-v1", "net/fabricmc/fabric/mixin/entity/event/effect/LivingEntityMixin");
		assertEquals(2, adapt(mixin, "net/minecraft/world/entity/LivingEntity"));
		assertEquals("Lnet/neoforged/neoforge/common/CommonHooks;canMobEffectBeApplied(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z",
				MixinFit.value(at(StagedFabricMixinFixture.method(mixin, "beforeForceAddEffect")), "target"));
		AnnotationNode copy = at(StagedFabricMixinFixture.method(mixin, "beforeRemoveAllEffects"));
		assertEquals("NEW", MixinFit.value(copy, "value"));
		assertEquals("java/util/HashMap", MixinFit.value(copy, "target"));
	}

	@Test void theLanguageCopyTheCarrierNoLongerMakesBecomesAnInjectorHandedTheMap() throws Exception {
		ClassNode mixin = fabric("fabric-resource-loader-v1", "net/fabricmc/fabric/mixin/resource/server/LanguageMixin");
		assertEquals(1, adapt(mixin, "net/minecraft/locale/Language"));
		MethodNode wrapper = StagedFabricMixinFixture.method(mixin, "create");
		AnnotationNode injector = MixinFit.injectorOf(wrapper);
		assertEquals(CarrierDecisions.INJECT, injector.desc);
		assertEquals("Lnet/neoforged/neoforge/server/LanguageHook;captureLanguageMap(Ljava/util/Map;Ljava/util/Map;)V", MixinFit.value(at(wrapper), "target"));
		assertEquals("(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;Ljava/util/Map;)V", wrapper.desc);
		assertNull(MixinFit.injectorOf(StagedFabricMixinFixture.method(mixin, "create" + CarrierDecisions.SUFFIX)));
		verify(mixin, wrapper);
	}

	/** No name, no fingerprint: another handler name and one more instruction move the same; a group or the switch does not. */
	@Test void theMoveKnowsNoNameOrBodyAndStandsDownForAGroupOrTheSwitch() throws Exception {
		ClassNode renamed = fabric("fabric-item-api-v1", "net/fabricmc/fabric/mixin/item/EnchantCommandMixin");
		MethodNode handler = StagedFabricMixinFixture.method(renamed, "callAllowEnchantingEvent");
		handler.name = "lark$ask";
		handler.instructions.insert(new InsnNode(Opcodes.NOP));
		renamed.name = "org/example/RenamedEnchant";
		MixinStubRebind.noteEcosystem(renamed.name, Ecosystem.FABRIC);
		assertEquals(1, adapt(renamed, "net/minecraft/server/commands/EnchantCommand"));
		assertNotNull(StagedFabricMixinFixture.method(renamed, "lark$ask" + CarrierDecisions.SUFFIX));

		ClassNode grouped = fabric("fabric-item-api-v1", "net/fabricmc/fabric/mixin/item/EnchantCommandMixin");
		StagedFabricMixinFixture.method(grouped, "callAllowEnchantingEvent").visibleAnnotations.add(new AnnotationNode(CarrierDecisions.GROUP));
		assertEquals(0, adapt(grouped, "net/minecraft/server/commands/EnchantCommand"));

		System.setProperty(CarrierDecisions.PROPERTY, "off");
		assertEquals(0, adapt(fabric("fabric-item-api-v1", "net/fabricmc/fabric/mixin/item/EnchantCommandMixin"), "net/minecraft/server/commands/EnchantCommand"));
	}

	/** Against the class the mod was compiled against there is nothing to move: the native call is there. */
	@Test void onTheNativeBodyNothingMoves() throws Exception {
		ClassNode mixin = fabric("fabric-item-api-v1", "net/fabricmc/fabric/mixin/item/EnchantCommandMixin");
		ClassNode nativeBody = natives().apply(Ecosystem.FABRIC, "net/minecraft/server/commands/EnchantCommand");
		assertEquals(0, CarrierDecisions.adapt(mixin, owner -> nativeBody, natives()));
	}

	private static int calls(MethodNode method, String name) {
		int n = 0;
		for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.name.equals(name)) n++;
		return n;
	}

	private static void verify(ClassNode owner, MethodNode method) throws Exception {
		new org.objectweb.asm.tree.analysis.Analyzer<>(new org.objectweb.asm.tree.analysis.BasicVerifier()).analyze(owner.name, method);
	}
}
