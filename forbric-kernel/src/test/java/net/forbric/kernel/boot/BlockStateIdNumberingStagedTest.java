/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/**
 * Lane K1's premise, on the real bytes: why the kernel must empty NeoForge's blockstate→id map right before EVERY freeze
 * it drives (KernelLifecycle.closeRegistrationWindow and closeClientEntrypointWindow, pinned by
 * {@code KernelLifecycleRegistrationWindowTest}).
 *
 * <p>Two numberings write the one map. NeoForge's {@code BlockCallbacks.onBake}, which every block-registry freeze runs,
 * adds every state of the whole registry through {@code IdMapper.add} (next id onwards) and empties nothing first, and
 * NeoForge's {@code unfreezeData} empties nothing either. fabric-registry-sync's {@code StateIdTracker}, attached to that
 * same map ({@code Block.BLOCK_STATE_REGISTRY}), adds each block's states the moment the block is registered. So a window
 * that registers a NeoForge mod's 2026 bridge states arrived at its freeze with them already at ids 0..2025, and the bake
 * put air at 2026: on a dedicated server every block-state id was its native NeoForge id plus 2026, past the client's map
 * (a Forbric client joining it died on "No value with id 35212"; native NeoForge 26.2.0.93 numbers the same registry
 * 0..34391, measured in c5/k1). If either premise changes (NeoForge's bake clears, or Fabric's tracker stops adding) the
 * clear may be unnecessary, and this test says so.
 */
class BlockStateIdNumberingStagedTest {
	private static final String CALLBACKS = "net/neoforged/neoforge/registries/NeoForgeRegistryCallbacks$BlockCallbacks";
	private static final String NEO_MAP = CALLBACKS + "$ClearableObjectIntIdentityMap";
	private static final String GAME_DATA = "net/neoforged/neoforge/registries/GameData";
	private static final String MAPPED_REGISTRY = "net/minecraft/core/MappedRegistry";
	private static final String ID_MAPPER = "net/minecraft/core/IdMapper";
	private static final String TRACKER = "net/fabricmc/fabric/impl/registry/sync/trackers/StateIdTracker";

	/** A class of the genuine stage: NeoForge's own classes from its carrier, the patched game's from the base. */
	private static ClassNode baseClass(String internalName) {
		Path jar = internalName.startsWith("net/neoforged/") ? TestFixtures.neoCarrier() : TestFixtures.stagedBase();
		return node(TestFixtures.requireEntry(Fixture.STAGED, jar, internalName + ".class"));
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static MethodNode method(ClassNode node, String name, String descPrefix) {
		return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.startsWith(descPrefix)).findFirst()
				.orElseThrow(() -> new AssertionError(node.name + "." + name + descPrefix + " is gone"));
	}

	private static List<MethodInsnNode> calls(MethodNode m) {
		List<MethodInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : m.instructions.toArray()) if (insn instanceof MethodInsnNode call) out.add(call);
		return out;
	}

	/** Whether {@code m} empties an id map: a call of {@code clear} on NeoForge's map, or of anything on IdMapper named clear. */
	private static boolean emptiesAnIdMap(MethodNode m) {
		return calls(m).stream().anyMatch(c -> c.name.equals("clear") && (c.owner.equals(NEO_MAP) || c.owner.equals(ID_MAPPER)));
	}

	/** NeoForge's bake adds every state of the registry it is handed, after whatever the map holds. */
	@Test
	void neoForgesBakeAppendsTheWholeRegistryAndEmptiesNothingFirst() {
		ClassNode callbacks = baseClass(CALLBACKS);
		MethodNode bake = method(callbacks, "onBake", "(Lnet/minecraft/core/Registry;)");
		assertTrue(calls(bake).stream().anyMatch(c -> c.owner.equals("net/minecraft/core/Registry") && c.name.equals("iterator")),
				"onBake walks the whole registry it is handed");
		assertTrue(calls(bake).stream().anyMatch(c -> c.owner.equals(NEO_MAP) && c.name.equals("add")),
				"onBake adds each state through the map's add (IdMapper.add numbers from nextId)");
		assertFalse(emptiesAnIdMap(bake), "onBake empties nothing before adding: the kernel's clear is what makes it number once");
		// The predicate can see a clear when there is one: onClear, NeoForge's own path to it.
		assertTrue(emptiesAnIdMap(method(callbacks, "onClear", "(Lnet/minecraft/core/Registry;Z)")),
				"control: the same check finds the clear in BlockCallbacks.onClear");
		// IdMapper.add numbers from nextId, never from a free slot.
		MethodNode add = method(baseClass(ID_MAPPER), "add", "(Ljava/lang/Object;)V");
		boolean readsNextId = false;
		for (AbstractInsnNode insn : add.instructions.toArray()) {
			if (insn instanceof FieldInsnNode f && f.name.equals("nextId")) readsNextId = true;
		}
		assertTrue(readsNextId, "IdMapper.add maps the state at nextId");
	}

	/** Reopening the registries empties nothing, so the map a window's freeze starts from is whatever the window left. */
	@Test
	void neoForgesUnfreezeEmptiesNothing() {
		ClassNode gameData = baseClass(GAME_DATA);
		for (MethodNode m : gameData.methods) {
			if (m.name.equals("unfreezeData") || m.name.startsWith("lambda$unfreezeData")) {
				assertFalse(emptiesAnIdMap(m), "GameData." + m.name + " empties no id map");
				assertFalse(calls(m).stream().anyMatch(c -> c.name.equals("clear")), "GameData." + m.name + " clears nothing");
			}
		}
		MethodNode unfreeze = method(baseClass(MAPPED_REGISTRY), "unfreeze", "(Z)V");
		assertTrue(calls(unfreeze).isEmpty() || calls(unfreeze).stream().noneMatch(c -> c.name.contains("clear") || c.name.contains("Clear")),
				"MappedRegistry.unfreeze runs no clear callback: " + calls(unfreeze).stream().map(c -> c.name).toList());
	}

	/** fabric-registry-sync adds each newly registered block's states to the map it tracks, at once. */
	@Test
	void fabricsStateTrackerAddsABlocksStatesWhenItIsRegistered() throws IOException {
		Path api = TestFixtures.fabricApi();
		TestFixtures.requireFiles(Fixture.STAGED, "the pinned fabric-api", api);
		byte[] tracker = null;
		List<ClassNode> registrars = new ArrayList<>();
		Path nested = Files.createTempFile("k1-registry-sync", ".jar");
		try {
			try (ZipFile outer = new ZipFile(api.toFile())) {
				ZipEntry entry = outer.stream().filter(e -> e.getName().startsWith("META-INF/jars/fabric-registry-sync-v0-"))
						.findFirst().orElseThrow(() -> new AssertionError(api + " nests no fabric-registry-sync-v0"));
				try (var in = outer.getInputStream(entry)) {
					Files.copy(in, nested, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
				}
			}
			try (ZipInputStream inner = new ZipInputStream(Files.newInputStream(nested))) {
				for (ZipEntry e = inner.getNextEntry(); e != null; e = inner.getNextEntry()) {
					if (!e.getName().endsWith(".class")) continue;
					byte[] bytes = inner.readAllBytes();
					if (e.getName().equals(TRACKER + ".class")) tracker = bytes;
					else registrars.add(node(bytes));
				}
			}
		} finally {
			Files.deleteIfExists(nested);
		}
		assertNotNull(tracker, "fabric-registry-sync nests " + TRACKER);
		MethodNode added = method(node(tracker), "onEntryAdded", "(ILnet/minecraft/resources/Identifier;Ljava/lang/Object;)");
		boolean appends = false;
		for (AbstractInsnNode insn : added.instructions.toArray()) {
			if (insn instanceof InvokeDynamicInsnNode indy) {
				for (Object arg : indy.bsmArgs) {
					if (arg instanceof Handle h && h.getOwner().equals(ID_MAPPER) && h.getName().equals("add")) appends = true;
				}
			}
		}
		assertTrue(appends, "StateIdTracker.onEntryAdded hands each of the block's states to IdMapper::add");
		// ... and the map it is attached to is the block-state map NeoForge's bake numbers.
		int attached = 0;
		for (ClassNode c : registrars) {
			for (MethodNode m : c.methods) {
				AbstractInsnNode[] insns = m.instructions.toArray();
				boolean blockStates = false;
				for (AbstractInsnNode insn : insns) {
					if (insn instanceof FieldInsnNode f && f.owner.equals("net/minecraft/world/level/block/Block")
							&& f.name.equals("BLOCK_STATE_REGISTRY")) blockStates = true;
					if (blockStates && insn instanceof MethodInsnNode call && call.owner.equals(TRACKER) && call.name.equals("register")) {
						attached++;
						break;
					}
				}
			}
		}
		assertEquals(1, attached, "one fabric-registry-sync site attaches a StateIdTracker to Block.BLOCK_STATE_REGISTRY");
	}

	/**
	 * The sibling map has one numbering only (K1's review, B-9). fabric-registry-sync attaches a StateIdTracker to
	 * {@code Fluid.FLUID_STATE_REGISTRY} as well, but nothing on NeoForge's side numbers that map a second time: no class
	 * of the carrier touches it, and in the base only vanilla's own {@code Fluid} (the field) and {@code Fluids} (its
	 * bootstrap fill) do. If NeoForge grows a fluid-state bake, this map needs the clear the block map has, and this
	 * test says so.
	 */
	@Test
	void theFluidStateMapHasNoSecondNumbering() throws IOException {
		java.util.Set<String> writers = new java.util.TreeSet<>();
		for (Path jar : List.of(TestFixtures.stagedBase(), TestFixtures.neoCarrier())) {
			TestFixtures.requireFiles(Fixture.STAGED, "the genuine stage's base and carrier", jar);
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry e : java.util.Collections.list(zip.entries())) {
					if (!e.getName().endsWith(".class")) continue;
					byte[] bytes;
					try (var in = zip.getInputStream(e)) {
						bytes = in.readAllBytes();
					}
					if (!new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1).contains("FLUID_STATE_REGISTRY")) continue;
					ClassNode c = node(bytes);
					for (MethodNode m : c.methods) {
						for (AbstractInsnNode insn : m.instructions.toArray()) {
							if (insn instanceof FieldInsnNode f && f.name.equals("FLUID_STATE_REGISTRY")) writers.add(c.name);
						}
					}
				}
			}
		}
		assertEquals(java.util.Set.of("net/minecraft/world/level/material/Fluid", "net/minecraft/world/level/material/Fluids"),
				writers, "only vanilla's Fluid and Fluids touch the fluid-state map; anything else may number it a second time");
	}
}
