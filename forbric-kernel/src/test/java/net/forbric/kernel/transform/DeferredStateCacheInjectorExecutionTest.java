/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.util.KernelDeferredStateCaches;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * {@link DeferredStateCacheInjector}'s output, run: a NeoForge mod's block whose shape reads a sibling's holder that its
 * {@code DeferredRegister} registers later in the same {@code RegisterEvent} no longer throws "Trying to access unbound
 * value" when fabric-api's block add callback computes its state cache — the cache waits for the end of the dispatch, as
 * NeoForge's own bake does, and is computed there exactly once.
 *
 * <p>The stand-ins keep the shapes the edit and the failure key on: {@code BlockBehaviour$BlockStateBase} with a public
 * {@code initCache()} that computes the shape through its block, a block whose shape reads a holder that throws while
 * unbound (NeoForge's {@code DeferredHolder.value()}), and a registry whose add callback calls {@code initCache()} on
 * every block it is given (fabric-registry-sync's {@code Blocks} mixin). Occultism's golden sacrificial bowl, whose shape
 * reads the eldritch chalice registered after it, stopped the server exactly so.
 */
@ExecutesInjector(DeferredStateCacheInjector.class)
@ResourceLock("system-properties")
class DeferredStateCacheInjectorExecutionTest {
	private static final String BASE = "net.minecraft.world.level.block.state.BlockBehaviour";
	private static final String REGISTRY = "net.minecraft.core.MappedRegistry";
	private static final String STATE_OWNER = "net/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase";

	private static final Map<String, String> STAND_INS = Map.of(
			BASE, """
					package net.minecraft.world.level.block.state;

					import java.util.function.Supplier;

					public abstract class BlockBehaviour {
						/** NeoForge's DeferredHolder: throws until its value is registered. */
						public static final class Holder implements Supplier<Object> {
							private final String id;
							private Object value;

							public Holder(String id) {
								this.id = id;
							}

							public void bind(Object value) {
								this.value = value;
							}

							@Override
							public Object get() {
								if (value == null) throw new NullPointerException("Trying to access unbound value: " + id);
								return value;
							}
						}

						/** A block whose shape reads its sibling, as the mod's getShape does. */
						public static final class Block extends BlockBehaviour {
							private final Supplier<Object> sibling;
							public final BlockStateBase state = new BlockStateBase(this);

							public Block(Supplier<Object> sibling) {
								this.sibling = sibling;
							}

							Object getShape() {
								return sibling == null ? "full" : "beside " + sibling.get();
							}
						}

						public static class BlockStateBase {
							private final Block owner;
							private Object shape;
							public int computed;

							BlockStateBase(Block owner) {
								this.owner = owner;
							}

							public void initCache() {
								this.shape = owner.getShape();
								this.computed++;
							}

							public Object shape() {
								return shape;
							}
						}
					}
					""",
			REGISTRY, """
					package net.minecraft.core;

					import net.minecraft.world.level.block.state.BlockBehaviour;

					/** fabric-registry-sync's add callback on the block registry: every state's cache, the moment it is added. */
					public class MappedRegistry {
						public void register(BlockBehaviour.Block block) {
							block.state.initCache();
						}
					}
					""");

	@AfterEach void reset() {
		KernelDeferredStateCaches.close(false);
		System.clearProperty(KernelDeferredStateCaches.PROPERTY);
	}

	private record Game(ClassLoader loader, Object registry) {
		static Game of(Map<String, byte[]> classes) throws Throwable {
			ClassLoader loader = InjectorExecution.load(classes);
			return new Game(loader, InjectorExecution.construct(loader.loadClass(REGISTRY)));
		}

		Object holder(String id) throws Throwable {
			return InjectorExecution.construct(loader.loadClass(BASE + "$Holder"), id);
		}

		Object block(Object sibling) throws Throwable {
			Class<?> block = loader.loadClass(BASE + "$Block");
			return block.getConstructor(java.util.function.Supplier.class).newInstance(sibling);
		}

		/** RegisterEvent.register: the registry adds the block, then the DeferredRegister binds its holder. */
		void register(Object block, Object holder) throws Throwable {
			InjectorExecution.invoke(registry, "register", block);
			if (holder != null) InjectorExecution.invoke(holder, "bind", block);
		}

		static Object state(Object block) throws ReflectiveOperationException {
			return block.getClass().getField("state").get(block);
		}

		static int computed(Object block) throws ReflectiveOperationException {
			Object state = state(block);
			return state.getClass().getField("computed").getInt(state);
		}

		static Object shape(Object block) throws Throwable {
			return InjectorExecution.invoke(state(block), "shape");
		}
	}

	private static Map<String, byte[]> repaired(Map<String, byte[]> original) {
		byte[] bytes = original.get(STATE_OWNER);
		byte[] out = InjectorExecution.transform(new DeferredStateCacheInjector(), DeferredStateCacheInjector.TARGET, bytes, EnvType.SERVER);
		assertNotSame(bytes, out, "the stand-in's initCache is the reviewed shape and is edited");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(STATE_OWNER, out);
		return classes;
	}

	@Test void aBlockWhoseShapeReadsALaterSiblingRegistersInsideTheEvent(@TempDir Path work) throws Throwable {
		Map<String, byte[]> classes = repaired(InjectorExecution.compile(work, STAND_INS));
		Game game = Game.of(classes);
		assertEquals("", InjectorExecution.verify(classes.get(STATE_OWNER), game.loader()));

		Object chalice = game.holder("occultism:eldritch_chalice");
		Object bowl = game.block(chalice), chaliceBlock = game.block(null);
		KernelDeferredStateCaches.open();
		game.register(bowl, null);
		game.register(chaliceBlock, chalice);
		assertEquals(0, Game.computed(bowl), "inside the event the cache waits, as NeoForge's add callback leaves it");
		assertNull(Game.shape(bowl));

		assertEquals(2, KernelDeferredStateCaches.close(true), "both held states are computed at the end of the dispatch");
		assertEquals(1, Game.computed(bowl), "once");
		assertEquals(1, Game.computed(chaliceBlock));
		assertEquals("beside " + chaliceBlock, Game.shape(bowl), "with the sibling the event registered after it");
		assertEquals(0, KernelDeferredStateCaches.close(true), "a second close finds nothing held");
	}

	/** Outside the window — vanilla's bootstrap, a Fabric mod's initializer, the kernel's own pass — the cache is computed now. */
	@Test void outsideTheWindowTheCacheIsComputedOnAdd(@TempDir Path work) throws Throwable {
		Game game = Game.of(repaired(InjectorExecution.compile(work, STAND_INS)));
		Object stone = game.block(null);
		game.register(stone, null);
		assertEquals(1, Game.computed(stone));
		assertEquals("full", Game.shape(stone));

		Object unbound = game.block(game.holder("mod:later"));
		NullPointerException thrown = assertThrows(NullPointerException.class, () -> game.register(unbound, null),
				"a Fabric mod's block whose shape reads an unbound value still throws, as on Fabric");
		assertTrue(thrown.getMessage().startsWith("Trying to access unbound value"), thrown.getMessage());
	}

	/** Only the dispatching thread's calls are held; another thread's state is computed at once. */
	@Test void onlyTheDispatchingThreadIsHeld(@TempDir Path work) throws Throwable {
		Game game = Game.of(repaired(InjectorExecution.compile(work, STAND_INS)));
		Object other = game.block(null);
		KernelDeferredStateCaches.open();
		CompletableFuture.runAsync(() -> {
			try {
				game.register(other, null);
			} catch (Throwable t) {
				throw new AssertionError(t);
			}
		}).get();
		assertEquals(1, Game.computed(other));
		assertEquals(0, KernelDeferredStateCaches.close(true));
	}

	/** A failed dispatch: NeoForge rolled the registries back, and the held states are dropped, not computed. */
	@Test void aFailedDispatchDropsWhatWasHeld(@TempDir Path work) throws Throwable {
		Game game = Game.of(repaired(InjectorExecution.compile(work, STAND_INS)));
		Object bowl = game.block(game.holder("mod:never"));
		KernelDeferredStateCaches.open();
		game.register(bowl, null);
		assertEquals(0, KernelDeferredStateCaches.close(false));
		assertEquals(0, Game.computed(bowl));
		assertEquals(0, KernelDeferredStateCaches.close(true), "dropped, not kept for a later close");
	}

	/** RED premise: as merged, the add callback inside the event computes the shape and throws on the unbound sibling. */
	@Test void asMergedTheBlockThrowsInsideTheEvent(@TempDir Path work) throws Throwable {
		Game game = Game.of(InjectorExecution.compile(work, STAND_INS));
		Object bowl = game.block(game.holder("occultism:eldritch_chalice"));
		KernelDeferredStateCaches.open();
		NullPointerException thrown = assertThrows(NullPointerException.class, () -> game.register(bowl, null));
		assertTrue(thrown.getMessage().startsWith("Trying to access unbound value"), thrown.getMessage());
	}

	@Test void editedOnceAndSwitchedOffLeftAsMerged(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		Map<String, byte[]> edited = repaired(original);
		byte[] once = edited.get(STATE_OWNER);
		assertSame(once, InjectorExecution.transform(new DeferredStateCacheInjector(), DeferredStateCacheInjector.TARGET, once, EnvType.SERVER),
				"an initCache that already opens with the guard is left alone");

		System.setProperty(KernelDeferredStateCaches.PROPERTY, "off");
		byte[] bytes = original.get(STATE_OWNER);
		assertSame(bytes, InjectorExecution.transform(new DeferredStateCacheInjector(), DeferredStateCacheInjector.TARGET, bytes, EnvType.SERVER));
		KernelDeferredStateCaches.open();
		Game game = Game.of(edited);
		Object bowl = game.block(game.holder("mod:later"));
		assertThrows(NullPointerException.class, () -> game.register(bowl, null), "off, nothing is held even by an edited class");
	}

	/** The staged game's own {@code BlockStateBase}: the reviewed shape, edited once, and verified. */
	@Test void theStagedBlockStateBaseTakesTheGuard() {
		byte[] original = NativeCoremodParityTest.read(TestFixtures.stagedBase(), STATE_OWNER);
		byte[] out = InjectorExecution.transform(new DeferredStateCacheInjector(), DeferredStateCacheInjector.TARGET, original, EnvType.SERVER);
		assertNotSame(original, out, "the staged BlockStateBase.initCache is the reviewed shape");
		ClassNode node = new ClassNode();
		new ClassReader(out).accept(node, 0);
		assertEquals(0, DeferredStateCacheInjector.repair(node), "already guarded");
		assertSame(out, InjectorExecution.transform(new DeferredStateCacheInjector(), DeferredStateCacheInjector.TARGET, out, EnvType.SERVER));
	}
}
