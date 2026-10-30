package net.forbric.kernel.transform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

import static org.junit.jupiter.api.Assertions.*;

class TransferTransactionHooksTest {
	private static Path neo() {
		return TestFixtures.stagedRoot().resolve(TestFixtures.NEO_CARRIER_JAR);
	}
	private static Path fabric() throws Exception {
		Path modules = Path.of("build/transfer-api-compile");
		// Extracted from Fabric API by extractTransferApis, which runs only where the game side is built.
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(modules), "the game side's transfer API modules are not extracted: " + modules);
		try (var paths = Files.list(modules)) { return paths.filter(p -> p.getFileName().toString().startsWith("fabric-transfer-api-")).findFirst().orElseThrow(); }
	}
	private static byte[] bytes(Path jar, String name) throws Exception {
		return TestFixtures.requireEntry(NativeCoremodParityTest.fixtureOf(jar), jar, name.replace('.', '/') + ".class");
	}
	private static ClassNode node(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
	/** Class bytes by internal name from the NeoForge runtime and Fabric's transfer module, null when in neither. */
	private static java.util.function.Function<String, byte[]> classBytes() throws Exception {
		List<Path> jars = List.of(neo(), fabric());
		return name -> {
			for (Path jar : jars) {
				try (var zip = new java.util.zip.ZipFile(jar.toFile())) {
					var entry = zip.getEntry(name + ".class");
					if (entry != null) try (var in = zip.getInputStream(entry)) { return in.readAllBytes(); }
				} catch (java.io.IOException unreadable) {
					return null;
				}
			}
			return null;
		};
	}
	@Test void fabricsEngineIsFoundFromItsPublicTransaction() throws Exception {
		var engine = TransferTransactionHooks.fabricEngine(classBytes());
		assertNotNull(engine);
		// The names are this build's, read here only to show the search lands where the engine is.
		assertEquals("net/fabricmc/fabric/impl/transfer/transaction/TransactionManagerImpl", engine.manager());
		assertEquals("net/fabricmc/fabric/impl/transfer/transaction/TransactionManagerImpl$TransactionImpl", engine.transaction());
		assertNull(TransferTransactionHooks.fabricEngine(name -> null));
	}
	@Test void allNativeTransactionHooksHaveValidBytecodeAndAreIdempotent() throws Exception {
		var transformer = new TransferTransactionHooks(classBytes());
		List<String> names = new java.util.ArrayList<>(List.of(TransferTransactionHooks.NEO, TransferTransactionHooks.NEO_MANAGER));
		names.addAll(transformer.engine().classes());
		assertEquals(4, names.size());
		for (String name : names) {
			assertTrue(transformer.targets(name), name);
			byte[] original = bytes(name.startsWith("net.neoforged.") ? neo() : fabric(), name);
			byte[] changed = transformer.transform(name, original, null);
			assertNotSame(original, changed); assertSame(changed, transformer.transform(name, changed, null));
			ClassNode parsed = node(changed);
			assertTrue(parsed.methods.stream().anyMatch(m -> m.name.equals(TransferTransactionHooks.MARKER)));
			for (var method : parsed.methods) if (method.name.equals("close") || method.name.startsWith("forbric$")) {
				new Analyzer<>(new BasicVerifier()).analyze(parsed.name, method);
			}
		}
		assertFalse(transformer.targets("net.minecraft.world.level.block.entity.HopperBlockEntity"));
	}
	/** The wrapper validates with the native close's own leading checks; a close that opens with none is not hooked. */
	@Test void aCloseThatDoesNotValidateFirstRefusesTheHookInsteadOfClaimingCompatibility() throws Exception {
		var transformer = new TransferTransactionHooks(classBytes());
		ClassNode changed = node(bytes(neo(), TransferTransactionHooks.NEO));
		for (var method : changed.methods) if (method.name.equals("close") && method.desc.equals("(Z)V")) {
			var first = method.instructions.getFirst();
			while (!(first instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("validateOpen"))) {
				var next = first.getNext();
				if (first.getOpcode() >= 0) method.instructions.remove(first);
				first = next;
			}
			method.instructions.remove(first);
		}
		ClassWriter writer = new ClassWriter(0); changed.accept(writer); byte[] drift = writer.toByteArray();
		assertSame(drift, transformer.transform(TransferTransactionHooks.NEO, drift, null));
	}
	/** A leading call that writes state is not a check: copying it before beforeClose would run it twice. */
	@Test void aLeadingCallThatWritesStateIsNotCopiedAsAValidation() throws Exception {
		var transformer = new TransferTransactionHooks(classBytes());
		ClassNode changed = node(bytes(neo(), TransferTransactionHooks.NEO));
		for (var method : changed.methods) if (method.name.equals("validateOpen")) {
			var write = new org.objectweb.asm.tree.InsnList();
			write.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
			write.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ICONST_1));
			write.add(new org.objectweb.asm.tree.FieldInsnNode(org.objectweb.asm.Opcodes.PUTFIELD, changed.name, "open", "Z"));
			method.instructions.insert(write);
		}
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); changed.accept(writer); byte[] drift = writer.toByteArray();
		byte[] hooked = transformer.transform(TransferTransactionHooks.NEO, drift, null);
		assertNotSame(drift, hooked, "the manager's own check still leads the close");
		MethodNode wrapper = node(hooked).methods.stream().filter(m -> m.name.equals("close") && m.desc.equals("(Z)V")).findFirst().orElseThrow();
		for (var insn : wrapper.instructions) {
			assertFalse(insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("validateOpen"), "a writing call was copied");
		}
	}

	@Test void capabilityFallbackChangesOnlyTheFinalAbsentResult() throws Exception {
		byte[] original = bytes(neo(), TransferCapabilityFallback.TARGET);
		var transformer = new TransferCapabilityFallback(); byte[] changed = transformer.transform(TransferCapabilityFallback.TARGET, original, null);
		assertNotSame(original, changed); assertSame(changed, transformer.transform(TransferCapabilityFallback.TARGET, changed, null));
		ClassNode parsed = node(changed);
		assertTrue(parsed.methods.stream().anyMatch(m -> m.name.equals("forbric$transferFallback")));
		int hooks = 0;
		for (var method : parsed.methods) if (method.name.equals("getCapability")) {
			new Analyzer<>(new BasicVerifier()).analyze(parsed.name, method);
			for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.name.equals("neoFallback")) hooks++;
		}
		assertEquals(1, hooks);
	}
	@Test void forgeFallbackFollowsTheExistingCapabilityCompositionAndKeepsInvalidation() throws Exception {
		Path merged = TestFixtures.stagedBase();
		byte[] original = bytes(merged, ForgeTransferCapabilityFallback.TARGET);
		var transformer = new ForgeTransferCapabilityFallback();
		// Running before composition must decline, rather than claim a hook that never reaches a native provider.
		assertSame(original, transformer.transform(ForgeTransferCapabilityFallback.TARGET, original, null));
		byte[] composed = new ForgeCapabilityCompositionTransformer().transform(ForgeTransferCapabilityFallback.TARGET, original, null);
		byte[] changed = transformer.transform(ForgeTransferCapabilityFallback.TARGET, composed, null);
		assertNotSame(composed, changed); assertSame(changed, transformer.transform(ForgeTransferCapabilityFallback.TARGET, changed, null));
		ClassNode parsed = node(changed);
		assertTrue(parsed.methods.stream().anyMatch(method -> method.name.equals("forbric$forgeTransferFallback")));
		for (String methodName : List.of("getCapability", "invalidateCaps")) {
			var method = parsed.methods.stream().filter(candidate -> candidate.name.equals(methodName)).findFirst().orElseThrow();
			new Analyzer<>(new BasicVerifier()).analyze(parsed.name, method);
			boolean nativeCall = false, fallback = false;
			for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) {
				if (call.name.equals(methodName) && call.owner.contains("CapabilityProvider")) nativeCall = true;
				if (methodName.equals("invalidateCaps") && call.name.equals("invalidate") && call.owner.endsWith("/KernelForgeCapabilities")) nativeCall = true;
				if (call.name.equals(methodName.equals("getCapability") ? "forgeFallback" : "forgeInvalidated")) fallback = true;
			}
			assertTrue(nativeCall, "the real capability composition is still used"); assertTrue(fallback);
		}
	}
	/**
	 * BaseContainerBlockEntity's merged Forge override returns a generic whole-Container InvWrapper for ITEM_HANDLER
	 * before any provider is asked. Every result it returns must pass the owner-first hook, and nothing else changes.
	 */
	@Test void baseContainerItemQueryPassesTheOwnerFirstHook() throws Exception {
		Path merged = TestFixtures.stagedBase();
		byte[] original = bytes(merged, ForgeTransferCapabilityFallback.BASE_CONTAINER);
		var transformer = new ForgeTransferCapabilityFallback();
		byte[] changed = transformer.transform(ForgeTransferCapabilityFallback.BASE_CONTAINER, original, null);
		assertNotSame(original, changed); assertSame(changed, transformer.transform(ForgeTransferCapabilityFallback.BASE_CONTAINER, changed, null));
		ClassNode before = node(original), after = node(changed);
		var query = after.methods.stream().filter(method -> method.name.equals("getCapability") && method.desc.equals(
				"(Lnet/minecraftforge/common/capabilities/Capability;Lnet/minecraft/core/Direction;)Lnet/minecraftforge/common/util/LazyOptional;")).findFirst().orElseThrow();
		new Analyzer<>(new BasicVerifier()).analyze(after.name, query);
		int returns = 0, hooks = 0; boolean generic = false, superCall = false;
		for (var instruction : query.instructions) {
			if (instruction.getOpcode() == org.objectweb.asm.Opcodes.ARETURN) {
				returns++;
				var hook = instruction.getPrevious().getPrevious();
				assertTrue(hook instanceof MethodInsnNode call && call.name.equals("forgeOwnerFirst"), "every result passes the hook");
			}
			if (instruction instanceof MethodInsnNode call && call.name.equals("forgeOwnerFirst")) hooks++;
			if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode field && field.name.equals("itemHandler")) generic = true;
			if (instruction instanceof MethodInsnNode call && call.name.equals("getCapability") && call.owner.endsWith("/BlockEntity")) superCall = true;
		}
		assertTrue(returns >= 2 && hooks == returns, returns + " returns, " + hooks + " hooks");
		assertTrue(generic && superCall, "the native wrapper and the super-call are both still there");
		assertEquals(before.methods.size(), after.methods.size());
		// BlockEntity itself is still handled exactly as before.
		assertSame(original, transformer.transform("net.minecraft.world.level.block.entity.ChestBlockEntity", original, null));
	}
	@Test void standardForgeCertificateRejectsInjectedBehaviorEvenIfClassNameIsUnchanged() throws Exception {
		Path forge = TestFixtures.stagedRoot().resolve("forge-runtime/forge-runtime.jar");
		for (String name : List.of("net.minecraftforge.items.ItemStackHandler", "net.minecraftforge.fluids.capability.templates.FluidTank")) {
			byte[] original = bytes(forge, name);
			byte[] approved = ForgeTransferShapeAudit.certify(name, original);
			assertTrue(node(approved).methods.stream().anyMatch(method -> method.name.equals(ForgeTransferShapeAudit.MARKER)));
			assertEquals(ForgeTransferShapeAudit.fingerprint(original), ForgeTransferShapeAudit.fingerprint(approved));
			assertTrue(node(ForgeTransferShapeAudit.certify(name, approved)).methods.stream().anyMatch(method -> method.name.equals(ForgeTransferShapeAudit.MARKER)));
			ClassNode injected = node(approved);
			var callback = injected.methods.stream().filter(method -> method.name.equals("onContentsChanged")).findFirst().orElseThrow();
			callback.instructions.insert(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.NOP));
			ClassWriter writer = new ClassWriter(0); injected.accept(writer);
			byte[] denied = ForgeTransferShapeAudit.certify(name, writer.toByteArray());
			assertFalse(node(denied).methods.stream().anyMatch(method -> method.name.equals(ForgeTransferShapeAudit.MARKER)),
					"even an existing marker cannot approve changed executable bytes");
		}
	}
	@Test @ResourceLock("native-game-references") void everyRequiredHelperMatchesTheReviewedCarrierAndGameShapes() throws Exception {
		Path stage = TestFixtures.stagedRoot();
		var helpers = new java.util.LinkedHashSet<>(ForgeTransferShapeAudit.ITEM_HELPERS); helpers.addAll(ForgeTransferShapeAudit.FLUID_HELPERS);
		helpers.addAll(ForgeTransferShapeAudit.ENERGY_HELPERS);
		// The game classes the audit proves a merge delegate against, as KernelMixinBootstrap hands them over.
		try (StagedGameClasses game = StagedGameClasses.open()) {
			for (String name : helpers) {
				Path jar = name.startsWith("net.minecraftforge.") ? stage.resolve("forge-runtime/forge-runtime.jar")
						: name.startsWith("net.neoforged.") ? stage.resolve(TestFixtures.NEO_CARRIER_JAR)
						: stage.resolve(TestFixtures.BASE_JAR);
				byte[] approved = ForgeTransferShapeAudit.certify(name, bytes(jar, name), game.resources());
				assertTrue(node(approved).methods.stream().anyMatch(method -> method.name.equals(ForgeTransferShapeAudit.MARKER)), name);
			}
		}
	}
	/**
	 * Forge's standard EnergyStorage is certified as a whole: the bridge writes through its own receive/extract code and
	 * restores only its int energy field, so a change to any of its methods, or to that field, withdraws the write view.
	 */
	@Test void forgeEnergyStorageCertificateCoversItsWholeTransferContract() throws Exception {
		String name = "net.minecraftforge.energy.EnergyStorage";
		byte[] original = bytes(TestFixtures.stagedRoot().resolve("forge-runtime/forge-runtime.jar"), name);
		assertTrue(ForgeTransferShapeAudit.ENERGY_HELPERS.contains(name));
		assertTrue(node(ForgeTransferShapeAudit.certify(name, original)).methods.stream().anyMatch(method -> method.name.equals(ForgeTransferShapeAudit.MARKER)));
		for (String changedMethod : List.of("receiveEnergy", "extractEnergy", "getEnergyStored", "getMaxEnergyStored", "canReceive", "canExtract", "<init>")) {
			ClassNode changed = node(original);
			changed.methods.stream().filter(method -> method.name.equals(changedMethod)).findFirst().orElseThrow()
					.instructions.insert(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.NOP));
			ClassWriter writer = new ClassWriter(0); changed.accept(writer);
			assertFalse(node(ForgeTransferShapeAudit.certify(name, writer.toByteArray())).methods.stream().anyMatch(method -> method.name.equals(ForgeTransferShapeAudit.MARKER)), changedMethod);
		}
		ClassNode retyped = node(original);
		retyped.fields.stream().filter(field -> field.name.equals("energy")).findFirst().orElseThrow().access |= org.objectweb.asm.Opcodes.ACC_VOLATILE;
		ClassWriter writer = new ClassWriter(0); retyped.accept(writer);
		assertFalse(node(ForgeTransferShapeAudit.certify(name, writer.toByteArray())).methods.stream().anyMatch(method -> method.name.equals(ForgeTransferShapeAudit.MARKER)), "energy field");
	}
	@Test @ResourceLock("native-game-references") void itemStackTooltipChangesRemainAllowedButCountMutationDoesNot() throws Exception {
		TestFixtures.requireForgeCarrierWhereTheBaseNamesIt("ItemStack's hierarchy in the base");
		String name = "net.minecraft.world.item.ItemStack";
		Path jar = TestFixtures.stagedBase();
		byte[] original = bytes(jar, name);
		try (StagedGameClasses game = StagedGameClasses.open()) {
			for (String changedMethod : List.of("getTooltipLines", "setCount")) {
				ClassNode changed = node(original);
				var target = changed.methods.stream().filter(method -> method.name.equals(changedMethod)).findFirst().orElseThrow();
				target.instructions.insert(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.NOP));
				ClassWriter writer = new ClassWriter(0); changed.accept(writer);
				boolean certified = node(ForgeTransferShapeAudit.certify(name, writer.toByteArray(), game.resources())).methods.stream().anyMatch(method -> method.name.equals(ForgeTransferShapeAudit.MARKER));
				assertEquals(changedMethod.equals("getTooltipLines"), certified, changedMethod);
			}
		}
	}
	@Test void theRealNbtBuilderRepairIsOutsideTransferButCopyChangesAreNot() throws Exception {
		String name = "net.minecraft.nbt.CompoundTag";
		Path game = TestFixtures.stagedBase();
		byte[] original = bytes(game, name);
		byte[] repaired = new ForbricMergedBaseCompatTransformer().transform(name, original,
				new TransformContext(net.fabricmc.api.EnvType.SERVER, false, "intermediary"));
		assertNotSame(original, repaired);
		assertTrue(node(repaired).methods.stream().anyMatch(method -> method.name.equals("builder")));
		assertEquals(ForgeTransferShapeAudit.fingerprint(original), ForgeTransferShapeAudit.fingerprint(repaired));
		assertTrue(node(ForgeTransferShapeAudit.certify(name, repaired)).methods.stream().anyMatch(method -> method.name.equals(ForgeTransferShapeAudit.MARKER)));
		for (String methodName : List.of("copy", "builder")) {
			ClassNode changed = node(repaired);
			changed.methods.stream().filter(method -> method.name.equals(methodName)).findFirst().orElseThrow().instructions
					.insert(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.NOP));
			ClassWriter writer = new ClassWriter(0); changed.accept(writer);
			assertFalse(node(ForgeTransferShapeAudit.certify(name, writer.toByteArray())).methods.stream().anyMatch(method -> method.name.equals(ForgeTransferShapeAudit.MARKER)), methodName);
		}
	}
	/** An interface a mod's mixin could add: {@code methods} as name, descriptor, access triples (abstract unless default). */
	private static byte[] contract(String name, String... methods) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(org.objectweb.asm.Opcodes.V21, org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_INTERFACE
				| org.objectweb.asm.Opcodes.ACC_ABSTRACT, name, null, "java/lang/Object", null);
		for (int i = 0; i < methods.length; i += 3) {
			boolean body = !methods[i + 2].equals("abstract");
			var method = writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | (methods[i + 2].equals("static") ? org.objectweb.asm.Opcodes.ACC_STATIC : 0)
					| (body ? 0 : org.objectweb.asm.Opcodes.ACC_ABSTRACT), methods[i], methods[i + 1], null, null);
			if (body) {
				method.visitCode();
				org.objectweb.asm.Type returned = org.objectweb.asm.Type.getReturnType(methods[i + 1]);
				if (returned.getSort() == org.objectweb.asm.Type.VOID) method.visitInsn(org.objectweb.asm.Opcodes.RETURN);
				else if (returned.getSort() >= org.objectweb.asm.Type.ARRAY) { method.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL); method.visitInsn(org.objectweb.asm.Opcodes.ARETURN); }
				else { method.visitInsn(org.objectweb.asm.Opcodes.ICONST_0); method.visitInsn(returned.getOpcode(org.objectweb.asm.Opcodes.IRETURN)); }
				method.visitMaxs(1, 8);
			}
			method.visitEnd();
		}
		writer.visitEnd();
		return writer.toByteArray();
	}
	/** The staged game's raw classes, plus {@code extra} (resource path to bytes) standing in for a mod's jar. */
	private static java.util.function.Function<String, byte[]> withMod(StagedGameClasses game, java.util.Map<String, byte[]> extra) {
		return path -> extra.containsKey(path) ? extra.get(path) : game.resources().apply(path);
	}
	@Test @ResourceLock("native-game-references") void redundantMixinHierarchySignatureIsIgnoredWithoutErasingNewGenericOrExecutableContracts() throws Exception {
		TestFixtures.requireForgeCarrierWhereTheBaseNamesIt("ItemStack's hierarchy in the base");
		String name = "net.minecraft.world.item.ItemStack";
		Path game = TestFixtures.stagedBase();
		byte[] original = bytes(game, name); ClassNode woven = node(original);
		assertNull(woven.signature, "the real staged class has no generic signature");
		// A mod's two interfaces: one of default methods named like nothing the audit reviews, one with only a static.
		var mod = java.util.Map.of("org/quarry/duck/Stamped.class", contract("org/quarry/duck/Stamped", "stampOf", "()Ljava/lang/String;", "default",
						"stampWith", "(Ljava/lang/Object;)Z", "default"),
				"org/quarry/duck/Peek.class", contract("org/quarry/duck/Peek", "check", "(Ljava/lang/Object;)Ljava/lang/Object;", "static"));
		woven.interfaces.add("org/quarry/duck/Stamped");
		woven.interfaces.add("org/quarry/duck/Peek");
		StringBuilder erased = new StringBuilder("L").append(woven.superName).append(';');
		for (String contract : woven.interfaces) erased.append('L').append(contract).append(';');
		woven.signature = erased.toString();
		try (StagedGameClasses staged = StagedGameClasses.open()) {
			var resources = withMod(staged, mod);
			ClassWriter writer = new ClassWriter(0); woven.accept(writer);
			assertEquals(ForgeTransferShapeAudit.fingerprint(original, null, resources), ForgeTransferShapeAudit.fingerprint(writer.toByteArray(), null, resources));
			assertTrue(node(ForgeTransferShapeAudit.certify(name, writer.toByteArray(), resources)).methods.stream().anyMatch(method -> method.name.equals(ForgeTransferShapeAudit.MARKER)));
			assertEquals(java.util.Set.of("org/quarry/duck/Stamped", "org/quarry/duck/Peek"), ForgeTransferShapeAudit.excludedInterfaces(name));
			// Without the bytes to judge them by, added interfaces stay in the digest.
			assertNotEquals(ForgeTransferShapeAudit.fingerprint(original), ForgeTransferShapeAudit.fingerprint(writer.toByteArray()));
			woven.signature = "<T:Ljava/lang/Object;>" + erased;
			writer = new ClassWriter(0); woven.accept(writer);
			assertNotEquals(ForgeTransferShapeAudit.fingerprint(original, null, resources), ForgeTransferShapeAudit.fingerprint(writer.toByteArray(), null, resources), "real generic metadata is not erased");
			woven.signature = erased.toString();
			woven.methods.stream().filter(method -> method.name.equals("copy") && method.desc.equals("()Lnet/minecraft/world/item/ItemStack;")).findFirst().orElseThrow()
					.instructions.insert(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.NOP));
			writer = new ClassWriter(0); woven.accept(writer);
			assertFalse(node(ForgeTransferShapeAudit.certify(name, writer.toByteArray(), resources)).methods.stream().anyMatch(method -> method.name.equals(ForgeTransferShapeAudit.MARKER)));
			assertEquals(java.util.Set.of(), ForgeTransferShapeAudit.excludedInterfaces(name));
		}
	}
	/**
	 * Interfaces a mod adds to Item stay out of the digest only while what the class implements for them cannot change a
	 * transfer: a value getter that reads a field of its own, setters, and defaults named like nothing reviewed. A getter
	 * with a callback, or an interface named like a reviewed operation, puts its interface back in.
	 */
	@Test @ResourceLock("native-game-references") void addedItemInterfacesDoNotHideAnEffectfulGetterOrAReviewedName() throws Exception {
		String name = "net.minecraft.world.item.Item";
		Path game = TestFixtures.stagedBase();
		byte[] original = bytes(game, name);
		var mod = java.util.Map.of(
				"org/quarry/duck/Remembers.class", contract("org/quarry/duck/Remembers", "quarry$remembered", "()Ljava/lang/Object;", "abstract"),
				"org/quarry/duck/Configured.class", contract("org/quarry/duck/Configured", "quarry$setHandler", "(Ljava/lang/Object;)V", "abstract",
						"quarry$handler", "()Ljava/lang/Object;", "abstract"),
				"org/quarry/duck/Defaults.class", contract("org/quarry/duck/Defaults", "allowsQuarry", "(Ljava/lang/Object;)Z", "default"),
				"org/quarry/duck/Overrides.class", contract("org/quarry/duck/Overrides", "getDefaultMaxStackSize", "()I", "default"));
		try (StagedGameClasses staged = StagedGameClasses.open()) {
			var resources = withMod(staged, mod);
			ClassNode woven = node(original);
			woven.interfaces.addAll(List.of("org/quarry/duck/Remembers", "org/quarry/duck/Configured", "org/quarry/duck/Defaults"));
			var memo = getter(woven, "quarry$remembered", "memo");
			getter(woven, "quarry$handler", "handler");
			var setter = new org.objectweb.asm.tree.MethodNode(org.objectweb.asm.Opcodes.ACC_PUBLIC, "quarry$setHandler", "(Ljava/lang/Object;)V", null, null);
			setter.visitCode(); setter.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0); setter.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
			setter.visitFieldInsn(org.objectweb.asm.Opcodes.PUTFIELD, woven.name, "handler", "Ljava/lang/Object;");
			setter.visitInsn(org.objectweb.asm.Opcodes.RETURN); setter.visitMaxs(2, 2); setter.visitEnd(); woven.methods.add(setter);
			StringBuilder erased = new StringBuilder("L").append(woven.superName).append(';');
			for (String contract : woven.interfaces) erased.append('L').append(contract).append(';'); woven.signature = erased.toString();
			ClassWriter writer = new ClassWriter(0); woven.accept(writer);
			assertEquals(ForgeTransferShapeAudit.fingerprint(original, null, resources), ForgeTransferShapeAudit.fingerprint(writer.toByteArray(), null, resources));
			memo.instructions.insert(new MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC, "test/UnprovedSideEffect", "onRead", "()V", false));
			writer = new ClassWriter(0); woven.accept(writer);
			assertFalse(node(ForgeTransferShapeAudit.certify(name, writer.toByteArray(), resources)).methods.stream().anyMatch(method -> method.name.equals(ForgeTransferShapeAudit.MARKER)),
					"a field accessor with an additional callback is not a plain read");
			ClassNode overriding = node(original);
			overriding.interfaces.add("org/quarry/duck/Overrides");
			writer = new ClassWriter(0); overriding.accept(writer);
			assertNotEquals(ForgeTransferShapeAudit.fingerprint(original, null, resources), ForgeTransferShapeAudit.fingerprint(writer.toByteArray(), null, resources),
					"an interface naming a reviewed operation stays in the digest");
		}
	}
	private static org.objectweb.asm.tree.MethodNode getter(ClassNode owner, String name, String field) {
		owner.fields.add(new org.objectweb.asm.tree.FieldNode(org.objectweb.asm.Opcodes.ACC_PRIVATE, field, "Ljava/lang/Object;", null, null));
		var getter = new org.objectweb.asm.tree.MethodNode(org.objectweb.asm.Opcodes.ACC_PUBLIC, name, "()Ljava/lang/Object;", null, null);
		getter.visitCode(); getter.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
		getter.visitFieldInsn(org.objectweb.asm.Opcodes.GETFIELD, owner.name, field, "Ljava/lang/Object;");
		getter.visitInsn(org.objectweb.asm.Opcodes.ARETURN); getter.visitMaxs(1, 1); getter.visitEnd(); owner.methods.add(getter);
		return getter;
	}
}
