/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.transform.FabricFreezePointInjector;
import net.forbric.kernel.transform.InjectorExecution;

/**
 * {@link FabricFreezeHookMixinAdapter} on the mixin's class node: a Fabric mod's HEAD and TAIL injectors on
 * {@code BuiltInRegistries.freeze()} move to the two hooks {@link FabricFreezePointInjector} adds, which the kernel calls
 * after the Fabric entrypoints — where fabric-registry-sync puts the freeze on a native Fabric game (issue #52).
 *
 * <p>The guest mixins are real {@code @Mixin} sources compiled here against sponge-mixin, so every annotation value is
 * in the form javac writes it (an enum is a {@code String[2]}, an array a list); {@code BuiltInRegistries} is a stand-in
 * compiled beside them and given its hooks by the real injector. Each mixin has its own name, because the adapter's
 * {@code moved()} rows are process-wide: a case that must not move asserts that its name has no row at all.
 *
 * <p>Everything the adapter refuses is refused whole — the mixin's bytes are the same afterwards — so an injector that
 * needs {@code freeze()}'s own body, or a handler that takes more than its callback, keeps running at the freeze in
 * {@code Bootstrap} instead of at an empty hook where it would silently find nothing.
 */
@ResourceLock("system-properties")
class FabricFreezeHookMixinAdapterTest {
	private static final String PACKAGE = "test/freeze/";
	private static final String HEAD = FabricFreezePointInjector.HEAD_HOOK;
	private static final String TAIL = FabricFreezePointInjector.TAIL_HOOK;
	private static final String CREATE_MIXIN = "com/zurrtum/create/mixin/BuiltInRegistriesMixin";

	/** The handler every one-injector mixin below declares, unless its case is the handler itself. */
	private static final String HANDLER = "static void observe(CallbackInfo ci)";

	/** Create Fly's two injectors, as its {@code BuiltInRegistriesMixin} declares them. */
	private static final String CREATE = """
				@Inject(method = "freeze()V", at = @At("HEAD"))
				private static void onInitialize(CallbackInfo ci) {
					if (!Create.fabricApiLoaded()) Create.register();
				}

				@Inject(method = "freeze()V", at = @At("TAIL"))
				private static void afterFreeze(CallbackInfo ci) {
					Create.registerArmInteractionPoints();
					Create.registerFanProcessingTypes();
				}
			""";

	/** One-injector mixins that move, by name: the injector, and which hook its handler lands on. */
	private static final Map<String, Moving> MOVES = Map.of(
			"BareNameMixin", new Moving("""
					@Inject(method = "freeze", at = @At("HEAD"))""", HEAD),
			"QualifiedMixin", new Moving("""
					@Inject(method = "Lnet/minecraft/core/registries/BuiltInRegistries;freeze()V", at = @At("TAIL"))""", TAIL),
			"BothSpellingsMixin", new Moving("""
					@Inject(method = {"freeze", "freeze()V"}, at = @At("HEAD"))""", HEAD),
			// RETURN in a void method with one return is TAIL: the hook has one return as well.
			"ReturnMixin", new Moving("""
					@Inject(method = "freeze()V", at = @At("RETURN"))""", TAIL),
			// Options that do not depend on freeze()'s body are carried over as written.
			"ToleratedOptionsMixin", new Moving("""
					@Inject(method = "freeze()V", at = @At(value = "TAIL", ordinal = 0, remap = false), remap = false, \
					locals = LocalCapture.NO_CAPTURE)""", TAIL),
			// Cancelling at TAIL only returns from a void method that has already frozen: nothing for the move to lose.
			"CancellableTailMixin", new Moving("""
					@Inject(method = "freeze()V", at = @At("TAIL"), cancellable = true)""", TAIL),
			// bootStrap()'s call of freeze(): LiquidBounce's creative tabs hang right before it — the freeze's HEAD.
			"BootStrapCallMixin", new Moving("""
					@Inject(method = "bootStrap", at = @At(value = "INVOKE", \
					target = "Lnet/minecraft/core/registries/BuiltInRegistries;freeze()V"))""", HEAD),
			"BootStrapCallBeforeMixin", new Moving("""
					@Inject(method = "bootStrap()V", at = @At(value = "INVOKE", target = "freeze()V", \
					shift = At.Shift.BEFORE, ordinal = 0))""", HEAD),
			// Right after it nothing has run since the freeze returned: its TAIL.
			"BootStrapCallAfterMixin", new Moving("""
					@Inject(method = "Lnet/minecraft/core/registries/BuiltInRegistries;bootStrap()V", at = @At(value = "INVOKE", \
					target = "Lnet/minecraft/core/registries/BuiltInRegistries;freeze()V", shift = At.Shift.AFTER))""", TAIL));

	/** One-handler mixins that stay where they are, by name: what its class body declares. */
	private static final Map<String, String> STAYS = Map.ofEntries(
			// An instruction inside freeze(): the empty hook has none.
			Map.entry("InvokeMixin", one("""
					@Inject(method = "freeze()V", at = @At(value = "INVOKE", \
					target = "Lnet/minecraft/core/WritableRegistry;freeze()Lnet/minecraft/core/Registry;"))""", HANDLER)),
			Map.entry("ShiftMixin", one("""
					@Inject(method = "freeze()V", at = @At(value = "TAIL", shift = At.Shift.BEFORE))""", HANDLER)),
			Map.entry("ByMixin", one("""
					@Inject(method = "freeze()V", at = @At(value = "HEAD", by = 1))""", HANDLER)),
			// The hook has one return; a third would not be found.
			Map.entry("OrdinalMixin", one("""
					@Inject(method = "freeze()V", at = @At(value = "RETURN", ordinal = 2))""", HANDLER)),
			Map.entry("SliceMixin", one("""
					@Inject(method = "freeze()V", at = @At("TAIL"), slice = @Slice(from = @At("HEAD")))""", HANDLER)),
			// freeze()'s locals are not the hook's.
			// A cancel at HEAD skips the freeze; on the empty hook it would skip nothing.
			Map.entry("CancellableHeadMixin", one("""
					@Inject(method = "freeze()V", at = @At("HEAD"), cancellable = true)""", HANDLER)),
			Map.entry("LocalsMixin", one("""
					@Inject(method = "freeze()V", at = @At("TAIL"), locals = LocalCapture.CAPTURE_FAILHARD)""", HANDLER)),
			// The descriptor a MixinExtras @Local sugar leaves: a local the hook does not have.
			Map.entry("ExtraParameterMixin", one("""
					@Inject(method = "freeze()V", at = @At("TAIL"))""", "static void observe(CallbackInfo ci, int captured)")),
			Map.entry("InstanceHandlerMixin", one("""
					@Inject(method = "freeze()V", at = @At("TAIL"))""", "void observe(CallbackInfo ci)")),
			// Only the injector kind differs from a moving shape.
			Map.entry("RedirectMixin", one("""
					@Redirect(method = "freeze()V", at = @At("HEAD"))""", HANDLER)),
			Map.entry("ModifyArgMixin", one("""
					@ModifyArg(method = "freeze()V", at = @At("HEAD"))""", HANDLER)),
			// The same handler would run at the hook and in bootStrap(): it cannot be in both places at once.
			Map.entry("AlsoBootStrapMixin", one("""
					@Inject(method = {"freeze()V", "bootStrap()V"}, at = @At("TAIL"))""", HANDLER)),
			// ViaFabricPlus' registry hook: before the contents, not at the freeze; it registers what it needs there.
			Map.entry("BootStrapContentsMixin", one("""
					@Inject(method = "bootStrap", at = @At(value = "INVOKE", \
					target = "Lnet/minecraft/core/registries/BuiltInRegistries;createContents()V"))""", HANDLER)),
			Map.entry("BootStrapHeadMixin", one("""
					@Inject(method = "bootStrap", at = @At("HEAD"))""", HANDLER)),
			Map.entry("BootStrapTailMixin", one("""
					@Inject(method = "bootStrap", at = @At("TAIL"))""", HANDLER)),
			// A cancel before the call returns from bootStrap() unfrozen; the empty hook cannot do that.
			Map.entry("BootStrapCancellableCallMixin", one("""
					@Inject(method = "bootStrap", at = @At(value = "INVOKE", \
					target = "Lnet/minecraft/core/registries/BuiltInRegistries;freeze()V"), cancellable = true)""", HANDLER)),
			Map.entry("BootStrapCallByMixin", one("""
					@Inject(method = "bootStrap", at = @At(value = "INVOKE", \
					target = "Lnet/minecraft/core/registries/BuiltInRegistries;freeze()V", shift = At.Shift.BY, by = 1))""", HANDLER)),
			// bootStrap() calls freeze() once; a second call would not be found.
			Map.entry("BootStrapCallOrdinalMixin", one("""
					@Inject(method = "bootStrap", at = @At(value = "INVOKE", \
					target = "Lnet/minecraft/core/registries/BuiltInRegistries;freeze()V", ordinal = 1))""", HANDLER)),
			// Some other freeze() — a registry's, not BuiltInRegistries'.
			Map.entry("BootStrapOtherFreezeMixin", one("""
					@Inject(method = "bootStrap", at = @At(value = "INVOKE", \
					target = "Lnet/minecraft/core/WritableRegistry;freeze()Lnet/minecraft/core/Registry;"))""", HANDLER)));

	/** Create Fly's shape under names no case lets move, for the conditions outside the mixin's own injectors. */
	private static final List<String> CREATE_SHAPED = List.of("CreateShapedMixin", "TwiceMixin", "ForeignOwnedMixin",
			"NoRegistrySyncMixin", "UnhookedMixin", "SwitchedOffMixin");

	@TempDir static Path work;
	private static Map<String, byte[]> classes;

	private Supplier<Boolean> registrySync;

	private record Moving(String injector, String hook) {
	}

	@BeforeAll
	static void compile() throws Exception {
		Map<String, String> sources = new TreeMap<>();
		sources.put("net.minecraft.core.registries.BuiltInRegistries", """
				package net.minecraft.core.registries;

				public final class BuiltInRegistries {
					public static void bootStrap() {
						createContents();
						freeze();
						validate();
					}

					private static void createContents() {
					}

					private static void freeze() {
					}

					private static void validate() {
					}
				}
				""");
		sources.put("test.freeze.Create", """
				package test.freeze;

				public final class Create {
					public static boolean fabricApiLoaded() {
						return true;
					}

					public static void register() {
					}

					public static void registerArmInteractionPoints() {
					}

					public static void registerFanProcessingTypes() {
					}
				}
				""");
		for (String name : CREATE_SHAPED) sources.put(binary(name), mixin(name, "@Mixin(BuiltInRegistries.class)", CREATE));
		for (var shape : MOVES.entrySet()) {
			sources.put(binary(shape.getKey()), mixin(shape.getKey(), "@Mixin(BuiltInRegistries.class)",
					one(shape.getValue().injector(), HANDLER)));
		}
		for (var shape : STAYS.entrySet()) {
			sources.put(binary(shape.getKey()), mixin(shape.getKey(), "@Mixin(BuiltInRegistries.class)", shape.getValue()));
		}
		sources.put(binary("TwoTargetsMixin"), mixin("TwoTargetsMixin", """
				@Mixin(targets = {"net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.core.Registry"})""", CREATE));
		sources.put(binary("MixedMixin"), mixin("MixedMixin", "@Mixin(BuiltInRegistries.class)", """
					@Inject(method = "freeze()V", at = @At("HEAD"))
					private static void before(CallbackInfo ci) {
						Create.register();
					}

					@Inject(method = "freeze()V", at = @At(value = "INVOKE", \
				target = "Lnet/minecraft/core/WritableRegistry;freeze()Lnet/minecraft/core/Registry;"))
					private static void inside(CallbackInfo ci) {
						Create.register();
					}
				"""));
		classes = InjectorExecution.compile(work, sources);
	}

	@BeforeEach
	void fabricRegistrySyncIsInTheGame() {
		registrySync = FabricFreezeHookMixinAdapter.registrySyncPresent;
		FabricFreezeHookMixinAdapter.registrySyncPresent = () -> true;
	}

	@AfterEach
	void reset() {
		FabricFreezeHookMixinAdapter.registrySyncPresent = registrySync;
		MixinStubRebind.forget();
	}

	@Test
	void createsHeadAndTailInjectorsMoveToTheirHooksAndKeepTheirBodies() {
		ClassNode mixin = fabric("CreateShapedMixin");
		Map<String, String> bodies = bodies(mixin);
		assertEquals(2, FabricFreezeHookMixinAdapter.adapt(mixin, hooked()));
		assertEquals(Map.of("onInitialize", List.of(HEAD + "()V"), "afterFreeze", List.of(TAIL + "()V")), injected(mixin));
		assertEquals("HEAD", MixinFit.value(StagedFabricMixinFixture.at(mixin, "onInitialize"), "value"));
		assertEquals("TAIL", MixinFit.value(StagedFabricMixinFixture.at(mixin, "afterFreeze"), "value"));
		assertEquals(bodies, bodies(mixin));
		assertEquals(List.of(mixin.name + "#afterFreeze -> " + TAIL, mixin.name + "#onInitialize -> " + HEAD), rows(mixin));
		// What Mixin reads is the class file: the rewritten method arrays must survive being written.
		ClassNode written = MixinFit.parse(StagedFabricMixinFixture.bytes(mixin));
		assertEquals(injected(mixin), injected(written));
	}

	@Test
	void aMovedMixinIsNotMovedAgain() {
		ClassNode mixin = fabric("TwiceMixin");
		assertEquals(2, FabricFreezeHookMixinAdapter.adapt(mixin, hooked()));
		byte[] moved = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, FabricFreezeHookMixinAdapter.adapt(mixin, hooked()));
		assertArrayEquals(moved, StagedFabricMixinFixture.bytes(mixin));
		assertEquals(2, rows(mixin).size(), rows(mixin).toString());
	}

	/** Every spelling of {@code freeze()} Mixin accepts, and RETURN as the TAIL it is in a one-return method. */
	@ParameterizedTest(name = "{0}")
	@MethodSource("moving")
	void eachSpellingOfFreezeMovesToTheHookItsPointNames(String name) {
		ClassNode mixin = fabric(name);
		String hook = MOVES.get(name).hook();
		Map<String, String> bodies = bodies(mixin);
		assertEquals(1, FabricFreezeHookMixinAdapter.adapt(mixin, hooked()), name);
		assertEquals(Map.of("observe", List.of(hook + "()V")), injected(mixin));
		assertEquals(bodies, bodies(mixin));
		assertEquals(List.of(mixin.name + "#observe -> " + hook), rows(mixin));
	}

	/** On the empty hook there is no call of freeze() to find: the point becomes the edge of the hook it stood for. */
	@ParameterizedTest(name = "{0}")
	@MethodSource("movingOffTheCall")
	void anInjectorAtBootStrapsCallOfFreezeBecomesTheEdgeOfItsHook(String name) {
		ClassNode mixin = fabric(name);
		assertEquals(1, FabricFreezeHookMixinAdapter.adapt(mixin, hooked()), name);
		var at = StagedFabricMixinFixture.at(mixin, "observe");
		assertEquals(HEAD.equals(MOVES.get(name).hook()) ? "HEAD" : "TAIL", MixinFit.value(at, "value"));
		assertEquals(List.of("value", MixinFit.value(at, "value")), at.values, name);
		// What Mixin reads is the class file: the new point must survive being written.
		ClassNode written = MixinFit.parse(StagedFabricMixinFixture.bytes(mixin));
		assertEquals(MixinFit.value(at, "value"), MixinFit.value(StagedFabricMixinFixture.at(written, "observe"), "value"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("staying")
	void anInjectorThatNeedsFreezesBodyOrMoreThanItsCallbackStays(String name) {
		assertStays(fabric(name), hooked());
	}

	/** text_styles' NeoForge injector there runs where NeoForge freezes; a mixin of no known owner is not guessed at. */
	@ParameterizedTest(name = "{0}")
	@NullSource
	@EnumSource(value = Ecosystem.class, names = { "FORGE", "NEOFORGE" })
	void aMixinThatIsNotAFabricModsStays(Ecosystem owner) {
		ClassNode mixin = parse("ForeignOwnedMixin");
		if (owner != null) MixinStubRebind.noteEcosystem(mixin.name, owner);
		assertStays(mixin, hooked());
	}

	/** Without fabric-registry-sync a Fabric game freezes in Bootstrap too, and Create's HEAD injector relies on it. */
	@Test
	void withoutFabricRegistrySyncNothingMoves() {
		for (Supplier<Boolean> answer : List.<Supplier<Boolean>>of(() -> false, () -> null)) {
			FabricFreezeHookMixinAdapter.registrySyncPresent = answer;
			assertStays(fabric("NoRegistrySyncMixin"), hooked());
		}
	}

	@Test
	void withoutBothHooksOnTheTargetNothingMoves() {
		ClassNode plain = MixinFit.parse(classes.get(FabricFreezeHookMixinAdapter.BUILT_IN_REGISTRIES));
		ClassNode headOnly = hooked().apply(FabricFreezeHookMixinAdapter.BUILT_IN_REGISTRIES);
		headOnly.methods.removeIf(m -> m.name.equals(TAIL));
		for (Function<String, ClassNode> targets : List.<Function<String, ClassNode>>of(only(plain), only(headOnly), name -> null)) {
			assertStays(fabric("UnhookedMixin"), targets);
		}
	}

	@Test
	void theSwitchOffMovesNothing() {
		Function<String, ClassNode> hooked = hooked();
		String old = System.setProperty(FabricFreezePointInjector.PROPERTY, "off");
		try {
			assertStays(fabric("SwitchedOffMixin"), hooked);
		} finally {
			if (old == null) System.clearProperty(FabricFreezePointInjector.PROPERTY);
			else System.setProperty(FabricFreezePointInjector.PROPERTY, old);
		}
	}

	/** A mixin applied to a second class as well would move off that class's freeze too. */
	@Test
	void aMixinWithASecondTargetStays() {
		assertStays(fabric("TwoTargetsMixin"), hooked());
	}

	/** Each injector is judged alone: the one inside freeze() stays in Bootstrap while its neighbour moves. */
	@Test
	void onlyTheEligibleInjectorOfAMixinMoves() {
		ClassNode mixin = fabric("MixedMixin");
		assertEquals(1, FabricFreezeHookMixinAdapter.adapt(mixin, hooked()));
		assertEquals(Map.of("before", List.of(HEAD + "()V"), "inside", List.of("freeze()V")), injected(mixin));
		assertEquals(List.of(mixin.name + "#before -> " + HEAD), rows(mixin));
	}

	/** The released jar, so a Create Fly that changes either injector is noticed here and not at boot. */
	@Test
	void releasedCreateFlyMovesOnInitializeToTheHeadHookAndAfterFreezeToTheTailHook() throws Exception {
		ClassNode mixin = CreateGuestMixinFixture.mixin(CREATE_MIXIN);
		MixinStubRebind.noteEcosystem(CREATE_MIXIN, Ecosystem.FABRIC);
		Map<String, String> bodies = bodies(mixin);
		assertEquals(2, FabricFreezeHookMixinAdapter.adapt(mixin, hooked()));
		assertEquals(Map.of("onInitialize", List.of(HEAD + "()V"), "afterFreeze", List.of(TAIL + "()V")), injected(mixin));
		assertEquals(bodies, bodies(mixin));
		assertEquals(List.of(CREATE_MIXIN + "#afterFreeze -> " + TAIL, CREATE_MIXIN + "#onInitialize -> " + HEAD), rows(mixin));
		assertEquals(0, FabricFreezeHookMixinAdapter.adapt(mixin, hooked()));
	}

	static Stream<String> moving() {
		return MOVES.keySet().stream().sorted();
	}

	static Stream<String> movingOffTheCall() {
		return MOVES.keySet().stream().filter(name -> name.startsWith("BootStrapCall")).sorted();
	}

	static Stream<String> staying() {
		return STAYS.keySet().stream().sorted();
	}

	/** Refused whole: nothing returned, not a byte of the mixin changed, and no row under its name. */
	private static void assertStays(ClassNode mixin, Function<String, ClassNode> targets) {
		byte[] before = StagedFabricMixinFixture.bytes(mixin);
		assertEquals(0, FabricFreezeHookMixinAdapter.adapt(mixin, targets), mixin.name);
		assertArrayEquals(before, StagedFabricMixinFixture.bytes(mixin), mixin.name + " was rewritten");
		assertEquals(List.of(), rows(mixin));
	}

	/** {@code BuiltInRegistries} as the kernel loads it: the stand-in through the real {@link FabricFreezePointInjector}. */
	private static Function<String, ClassNode> hooked() {
		byte[] plain = classes.get(FabricFreezeHookMixinAdapter.BUILT_IN_REGISTRIES);
		return only(MixinFit.parse(new FabricFreezePointInjector().transform(FabricFreezePointInjector.TARGET, plain, null)));
	}

	private static Function<String, ClassNode> only(ClassNode builtInRegistries) {
		return name -> name.equals(FabricFreezeHookMixinAdapter.BUILT_IN_REGISTRIES) ? builtInRegistries : null;
	}

	private static ClassNode parse(String name) {
		return MixinFit.parse(classes.get(PACKAGE + name));
	}

	private static ClassNode fabric(String name) {
		ClassNode mixin = parse(name);
		MixinStubRebind.noteEcosystem(mixin.name, Ecosystem.FABRIC);
		return mixin;
	}

	/** Each injector handler's {@code method} list, by handler name. */
	private static Map<String, List<String>> injected(ClassNode mixin) {
		Map<String, List<String>> out = new HashMap<>();
		for (MethodNode method : mixin.methods) {
			var injector = MixinFit.injectorOf(method);
			if (injector != null) out.put(method.name, MixinFit.stringList(MixinFit.value(injector, "method")));
		}
		return out;
	}

	private static Map<String, String> bodies(ClassNode mixin) {
		Map<String, String> out = new HashMap<>();
		for (MethodNode method : mixin.methods) out.put(method.name + method.desc, MixinInstructionFingerprint.hash(method));
		return out;
	}

	private static List<String> rows(ClassNode mixin) {
		return FabricFreezeHookMixinAdapter.moved().stream().filter(row -> row.startsWith(mixin.name + "#")).toList();
	}

	private static String binary(String name) {
		return (PACKAGE + name).replace('/', '.');
	}

	/** One injector over a handler {@code observe} that calls into Create, so its body is something to keep. */
	private static String one(String injector, String handler) {
		return "\t" + injector + "\n\tprivate " + handler + " {\n\t\tCreate.register();\n\t}\n";
	}

	private static String mixin(String name, String annotation, String body) {
		return """
				package test.freeze;

				import net.minecraft.core.registries.BuiltInRegistries;
				import org.spongepowered.asm.mixin.Mixin;
				import org.spongepowered.asm.mixin.injection.At;
				import org.spongepowered.asm.mixin.injection.Inject;
				import org.spongepowered.asm.mixin.injection.ModifyArg;
				import org.spongepowered.asm.mixin.injection.Redirect;
				import org.spongepowered.asm.mixin.injection.Slice;
				import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
				import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

				%s
				public abstract class %s {
				%s}
				""".formatted(annotation, name, body);
	}
}
