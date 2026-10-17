package net.forbric.kernel.runtime.transfer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

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
	@Test void theOwnersProviderGoesInFrontOfFabricsOwnFallbacks() {
		List<String> issues = capture();
		Lookup lookup = new Lookup(new CopyOnWriteArrayList<>());
		BlockApiLookup.BlockApiProvider<Object, Direction> generic = (level, pos, state, entity, face) -> "generic", owner = (level, pos, state, entity, face) -> "owner";
		lookup.registerFallback(generic);
		BlockTransferBridge.ahead(lookup, owner);
		assertEquals(List.of(owner, generic), lookup.chain);
		assertEquals(List.of(), issues);
	}
	@Test void aLookupWithoutAWritableListStillGetsTheProviderAndSaysItIsLate() {
		List<String> issues = capture();
		java.util.Map<Integer, Object> kept = new java.util.HashMap<>();
		// registerFallback keeps providers where no list field of the lookup holds them: there is no list to reorder.
		Lookup lookup = new Lookup(Collections.unmodifiableList(new ArrayList<>())) {
			@Override public void registerFallback(BlockApiProvider<Object, Direction> provider) { kept.put(kept.size(), provider); }
		};
		BlockApiLookup.BlockApiProvider<Object, Direction> owner = (level, pos, state, entity, face) -> "owner";
		BlockTransferBridge.ahead(lookup, owner);
		assertEquals(java.util.Map.of(0, owner), kept);
		assertTrue(issues.contains("FABRIC_FALLBACK_ORDER"), issues::toString);
	}
	@Test void aListThatRefusesToBeReorderedKeepsTheProviderAppended() {
		List<String> issues = capture();
		BlockApiLookup.BlockApiProvider<Object, Direction> generic = (level, pos, state, entity, face) -> "generic", owner = (level, pos, state, entity, face) -> "owner";
		List<BlockApiLookup.BlockApiProvider<Object, Direction>> appendOnly = new ArrayList<>() {
			@Override public BlockApiLookup.BlockApiProvider<Object, Direction> remove(int index) { throw new UnsupportedOperationException(); }
		};
		Lookup lookup = new Lookup(appendOnly);
		lookup.registerFallback(generic);
		BlockTransferBridge.ahead(lookup, owner);
		assertEquals(List.of(generic, owner), lookup.chain);
		assertTrue(issues.contains("FABRIC_FALLBACK_ORDER"), issues::toString);
	}
	/**
	 * On the real Fabric lookup, obtained through the public BlockApiLookup.get: the provider ends up first in the list
	 * registerFallback appended it to, and that list is a CopyOnWriteArrayList, so inserting at index 0 is legal and safe
	 * against a concurrent find. The field is located here by its type, the way ahead() locates it by its contents.
	 */
	@Test void theRealFabricLookupTakesTheProviderFirst() throws Exception {
		List<String> issues = capture();
		BlockApiLookup<Object, Direction> lookup = BlockApiLookup.get(Identifier.fromNamespaceAndPath("forbrictest", "ahead"), Object.class, Direction.class);
		BlockApiLookup.BlockApiProvider<Object, Direction> generic = (level, pos, state, entity, face) -> "generic", owner = (level, pos, state, entity, face) -> "owner";
		lookup.registerFallback(generic);
		BlockTransferBridge.ahead(lookup, owner);
		assertEquals(List.of(), issues);
		List<?> fallbacks = null;
		for (java.lang.reflect.Field field : lookup.getClass().getDeclaredFields()) {
			if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !List.class.isAssignableFrom(field.getType())) continue;
			field.setAccessible(true);
			if (field.get(lookup) instanceof List<?> list && list.contains(owner)) { assertNull(fallbacks, "two lists hold the provider"); fallbacks = list; }
		}
		assertNotNull(fallbacks, "no list of the real lookup holds the provider");
		assertInstanceOf(CopyOnWriteArrayList.class, fallbacks);
		assertEquals(List.of(owner, generic), fallbacks);
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
	/** A lookup that keeps its fallbacks in a list under a name of its own, with no accessor for it. */
	static class Lookup implements BlockApiLookup<Object, Direction> {
		private final List<BlockApiProvider<Object, Direction>> chain;
		Lookup(List<BlockApiProvider<Object, Direction>> chain) { this.chain = chain; }
		public void registerFallback(BlockApiProvider<Object, Direction> provider) { chain.add(provider); }
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
