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
 * the old text: the mip-level repair (since deleted with the merged base) and the late block-state callback were both
 * made generic, went silent or changed their wording, and every m9 run would have gone red whether the repairs worked or
 * not.
 *
 * <p>So this reads the gate's own patterns out of {@code run/gate-m9-client.sh} and greps (with the real
 * {@code grep -acE}, as the gate does) what the mechanisms actually print when they are run here. The one line the
 * gate cannot see in its pack, the late completion's, is pinned here on the real code path. Every fixture
 * uses names no mod has; the only real names are the platform's: the bootstrap class a compat claim is anchored on,
 * and the block-state registry the walk is over. Each positive has a look-alike that the mechanism must leave alone,
 * and whose output the same gate pattern must not accept.
 */
@ExecutesInjector(RegistryElementCallbackInjector.class)
class M9MechanismLinesContractTest {
	private static final Path GATE = Path.of("run/gate-m9-client.sh");
	private static final String BOOTSTRAP = "net.minecraft.server.Bootstrap";
	private static final String STREAMS_CLAIM = "forbric-merged-base-compat#wrapTheStreamsVanillaWraps";
	private static final String WALKER = "unknown/cachecontract/EarlyWalker";
	private static final String LOOP = "if(CacheElement.class.isAssignableFrom(BlockState.class)) for(BlockState state:Block.BLOCK_STATE_REGISTRY) ((CacheElement)state).initializeDerivedState();";
	private static final TransformContext CLIENT = new TransformContext(EnvType.CLIENT, false, "intermediary");
	/**
	 * The late completion's line. gate-m9 cannot assert it: in that pack the walk runs at world start, after the
	 * last registration, so nothing is ever late there (measured). It is pinned here instead, on the real path.
	 */
	private static final String LATE_COMPLETION = "\\[Forbric/Lifecycle\\] completed [1-9][0-9]* registry element callback\\(s\\) for late registrations";

	@TempDir Path root;

	// ---- the claim census line --------------------------------------------------------------------------------
	// The gate reads one census line for every REQUIRED claim of the chain: a repair handed its target that makes no
	// edit. Its subject here is the compat repair that puts vanilla's wrapStreams() call back into Bootstrap.bootStrap(),
	// on a fixture shaped like NeoForge's (which calls the snapshot instead) and on one shaped like vanilla's.

	@Test
	void aClaimThatDeclinesItsTargetIsNamedOnTheCensusLineTheGateReads() throws Exception {
		byte[] raw = bootstrap(true).get(internal(BOOTSTRAP));
		TransformChain chain = chain();
		String[] out = new String[1];
		assertSame(raw, capture(() -> chain.applyBeforeMixin(BOOTSTRAP, raw, CLIENT), out));

		AnchorLedger.Report report = chain.ledger().report();
		assertEquals(1, report.misses().size(), () -> "exactly the streams repair declined: " + report.misses());
		AnchorLedger.Miss miss = report.misses().getFirst();
		assertEquals(STREAMS_CLAIM, miss.transformer());
		assertEquals(BOOTSTRAP, miss.className());
		assertTrue(miss.cost().contains("latest.log"), miss.cost());
		assertEquals(1, grep(gatePattern("no repair was handed its target and declined"), out[0]),
				"the census line the gate reads goes red the moment a REQUIRED target is left unrepaired: " + out[0]);
	}

	@Test
	void aClaimThatLandsIsAHitAndTheCensusLineStaysQuiet() throws Exception {
		byte[] raw = bootstrap(false).get(internal(BOOTSTRAP));
		TransformChain chain = chain();
		String[] out = new String[1];
		assertNotSame(raw, capture(() -> chain.applyBeforeMixin(BOOTSTRAP, raw, CLIENT), out));
		AnchorLedger.Report report = chain.ledger().report();
		assertTrue(report.clean(), () -> String.valueOf(report.misses()));
		assertEquals(1, report.hit(), "the one claim anchored on Bootstrap landed");
		assertEquals(0, grep(gatePattern("no repair was handed its target and declined"), out[0]), out[0]);
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

	/** {@code Bootstrap} with its own {@code wrapStreams()}, which {@code bootStrap()} calls (vanilla) or does not (NeoForge). */
	private Map<String, byte[]> bootstrap(boolean callsWrapStreams) throws Exception {
		String source = """
				package net.minecraft.server;
				public class Bootstrap {
				    private static final java.util.concurrent.atomic.AtomicLong bootstrapDuration = new java.util.concurrent.atomic.AtomicLong(-1L);
				    private static volatile boolean isBootstrapped;
				    public static int wraps;
				    public static void bootStrap() {
				        if (!isBootstrapped) {
				            isBootstrapped = true;
				            long start = System.nanoTime();
				            %s
				            bootstrapDuration.set(System.nanoTime() - start);
				        }
				    }
				    private static void wrapStreams() { wraps++; }
				}
				""".formatted(callsWrapStreams ? "wrapStreams();" : "");
		return InjectorExecution.compile(root.resolve("bootstrap" + callsWrapStreams), Map.of(BOOTSTRAP, source));
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
