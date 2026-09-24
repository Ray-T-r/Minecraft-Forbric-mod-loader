package net.forbric.kernel.transform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TransferTransactionHooksTest {
	private static Path neo() {
		return Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run/neoforge-runtime/neoforge-runtime.jar");
	}
	private static Path fabric() throws Exception {
		Path modules = Path.of("build/transfer-api-compile");
		assumeTrue(Files.isDirectory(modules));
		try (var paths = Files.list(modules)) { return paths.filter(p -> p.getFileName().toString().startsWith("fabric-transfer-api-")).findFirst().orElseThrow(); }
	}
	private static byte[] bytes(Path jar, String name) throws Exception {
		assumeTrue(Files.isRegularFile(jar));
		try (ZipFile zip = new ZipFile(jar.toFile())) { return zip.getInputStream(zip.getEntry(name.replace('.', '/') + ".class")).readAllBytes(); }
	}
	private static ClassNode node(byte[] bytes) { ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0); return node; }
	@Test void allNativeTransactionHooksHaveValidBytecodeAndAreIdempotent() throws Exception {
		for (String name : List.of(TransferTransactionHooks.NEO, TransferTransactionHooks.NEO_MANAGER, TransferTransactionHooks.FABRIC, TransferTransactionHooks.FABRIC_MANAGER)) {
			byte[] original = bytes(name.startsWith("net.fabricmc.") ? fabric() : neo(), name);
			var transformer = new TransferTransactionHooks(); byte[] changed = transformer.transform(name, original, null);
			assertNotSame(original, changed); assertSame(changed, transformer.transform(name, changed, null));
			ClassNode parsed = node(changed);
			assertTrue(parsed.methods.stream().anyMatch(m -> m.name.equals("forbric$transferHooks")));
			for (var method : parsed.methods) if (method.name.equals("close") || method.name.startsWith("forbric$")) {
				new Analyzer<>(new BasicVerifier()).analyze(parsed.name, method);
			}
		}
	}
	@Test void changedNativeValidationRefusesTheHookInsteadOfClaimingCompatibility() throws Exception {
		ClassNode changed = node(bytes(neo(), TransferTransactionHooks.NEO));
		for (var method : changed.methods) if (method.name.equals("close") && method.desc.equals("(Z)V")) {
			for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call && call.name.equals("validateOpen")) call.name = "changedValidation";
		}
		ClassWriter writer = new ClassWriter(0); changed.accept(writer); byte[] drift = writer.toByteArray();
		assertSame(drift, new TransferTransactionHooks().transform(TransferTransactionHooks.NEO, drift, null));
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
		Path merged = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run/merged-base/patched-mc-merged-26.2.jar");
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
		Path merged = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run/merged-base/patched-mc-merged-26.2.jar");
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
		Path forge = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run/forge-runtime/forge-runtime.jar");
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
	@Test void everyRequiredHelperMatchesTheReviewedCarrierAndGameShapes() throws Exception {
		Path stage = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run");
		var helpers = new java.util.LinkedHashSet<>(ForgeTransferShapeAudit.ITEM_HELPERS); helpers.addAll(ForgeTransferShapeAudit.FLUID_HELPERS);
		helpers.addAll(ForgeTransferShapeAudit.ENERGY_HELPERS);
		for (String name : helpers) {
			Path jar = name.startsWith("net.minecraftforge.") ? stage.resolve("forge-runtime/forge-runtime.jar")
					: name.startsWith("net.neoforged.") ? stage.resolve("neoforge-runtime/neoforge-runtime.jar")
					: stage.resolve("merged-base/patched-mc-merged-26.2.jar");
			byte[] approved = ForgeTransferShapeAudit.certify(name, bytes(jar, name));
			assertTrue(node(approved).methods.stream().anyMatch(method -> method.name.equals(ForgeTransferShapeAudit.MARKER)), name);
		}
	}
	/**
	 * Forge's standard EnergyStorage is certified as a whole: the bridge writes through its own receive/extract code and
	 * restores only its int energy field, so a change to any of its methods, or to that field, withdraws the write view.
	 */
	@Test void forgeEnergyStorageCertificateCoversItsWholeTransferContract() throws Exception {
		String name = "net.minecraftforge.energy.EnergyStorage";
		byte[] original = bytes(Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run/forge-runtime/forge-runtime.jar"), name);
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
	@Test void itemStackTooltipChangesRemainAllowedButCountMutationDoesNot() throws Exception {
		String name = "net.minecraft.world.item.ItemStack";
		Path game = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run/merged-base/patched-mc-merged-26.2.jar");
		byte[] original = bytes(game, name);
		for (String changedMethod : List.of("getTooltipLines", "setCount")) {
			ClassNode changed = node(original);
			var target = changed.methods.stream().filter(method -> method.name.equals(changedMethod)).findFirst().orElseThrow();
			target.instructions.insert(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.NOP));
			ClassWriter writer = new ClassWriter(0); changed.accept(writer);
			boolean certified = node(ForgeTransferShapeAudit.certify(name, writer.toByteArray())).methods.stream().anyMatch(method -> method.name.equals(ForgeTransferShapeAudit.MARKER));
			assertEquals(changedMethod.equals("getTooltipLines"), certified, changedMethod);
		}
	}
	@Test void theRealNbtBuilderRepairIsOutsideTransferButCopyChangesAreNot() throws Exception {
		String name = "net.minecraft.nbt.CompoundTag";
		Path game = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run/merged-base/patched-mc-merged-26.2.jar");
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
	@Test void redundantMixinHierarchySignatureIsIgnoredWithoutErasingNewGenericOrExecutableContracts() throws Exception {
		String name = "net.minecraft.world.item.ItemStack";
		Path game = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run/merged-base/patched-mc-merged-26.2.jar");
		byte[] original = bytes(game, name); ClassNode woven = node(original);
		assertNull(woven.signature, "the real staged class has no generic signature");
		woven.interfaces.add("net/fabricmc/fabric/api/item/v1/FabricItemStack");
		woven.interfaces.add("net/fabricmc/fabric/mixin/transfer/ItemStackAccessor");
		StringBuilder erased = new StringBuilder("L").append(woven.superName).append(';');
		for (String contract : woven.interfaces) erased.append('L').append(contract).append(';');
		woven.signature = erased.toString();
		ClassWriter writer = new ClassWriter(0); woven.accept(writer);
		assertEquals(ForgeTransferShapeAudit.fingerprint(original), ForgeTransferShapeAudit.fingerprint(writer.toByteArray()));
		woven.signature = "<T:Ljava/lang/Object;>" + erased;
		writer = new ClassWriter(0); woven.accept(writer);
		assertNotEquals(ForgeTransferShapeAudit.fingerprint(original), ForgeTransferShapeAudit.fingerprint(writer.toByteArray()), "real generic metadata is not erased");
		woven.signature = erased.toString();
		woven.methods.stream().filter(method -> method.name.equals("copy") && method.desc.equals("()Lnet/minecraft/world/item/ItemStack;")).findFirst().orElseThrow()
				.instructions.insert(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.NOP));
		writer = new ClassWriter(0); woven.accept(writer);
		assertFalse(node(ForgeTransferShapeAudit.certify(name, writer.toByteArray())).methods.stream().anyMatch(method -> method.name.equals(ForgeTransferShapeAudit.MARKER)));
	}
	@Test void separateFabricItemInterfacesDoNotHideAnEffectfulVariantCacheGetter() throws Exception {
		String name = "net.minecraft.world.item.Item";
		Path game = Path.of(System.getenv().getOrDefault("FORBRIC_OLD", "../forbric-loader"), "run/merged-base/patched-mc-merged-26.2.jar");
		byte[] original = bytes(game, name); ClassNode woven = node(original);
		woven.interfaces.addAll(List.of("net/fabricmc/fabric/api/item/v1/FabricItem", "net/fabricmc/fabric/impl/item/ItemExtensions", "net/fabricmc/fabric/impl/transfer/item/ItemVariantCache"));
		String type = "Lnet/fabricmc/fabric/api/transfer/v1/item/ItemVariant;";
		woven.fields.add(new org.objectweb.asm.tree.FieldNode(org.objectweb.asm.Opcodes.ACC_PRIVATE, "cachedItemVariant", type, null, null));
		var getter = new org.objectweb.asm.tree.MethodNode(org.objectweb.asm.Opcodes.ACC_PUBLIC, "fabric_getCachedItemVariant", "()" + type, null, null);
		getter.visitCode(); getter.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
		getter.visitFieldInsn(org.objectweb.asm.Opcodes.GETFIELD, woven.name, "cachedItemVariant", type);
		getter.visitInsn(org.objectweb.asm.Opcodes.ARETURN); getter.visitMaxs(1, 1); getter.visitEnd(); woven.methods.add(getter);
		StringBuilder erased = new StringBuilder("L").append(woven.superName).append(';');
		for (String contract : woven.interfaces) erased.append('L').append(contract).append(';'); woven.signature = erased.toString();
		ClassWriter writer = new ClassWriter(0); woven.accept(writer);
		assertEquals(ForgeTransferShapeAudit.fingerprint(original), ForgeTransferShapeAudit.fingerprint(writer.toByteArray()));
		getter.instructions.insert(new MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC, "test/UnprovedSideEffect", "onRead", "()V", false));
		writer = new ClassWriter(0); woven.accept(writer);
		assertFalse(node(ForgeTransferShapeAudit.certify(name, writer.toByteArray())).methods.stream().anyMatch(method -> method.name.equals(ForgeTransferShapeAudit.MARKER)),
				"a field accessor with an additional callback is not the reviewed read-only getter");
	}
}
