/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.boot.DeadEventAudit;
import net.forbric.kernel.mixin.NativeGameReferences;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.Printer;

/**
 * The delivery bookkeeping {@link NativeDualEventInjector} writes into the staged merged base, and its two carriers' hook
 * classes, comes out again whole: every method the injector rewrites reads, once {@link DeliveryBookkeeping} has taken
 * the bookkeeping out, instruction for instruction (and handler for handler) as it read before the injector touched it.
 * That is what lets whatever decides where a guest's injector lands read the game's code: a point decided there is the
 * same point in the instrumented body Mixin applies to.
 */
class DeliveryBookkeepingStagedTest {
	private static List<Path> jars() {
		Path root = TestFixtures.stagedRoot().normalize();
		Path forge = root.resolve(TestFixtures.FORGE_INTEROP_JAR);
		if (!Files.isRegularFile(forge)) forge = root.resolve(TestFixtures.FORGE_CARRIER_JAR);
		return List.of(root.resolve(TestFixtures.BASE_JAR), forge, root.resolve(TestFixtures.NEO_CARRIER_JAR));
	}

	/** The game's resources as the kernel reads them at boot: the merged base first, then the two carriers. */
	static final class Resources implements Function<String, byte[]>, AutoCloseable {
		final List<ZipFile> zips = new ArrayList<>();

		Resources() throws IOException {
			for (Path jar : jars()) {
				TestFixtures.requireFile(Fixture.STAGED, jar, "staged jar absent: " + jar);
				zips.add(new ZipFile(jar.toFile()));
			}
		}

		@Override public synchronized byte[] apply(String path) {
			for (ZipFile zip : zips) {
				ZipEntry entry = zip.getEntry(path);
				if (entry == null) continue;
				try (var in = zip.getInputStream(entry)) {
					return in.readAllBytes();
				} catch (IOException unreadable) {
					return null;
				}
			}
			return null;
		}

		List<String> classes(int jar) {
			List<String> out = new ArrayList<>();
			for (var entries = zips.get(jar).entries(); entries.hasMoreElements(); ) {
				String name = entries.nextElement().getName();
				if (name.endsWith(".class") && !name.startsWith("META-INF/")) out.add(name.substring(0, name.length() - ".class".length()));
			}
			Collections.sort(out);
			return out;
		}

		@Override public void close() throws IOException {
			for (ZipFile zip : zips) zip.close();
		}
	}

	static NativeDualEventInjector injector(Resources resources) {
		NativeGameReferences references = new NativeGameReferences(resources);
		return new NativeDualEventInjector(resources, DeadEventAudit.forwardedForgeEvents(), NativeDualEventInjector.Families.DEFAULT,
				owner -> references.get(Ecosystem.FORGE, owner));
	}

	/** Parsed as the mixin service reads a target with code: frames as written, nothing expanded. */
	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	/**
	 * A method's executable instructions in order, each with its operands, a branch by the position of the instruction it
	 * reaches; then its handlers by the same positions. Labels, frames and line numbers are not code.
	 */
	static List<String> shape(MethodNode method) {
		List<AbstractInsnNode> code = new ArrayList<>();
		Map<AbstractInsnNode, Integer> position = new IdentityHashMap<>();
		for (AbstractInsnNode at : method.instructions) {
			if (at.getOpcode() >= 0) {
				position.put(at, code.size());
				code.add(at);
			}
		}
		Function<LabelNode, Integer> reach = label -> {
			for (AbstractInsnNode at = label; at != null; at = at.getNext()) if (at.getOpcode() >= 0) return position.get(at);
			return code.size();
		};
		List<String> out = new ArrayList<>();
		for (AbstractInsnNode at : code) {
			StringBuilder line = new StringBuilder(Printer.OPCODES[at.getOpcode()]);
			if (at instanceof MethodInsnNode call) line.append(' ').append(call.owner).append('.').append(call.name).append(call.desc);
			else if (at instanceof FieldInsnNode field) line.append(' ').append(field.owner).append('.').append(field.name).append(field.desc);
			else if (at instanceof VarInsnNode variable) line.append(' ').append(variable.var);
			else if (at instanceof IincInsnNode increment) line.append(' ').append(increment.var).append(' ').append(increment.incr);
			else if (at instanceof IntInsnNode integer) line.append(' ').append(integer.operand);
			else if (at instanceof LdcInsnNode constant) line.append(' ').append(constant.cst);
			else if (at instanceof TypeInsnNode type) line.append(' ').append(type.desc);
			else if (at instanceof JumpInsnNode jump) line.append(" ->").append(reach.apply(jump.label));
			else if (at instanceof TableSwitchInsnNode table) {
				line.append(" default ->").append(reach.apply(table.dflt));
				for (LabelNode label : table.labels) line.append(" ->").append(reach.apply(label));
			} else if (at instanceof LookupSwitchInsnNode lookup) {
				line.append(" default ->").append(reach.apply(lookup.dflt));
				for (int i = 0; i < lookup.keys.size(); i++) line.append(' ').append(lookup.keys.get(i)).append("->").append(reach.apply(lookup.labels.get(i)));
			} else if (at instanceof InvokeDynamicInsnNode indy) line.append(' ').append(indy.name).append(indy.desc).append(' ').append(Arrays.toString(indy.bsmArgs));
			else if (at instanceof MultiANewArrayInsnNode array) line.append(' ').append(array.desc).append(' ').append(array.dims);
			out.add(line.toString());
		}
		if (method.tryCatchBlocks != null) {
			for (TryCatchBlockNode block : method.tryCatchBlocks) {
				out.add("TRY " + reach.apply(block.start) + ".." + reach.apply(block.end) + " -> " + reach.apply(block.handler) + " " + block.type);
			}
		}
		return out;
	}

	@Test
	void everyMethodTheInjectorRewritesReadsAsTheGamesOwnCodeOnceTheBookkeepingIsOut() throws Exception {
		try (Resources resources = new Resources()) {
			NativeDualEventInjector injector = injector(resources);
			int callers = 0, hooks = 0, methods = 0;
			for (int jar = 0; jar < resources.zips.size(); jar++) {
				for (String owner : resources.classes(jar)) {
					byte[] before = resources.zips.get(jar).getInputStream(resources.zips.get(jar).getEntry(owner + ".class")).readAllBytes();
					byte[] after = injector.transform(owner.replace('/', '.'), before, null);
					if (after == before) continue;
					ClassNode was = node(before), now = node(after);
					int stripped = DeliveryBookkeeping.strip(now);
					assertTrue(stripped > 0, owner + ": rewritten, so something to take out");
					for (int i = 0; i < was.methods.size(); i++) {
						MethodNode original = was.methods.get(i), read = now.methods.get(i);
						assertEquals(original.name + original.desc, read.name + read.desc);
						assertFalse(DeliveryBookkeeping.mentions(read), owner + "." + read.name + read.desc + ": bookkeeping left");
						assertEquals(shape(original), shape(read), owner + "." + read.name + read.desc);
					}
					methods += stripped;
					if (jar == 0) callers++; else hooks++;
				}
			}
			// The denominator: the callers the stand-down rewrites, among them the key handler whose join a guest reads,
			// and the hook classes reporting their own deliveries.
			assertTrue(callers >= 20 && hooks > 0 && methods >= callers, callers + " callers, " + hooks + " hook classes, " + methods + " methods");
			System.out.println("[staged] bookkeeping taken out of " + methods + " methods in " + callers + " callers and " + hooks + " hook classes");
		}
	}

	@Test
	void aMethodWithoutBookkeepingOrWithAShapeItDoesNotKnowIsLeftAlone() throws Exception {
		try (Resources resources = new Resources()) {
			byte[] before = resources.apply("net/minecraft/client/KeyboardHandler.class");
			assertNotNull(before);
			ClassNode plain = node(before);
			assertEquals(0, DeliveryBookkeeping.strip(plain), "nothing to take out of the game's own class");
			ClassNode now = node(injector(resources).transform("net.minecraft.client.KeyboardHandler", before, null));
			MethodNode keyPress = now.methods.stream().filter(m -> m.name.equals("keyPress")).findFirst().orElseThrow();
			assertTrue(DeliveryBookkeeping.mentions(keyPress), "the staged keyPress is bracketed: both families' key hooks are called");
			// A bookkeeping call in a shape no writer produces: the whole method stays as it is.
			MethodInsnNode open = null;
			for (AbstractInsnNode at : keyPress.instructions) if (at instanceof MethodInsnNode call && call.name.equals("record")) open = call;
			assertNotNull(open);
			keyPress.instructions.insertBefore(open, new InsnNode(org.objectweb.asm.Opcodes.NOP));
			keyPress.instructions.remove(open.getNext()); // the guard's store
			List<String> mangled = shape(keyPress);
			assertFalse(DeliveryBookkeeping.strip(keyPress));
			assertEquals(mangled, shape(keyPress));
		}
	}
}
