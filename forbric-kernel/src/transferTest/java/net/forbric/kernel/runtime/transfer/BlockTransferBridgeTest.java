package net.forbric.kernel.runtime.transfer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;

import net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The part of the bridge that decides on its own rather than through TransferPrecedence. ahead(): a Fabric consumer
 * reaches a NeoForge owner only because the owner's provider goes in FRONT of Fabric's generic Container fallback; if
 * that silently degraded to appending, every NeoForge Container would again be answered by Fabric's ContainerStorage.
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
