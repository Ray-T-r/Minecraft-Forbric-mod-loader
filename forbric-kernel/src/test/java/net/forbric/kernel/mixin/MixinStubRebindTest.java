package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.api.Ecosystem;

/** Fabric mods' injectors on the merged base's carrier stubs, as shipped, against the real merged classes. */
@ResourceLock("system-properties")
class MixinStubRebindTest {
	private static final Path MERGED = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run/merged-base/patched-mc-merged-26.2.jar");
	private static final Path POPULAR = Path.of("run/client-popular/mods");
	private static final Path MERGED_PACK = Path.of("run/client-merged-pack/mods");

	@AfterEach void reset() {
		System.clearProperty(MixinStubRebind.PROPERTY);
		System.clearProperty(MixinStubRebind.MODIFY_VARIABLE_PROPERTY);
		System.clearProperty(MixinStubRebind.CAPTURES_PROPERTY);
		MixinStubRebind.forget();
	}

	@Test void architecturysBreakSpeedMovesToTheBodyAndStillReceivesTheState() throws Exception {
		ClassNode mixin = fromJar(POPULAR.resolve("architectury-fabric-21.1.10.jar"), "dev/architectury/mixin/fabric/MixinPlayer");
		ClassNode player = merged("net/minecraft/world/entity/player/Player");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		assertEquals(1, MixinStubRebind.adapt(mixin, name -> player));
		MethodNode outer = mixin.methods.stream().filter(m -> m.name.equals("breakSpeed")).findFirst().orElseThrow();
		assertEquals(List.of("getDestroySpeed(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)F"),
				MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(outer), "method")));
		assertTrue(outer.desc.startsWith("(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;"
				+ "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;"), outer.desc);
		assertNull(MixinFit.injectorOf(mixin.methods.stream().filter(m -> m.name.equals("breakSpeed" + MixinHandlerShim.INNER_SUFFIX)).findFirst().orElseThrow()));
		List<Integer> loads = Arrays.stream(outer.instructions.toArray()).filter(VarInsnNode.class::isInstance).map(i -> ((VarInsnNode) i).var).toList();
		assertEquals(List.of(0, 1, 3), loads, "this, the state (the stub passes its only argument first), the callback");
		new Analyzer<>(new BasicVerifier()).analyze(mixin.name, outer);
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> player), "a second pass changes nothing");
	}

	@Test void fabricApisElytraCheckMovesWhereItsFieldReadIs() throws Exception {
		ClassNode mixin = StagedFabricMixinFixture.mixin("fabric-entity-events-v1", "net/fabricmc/fabric/mixin/entity/event/elytra/LivingEntityMixin");
		ClassNode living = merged("net/minecraft/world/entity/LivingEntity");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		assertEquals(1, MixinStubRebind.adapt(mixin, name -> living));
		MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals("injectElytraCheck")).findFirst().orElseThrow();
		assertEquals(List.of("canGlide(Z)Z"), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler), "method")));
	}

	@Test void aPairVanillaAlreadyHasStaysWhereTheModPutIt() throws Exception {
		Path xaero;
		try (var files = Files.list(MERGED_PACK)) {
			xaero = files.filter(p -> p.getFileName().toString().contains("xaerominimap-fabric")).findFirst().orElse(null);
		}
		Assumptions.assumeTrue(xaero != null, "Xaero's Minimap (Fabric) fixture absent");
		ClassNode mixin = fromJar(xaero, "xaero/common/mixin/MixinFabricMinecraftClient");
		ClassNode minecraft = merged("net/minecraft/client/Minecraft");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> minecraft),
				"vanilla's own disconnect(Screen, boolean) forwards to its three-argument overload: the mod chose it");
	}

	@Test void aNeoForgeModsMixinIsLeftAsNeoForgeWouldHaveIt() throws Exception {
		ClassNode mixin = fromJar(POPULAR.resolve("architectury-fabric-21.1.10.jar"), "dev/architectury/mixin/fabric/MixinPlayer");
		ClassNode player = merged("net/minecraft/world/entity/player/Player");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.NEOFORGE);
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> player), "compiled against the stub-first shape: native behaviour");
		MixinStubRebind.forget();
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> player), "no known owner: no move");
	}

	@Test void theSwitchMovesNothing() throws Exception {
		System.setProperty(MixinStubRebind.PROPERTY, "off");
		ClassNode mixin = fromJar(POPULAR.resolve("architectury-fabric-21.1.10.jar"), "dev/architectury/mixin/fabric/MixinPlayer");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		ClassNode player = merged("net/minecraft/world/entity/player/Player");
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> player));
	}

	/**
	 * malilib keeps its mods' number formats (%d, %02d, %.2f) out of vanilla's rewrite with a @ModifyArgs on
	 * Language.loadFromJson(InputStream, BiConsumer) — on the merged base a stub passing a no-op lambda to NeoForge's
	 * three-argument body, which is the one ClientLanguage calls. The handler's @Local entry is in the body's table.
	 */
	@Test void malilibsFormatRestoreMovesToTheBodyClientLanguageCalls() throws Exception {
		Path jar = MERGED_PACK.resolve("malilib-fabric-26.2-0.29.3.jar");
		Assumptions.assumeTrue(Files.isRegularFile(jar), "malilib absent from the merged pack");
		ClassNode mixin = fromJar(jar, "fi/dy/masa/malilib/mixin/client/MixinLanguage");
		ClassNode language = merged("net/minecraft/locale/Language");
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		MethodNode handler = mixin.methods.stream().filter(m -> m.name.equals("malilib_onLoadCustomText")).findFirst().orElseThrow();
		String desc = handler.desc;
		assertEquals(1, MixinStubRebind.adapt(mixin, name -> language));
		handler = mixin.methods.stream().filter(m -> m.name.equals("malilib_onLoadCustomText")).findFirst().orElseThrow();
		assertEquals(List.of("loadFromJson(Ljava/io/InputStream;Ljava/util/function/BiConsumer;Ljava/util/function/BiConsumer;)V"),
				MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(handler), "method")));
		assertEquals(desc, handler.desc, "a @ModifyArgs handler keeps its own signature");
		assertTrue(mixin.methods.stream().noneMatch(m -> m.name.endsWith(MixinHandlerShim.INNER_SUFFIX)));
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> language), "a second pass changes nothing");
		MixinStubRebind.forget();
		ClassNode neo = fromJar(jar, "fi/dy/masa/malilib/mixin/client/MixinLanguage");
		MixinStubRebind.noteEcosystem(neo.name, Ecosystem.NEOFORGE);
		assertEquals(0, MixinStubRebind.adapt(neo, name -> language), "not a Fabric mod's mixin");
	}

	/** MixinFit asks the rebind: malilib's hook reads FIT for a Fabric mod (where it moves), PARTIAL otherwise. */
	@Test void theVerdictFollowsTheRebind() throws Exception {
		Path jar = MERGED_PACK.resolve("malilib-fabric-26.2-0.29.3.jar");
		Assumptions.assumeTrue(Files.isRegularFile(jar), "malilib absent from the merged pack");
		byte[] mixin;
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			mixin = zip.getInputStream(zip.getEntry("fi/dy/masa/malilib/mixin/client/MixinLanguage.class")).readAllBytes();
		}
		byte[] language;
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			language = zip.getInputStream(zip.getEntry("net/minecraft/locale/Language.class")).readAllBytes();
		}
		java.util.function.Function<String, byte[]> resolver = name -> name.equals("net/minecraft/locale/Language.class") ? language : null;
		assertEquals(MixinFit.Verdict.PARTIAL, MixinFit.evaluate(mixin, resolver).verdict(), "premise: bound to the stub");
		MixinStubRebind.noteEcosystem("fi/dy/masa/malilib/mixin/client/MixinLanguage", Ecosystem.FABRIC);
		MixinFit.Result fit = MixinFit.evaluate(mixin, resolver);
		assertEquals(MixinFit.Verdict.FIT, fit.verdict(), fit.toString());
		System.setProperty(MixinStubRebind.PROPERTY, "off");
		assertEquals(MixinFit.Verdict.PARTIAL, MixinFit.evaluate(mixin, resolver).verdict(), "the rebind's switch");
	}

	/** A non-capturing lambda is a constant; a capturing one, or a string concatenation, is work the stub does. */
	@Test void aLambdaStubIsAStubButACapturingOneIsNot() throws Exception {
		ClassNode language = merged("net/minecraft/locale/Language");
		MethodNode stub = language.methods.stream().filter(m -> m.name.equals("loadFromJson")
				&& m.desc.equals("(Ljava/io/InputStream;Ljava/util/function/BiConsumer;)V")).findFirst().orElseThrow();
		assertNotNull(MixinStubRebind.delegation(language, stub));
		org.objectweb.asm.tree.InvokeDynamicInsnNode indy = Arrays.stream(stub.instructions.toArray())
				.filter(org.objectweb.asm.tree.InvokeDynamicInsnNode.class::isInstance).map(org.objectweb.asm.tree.InvokeDynamicInsnNode.class::cast)
				.findFirst().orElseThrow();
		String nonCapturing = indy.desc;
		indy.desc = "(Ljava/lang/Object;)Ljava/util/function/BiConsumer;";
		stub.instructions.insertBefore(indy, new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ACONST_NULL));
		assertNull(MixinStubRebind.delegation(language, stub), "a capturing lambda");
		indy.desc = nonCapturing;
		stub.instructions.remove(indy.getPrevious());
		indy.bsm = new org.objectweb.asm.Handle(org.objectweb.asm.Opcodes.H_INVOKESTATIC, "java/lang/invoke/StringConcatFactory",
				"makeConcatWithConstants", indy.bsm.getDesc(), false);
		assertNull(MixinStubRebind.delegation(language, stub), "a string concatenation");
	}

	/** An interface default forwarding to an abstract overload has nowhere to inject: no stub. */
	@Test void aDefaultForwardingToAnAbstractOverloadIsNoStub() throws Exception {
		ClassNode loader = merged("net/minecraft/client/renderer/texture/atlas/SpriteResourceLoader");
		MethodNode forwarding = loader.methods.stream().filter(m -> m.name.equals("loadSprite")
				&& Type.getArgumentTypes(m.desc).length == 2 && m.instructions.size() > 0).findFirst().orElse(null);
		Assumptions.assumeTrue(forwarding != null, "SpriteResourceLoader.loadSprite reshaped");
		assertNull(MixinStubRebind.delegation(loader, forwarding));
	}

	// --- trailing captures of the target's arguments on the @At-driven kinds ---

	private static final String FUEL = "net/minecraft/world/level/block/entity/FuelValues";
	private static final String FUEL_VALUES = "L" + FUEL + ";";
	private static final String PROVIDER = "Lnet/minecraft/core/HolderLookup$Provider;";
	private static final String FLAGS = "Lnet/minecraft/world/flag/FeatureFlagSet;";
	private static final String BUILDER = "L" + FUEL + "$Builder;";
	private static final String STUB_BURN = "vanillaBurnTimes(" + PROVIDER + FLAGS + "I)" + FUEL_VALUES;
	private static final String BODY_BURN = "vanillaBurnTimes(" + BUILDER + "I)" + FUEL_VALUES;
	private static final String STATE = "Lnet/minecraft/world/level/block/state/BlockState;";
	private static final String POS = "Lnet/minecraft/core/BlockPos;";
	private static final String MODIFY_RETURN = "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;";
	private static final String WRAP_OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
	private static final String OPERATION = "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;";
	private static final String MODIFY_VARIABLE = "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";

	/**
	 * torrential's fuel modifier captures all three of the stub's arguments after the value it modifies. Two of them
	 * go into a Builder and never reach the body, so on the body MixinExtras rejects the handler — it stays on the
	 * stub, where it binds, and both adapters and the verdict say so.
	 */
	@Test void aReturnModifierCapturingTheStubsArgumentsStaysOnTheStub() throws Exception {
		ClassNode fuel = merged(FUEL);
		ClassNode mixin = synthetic("sircow/torrential/mixin/FuelValuesMixin", FUEL, "torrential$modifyFuelValues",
				"(" + FUEL_VALUES + PROVIDER + FLAGS + "I)" + FUEL_VALUES, true, injector(MODIFY_RETURN, STUB_BURN, List.of(at("RETURN"))));
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		MethodNode handler = mixin.methods.getFirst();
		assertNull(MixinStubRebind.destination(mixin.name, handler, fuel));
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> fuel));
		assertEquals(List.of(STUB_BURN), selectors(mixin, "torrential$modifyFuelValues"));

		MethodNode stub = fuel.methods.stream().filter(m -> STUB_BURN.equals(m.name + m.desc)).findFirst().orElseThrow();
		MethodNode body = fuel.methods.stream().filter(m -> BODY_BURN.equals(m.name + m.desc)).findFirst().orElseThrow();
		assertFalse(MixinRetarget.handlerFits(handler, MixinFit.injectorOf(handler), fuel, stub, body), "R1 agrees");

		System.setProperty(MixinStubRebind.CAPTURES_PROPERTY, "off");
		assertTrue(MixinRetarget.handlerFits(handler, MixinFit.injectorOf(handler), fuel, stub, body), "the A/B switch: R1 as before");
		assertEquals(1, MixinStubRebind.adapt(mixin, name -> fuel), "the A/B switch: the move that made MixinExtras reject it");
		assertEquals(List.of(BODY_BURN), selectors(mixin, "torrential$modifyFuelValues"));
		System.clearProperty(MixinStubRebind.CAPTURES_PROPERTY);

		ClassNode plain = synthetic("sircow/torrential/mixin/FuelValuesMixin", FUEL, "modify",
				"(" + FUEL_VALUES + ")" + FUEL_VALUES, true, injector(MODIFY_RETURN, STUB_BURN, List.of(at("RETURN"))));
		assertEquals(1, MixinStubRebind.adapt(plain, name -> fuel), "no capture: the move it always made");
		assertEquals(List.of(BODY_BURN), selectors(plain, "modify"));
	}

	/** puzzleslib's break-speed modifier captures the state, which the stub passes through first: it still moves. */
	@Test void aCaptureTheStubPassesThroughInPlaceStillMoves() throws Exception {
		ClassNode player = merged("net/minecraft/world/entity/player/Player");
		ClassNode mixin = synthetic("fuzs/puzzleslib/fabric/mixin/PlayerFabricMixin", "net/minecraft/world/entity/player/Player",
				"getDestroySpeed", "(F" + STATE + ")F", false, injector(MODIFY_RETURN, "getDestroySpeed", List.of(at("TAIL"))));
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		assertEquals(1, MixinStubRebind.adapt(mixin, name -> player));
		assertEquals(List.of("getDestroySpeed(" + STATE + POS + ")F"), selectors(mixin, "getDestroySpeed"));

		ClassNode wrongPrefix = synthetic("fuzs/puzzleslib/fabric/mixin/PlayerFabricMixin", "net/minecraft/world/entity/player/Player",
				"getDestroySpeed", "(F" + POS + ")F", false, injector(MODIFY_RETURN, "getDestroySpeed", List.of(at("TAIL"))));
		assertEquals(0, MixinStubRebind.adapt(wrongPrefix, name -> player), "not a prefix of the stub's arguments");
	}

	/** A wrap whose trailing capture is an argument the stub consumes stays; the same wrap without it moves. */
	@Test void aWrapCapturingAnArgumentTheStubConsumesStays() throws Exception {
		ClassNode fuel = merged(FUEL);
		String add = "L" + FUEL + "$Builder;add(Lnet/minecraft/world/level/ItemLike;I)" + BUILDER;
		String wrap = "(" + BUILDER + "Lnet/minecraft/world/level/ItemLike;I" + OPERATION;
		ClassNode capturing = synthetic("com/example/FuelWrap", FUEL, "wrapAdd", wrap + PROVIDER + ")" + BUILDER, true,
				injector(WRAP_OPERATION, STUB_BURN, List.of(at("INVOKE", "target", add))));
		MixinStubRebind.noteEcosystem(capturing.name, Ecosystem.FABRIC);
		assertEquals(0, MixinStubRebind.adapt(capturing, name -> fuel));
		ClassNode plain = synthetic("com/example/FuelWrap", FUEL, "wrapAdd", wrap + ")" + BUILDER, true,
				injector(WRAP_OPERATION, STUB_BURN, List.of(at("INVOKE", "target", add))));
		assertEquals(1, MixinStubRebind.adapt(plain, name -> fuel));
		assertEquals(List.of(BODY_BURN), selectors(plain, "wrapAdd"));
	}

	/** The injector's own part, per kind: the value, or the receiver and arguments of the access, or declined. */
	@Test void theContractsOwnSizeIsReadFromTheAccessOrDeclined() {
		MethodNode body = new MethodNode(Opcodes.ACC_STATIC, "m", "()V", null, null);
		body.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD, "p/O", "f", "I"));
		body.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC, "p/O", "s", "I"));
		body.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTFIELD, "p/O", "w", "I"));
		body.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTSTATIC, "p/O", "t", "I"));
		body.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD, "p/O", "both", "I"));
		body.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTFIELD, "p/O", "both", "I"));
		body.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "p/O", "st", "(IJ)V", false));
		body.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "p/O", "vi", "(I)V", false));
		String redirect = "Lorg/spongepowered/asm/mixin/injection/Redirect;";
		Type[] many = Type.getArgumentTypes("(IIIIIII)V");
		assertEquals(1, MixinStubRebind.intrinsicArity(injector(redirect, "m", at("FIELD", "target", "Lp/O;f:I")), many, 7, body));
		assertEquals(0, MixinStubRebind.intrinsicArity(injector(redirect, "m", at("FIELD", "target", "Lp/O;s:I")), many, 7, body));
		assertEquals(2, MixinStubRebind.intrinsicArity(injector(redirect, "m", at("FIELD", "target", "Lp/O;w:I")), many, 7, body));
		assertEquals(1, MixinStubRebind.intrinsicArity(injector(redirect, "m", at("FIELD", "target", "Lp/O;t:I")), many, 7, body));
		assertEquals(-1, MixinStubRebind.intrinsicArity(injector(redirect, "m", at("FIELD", "target", "Lp/O;both:I")), many, 7, body),
				"a read and a write: no one shape");
		assertEquals(2, MixinStubRebind.intrinsicArity(injector(redirect, "m", at("INVOKE", "target", "Lp/O;st(IJ)V")), many, 7, body));
		assertEquals(2, MixinStubRebind.intrinsicArity(injector(redirect, "m", at("INVOKE", "target", "Lp/O;vi(I)V")), many, 7, body));
		assertEquals(-1, MixinStubRebind.intrinsicArity(injector(redirect, "m", at("INVOKE", "target", "Lp/O;gone()V")), many, 7, body));
		assertEquals(3, MixinStubRebind.intrinsicArity(injector(redirect, "m", at("NEW", "target", "(IJI)Lp/T;")), many, 7, body));
		assertEquals(-1, MixinStubRebind.intrinsicArity(injector(redirect, "m", at("NEW", "target", "Lp/T;")), many, 7, body));
		assertEquals(1, MixinStubRebind.intrinsicArity(injector(MODIFY_RETURN, "m", at("RETURN")), many, 7, body));
		assertEquals(-1, MixinStubRebind.intrinsicArity(injector(WRAP_OPERATION, "m", at("INVOKE", "target", "Lp/O;vi(I)V")), many, 7, body),
				"a wrap whose third parameter is not the Operation");
		Type[] wrapped = Type.getArgumentTypes("(Lp/O;I" + OPERATION + "J)V");
		assertEquals(3, MixinStubRebind.intrinsicArity(injector(WRAP_OPERATION, "m", at("INVOKE", "target", "Lp/O;vi(I)V")), wrapped, 4, body));
	}

	// --- @ModifyVariable by name ---

	/** torrential's Conduit Power bonus: `speed` is only in the body, stored first right after the tool's speed. */
	@Test void aModifyVariableByNameMovesToTheBodyThatHasTheLocal() throws Exception {
		ClassNode player = merged("net/minecraft/world/entity/player/Player");
		ClassNode mixin = conduit("(F" + STATE + ")F", "speed", 0);
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		assertEquals("getDestroySpeed", MixinStubRebind.destination(mixin.name, mixin.methods.getFirst(), player).name);
		assertEquals(1, MixinStubRebind.adapt(mixin, name -> player));
		assertEquals(List.of("getDestroySpeed(" + STATE + POS + ")F"), selectors(mixin, "torrential$applyConduitModifier"));
		assertEquals(0, MixinStubRebind.adapt(mixin, name -> player), "a second pass changes nothing");

		ClassNode noTable = new ClassNode();
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			new ClassReader(zip.getInputStream(zip.getEntry("net/minecraft/world/entity/player/Player.class")).readAllBytes())
					.accept(noTable, ClassReader.SKIP_DEBUG);
		}
		assertNull(MixinStubRebind.destination(mixin.name, conduit("(F" + STATE + ")F", "speed", 0).methods.getFirst(), noTable),
				"no local variable table, no proof (MixinFit re-reads with it)");
	}

	@Test void aModifyVariableIsLeftWhereNothingProvesTheLocal() throws Exception {
		String[] why = { "by ordinal, not by name", "a name the body does not have", "the name in another type",
				"the name in two slots", "a capture that is not the stub's first argument", "an ordinal past the body's stores",
				"the switch", "a NeoForge mod's mixin" };
		for (int mode = 0; mode < why.length; mode++) {
			ClassNode player = merged("net/minecraft/world/entity/player/Player");
			ClassNode mixin = switch (mode) {
				case 1 -> conduit("(F" + STATE + ")F", "velocity", 0);
				case 2 -> conduit("(I" + STATE + ")I", "speed", 0);
				case 4 -> conduit("(F" + POS + ")F", "speed", 0);
				case 5 -> conduit("(F" + STATE + ")F", "speed", 40);
				default -> conduit("(F" + STATE + ")F", "speed", 0);
			};
			AnnotationNode injector = MixinFit.injectorOf(mixin.methods.getFirst());
			if (mode == 0) {
				injector.values.set(injector.values.indexOf("name"), "ordinal");
				injector.values.set(injector.values.indexOf("ordinal") + 1, 0);
			}
			if (mode == 3) {
				MethodNode body = player.methods.stream().filter(m -> m.name.equals("getDestroySpeed") && m.desc.equals("(" + STATE + POS + ")F"))
						.findFirst().orElseThrow();
				org.objectweb.asm.tree.LocalVariableNode speed = body.localVariables.stream().filter(l -> l.name.equals("speed")).findFirst().orElseThrow();
				body.localVariables.add(new org.objectweb.asm.tree.LocalVariableNode("speed", "F", null, speed.start, speed.end, 9));
			}
			if (mode == 6) System.setProperty(MixinStubRebind.MODIFY_VARIABLE_PROPERTY, "off");
			MixinStubRebind.noteEcosystem(mixin.name, mode == 7 ? Ecosystem.NEOFORGE : Ecosystem.FABRIC);
			assertEquals(0, MixinStubRebind.adapt(mixin, name -> player), why[mode]);
			assertEquals(List.of("getDestroySpeed"), selectors(mixin, "torrential$applyConduitModifier"), why[mode]);
			System.clearProperty(MixinStubRebind.MODIFY_VARIABLE_PROPERTY);
		}
	}

	private static ClassNode conduit(String desc, String local, int ordinal) {
		AnnotationNode injector = injector(MODIFY_VARIABLE, "getDestroySpeed", at("STORE", "ordinal", ordinal));
		injector.values.addAll(List.of("name", new java.util.ArrayList<>(List.of(local))));
		return synthetic("sircow/torrential/mixin/FabricPlayerMixin", "net/minecraft/world/entity/player/Player",
				"torrential$applyConduitModifier", desc, false, injector);
	}

	private static ClassNode synthetic(String name, String target, String handlerName, String desc, boolean isStatic, AnnotationNode injector) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.name = name;
		mixin.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new java.util.ArrayList<>(List.of("value", new java.util.ArrayList<>(List.of(Type.getObjectType(target)))));
		mixin.invisibleAnnotations = new java.util.ArrayList<>(List.of(type));
		MethodNode handler = new MethodNode(Opcodes.ACC_PRIVATE | (isStatic ? Opcodes.ACC_STATIC : 0), handlerName, desc, null, null);
		handler.visibleAnnotations = new java.util.ArrayList<>(List.of(injector));
		mixin.methods = new java.util.ArrayList<>(List.of(handler));
		return mixin;
	}

	private static AnnotationNode injector(String desc, String selector, Object at) {
		AnnotationNode injector = new AnnotationNode(desc);
		injector.values = new java.util.ArrayList<>(List.of("method", new java.util.ArrayList<>(List.of(selector)), "at", at));
		return injector;
	}

	private static AnnotationNode at(String value, Object... more) {
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new java.util.ArrayList<>(List.of("value", value));
		at.values.addAll(List.of(more));
		return at;
	}

	private static List<String> selectors(ClassNode mixin, String handler) {
		return MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(mixin.methods.stream()
				.filter(m -> m.name.equals(handler)).findFirst().orElseThrow()), "method"));
	}

	private static ClassNode merged(String name) throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(MERGED), "actual game required");
		try (ZipFile zip = new ZipFile(MERGED.toFile())) {
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(zip.getEntry(name + ".class")).readAllBytes()).accept(node, 0);
			return node;
		}
	}

	private static ClassNode fromJar(Path jar, String name) throws Exception {
		Assumptions.assumeTrue(Files.isRegularFile(jar), jar + " absent");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(name + ".class");
			assertNotNull(entry, name);
			return MixinFit.parse(zip.getInputStream(entry).readAllBytes());
		}
	}
}
