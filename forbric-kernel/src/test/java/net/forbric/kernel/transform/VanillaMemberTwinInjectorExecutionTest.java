/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import net.fabricmc.api.EnvType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * {@link VanillaMemberTwinInjector}'s output, run: on a base that lacks vanilla's member (the genuine NeoForge base's
 * shape), a vanilla-compiled caller of the twin gets vanilla's behaviour. Two of the three row kinds, each on the class a
 * real row names:
 * <ul>
 *   <li>VANILLA_BODY, {@code CropBlock.getGrowthSpeed(Block, BlockGetter, BlockPos)}: the copied body runs and answers
 *       vanilla's value. Its lambda shares name and descriptor with a different lambda of the base's own overload, so
 *       the copy must bring vanilla's lambda under a fresh name and call that one — the base's own overload keeps
 *       answering with its own;</li>
 *   <li>DELEGATE, {@code AxeItem.evaluateNewBlockState(Level, BlockPos, Player, BlockState)}: the stub reaches
 *       NeoForge's widened overload with its own arguments in place and null for the {@code UseOnContext} it lacks.</li>
 * </ul>
 * On the merged base's shape (vanilla's member already declared) and with the switch off, the class is handed back as it
 * came. The game classes are stand-ins under the real names, compiled twice: once as vanilla declares them (the FABRIC
 * reference the injector copies from) and once as the genuine base does. VanillaMemberTwinInjectorTest proves the rows
 * land and link on the staged genuine base itself; this runs what they add.
 */
@ExecutesInjector(VanillaMemberTwinInjector.class)
class VanillaMemberTwinInjectorExecutionTest {
	private static final String CROP = VanillaMemberTwinInjector.CROP.replace('/', '.');
	private static final String AXE = VanillaMemberTwinInjector.AXE.replace('/', '.');
	private static final Map<String, String> TYPES = Map.of(
			"net.minecraft.core.BlockPos", """
					package net.minecraft.core;
					public class BlockPos { public final int moisture; public BlockPos(int moisture) { this.moisture = moisture; } }
					""",
			"net.minecraft.world.level.BlockGetter", "package net.minecraft.world.level; public interface BlockGetter { }",
			"net.minecraft.world.level.Level", "package net.minecraft.world.level; public class Level { }",
			"net.minecraft.world.level.block.Block", "package net.minecraft.world.level.block; public class Block { }",
			"net.minecraft.world.level.block.state.BlockState", "package net.minecraft.world.level.block.state; public class BlockState { }",
			"net.minecraft.world.entity.player.Player", "package net.minecraft.world.entity.player; public class Player { }",
			"net.minecraft.world.item.context.UseOnContext", "package net.minecraft.world.item.context; public class UseOnContext { }");
	/** As vanilla compiled them: the reference the twins are copied from. */
	private static final Map<String, String> VANILLA = Map.of(
			CROP, """
					package net.minecraft.world.level.block;
					import net.minecraft.core.BlockPos;
					import net.minecraft.world.level.BlockGetter;
					public class CropBlock extends Block {
						protected static float getGrowthSpeed(Block block, BlockGetter level, BlockPos pos) {
							java.util.function.IntSupplier moisture = () -> pos.moisture * 2;
							return 1.0F + moisture.getAsInt();
						}
					}
					""",
			AXE, """
					package net.minecraft.world.item;
					import java.util.Optional;
					import net.minecraft.core.BlockPos;
					import net.minecraft.world.entity.player.Player;
					import net.minecraft.world.level.Level;
					import net.minecraft.world.level.block.state.BlockState;
					public class AxeItem {
						private Optional<BlockState> evaluateNewBlockState(Level level, BlockPos pos, Player player, BlockState state) {
							return Optional.empty();
						}
					}
					""");
	/** As the genuine base has them: NeoForge's overloads only, a same-named lambda with another body. */
	private static final Map<String, String> GENUINE = Map.of(
			CROP, """
					package net.minecraft.world.level.block;
					import net.minecraft.core.BlockPos;
					import net.minecraft.world.level.BlockGetter;
					import net.minecraft.world.level.block.state.BlockState;
					public class CropBlock extends Block {
						protected static float getGrowthSpeed(BlockState state, BlockGetter level, BlockPos pos) {
							java.util.function.IntSupplier moisture = () -> pos.moisture * 3;
							return 100.0F + moisture.getAsInt();
						}
					}
					""",
			AXE, """
					package net.minecraft.world.item;
					import java.util.Optional;
					import net.minecraft.core.BlockPos;
					import net.minecraft.world.entity.player.Player;
					import net.minecraft.world.item.context.UseOnContext;
					import net.minecraft.world.level.Level;
					import net.minecraft.world.level.block.state.BlockState;
					public class AxeItem {
						public static int calls;
						public static Object context = "never called";
						private Optional<BlockState> evaluateNewBlockState(Level level, BlockPos pos, Player player, BlockState state,
								UseOnContext context) {
							calls++;
							AxeItem.context = context;
							return Optional.of(state);
						}
					}
					""");

	@AfterEach
	void reset() {
		System.clearProperty(VanillaMemberTwinInjector.PROPERTY);
	}

	@Test
	void onABaseWithoutThemTheTwinsAnswerAsVanillaDoes(@TempDir Path work) throws Throwable {
		Map<String, byte[]> vanilla = compile(work.resolve("vanilla"), VANILLA), genuine = compile(work.resolve("genuine"), GENUINE);
		VanillaMemberTwinInjector twins = new VanillaMemberTwinInjector(owner -> node(vanilla.get(owner)));
		Map<String, byte[]> classes = new HashMap<>(genuine);
		for (String name : new String[] {CROP, AXE}) {
			String internal = name.replace('.', '/');
			classes.put(internal, InjectorExecution.transform(twins, name, genuine.get(internal), EnvType.CLIENT));
			assertNotSame(genuine.get(internal), classes.get(internal), name + ": the twin landed");
		}
		assertEquals(2, twins.twinsAdded());
		ClassLoader loader = InjectorExecution.load(classes);
		for (String name : new String[] {CROP, AXE}) assertEquals("", InjectorExecution.verify(classes.get(name.replace('.', '/')), loader));

		Class<?> block = loader.loadClass("net.minecraft.world.level.block.Block"), getter = loader.loadClass("net.minecraft.world.level.BlockGetter");
		Class<?> pos = loader.loadClass("net.minecraft.core.BlockPos"), state = loader.loadClass("net.minecraft.world.level.block.state.BlockState");
		Object five = InjectorExecution.construct(pos, 5);

		// VANILLA_BODY: vanilla's 1 + 5 * 2, through vanilla's own lambda; the base's overload still says 100 + 5 * 3.
		Class<?> crop = loader.loadClass(CROP);
		Method twin = crop.getDeclaredMethod("getGrowthSpeed", block, getter, pos);
		twin.setAccessible(true);
		assertEquals(11.0F, twin.invoke(null, null, null, five), "vanilla's body, with vanilla's lambda");
		Method own = crop.getDeclaredMethod("getGrowthSpeed", state, getter, pos);
		own.setAccessible(true);
		assertEquals(115.0F, own.invoke(null, null, null, five), "the base's own overload, with its own lambda");

		// DELEGATE: vanilla's signature, NeoForge's body, no UseOnContext.
		Class<?> axe = loader.loadClass(AXE);
		Class<?> level = loader.loadClass("net.minecraft.world.level.Level"), player = loader.loadClass("net.minecraft.world.entity.player.Player");
		Method stub = axe.getDeclaredMethod("evaluateNewBlockState", level, pos, player, state);
		stub.setAccessible(true);
		Object log = InjectorExecution.construct(state);
		assertEquals(Optional.of(log), stub.invoke(InjectorExecution.construct(axe), null, five, null, log), "NeoForge's live answer");
		assertEquals(1, InjectorExecution.getStatic(axe, "calls"), "the stub forwarded once");
		assertNull(InjectorExecution.getStatic(axe, "context"), "with null for the context vanilla's signature has no place for");

		// As shipped, the genuine shape has neither member: a vanilla-compiled caller would get NoSuchMethodError.
		ClassLoader shipped = InjectorExecution.load(genuine);
		assertThrows(NoSuchMethodException.class, () -> shipped.loadClass(CROP).getDeclaredMethod("getGrowthSpeed",
				shipped.loadClass(block.getName()), shipped.loadClass(getter.getName()), shipped.loadClass(pos.getName())), "premise");
	}

	@Test
	@ResourceLock("system-properties")
	void whereVanillasMemberIsDeclaredOrTheSwitchIsOffTheClassIsHandedBack(@TempDir Path work) throws Exception {
		Map<String, byte[]> vanilla = compile(work.resolve("vanilla"), VANILLA), genuine = compile(work.resolve("genuine"), GENUINE);
		VanillaMemberTwinInjector twins = new VanillaMemberTwinInjector(owner -> node(vanilla.get(owner)));
		for (String name : new String[] {CROP, AXE}) {
			byte[] declared = vanilla.get(name.replace('.', '/'));
			assertSame(declared, InjectorExecution.transform(twins, name, declared, EnvType.CLIENT), name + " already declares it");
		}
		System.setProperty(VanillaMemberTwinInjector.PROPERTY, "off");
		for (String name : new String[] {CROP, AXE}) {
			byte[] lacking = genuine.get(name.replace('.', '/'));
			assertSame(lacking, InjectorExecution.transform(twins, name, lacking, EnvType.CLIENT), name + " with the switch off");
		}
		assertEquals(0, twins.twinsAdded());
	}

	private static Map<String, byte[]> compile(Path work, Map<String, String> classes) throws Exception {
		Map<String, String> sources = new HashMap<>(TYPES);
		sources.putAll(classes);
		return InjectorExecution.compile(work, sources);
	}

	private static ClassNode node(byte[] bytes) {
		if (bytes == null) return null;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
		return node;
	}
}
