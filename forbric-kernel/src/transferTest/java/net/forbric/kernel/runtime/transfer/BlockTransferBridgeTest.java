package net.forbric.kernel.runtime.transfer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.wrapper.InvWrapper;

/**
 * The parts of the bridge that decide on their own rather than through TransferPrecedence. ahead(): a Fabric
 * consumer reaches a Forge or NeoForge owner only because the owner's provider goes in FRONT of Fabric's generic
 * Container fallback; if that silently degraded to appending, every Forge/NeoForge Container would again be
 * answered by Fabric's ContainerStorage. wholeContainer() and vanillaWrites(): which Forge answer is Forge's generic
 * InvWrapper, and which Containers NeoForge's own wrapper may write in its place.
 */
class BlockTransferBridgeTest {
	private static final String IMPL = "net/fabricmc/fabric/impl/lookup/block/BlockApiLookupImpl";

	@Test void theOwnersProviderGoesInFrontOfFabricsOwnFallbacks() {
		List<String> issues = capture();
		Lookup lookup = new Lookup(new CopyOnWriteArrayList<>());
		BlockApiLookup.BlockApiProvider<Object, Direction> generic = (level, pos, state, entity, face) -> "generic", owner = (level, pos, state, entity, face) -> "owner";
		lookup.registerFallback(generic);
		BlockTransferBridge.ahead(lookup, owner);
		assertEquals(List.of(owner, generic), lookup.fallbacks);
		assertEquals(List.of(), issues);
	}
	@Test void aLookupWithoutAWritableListStillGetsTheProviderAndSaysItIsLate() {
		List<String> issues = capture();
		Lookup lookup = new Lookup(Collections.unmodifiableList(new ArrayList<>())) {
			@Override public void registerFallback(BlockApiProvider<Object, Direction> provider) { appended.add(provider); }
		};
		BlockApiLookup.BlockApiProvider<Object, Direction> owner = (level, pos, state, entity, face) -> "owner";
		BlockTransferBridge.ahead(lookup, owner);
		assertEquals(List.of(owner), lookup.appended);
		assertTrue(issues.contains("FABRIC_FALLBACK_ORDER"), issues::toString);
	}
	/**
	 * ahead() relies on three facts about the real Fabric lookup, read from the jar the bridge compiles against:
	 * getFallbackProviders returns the very list registerFallback appends to and find walks, and that list is a
	 * CopyOnWriteArrayList, so inserting at index 0 is legal and safe against a concurrent find.
	 */
	@Test void theRealFabricLookupHandsOutItsLiveFallbackList() throws Exception {
		ClassNode impl = new ClassNode();
		try (InputStream in = BlockApiLookup.class.getClassLoader().getResourceAsStream(IMPL + ".class")) {
			assertNotNull(in, "Fabric's BlockApiLookupImpl is not on the transfer test classpath");
			new ClassReader(in).accept(impl, 0);
		}
		MethodNode getter = method(impl, "getFallbackProviders");
		assertTrue((getter.access & Opcodes.ACC_PUBLIC) != 0);
		List<AbstractInsnNode> code = real(getter);
		assertEquals(3, code.size(), "getFallbackProviders is no longer a plain field read");
		String field = ((FieldInsnNode) code.get(1)).name;
		assertEquals(Opcodes.GETFIELD, code.get(1).getOpcode());
		assertEquals(Opcodes.ARETURN, code.get(2).getOpcode());
		assertTrue(reads(method(impl, "registerFallback"), field), "registerFallback no longer appends to " + field);
		assertTrue(reads(method(impl, "find", 5), field), "find no longer walks " + field);
		boolean copyOnWrite = false;
		for (AbstractInsnNode insn : real(method(impl, "<init>"))) {
			if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) copyOnWrite = type.desc.equals("java/util/concurrent/CopyOnWriteArrayList");
			if (insn instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD && put.name.equals(field)) break;
		}
		assertTrue(copyOnWrite, field + " is no longer a CopyOnWriteArrayList");
	}

	@Test void onlyForgesOwnInvWrapperIsTheGenericWholeContainerView() {
		Container chest = container();
		assertTrue(BlockTransferBridge.wholeContainer(new InvWrapper(chest)));
		// A subclass can refuse slots or faces of its own; it stays a handler for the audit to accept or refuse.
		assertFalse(BlockTransferBridge.wholeContainer(new InvWrapper(chest) { }));
		assertFalse(BlockTransferBridge.wholeContainer(null));
		assertFalse(BlockTransferBridge.wholeContainer(chest));
	}
	/**
	 * NeoForge's Container wrapper writes through setItem(slot, stack, true), which BaseContainerBlockEntity implements
	 * without calling the two-argument setItem. A mod class that declares either form, or onTransfer, anywhere below
	 * the vanilla base would have its own writes skipped or replayed, so its Container is not offered to NeoForge.
	 */
	@Test void neoForgesContainerWrapperWritesOnlyContainersWhoseWritesAreTheGamesOwn() {
		String item = Type.getDescriptor(net.minecraft.world.item.ItemStack.class);
		Loader loader = new Loader();
		Class<?> chest = loader.define("PlainChest", RandomizableContainerBlockEntity.class);
		assertTrue(BlockTransferBridge.vanillaWrites(chest));
		assertTrue(BlockTransferBridge.vanillaWrites(loader.define("PlainCrate", BaseContainerBlockEntity.class)));
		assertTrue(BlockTransferBridge.vanillaWrites(RandomizableContainerBlockEntity.class));
		assertFalse(BlockTransferBridge.vanillaWrites(loader.define("ResettingMachine", BaseContainerBlockEntity.class, "setItem", "(I" + item + ")V")));
		assertFalse(BlockTransferBridge.vanillaWrites(loader.define("TransactionAwareMachine", RandomizableContainerBlockEntity.class, "setItem", "(I" + item + "Z)V")));
		assertFalse(BlockTransferBridge.vanillaWrites(loader.define("CountingMachine", BaseContainerBlockEntity.class, "onTransfer",
				"(II" + Type.getDescriptor(net.neoforged.neoforge.transfer.transaction.TransactionContext.class) + ")V")));
		// An override further down a chain of otherwise plain mod classes counts as much as one right below the base.
		assertFalse(BlockTransferBridge.vanillaWrites(loader.define("SortingChest", chest, "setItem", "(I" + item + ")V")));
		// A Container that is not a BaseContainerBlockEntity at all.
		assertFalse(BlockTransferBridge.vanillaWrites(container().getClass()));
	}

	private static Container container() {
		return (Container) java.lang.reflect.Proxy.newProxyInstance(BlockTransferBridgeTest.class.getClassLoader(), new Class<?>[] { Container.class }, (proxy, method, arguments) -> {
			throw new UnsupportedOperationException(method.getName());
		});
	}
	/** Mod block entity classes: javac cannot extend the merged base's block entities, so they are written directly. */
	private static final class Loader extends ClassLoader {
		Loader() { super(BlockTransferBridgeTest.class.getClassLoader()); }
		Class<?> define(String name, Class<?> parent, String... methods) {
			ClassWriter writer = new ClassWriter(0);
			writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "forbrictest/" + name, null, Type.getInternalName(parent), null);
			for (int i = 0; i < methods.length; i += 2) writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, methods[i], methods[i + 1], null, null).visitEnd();
			writer.visitEnd();
			byte[] bytes = writer.toByteArray();
			return defineClass("forbrictest." + name, bytes, 0, bytes.length);
		}
	}
	private static List<String> capture() {
		List<String> issues = new CopyOnWriteArrayList<>();
		TransferIssues.setReporter(issue -> issues.add(issue.code()));
		return issues;
	}
	private static MethodNode method(ClassNode owner, String name) { return method(owner, name, -1); }
	private static MethodNode method(ClassNode owner, String name, int arguments) {
		return owner.methods.stream().filter(m -> m.name.equals(name) && (arguments < 0 || org.objectweb.asm.Type.getArgumentTypes(m.desc).length == arguments))
				.findFirst().orElseThrow(() -> new AssertionError(name + " is gone from " + owner.name));
	}
	private static List<AbstractInsnNode> real(MethodNode method) {
		List<AbstractInsnNode> code = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) code.add(insn);
		return code;
	}
	private static boolean reads(MethodNode method, String field) {
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof FieldInsnNode get && get.getOpcode() == Opcodes.GETFIELD && get.name.equals(field)) return true;
		return false;
	}

	/** Fabric's lookup shape: a public getFallbackProviders over the list its fallbacks live in. */
	static class Lookup implements BlockApiLookup<Object, Direction> {
		final List<BlockApiProvider<Object, Direction>> fallbacks, appended = new ArrayList<>();
		Lookup(List<BlockApiProvider<Object, Direction>> fallbacks) { this.fallbacks = fallbacks; }
		public List<BlockApiProvider<Object, Direction>> getFallbackProviders() { return fallbacks; }
		public void registerFallback(BlockApiProvider<Object, Direction> provider) { fallbacks.add(provider); }
		public Object find(Level level, BlockPos pos, BlockState state, BlockEntity entity, Direction face) { throw new UnsupportedOperationException(); }
		public void registerSelf(BlockEntityType<?>... types) { throw new UnsupportedOperationException(); }
		public void registerForBlocks(BlockApiProvider<Object, Direction> provider, Block... blocks) { throw new UnsupportedOperationException(); }
		public <T extends BlockEntity> void registerForBlockEntity(BiFunction<? super T, Direction, Object> provider, BlockEntityType<T> type) { throw new UnsupportedOperationException(); }
		public void registerForBlockEntities(BlockEntityApiProvider<Object, Direction> provider, BlockEntityType<?>... types) { throw new UnsupportedOperationException(); }
		public Identifier getId() { throw new UnsupportedOperationException(); }
		public Class<Object> apiClass() { return Object.class; }
		public Class<Direction> contextClass() { return Direction.class; }
		public BlockApiProvider<Object, Direction> getProvider(Block block) { throw new UnsupportedOperationException(); }
	}
}
