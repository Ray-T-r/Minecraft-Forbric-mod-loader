/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;

/**
 * {@link ItemUseOnInjector}'s output, run: MinecraftForge's {@code ItemStack.useOn} posts NeoForge's
 * {@code UseItemOnBlockEvent} in its {@code ITEM_AFTER_BLOCK} phase again (and returns a cancelling listener's result),
 * and on the server, on the client and from MinecraftForge's own hook, {@code Item.useOn} is reached through the one
 * relay {@code forbric$useOnItem}, the method fabric-api's {@code ItemEvents.USE_ON} wrap is pointed at.
 *
 * <p>The stand-ins keep the reviewed shapes: {@code useOn} returns {@code onPlaceItemIntoWorld} on a server and runs a
 * lambda {@code c -> getItem().useOn(c)} on a client; each hook makes one {@code Item.useOn} call. The stand-in item
 * records which method called its {@code useOn}, which is how the relay is seen to be the caller.
 */
@ExecutesInjector(ItemUseOnInjector.class)
class ItemUseOnInjectorExecutionTest {
	private static final String STACK = ItemUseOnInjector.STACK;
	private static final String CONTEXT = "net.minecraft.world.item.context.UseOnContext";
	private static final String EVENT = "net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent";

	private static final Map<String, String> STAND_INS = Map.ofEntries(
			Map.entry("net.minecraft.world.InteractionResult", """
					package net.minecraft.world;
					public enum InteractionResult { SUCCESS, PASS, FAIL }
					"""),
			Map.entry("net.minecraft.world.item.Item", """
					package net.minecraft.world.item;

					import java.util.ArrayList;
					import java.util.List;
					import net.minecraft.world.InteractionResult;
					import net.minecraft.world.item.context.UseOnContext;

					public class Item {
						/** Who called useOn, nearest frame first: the relay, or the call site the merge left. */
						public static final List<String> callers = new ArrayList<>();

						public InteractionResult useOn(UseOnContext context) {
							callers.add(StackWalker.getInstance().walk(s -> s.skip(1).findFirst().orElseThrow().getMethodName()));
							return InteractionResult.SUCCESS;
						}
					}
					"""),
			Map.entry(CONTEXT, """
					package net.minecraft.world.item.context;

					import net.minecraft.world.item.ItemStack;

					public class UseOnContext {
						private final ItemStack stack;
						public final boolean clientSide;

						public UseOnContext(ItemStack stack, boolean clientSide) {
							this.stack = stack;
							this.clientSide = clientSide;
						}

						public ItemStack getItemInHand() {
							return stack;
						}
					}
					"""),
			Map.entry(STACK, """
					package net.minecraft.world.item;

					import net.minecraft.world.InteractionResult;
					import net.minecraft.world.item.context.UseOnContext;
					import net.minecraftforge.common.ForgeHooks;
					import net.neoforged.neoforge.common.CommonHooks;

					public class ItemStack {
						private final Item item;

						public ItemStack(Item item) {
							this.item = item;
						}

						public Item getItem() {
							return item;
						}

						public InteractionResult useOn(UseOnContext context) {
							if (!context.clientSide) return CommonHooks.onPlaceItemIntoWorld(context);
							return ForgeHooks.onItemUse(context, c -> getItem().useOn(c));
						}
					}
					"""),
			Map.entry(ItemUseOnInjector.NEO_HOOKS, """
					package net.neoforged.neoforge.common;

					import net.minecraft.world.InteractionResult;
					import net.minecraft.world.item.context.UseOnContext;

					public class CommonHooks {
						public static InteractionResult onPlaceItemIntoWorld(UseOnContext context) {
							return context.getItemInHand().getItem().useOn(context);
						}
					}
					"""),
			Map.entry(ItemUseOnInjector.FORGE_HOOKS, """
					package net.minecraftforge.common;

					import java.util.function.Function;
					import net.minecraft.world.InteractionResult;
					import net.minecraft.world.item.context.UseOnContext;

					public class ForgeHooks {
						public static InteractionResult onItemUse(UseOnContext context, Function<UseOnContext, InteractionResult> callback) {
							return callback.apply(context);
						}

						public static InteractionResult onPlaceItemIntoWorld(UseOnContext context) {
							return context.getItemInHand().getItem().useOn(context);
						}
					}
					"""),
			Map.entry("net.neoforged.bus.api.Event", """
					package net.neoforged.bus.api;

					public abstract class Event {
						private boolean canceled;

						public boolean isCanceled() {
							return canceled;
						}

						public void setCanceled(boolean canceled) {
							this.canceled = canceled;
						}
					}
					"""),
			Map.entry("net.neoforged.bus.api.IEventBus", """
					package net.neoforged.bus.api;

					public interface IEventBus {
						<T extends Event> T post(T event);
					}
					"""),
			Map.entry("net.neoforged.neoforge.common.NeoForge", """
					package net.neoforged.neoforge.common;

					import java.util.ArrayList;
					import java.util.List;
					import java.util.function.Consumer;
					import net.neoforged.bus.api.Event;
					import net.neoforged.bus.api.IEventBus;

					public class NeoForge {
						public static final List<Consumer<Event>> LISTENERS = new ArrayList<>();
						public static final IEventBus EVENT_BUS = new IEventBus() {
							@Override
							public <T extends Event> T post(T event) {
								for (Consumer<Event> listener : LISTENERS) listener.accept(event);
								return event;
							}
						};
					}
					"""),
			Map.entry(EVENT, """
					package net.neoforged.neoforge.event.entity.player;

					import net.minecraft.world.InteractionResult;
					import net.minecraft.world.item.context.UseOnContext;
					import net.neoforged.bus.api.Event;

					public class UseItemOnBlockEvent extends Event {
						public enum UsePhase { ITEM_BEFORE_BLOCK, BLOCK, ITEM_AFTER_BLOCK }

						private final UseOnContext context;
						private final UsePhase phase;
						private InteractionResult result = InteractionResult.PASS;

						public UseItemOnBlockEvent(UseOnContext context, UsePhase phase) {
							this.context = context;
							this.phase = phase;
						}

						public UseOnContext getUseOnContext() {
							return context;
						}

						public UsePhase getUsePhase() {
							return phase;
						}

						public void cancelWithResult(InteractionResult result) {
							this.result = result;
							setCanceled(true);
						}

						public InteractionResult getCancellationResult() {
							return result;
						}
					}
					"""));

	private static final List<String> TARGETS = List.of(STACK, ItemUseOnInjector.NEO_HOOKS, ItemUseOnInjector.FORGE_HOOKS);

	private static ClassLoader transformed(Map<String, byte[]> original) {
		Map<String, byte[]> classes = new HashMap<>(original);
		for (String target : TARGETS) {
			String internal = target.replace('.', '/');
			classes.put(internal, InjectorExecution.transform(new ItemUseOnInjector(), target, original.get(internal), EnvType.SERVER));
		}
		ClassLoader loader = InjectorExecution.load(classes);
		for (String target : TARGETS) assertEquals("", InjectorExecution.verify(classes.get(target.replace('.', '/')), loader), target);
		return loader;
	}

	/** One use of a fresh stack; returns what useOn answered. */
	private static Object use(ClassLoader loader, boolean clientSide) throws Throwable {
		Object item = InjectorExecution.construct(loader.loadClass("net.minecraft.world.item.Item"));
		Object stack = InjectorExecution.construct(loader.loadClass(STACK), item);
		Object context = InjectorExecution.construct(loader.loadClass(CONTEXT), stack, clientSide);
		return InjectorExecution.invoke(stack, "useOn", context);
	}

	@SuppressWarnings("unchecked")
	private static List<String> callers(ClassLoader loader) throws ReflectiveOperationException {
		return (List<String>) InjectorExecution.getStatic(loader.loadClass("net.minecraft.world.item.Item"), "callers");
	}

	@SuppressWarnings("unchecked")
	private static List<java.util.function.Consumer<Object>> listeners(ClassLoader loader) throws ReflectiveOperationException {
		return (List<java.util.function.Consumer<Object>>) InjectorExecution.getStatic(
				loader.loadClass("net.neoforged.neoforge.common.NeoForge"), "LISTENERS");
	}

	@Test void bothSidesPostTheAfterBlockPhaseAndReachTheItemThroughTheRelay(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		ClassLoader loader = transformed(original);
		List<String> phases = new ArrayList<>();
		listeners(loader).add(event -> phases.add(String.valueOf(call(event, "getUsePhase"))));

		assertEquals("SUCCESS", String.valueOf(use(loader, false)));
		assertEquals("SUCCESS", String.valueOf(use(loader, true)));
		assertEquals(List.of("ITEM_AFTER_BLOCK", "ITEM_AFTER_BLOCK"), phases, "NeoForge's phase is posted on both sides");
		assertEquals(List.of(ItemUseOnInjector.RELAY, ItemUseOnInjector.RELAY), callers(loader),
				"server and client both reach Item.useOn through the relay Fabric's wrap is pointed at");

		// A mod calling MinecraftForge's own hook goes through the same relay.
		Object item = InjectorExecution.construct(loader.loadClass("net.minecraft.world.item.Item"));
		Object context = InjectorExecution.construct(loader.loadClass(CONTEXT),
				InjectorExecution.construct(loader.loadClass(STACK), item), false);
		InjectorExecution.invokeStatic(loader.loadClass(ItemUseOnInjector.FORGE_HOOKS), "onPlaceItemIntoWorld", context);
		assertEquals(ItemUseOnInjector.RELAY, callers(loader).get(2));

		ClassLoader merged = InjectorExecution.load(original);
		List<String> unposted = new ArrayList<>();
		listeners(merged).add(event -> unposted.add("posted"));
		use(merged, false);
		use(merged, true);
		assertEquals(List.of(), unposted, "premise: as merged, NeoForge's ITEM_AFTER_BLOCK phase is never posted");
		assertEquals(List.of("onPlaceItemIntoWorld", "lambda$useOn$0"), callers(merged),
				"premise: as merged, the calls Fabric's wrap targets are in two other places");
	}

	/** NeoForge's own useOn, the genuine base's: it posts ITEM_AFTER_BLOCK itself, keeping the event in a local. */
	private static final String NEOFORGE_STACK = """
			package net.minecraft.world.item;

			import net.minecraft.world.InteractionResult;
			import net.minecraft.world.item.context.UseOnContext;
			import net.minecraftforge.common.ForgeHooks;
			import net.neoforged.neoforge.common.CommonHooks;
			import net.neoforged.neoforge.common.NeoForge;
			import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;

			public class ItemStack {
				private final Item item;

				public ItemStack(Item item) {
					this.item = item;
				}

				public Item getItem() {
					return item;
				}

				public InteractionResult useOn(UseOnContext context) {
					UseItemOnBlockEvent event = NeoForge.EVENT_BUS.post(new UseItemOnBlockEvent(context, UseItemOnBlockEvent.UsePhase.ITEM_AFTER_BLOCK));
					if (event.isCanceled()) return event.getCancellationResult();
					if (!context.clientSide) return CommonHooks.onPlaceItemIntoWorld(context);
					return ForgeHooks.onItemUse(context, c -> getItem().useOn(context));
				}
			}
			""";

	/**
	 * On NeoForge's own body the event is already posted, so it is posted once — not twice — and both sides still reach
	 * the item through the relay. The relayed CommonHooks runs against the very ItemStack the same pass produced: before,
	 * the ItemStack half stood down here and the server's use threw NoSuchMethodError.
	 */
	@Test void neoForgesOwnBodyPostsOnceAndReachesTheItemThroughTheRelay(@TempDir Path work) throws Throwable {
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put(STACK, NEOFORGE_STACK);
		Map<String, byte[]> original = InjectorExecution.compile(work, sources);
		ClassLoader loader = transformed(original);
		List<String> phases = new ArrayList<>();
		listeners(loader).add(event -> phases.add(String.valueOf(call(event, "getUsePhase"))));

		assertEquals("SUCCESS", String.valueOf(use(loader, false)));
		assertEquals("SUCCESS", String.valueOf(use(loader, true)));
		assertEquals(List.of("ITEM_AFTER_BLOCK", "ITEM_AFTER_BLOCK"), phases, "one post per use, NeoForge's own");
		assertEquals(List.of(ItemUseOnInjector.RELAY, ItemUseOnInjector.RELAY), callers(loader),
				"server and client both reach Item.useOn through the relay Fabric's wrap is pointed at");
	}

	/**
	 * A body the edit declines: MinecraftForge's, but its client lambda reads the item into a local first. The relayed
	 * CommonHooks still links, through a bridge that calls Item.useOn itself, and nothing reaches the item through a relay:
	 * there is none, so fabric-api's wrap is not moved onto a method only the server's call would reach.
	 */
	@Test void aDeclinedBodyStillLinksAndReachesTheItemThroughNoRelay(@TempDir Path work) throws Throwable {
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put(STACK, STAND_INS.get(STACK).replace("c -> getItem().useOn(c)", "c -> { Item item = getItem(); return item.useOn(c); }"));
		assertNotEquals(STAND_INS.get(STACK), sources.get(STACK), "premise: the lambda was rewritten");
		ClassLoader loader = transformed(InjectorExecution.compile(work, sources));
		List<String> phases = new ArrayList<>();
		listeners(loader).add(event -> phases.add("posted"));

		assertEquals("SUCCESS", String.valueOf(use(loader, false)));
		assertEquals("SUCCESS", String.valueOf(use(loader, true)));
		assertEquals(List.of(ItemUseOnInjector.BRIDGE, "lambda$useOn$0"), callers(loader),
				"the server's call reaches the item from the bridge itself, the client's from its lambda: no relay anywhere");
		assertEquals(List.of(), phases, "the declined edit put no post in");
		assertThrows(NoSuchMethodException.class, () -> loader.loadClass(STACK).getMethod(ItemUseOnInjector.RELAY,
				loader.loadClass("net.minecraft.world.item.Item"), loader.loadClass(CONTEXT)));
	}

	@Test void aListenerThatCancelsDecidesTheResultAndTheItemIsNotUsed(@TempDir Path work) throws Throwable {
		ClassLoader loader = transformed(InjectorExecution.compile(work, STAND_INS));
		Object fail = Enum.valueOf(loader.loadClass("net.minecraft.world.InteractionResult").asSubclass(Enum.class), "FAIL");
		listeners(loader).add(event -> call(event, "cancelWithResult", fail));
		assertSame(fail, use(loader, false));
		assertSame(fail, use(loader, true));
		assertEquals(List.of(), callers(loader), "a cancelled use never reaches Item.useOn");
	}

	@Test void aSecondPassAndOtherClassesAreLeftAlone(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		for (String target : TARGETS) {
			byte[] once = InjectorExecution.transform(new ItemUseOnInjector(), target, original.get(target.replace('.', '/')), EnvType.SERVER);
			assertSame(once, InjectorExecution.transform(new ItemUseOnInjector(), target, once, EnvType.SERVER), target);
		}
		byte[] item = original.get("net/minecraft/world/item/Item");
		assertSame(item, InjectorExecution.transform(new ItemUseOnInjector(), "net.minecraft.world.item.Item", item, EnvType.SERVER));
	}

	private static Object call(Object receiver, String method, Object... args) {
		try {
			return InjectorExecution.invoke(receiver, method, args);
		} catch (Throwable failed) {
			throw new AssertionError(failed);
		}
	}
}
