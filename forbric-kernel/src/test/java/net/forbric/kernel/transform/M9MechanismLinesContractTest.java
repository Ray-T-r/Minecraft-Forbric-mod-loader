/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.GateLogContract;
import net.forbric.kernel.interop.RegistryElementCallbacks;

/**
 * gate-m9 asserts on LOG LINES, and twice those lines were reworded or dropped while the gate kept grepping for
 * the old text: the mip-level repair and the late block-state callback were both made generic, went silent or
 * changed their wording, and every m9 run would have gone red whether the repairs worked or not.
 *
 * <p>So this reads the gate's own patterns out of {@code run/gate-m9-client.sh} and greps (with the real
 * {@code grep -acE}, as the gate does) what the mechanisms actually print when they are run here. The one line the
 * gate cannot see in its pack, the late completion's, is pinned here on the real code path. Every fixture
 * uses names no mod has; the only real names are the platform's: the atlas loader the mip repair must bound, and
 * the block-state registry the walk is over. Each positive has a look-alike that the mechanism must leave alone,
 * and whose output the same gate pattern must not accept.
 */
@ExecutesInjector(RegistryElementCallbackInjector.class)
class M9MechanismLinesContractTest {
	private static final Path GATE = Path.of("run/gate-m9-client.sh");
	private static final String SPRITE_LOADER = "net.minecraft.client.renderer.texture.SpriteLoader";
	private static final String MIP_CLAIM = "forbric-merged-base-compat#letTheAtlasLowerItsMipLevelLikeVanilla";
	private static final String WALKER = "unknown/cachecontract/EarlyWalker";
	private static final String LOOP = "if(CacheElement.class.isAssignableFrom(BlockState.class)) for(BlockState state:Block.BLOCK_STATE_REGISTRY) ((CacheElement)state).initializeDerivedState();";
	private static final TransformContext CLIENT = new TransformContext(EnvType.CLIENT, false, "intermediary");
	/**
	 * The late completion's line. gate-m9 cannot assert it: in that pack the walk runs at world start, after the
	 * last registration, so nothing is ever late there (measured). It is pinned here instead, on the real path.
	 */
	private static final String LATE_COMPLETION = "\\[Forbric/Lifecycle\\] completed [1-9][0-9]* registry element callback\\(s\\) for late registrations";

	@TempDir Path root;

	// ---- the mip-level bound -----------------------------------------------------------------------------------

	@Test
	void theAtlasLoaderBeingBoundedIsWhatTheGateSees() throws Exception {
		byte[] raw = atlas(SPRITE_LOADER, true).get(internal(SPRITE_LOADER));
		String[] out = new String[1];
		byte[] edited = capture(() -> new ForbricMergedBaseCompatTransformer().transform(SPRITE_LOADER, raw, null), out);
		assertNotSame(raw, edited);
		assertEquals(1, grep(gatePattern("an atlas may lower its mip level again"), out[0]), out[0]);
		assertTrue(out[0].contains(SPRITE_LOADER + ".assemble bounds"), "the line names the method it bounded: " + out[0]);

		String[] again = new String[1];
		assertSame(edited, capture(() -> new ForbricMergedBaseCompatTransformer().transform(SPRITE_LOADER, edited, null), again));
		assertEquals(0, grep(gatePattern("an atlas may lower its mip level again"), again[0]), "an already bounded class says nothing twice");
	}

	@Test
	void aRenamedAtlasIsBoundedAndSaysSoButIsNotTheClassTheGateNeeds() throws Exception {
		byte[] raw = atlas("unknown.images.Atlas", true).get("unknown/images/Atlas");
		String[] out = new String[1];
		assertNotSame(raw, capture(() -> new ForbricMergedBaseCompatTransformer().transform("unknown.images.Atlas", raw, null), out));
		assertTrue(out[0].contains("unknown.images.Atlas.assemble bounds the mip level it allocates"), out[0]);
		assertEquals(0, grep(gatePattern("an atlas may lower its mip level again"), out[0]),
				"the gate asks for the platform's atlas loader, not for any class the proof happens to bound");
	}

	@Test
	void aLookAlikeTheProofRejectsIsSilentAndTheLedgerNamesTheRepairThatDeclined() throws Exception {
		byte[] raw = atlas(SPRITE_LOADER, false).get(internal(SPRITE_LOADER));
		TransformChain chain = chain();
		String[] out = new String[1];
		assertSame(raw, capture(() -> chain.applyBeforeMixin(SPRITE_LOADER, raw, CLIENT), out));
		assertEquals(0, grep(gatePattern("an atlas may lower its mip level again"), out[0]), out[0]);

		AnchorLedger.Report report = chain.ledger().report();
		assertEquals(1, report.misses().size(), () -> "exactly the mip repair declined: " + report.misses());
		AnchorLedger.Miss miss = report.misses().getFirst();
		assertEquals(MIP_CLAIM, miss.transformer());
		assertEquals(SPRITE_LOADER, miss.className());
		assertTrue(miss.cost().contains("black screen"), miss.cost());
		assertEquals(1, grep(gatePattern("no repair was handed its target and declined"), out[0]),
				"the census line the gate reads goes red the moment the atlas loader is not bounded: " + out[0]);
	}

	@Test
	void theBoundedAtlasLoaderIsAHitOnTheLedger() throws Exception {
		byte[] raw = atlas(SPRITE_LOADER, true).get(internal(SPRITE_LOADER));
		TransformChain chain = chain();
		String[] out = new String[1];
		assertNotSame(raw, capture(() -> chain.applyBeforeMixin(SPRITE_LOADER, raw, CLIENT), out));
		AnchorLedger.Report report = chain.ledger().report();
		assertTrue(report.clean(), () -> String.valueOf(report.misses()));
		assertEquals(1, report.hit(), "the one claim anchored on the atlas loader landed");
		assertEquals(0, grep(gatePattern("no repair was handed its target and declined"), out[0]), out[0]);
	}

	@Test
	void switchedOffTheClaimStandsDownInsteadOfReportingTheSwitchAsABrokenAnchor() throws Exception {
		byte[] raw = atlas(SPRITE_LOADER, true).get(internal(SPRITE_LOADER));
		String previous = System.setProperty(ForbricMergedBaseCompatTransformer.MIPMAP_PROPERTY, "off");
		try {
			TransformChain chain = chain();
			String[] out = new String[1];
			assertSame(raw, capture(() -> chain.applyBeforeMixin(SPRITE_LOADER, raw, CLIENT), out));
			AnchorLedger.Report report = chain.ledger().report();
			assertTrue(report.clean(), () -> String.valueOf(report.misses()));
			assertEquals(0, grep(gatePattern("an atlas may lower its mip level again"), out[0]), "the switch is the one red it should be");
			assertEquals(0, grep(gatePattern("no repair was handed its target and declined"), out[0]), out[0]);
		} finally {
			if (previous == null) System.clearProperty(ForbricMergedBaseCompatTransformer.MIPMAP_PROPERTY);
			else System.setProperty(ForbricMergedBaseCompatTransformer.MIPMAP_PROPERTY, previous);
		}
	}

	// ---- the late block-state callback -------------------------------------------------------------------------

	@Test
	void aRecognisedWalkIsWhatTheGateSeesAndItsLateCompletionSaysHowMany() throws Throwable {
		Map<String, byte[]> classes = walker(LOOP);
		byte[] raw = classes.get(WALKER);
		String[] instrumented = new String[1];
		byte[] edited = capture(() -> InjectorExecution.transform(injector(classes), binary(WALKER), raw, EnvType.SERVER), instrumented);
		assertNotSame(raw, edited);
		assertEquals(1, grep(gatePattern("a closed block-state walk is instrumented"), instrumented[0]), instrumented[0]);
		assertTrue(instrumented[0].contains("unknown.cachecontract.EarlyWalker.populate is a closed walk"), instrumented[0]);

		classes.put(WALKER, edited);
		ClassLoader loader = InjectorExecution.load(classes);
		Class<?> state = loader.loadClass("net.minecraft.world.level.block.state.BlockState");
		Object registry = loader.loadClass("net.minecraft.world.level.block.Block").getField("BLOCK_STATE_REGISTRY").get(null);
		Object first = InjectorExecution.construct(state);
		InjectorExecution.invoke(registry, "add", first);
		InjectorExecution.invokeStatic(loader.loadClass(binary(WALKER)), "populate");
		Object late = InjectorExecution.construct(state);
		InjectorExecution.invoke(registry, "add", late);

		String[] completed = new String[1];
		assertEquals(1, capture(() -> RegistryElementCallbacks.completeLateRegistrations(registry), completed));
		assertEquals(1, grep(LATE_COMPLETION, completed[0]), completed[0]);
		assertEquals(1, state.getField("calls").getInt(late));
		assertEquals(1, state.getField("calls").getInt(first), "the walk's own element is not called again");

		String[] nothingLate = new String[1];
		assertEquals(0, capture(() -> RegistryElementCallbacks.completeLateRegistrations(registry), nothingLate));
		assertEquals(0, grep(LATE_COMPLETION, nothingLate[0]),
				"nothing completed is not reported as a completion: " + nothingLate[0]);
	}

	@Test
	void aLookAlikeWalkIsLeftAloneAndNeitherLineAppears() throws Throwable {
		// Conditional per-element work: the state it skipped is not one the walk "missed", so there is no proof.
		Map<String, byte[]> classes = walker("for(BlockState state:Block.BLOCK_STATE_REGISTRY) if(state.calls==0)((CacheElement)state).initializeDerivedState();");
		byte[] raw = classes.get(WALKER);
		String[] out = new String[1];
		assertSame(raw, capture(() -> InjectorExecution.transform(injector(classes), binary(WALKER), raw, EnvType.SERVER), out));
		assertEquals(0, grep(gatePattern("a closed block-state walk is instrumented"), out[0]), out[0]);

		ClassLoader loader = InjectorExecution.load(classes);
		Object registry = loader.loadClass("net.minecraft.world.level.block.Block").getField("BLOCK_STATE_REGISTRY").get(null);
		Class<?> state = loader.loadClass("net.minecraft.world.level.block.state.BlockState");
		InjectorExecution.invoke(registry, "add", InjectorExecution.construct(state));
		InjectorExecution.invokeStatic(loader.loadClass(binary(WALKER)), "populate");
		Object late = InjectorExecution.construct(state);
		InjectorExecution.invoke(registry, "add", late);
		String[] completed = new String[1];
		assertEquals(0, capture(() -> RegistryElementCallbacks.completeLateRegistrations(registry), completed));
		assertEquals(0, grep(LATE_COMPLETION, completed[0]), completed[0]);
		assertEquals(0, state.getField("calls").getInt(late));
	}

	// ---- fixtures ------------------------------------------------------------------------------------------------

	/** A class that picks a mip level the way the merged SpriteLoader does, under {@code binaryName}. */
	private Map<String, byte[]> atlas(String binaryName, boolean limitIsTheImageLog) throws Exception {
		int dot = binaryName.lastIndexOf('.');
		String pkg = binaryName.substring(0, dot), simple = binaryName.substring(dot + 1);
		String source = """
				package %s;
				public class %s {
				    public static boolean configured;
				    public static boolean policy(){return configured;}
				    public int assemble(int requested,int imageSize){
				        int maximum=%s; int selected;
				        if(maximum<requested && policy()) selected=maximum; else selected=requested;
				        return new net.minecraft.client.renderer.texture.Stitcher(16,16,selected,0).level;
				    }
				}
				""".formatted(pkg, simple, limitIsTheImageLog ? "net.minecraft.util.Mth.log2(imageSize)" : "imageSize");
		return InjectorExecution.compile(root.resolve(simple + limitIsTheImageLog), Map.of(
				"net.minecraft.util.Mth", "package net.minecraft.util; public class Mth {public static int log2(int size){return 31-Integer.numberOfLeadingZeros(size);}}",
				"net.minecraft.client.renderer.texture.Stitcher", "package net.minecraft.client.renderer.texture; public class Stitcher {public final int level; public Stitcher(int w,int h,int selected,int other){level=selected;}}",
				binaryName, source));
	}

	/** A guest initializer over the block-state registry, plus the minimal platform types it names. */
	private Map<String, byte[]> walker(String body) throws Exception {
		Map<String, byte[]> compiled = InjectorExecution.compile(root.resolve("walker" + Math.abs(body.hashCode())), Map.of(
				"net.minecraft.core.IdMapper", "package net.minecraft.core; public class IdMapper<T> implements Iterable<T> {private final java.util.List<T> values=new java.util.ArrayList<>(); public void add(T value){values.add(value);} public java.util.Iterator<T> iterator(){return values.iterator();}}",
				"net.minecraft.world.level.block.Block", "package net.minecraft.world.level.block; public class Block {public static final net.minecraft.core.IdMapper<net.minecraft.world.level.block.state.BlockState> BLOCK_STATE_REGISTRY=new net.minecraft.core.IdMapper<>();}",
				"unknown.cachecontract.CacheElement", "package unknown.cachecontract; public interface CacheElement {void initializeDerivedState();}",
				"net.minecraft.world.level.block.state.BlockState", "package net.minecraft.world.level.block.state; public class BlockState {public int calls; public void initializeDerivedState(){calls++;}}",
				"unknown.cachecontract.EarlyWalker", "package unknown.cachecontract;import net.minecraft.world.level.block.Block;import net.minecraft.world.level.block.state.BlockState;public class EarlyWalker {public static void populate(){" + body + "}}"));
		// The callback interface arrives on the element by a mixin, after the initializer was compiled.
		ClassNode state = new ClassNode();
		new ClassReader(compiled.get("net/minecraft/world/level/block/state/BlockState")).accept(state, 0);
		state.interfaces.add("unknown/cachecontract/CacheElement");
		ClassWriter writer = new ClassWriter(0);
		state.accept(writer);
		compiled.put(state.name, writer.toByteArray());
		return compiled;
	}

	private static RegistryElementCallbackInjector injector(Map<String, byte[]> classes) {
		return new RegistryElementCallbackInjector(name -> {
			byte[] bytes = classes.get(name.replace('.', '/'));
			if (bytes == null) return null;
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);
			return node;
		});
	}

	private static TransformChain chain() {
		TransformChain chain = new TransformChain();
		chain.register(TransformPhase.COREMOD, new ForbricMergedBaseCompatTransformer(name -> null));
		return chain;
	}

	private static String internal(String binary) { return binary.replace('.', '/'); }
	private static String binary(String internal) { return internal.replace('/', '.'); }

	// ---- the gate's own patterns, and grep ---------------------------------------------------------------------

	private static String gatePattern(String what) throws Exception { return GateLogContract.pattern(GATE, what); }
	private int grep(String pattern, String text) throws Exception { return GateLogContract.count(root, pattern, text); }
	private static <T> T capture(java.util.concurrent.Callable<T> body, String[] out) throws Exception { return GateLogContract.capture(body, out); }
}
