/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;
import net.forbric.api.Side;
import net.forbric.kernel.classloading.ForbricClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The guest scan as production wires it: {@link KernelEventSubscribers#reportDeadEvents}, handed a real
 * {@link ForbricClassLoader} and the guests' jars.
 *
 * <p>Before the dead-event audit names a mod for an event the merged game never posts, it asks the loaded jars whether one
 * of them posts it (Tombstone's phantom spawner calls {@code ForgeEventFactory.onPlayerSpawnPhantom}); which hooks post the
 * event is read from the hook classes the game's class loader serves. The scan itself was tested; the wiring was not:
 * replacing the scan with {@code events -> Map.of()}, or making the hook classes unreadable, left the suite green. And the
 * hook classes are read the way the merged-base repairs read game classes — the loader's own jars first, then its parent —
 * which matters on a launch where the parent also serves a copy: these run both shapes.
 */
class GuestEventPostersWiringTest {
	private static final String FACTORY = "net/minecraftforge/event/ForgeEventFactory";
	private static final String PHANTOMS = "net/minecraftforge/event/entity/player/PlayerSpawnPhantomsEvent";
	@TempDir Path temporary;
	private List<ModCatalog.Entry> previous;

	@BeforeEach void catalogue() {
		previous = ModCatalog.everything();
		ModCatalog.publish(List.of(new ModCatalog.Entry(Ecosystem.FORGE, "nightmod", "Night", "1", "", List.of(), "night.jar", "", "")));
	}

	@AfterEach void restore() {
		ModCatalog.publish(previous);
	}

	/** {@code ForgeEventFactory} with {@code onPlayerSpawnPhantom()}, which constructs the phantom event when {@code posts}. */
	private static byte[] factory(boolean posts) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, FACTORY, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onPlayerSpawnPhantom", "()V", null, null);
		mv.visitCode();
		if (posts) {
			mv.visitTypeInsn(Opcodes.NEW, PHANTOMS);
			mv.visitInsn(Opcodes.POP);
		}
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(1, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A guest whose spawner calls the hook, as Tombstone's does. */
	private static byte[] spawner() {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "tomb/Spawner", null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
		mv.visitCode();
		mv.visitMethodInsn(Opcodes.INVOKESTATIC, FACTORY, "onPlayerSpawnPhantom", "()V", false);
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 1);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private Path jar(String name, Map<String, byte[]> entries) throws Exception {
		Path jar = temporary.resolve(name);
		try (OutputStream out = Files.newOutputStream(jar); ZipOutputStream zip = new ZipOutputStream(out)) {
			for (Map.Entry<String, byte[]> e : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(e.getKey()));
				zip.write(e.getValue());
				zip.closeEntry();
			}
		}
		return jar;
	}

	private boolean nightmodNamed(ForbricClassLoader loader, List<Path> guests) {
		KernelEventSubscribers.reportDeadEvents(loader, guests, Side.DEDICATED_SERVER, Map.of("nightmod", Set.of(PHANTOMS)), Map.of());
		return ModCatalog.failures().stream().anyMatch(e -> e.modId().equals("nightmod")
				&& e.statusDetail().contains("PlayerSpawnPhantomsEvent"));
	}

	@Test void theCarrierInTheLoadersOwnJarsIsReadBeforeAStaleCopyOnItsParent() throws Exception {
		assertTrue(DeadEventAudit.DEAD.containsKey(PHANTOMS), "the phantom event is a DEAD row");
		Path carrier = jar("carrier.jar", Map.of(FACTORY + ".class", factory(true)));
		Path stale = jar("stale.jar", Map.of(FACTORY + ".class", factory(false)));
		Path tomb = jar("tomb.jar", Map.of("tomb/Spawner.class", spawner()));
		try (URLClassLoader parent = new URLClassLoader(new URL[] {stale.toUri().toURL()}, null);
				ForbricClassLoader loader = new ForbricClassLoader(new URL[] {carrier.toUri().toURL()}, parent)) {
			assertEquals(false, nightmodNamed(loader, List.of(tomb)),
					"a loaded guest posts the event through the carrier's hook, so its listener is not accused");
			assertEquals(true, nightmodNamed(loader, List.of()), "without the guest, the listener is named — the control");
		}
	}

	@Test void aModListeningInCodeReachesTheAuditThroughTheSameCall() throws Exception {
		// RenderNameTagEvent is never posted and is a client event; ItemTossEvent is never posted on either side. A mod whose
		// only listener is ItemTossEvent.BUS.addListener(...) is named by the very call production makes.
		String toss = "net/minecraftforge/event/entity/item/ItemTossEvent", bus = "net/minecraftforge/eventbus/api/bus/CancellableEventBus";
		ClassWriter mod = new ClassWriter(0);
		mod.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "toss/TossMod", null, "java/lang/Object", null);
		var id = mod.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", true);
		id.visit("value", "tossmod");
		id.visitEnd();
		MethodVisitor init = mod.visitMethod(Opcodes.ACC_PUBLIC, "init", "(Ljava/util/function/Consumer;)V", null, null);
		init.visitCode();
		init.visitFieldInsn(Opcodes.GETSTATIC, toss, "BUS", "L" + bus + ";");
		init.visitVarInsn(Opcodes.ALOAD, 1);
		init.visitMethodInsn(Opcodes.INVOKEVIRTUAL, bus, "addListener", "(Ljava/util/function/Consumer;)Ljava/lang/Object;", false);
		init.visitInsn(Opcodes.POP);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(2, 2);
		init.visitEnd();
		mod.visitEnd();
		Path jar = jar("toss.jar", Map.of("toss/TossMod.class", mod.toByteArray()));
		// The loader serves ItemTossEvent with a static bus whose hasListeners answers as the run below says: the listener
		// found in the mod's code counts once the server is up, and only if its bus has one then.
		for (boolean listened : new boolean[] {false, true}) {
			ModCatalog.publish(List.of(new ModCatalog.Entry(Ecosystem.FORGE, "tossmod", "Toss", "1", "", List.of(), "toss.jar", "", "")));
			Path game = jar("game-" + listened + ".jar", eventWithBus(toss, bus, listened));
			try (URLClassLoader parent = new URLClassLoader(new URL[0], null);
					ForbricClassLoader loader = new ForbricClassLoader(new URL[] {game.toUri().toURL()}, parent)) {
				DeadEventAudit.resetPending();
				KernelEventSubscribers.reportDeadEvents(loader, List.of(jar), Side.DEDICATED_SERVER, Map.of(),
						Map.of(jar, KernelEventSubscribers.scanJar(jar)));
				assertTrue(ModCatalog.failures().isEmpty(), "a listener found in code is judged once the server is up");
				DeadEventAudit.judgePending(java.util.EnumSet.noneOf(net.forbric.api.GameEventBridge.class));
			} finally {
				DeadEventAudit.resetPending();
			}
			assertEquals(listened, ModCatalog.failures().stream().anyMatch(e -> e.modId().equals("tossmod")
					&& e.statusDetail().contains("ItemTossEvent")), "the bus has a listener: " + listened + " " + ModCatalog.failures());
		}
	}

	/**
	 * {@code event} with a static {@code BUS} of {@code busType}, an interface declaring {@code hasListeners()}, held by an
	 * implementation that answers {@code listened}.
	 */
	private static Map<String, byte[]> eventWithBus(String event, String busType, boolean listened) {
		ClassWriter bus = new ClassWriter(0);
		bus.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, busType, null, "java/lang/Object", null);
		bus.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "hasListeners", "()Z", null, null).visitEnd();
		bus.visitEnd();
		String impl = "test/Bus";
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, impl, null, "java/lang/Object", new String[] {busType});
		MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(1, 1);
		init.visitEnd();
		MethodVisitor has = cw.visitMethod(Opcodes.ACC_PUBLIC, "hasListeners", "()Z", null, null);
		has.visitCode();
		has.visitInsn(listened ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
		has.visitInsn(Opcodes.IRETURN);
		has.visitMaxs(1, 1);
		has.visitEnd();
		cw.visitEnd();
		ClassWriter ev = new ClassWriter(0);
		ev.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, event, null, "java/lang/Object", null);
		ev.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "BUS", "L" + busType + ";", null, null).visitEnd();
		MethodVisitor clinit = ev.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
		clinit.visitCode();
		clinit.visitTypeInsn(Opcodes.NEW, impl);
		clinit.visitInsn(Opcodes.DUP);
		clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, impl, "<init>", "()V", false);
		clinit.visitFieldInsn(Opcodes.PUTSTATIC, event, "BUS", "L" + busType + ";");
		clinit.visitInsn(Opcodes.RETURN);
		clinit.visitMaxs(2, 0);
		clinit.visitEnd();
		ev.visitEnd();
		return Map.of(busType + ".class", bus.toByteArray(), impl + ".class", cw.toByteArray(), event + ".class", ev.toByteArray());
	}

	@Test void aCarrierOnlyTheParentServesIsStillRead() throws Exception {
		Path carrier = jar("carrier.jar", Map.of(FACTORY + ".class", factory(true)));
		Path own = jar("own.jar", Map.of("unrelated/Nothing.class", spawner()));
		Path tomb = jar("tomb.jar", Map.of("tomb/Spawner.class", spawner()));
		try (URLClassLoader parent = new URLClassLoader(new URL[] {carrier.toUri().toURL()}, null);
				ForbricClassLoader loader = new ForbricClassLoader(new URL[] {own.toUri().toURL()}, parent)) {
			assertEquals(false, nightmodNamed(loader, List.of(tomb)), "the parent's carrier is read when the loader has none");
			assertEquals(true, nightmodNamed(loader, List.of()), "the control");
		}
	}
}
