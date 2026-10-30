package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

import net.fabricmc.api.EnvType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import net.forbric.kernel.ExpectCaught;
import net.forbric.kernel.TestFixtures;

/**
 * Every class the transfer audit certifies, read from the staged jars and put through the transformers KernelBoot
 * registers before Mixin, in KernelBoot's own phases, sort indexes and order, is still certified.
 *
 * <p>{@link ForgeTransferShapeAuditStagedTest} puts the audited classes through {@link VanillaEarlyReturns} alone,
 * and that was the whole of what any unit test knew about the shape the game defines. It is not the whole chain:
 * the merged-base repair drops ItemStack's and PatchedDataComponentMap's interface-default delegates, the lambda
 * thunk injector gives FluidStack, ItemResource and FluidResource their own invocation thunks, and the predicate
 * getter recorder records FluidResource's fluid type on the way out. Each of those is behaviour-preserving, and each
 * one alone made the final class a shape nobody had reviewed -- so every ItemStackHandler and FluidTank was refused
 * as not rollback-safe, and only gate-m33 and gate-m39 said so.
 *
 * <p>The chain is read from KernelBoot's bytecode, not restated: every {@code chain.register(PHASE, new X(...)[, n])}
 * whose constructor the test can supply -- no arguments, or a game-resource lookup -- is registered exactly where
 * KernelBoot registers it, whatever condition guards it at boot. A transformer that needs KernelBoot's own runtime
 * state (worker declarations, the guest loader) is left out and named in the output.
 */
@ResourceLock("native-game-references")
class ForgeTransferShapeAuditChainStagedTest {
	private static final String KERNEL_BOOT = "net/forbric/kernel/boot/KernelBoot";
	private static final String CHAIN = "net/forbric/kernel/transform/TransformChain";
	private static final String PHASE = "net/forbric/kernel/transform/TransformPhase";

	/** {@code chain.register(phase, new transformer(...), sortIndex)} at instruction {@code position} of KernelBoot. */
	record Registration(String transformer, TransformPhase phase, int sortIndex, String constructor, long position) { }

	private static List<Path> jars() { return StagedGameClasses.jars(); }
	private static byte[] resource(List<Path> jars, String name) { return StagedGameClasses.resource(jars, name); }

	/** Every direct {@code chain.register(PHASE, new X(...)[, n])} in KernelBoot, in registration order. */
	static List<Registration> registrations() throws IOException {
		ClassNode boot = new ClassNode();
		try (InputStream in = ForgeTransferShapeAuditChainStagedTest.class.getClassLoader().getResourceAsStream(KERNEL_BOOT + ".class")) {
			assertTrue(in != null, "KernelBoot is not on the test classpath");
			new ClassReader(in).accept(boot, 0);
		}
		List<Registration> found = new ArrayList<>();
		for (int m = 0; m < boot.methods.size(); m++) {
			AbstractInsnNode[] insns = real(boot.methods.get(m));
			for (int i = 1; i < insns.length; i++) {
				if (!(insns[i] instanceof TypeInsnNode type) || type.getOpcode() != Opcodes.NEW
						|| !(insns[i - 1] instanceof FieldInsnNode phase) || phase.getOpcode() != Opcodes.GETSTATIC || !phase.owner.equals(PHASE)) continue;
				int ctor = i, nested = 0;
				while (++ctor < insns.length) {
					if (insns[ctor] instanceof TypeInsnNode inner && inner.getOpcode() == Opcodes.NEW && inner.desc.equals(type.desc)) nested++;
					if (insns[ctor] instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
							&& call.owner.equals(type.desc) && call.name.equals("<init>") && nested-- == 0) break;
				}
				if (ctor + 1 >= insns.length) continue;
				int at = ctor + 1, sortIndex = 0;
				Integer constant = intConstant(insns[at]);
				if (constant != null) { sortIndex = constant; at++; }
				if (at < insns.length && insns[at] instanceof MethodInsnNode register && register.owner.equals(CHAIN) && register.name.equals("register"))
					found.add(new Registration(type.desc.replace('/', '.'), TransformPhase.valueOf(phase.name), sortIndex,
							((MethodInsnNode) insns[ctor]).desc, ((long) m << 32) | i));
			}
		}
		found.sort(Comparator.comparingLong(Registration::position));
		return found;
	}

	/** The chain those registrations make, with game resources served from {@code jars}; the rest are returned in {@code skipped}. */
	static TransformChain chain(List<Path> jars, List<String> registered, List<String> skipped) throws Exception {
		TransformChain chain = new TransformChain();
		for (Registration r : registrations()) {
			Class<?> type = Class.forName(r.transformer());
			Constructor<?> constructor = Arrays.stream(type.getDeclaredConstructors())
					.filter(c -> org.objectweb.asm.Type.getConstructorDescriptor(c).equals(r.constructor())).findFirst().orElseThrow();
			Object[] arguments = new Object[constructor.getParameterCount()];
			boolean supplied = true;
			for (int p = 0; p < arguments.length; p++) {
				arguments[p] = lookup(constructor.getGenericParameterTypes()[p], jars);
				supplied &= arguments[p] != null;
			}
			String name = type.getSimpleName();
			if (!supplied) { skipped.add(name + r.constructor()); continue; }
			constructor.setAccessible(true);
			chain.register(r.phase(), (ClassTransformer) constructor.newInstance(arguments), r.sortIndex());
			registered.add(name);
		}
		return chain;
	}

	/** A game-resource lookup of the parameter's type, or null when the parameter is something else. */
	private static Object lookup(Type parameter, List<Path> jars) {
		if (!(parameter instanceof ParameterizedType generic)) return null;
		Type[] types = generic.getActualTypeArguments();
		if (generic.getRawType() == Predicate.class && types[0] == String.class) return (Predicate<String>) name -> resource(jars, name) != null;
		if (generic.getRawType() != Function.class || types[0] != String.class) return null;
		if (types[1] == byte[].class) return (Function<String, byte[]>) name -> resource(jars, name);
		if (types[1] == Boolean.class) return (Function<String, Boolean>) name -> resource(jars, name) != null;
		return null;
	}

	// The staged jars and the test classpath carry no fastutil, which the frame recomputer's hierarchy walk reaches from
	// DataComponentPatch; it abstains there, as the chain's last step, and the audit is about the steps before it.
	@ExpectCaught("cannot prove a frame repair")
	@Test void everyAuditedClassIsCertifiedAfterKernelBootsPreMixinChain() throws Exception {
		TestFixtures.requireForgeCarrier("MinecraftForge's capability and item-handler classes the transfer audit certifies");
		Map<String, String> refused = new TreeMap<>();
		TreeSet<String> rewritten = new TreeSet<>();
		// The merged-base repair proves a dropped delegate against the merged base's native-reference index, as at boot,
		// and the audit is handed the same raw game classes KernelMixinBootstrap hands it.
		try (StagedGameClasses game = StagedGameClasses.open()) {
			List<Path> jars = game.paths();
			List<String> registered = new ArrayList<>(), skipped = new ArrayList<>();
			TransformChain chain = chain(jars, registered, skipped);
			System.out.println("[TransferAuditChain] " + registered.size() + " transformer(s) registered; not reproducible here: " + skipped);
			for (EnvType side : EnvType.values()) {
				TransformContext context = new TransformContext(side, false, "named");
				for (String name : new TreeSet<>(ForgeTransferShapeAudit.auditedClasses())) {
					byte[] staged = resource(jars, name);
					assertTrue(staged != null, name + " is not in the staged jars: the fixture has drifted");
					byte[] defined = chain.applyBeforeMixin(name, staged, context);
					if (!Arrays.equals(staged, defined)) rewritten.add(name);
					if (!marked(ForgeTransferShapeAudit.certify(name, defined, game.resources())))
						refused.put(side + " " + name, ForgeTransferShapeAudit.fingerprint(defined, game.proof()));
				}
			}
		}
		// The chain must really have rewritten the classes the gates met, or this proves nothing about them.
		assertTrue(rewritten.containsAll(List.of("net.minecraft.world.item.ItemStack", "net.minecraftforge.fluids.FluidStack")),
				"premise: the pre-Mixin chain rewrites ItemStack and FluidStack; it rewrote " + rewritten);
		assertEquals(Map.of(), refused, "audited classes the kernel's own pre-Mixin chain turns into a shape the audit refuses "
				+ "(side class -> observed fingerprint): every ItemStackHandler/FluidTank using them would be refused in game");
	}

	@Test void aThunkThatForwardsSomewhereElseIsStillRefused() throws Exception {
		TestFixtures.requireForgeCarrier("MinecraftForge's capability and item-handler classes the transfer audit certifies");
		// Folding the kernel's own thunks back to the handle they replaced must not make the audit blind to where a
		// thunk really goes: the same chain output, with the thunk behind FluidStack's isFluidEqual method reference
		// forwarding to isFluidStackIdentical instead, folds to a different handle and is refused.
		String name = "net.minecraftforge.fluids.FluidStack";
		byte[] defined = defined(name);
		assertTrue(marked(ForgeTransferShapeAudit.certify(name, defined)), "premise: the chain's own FluidStack is certified");
		ClassNode node = new ClassNode();
		new ClassReader(defined).accept(node, 0);
		MethodInsnNode forward = null;
		for (MethodNode method : node.methods) if (method.name.startsWith(LambdaInvocationThunkInjector.PREFIX)) {
			MethodInsnNode call = LambdaInvocationThunkInjector.invocation(method);
			if (call != null && call.name.equals("isFluidEqual") && call.desc.equals("(Lnet/minecraftforge/fluids/FluidStack;)Z")) forward = call;
		}
		assertTrue(forward != null, "premise: FluidStack has a kernel thunk forwarding to isFluidEqual(FluidStack)");
		forward.name = "isFluidStackIdentical";
		org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
		node.accept(writer);
		assertFalse(marked(ForgeTransferShapeAudit.certify(name, writer.toByteArray())), "a thunk forwarding elsewhere was certified");
	}

	/**
	 * The fold of an interface-default delegate is the merged-base repair's own proof, and only that. ItemStack as the
	 * chain defines it has had getOrDefault and has dropped, because the repair proved DataComponentHolder's defaults
	 * are what every call reaches. The same class with a delegate the proof does NOT allow is a different class:
	 * getOrDefault through ItemInstance runs DataComponentGetter's body, not DataComponentHolder's, and get through
	 * ItemInstance reaches only an abstract declaration, so every call to that transfer root throws
	 * AbstractMethodError. Both must be refused, though each is a pure {@code I.super.m()} to a direct interface.
	 */
	@Test void aDelegateTheRepairCannotProveIsRefusedOnTheRealItemStack() throws Exception {
		TestFixtures.requireForgeCarrier("MinecraftForge's capability and item-handler classes the transfer audit certifies");
		String name = "net.minecraft.world.item.ItemStack";
		byte[] merged, defined;
		try (StagedGameClasses game = StagedGameClasses.open()) {
			merged = game.bytes(name);
			defined = chain(game.paths(), new ArrayList<>(), new ArrayList<>()).applyBeforeMixin(name, merged, new TransformContext(EnvType.SERVER, false, "named"));
			assertTrue(marked(ForgeTransferShapeAudit.certify(name, defined, game.resources())), "premise: the chain's own ItemStack is certified");
			// The positive fold: the merged class, its delegates still in place, digests as the class the repair leaves.
			assertTrue(method(node(merged), "getOrDefault", GET_OR_DEFAULT) != null && method(node(defined), "getOrDefault", GET_OR_DEFAULT) == null,
					"premise: the merge introduced getOrDefault and the chain's repair dropped it");
			ClassNode repaired = node(merged);
			assertTrue(game.proof().dropDefaultStubs(repaired) && method(repaired, "getOrDefault", GET_OR_DEFAULT) == null, "premise: the repair alone drops it");
			org.objectweb.asm.ClassWriter repairedWriter = new org.objectweb.asm.ClassWriter(0);
			repaired.accept(repairedWriter);
			assertEquals(ForgeTransferShapeAudit.fingerprint(repairedWriter.toByteArray(), game.proof()), ForgeTransferShapeAudit.fingerprint(merged, game.proof()));
			assertTrue(marked(ForgeTransferShapeAudit.certify(name, merged, game.resources())), "the merged ItemStack, proved, was refused");
			for (String[] root : new String[][] { { "getOrDefault", GET_OR_DEFAULT }, { "get", "(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;" } }) {
				ClassNode added = node(defined);
				assertTrue(method(added, root[0], root[1]) == null, "premise: the defined ItemStack inherits " + root[0]);
				NativeMergeFixtures.delegate(added, "net/minecraft/world/item/ItemInstance", root[0], root[1]);
				assertTrue(NativeMergeShapeRepair.delegate(method(added, root[0], root[1])) != null && added.interfaces.contains("net/minecraft/world/item/ItemInstance"),
						"premise: " + root[0] + " is a pure delegate to a direct interface, the shape the old fold took on sight");
				org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);
				added.accept(writer);
				assertFalse(marked(ForgeTransferShapeAudit.certify(name, writer.toByteArray(), game.resources())),
						root[0] + " through ItemInstance.super was certified");
			}
		}
		// No hierarchy, no proof: the merged class keeps its delegates in the digest and is not the reviewed shape.
		assertFalse(marked(ForgeTransferShapeAudit.certify(name, merged)), "a delegate was folded without a hierarchy to prove it");
	}

	private static final String GET_OR_DEFAULT = "(Lnet/minecraft/core/component/DataComponentType;Ljava/lang/Object;)Ljava/lang/Object;";

	/** {@code name} from the staged jars, through KernelBoot's pre-Mixin chain, server side. */
	private static byte[] defined(String name) throws Exception {
		try (StagedGameClasses game = StagedGameClasses.open()) {
			return chain(game.paths(), new ArrayList<>(), new ArrayList<>()).applyBeforeMixin(name, game.bytes(name), new TransformContext(EnvType.SERVER, false, "named"));
		}
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(desc)).findFirst().orElse(null);
	}

	private static boolean marked(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
		return node.methods.stream().anyMatch(m -> m.name.equals(ForgeTransferShapeAudit.MARKER));
	}

	private static AbstractInsnNode[] real(MethodNode method) {
		List<AbstractInsnNode> code = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) code.add(insn);
		return code.toArray(AbstractInsnNode[]::new);
	}

	private static Integer intConstant(AbstractInsnNode insn) {
		int op = insn.getOpcode();
		if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) return op - Opcodes.ICONST_0;
		if (insn instanceof IntInsnNode push && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)) return push.operand;
		if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) return value;
		return null;
	}
}
