/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
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
import net.forbric.kernel.runtime.StagedGameClassLoader;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.util.Printer;

/**
 * The stand-down as the kernel writes it into the staged merged base, with the bridged events and MinecraftForge's own
 * bodies it reads at boot.
 *
 * <p>Two properties the stand-ins pin in miniature, read here off the real bodies. Every hook call and post a merged
 * method makes is still that method's own instruction afterwards, in the same order, and every rewritten class still
 * passes the JVM's own verifier: a guest mixin written against either family targets those calls by owner, name and
 * descriptor, and a bracket that replaced one, or a handler whose frame is wrong, breaks the game rather than one event.
 * And on each real method where a native MinecraftForge call the merged method may bypass is judged faithful, each way
 * out that bypasses it carries the verdict its own branch deserves, both ways: a check MinecraftForge's own body makes
 * too excuses the stand-down, an exit only the merged method has delivers it. A verdict that flips loses the event
 * (shared where it is not) or delivers one MinecraftForge would not have (the reverse), and no gate run takes those
 * ways out.
 */
class NativeDualEventInjectorStagedTest {
	private static Path root() {
		return TestFixtures.stagedRoot().normalize();
	}

	private static List<Path> jars() {
		Path forge = root().resolve(TestFixtures.FORGE_INTEROP_JAR);
		if (!Files.isRegularFile(forge)) forge = root().resolve(TestFixtures.FORGE_CARRIER_JAR);
		return List.of(root().resolve(TestFixtures.MERGED_BASE_JAR), forge, root().resolve(TestFixtures.NEO_CARRIER_JAR));
	}

	/** The game's resources as the kernel reads them at boot: the merged base first, then the two carriers. */
	private static final class Resources implements Function<String, byte[]>, AutoCloseable {
		private final List<ZipFile> zips = new ArrayList<>();

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

		/** Every class of the merged base, by internal name. */
		List<String> baseClasses() {
			List<String> out = new ArrayList<>();
			for (var entries = zips.get(0).entries(); entries.hasMoreElements(); ) {
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

	private static NativeDualEventInjector injector(Resources resources) {
		NativeGameReferences references = new NativeGameReferences(resources);
		return new NativeDualEventInjector(resources, DeadEventAudit.forwardedForgeEvents(), NativeDualEventInjector.Families.DEFAULT,
				owner -> references.get(Ecosystem.FORGE, owner));
	}

	private static ClassNode node(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static Map<String, List<String>> familyCalls(ClassNode node) {
		Map<String, List<String>> out = new TreeMap<>();
		for (MethodNode method : node.methods) {
			List<String> calls = new ArrayList<>();
			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode call && (call.owner.startsWith("net/minecraftforge/") || call.owner.startsWith("net/neoforged/"))) {
					calls.add(Printer.OPCODES[call.getOpcode()] + " " + call.owner + "." + call.name + call.desc);
				}
			}
			out.put(method.name + method.desc, calls);
		}
		return out;
	}

	@Test
	void everyHookCallOfTheStagedBaseStaysTheCallersOwnAndEveryRewrittenClassPassesTheVerifier() throws Exception {
		try (Resources resources = new Resources()) {
			NativeDualEventInjector injector = injector(resources);
			Map<String, byte[]> rewritten = new TreeMap<>();
			int brackets = 0, recordings = 0;
			for (String owner : resources.baseClasses()) {
				byte[] before = resources.apply(owner + ".class"), after = injector.transform(owner.replace('/', '.'), before, null);
				if (after == before) continue;
				ClassNode was = node(before), now = node(after);
				assertEquals(familyCalls(was), familyCalls(now), owner + ": each method's hook calls and posts, in order");
				for (MethodNode method : now.methods) {
					for (NativeDualEventInjector.Bracket bracket : NativeDualEventInjector.brackets(method)) {
						brackets++;
						if (!bracket.scope()) recordings++;
					}
				}
				assertSame(after, injector.transform(owner.replace('/', '.'), after, null), owner + ": a second pass changes nothing");
				rewritten.put(owner, after);
			}
			// The denominator: the reviewer-reported sites are among what was rewritten, and recorded where they come first.
			assertTrue(rewritten.containsKey("net/minecraft/server/MinecraftServer"), rewritten.keySet().toString());
			assertTrue(rewritten.containsKey("net/minecraft/world/entity/LivingEntity"), rewritten.keySet().toString());
			assertTrue(rewritten.containsKey("net/minecraft/client/multiplayer/chat/ChatListener"), rewritten.keySet().toString());
			assertTrue(brackets > 50 && recordings > 0, brackets + " brackets, " + recordings + " recordings");
			ClassNode server = node(rewritten.get("net/minecraft/server/MinecraftServer"));
			boolean recordedTick = false;
			for (MethodNode method : server.methods) {
				if (!method.name.equals("tickServer")) continue;
				for (NativeDualEventInjector.Bracket bracket : NativeDualEventInjector.brackets(method)) {
					recordedTick |= !bracket.scope() && bracket.call().name.equals("onPreServerTick");
				}
			}
			assertTrue(recordedTick, "MinecraftForge's server tick in tickServer is recorded where it stands");

			// Each rewritten class, defined with the rest of the staged game and its libraries in one loader (so every
			// class it names is the same class its neighbours name), links: the JVM's own verifier checks the stack map
			// frames the brackets and their handlers carry.
			List<URL> urls = new ArrayList<>(StagedGameClassLoader.urls());
			urls.add(Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main").toUri().toURL());
			List<String> verified = new ArrayList<>(), unresolved = new ArrayList<>();
			try (URLClassLoader game = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader()) {
				@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
					byte[] bytes = rewritten.get(name.replace('.', '/'));
					return bytes == null ? super.findClass(name) : defineClass(name, bytes, 0, bytes.length);
				}
			}) {
				for (String owner : rewritten.keySet()) {
					try {
						Class.forName(owner.replace('/', '.'), false, game).getDeclaredMethods();
						verified.add(owner);
					} catch (VerifyError broken) {
						fail(owner + " fails verification after the stand-down: " + broken);
					} catch (LinkageError absent) {
						// A class a supertype or a signature names is not on the staged classpath: says nothing about frames.
						unresolved.add(owner + ": " + absent);
					}
				}
			}
			for (String key : List.of("net/minecraft/server/MinecraftServer", "net/minecraft/world/entity/LivingEntity",
					"net/minecraft/client/multiplayer/chat/ChatListener", "net/minecraft/client/KeyboardHandler",
					"net/minecraft/client/Minecraft", "net/minecraft/server/level/ServerPlayer")) {
				assertTrue(verified.contains(key), key + " is verified, not skipped: " + unresolved);
			}
			assertTrue(verified.size() * 10 >= rewritten.size() * 9, verified.size() + " of " + rewritten.size() + " verified: " + unresolved);
			System.out.println("[staged] " + resources.baseClasses().size() + " classes, " + rewritten.size() + " rewritten, " + brackets
					+ " brackets (" + recordings + " recordings), " + verified.size() + " verified; unresolved " + unresolved);
		}
	}

	/** Each settlement of {@code method}: how its branch reads ("IFEQ after Owner.member", jump or fall), and its verdict. */
	private static Map<String, String> verdicts(MethodNode method) {
		Map<String, String> out = new TreeMap<>();
		for (NativeDualEventInjector.Settlement settlement : NativeDualEventInjector.settlements(method)) {
			String key = describe(settlement.branch()) + (settlement.jump() ? " (taken)" : " (falls through)");
			String verdict = (settlement.forgeBypassesToo() ? "shared " : "neo-only ") + simple(settlement.events());
			String was = out.put(key, verdict);
			assertNull(was, "one settlement per verdict and branch: " + key);
		}
		return out;
	}

	/** A branch by its opcode and the nearest member it reads before it, past the kernel's own calls. */
	private static String describe(AbstractInsnNode branch) {
		for (AbstractInsnNode at = branch.getPrevious(); at != null; at = at.getPrevious()) {
			if (at instanceof MethodInsnNode call && !call.owner.startsWith("net/forbric/")) return Printer.OPCODES[branch.getOpcode()] + " after " + simple(call.owner) + "." + call.name;
			if (at instanceof FieldInsnNode field) return Printer.OPCODES[branch.getOpcode()] + " after " + simple(field.owner) + "." + field.name;
		}
		return Printer.OPCODES[branch.getOpcode()];
	}

	private static String simple(String names) {
		StringBuilder out = new StringBuilder();
		for (String name : names.split(";")) {
			if (out.length() > 0) out.append(';');
			out.append(name.substring(name.lastIndexOf('/') + 1));
		}
		return out.toString();
	}

	/**
	 * Every settlement on the staged bodies, by its branch and verdict, read against MinecraftForge's own body of each
	 * method. The system-chat listener's are the live ones: its bridge stands down on the NeoForge call there, so a flip
	 * either way is an event lost or invented in play. Its NeoForge cancel return (the null answer) is NeoForge's own way
	 * out, and delivers; the friend restriction, the missing player and the player who cannot receive system messages are
	 * checks MinecraftForge's body makes in front of its own call, and excuse.
	 *
	 * <p>The other methods' faithful scopes sit on NeoForge calls whose events no bridge for the faithful MinecraftForge
	 * event receives (screen key events beside the key input, the travel event beside the dimension change, the
	 * applicability check beside the effect added, the client tick beside the level tick, the log-out beside the level
	 * unload), so nothing is ever owed on them and their verdicts deliver nothing. They are pinned all the same, as the
	 * judgement reads them: the screen-key ways out that NeoForge's patch spells out (its pre and post hooks around the
	 * screen's own handler) are judged NeoForge's own, because MinecraftForge's body makes the same three tests inside its
	 * one hook call, where the alignment cannot follow; and NeoForge's travel cancel is NeoForge's own exit.
	 */
	@Test
	void eachWayOutOfAFaithfulDeliveryOnTheStagedBodiesCarriesItsOwnBranchsVerdict() throws Exception {
		Map<String, Map<String, String>> expected = new TreeMap<>();
		String chat = "SystemMessageReceivedEvent", key = "InputEvent$Key", level = "TickEvent$LevelTickEvent$Post;TickEvent$LevelTickEvent$Pre";
		expected.put("ChatListener.handleSystemMessage", Map.of(
				"IFNONNULL after ClientHooks.onClientSystemChat (falls through)", "neo-only " + chat,
				"IFNE after Minecraft.isFriendOnlyRestricted (taken)", "shared " + chat,
				"IFNULL after Minecraft.player (taken)", "shared " + chat,
				"IFEQ after ChatAbilities.canReceiveSystemMessages (taken)", "shared " + chat));
		expected.put("KeyboardHandler.keyPress", Map.of(
				"IFNE after ClientHooks.onScreenKeyPressedPre (taken)", "neo-only " + key,
				"IFNE after Screen.keyPressed (taken)", "neo-only " + key,
				"IFEQ after ClientHooks.onScreenKeyPressedPost (falls through)", "shared " + key,
				"IFNE after ClientHooks.onScreenKeyReleasedPre (taken)", "neo-only " + key,
				"IFNE after Screen.keyReleased (taken)", "neo-only " + key,
				"IFEQ after ClientHooks.onScreenKeyReleasedPost (falls through)", "shared " + key));
		expected.put("ServerPlayer.teleport", Map.of(
				"IFNE after CommonHooks.onTravelToDimension (falls through)", "neo-only PlayerEvent$PlayerChangedDimensionEvent",
				"IFEQ after ServerPlayer.isRemoved (falls through)", "shared PlayerEvent$PlayerChangedDimensionEvent",
				"IF_ACMPNE after ServerLevel.dimension (falls through)", "shared PlayerEvent$PlayerChangedDimensionEvent"));
		expected.put("Minecraft.tick", Map.of(
				"IFNE after Minecraft.pause (taken)", "shared " + level,
				"IFNULL after Minecraft.level (taken)", "shared " + level));
		expected.put("Minecraft.disconnect", Map.of("IFNULL after Minecraft.level (taken)", "shared LevelEvent$Unload"));
		expected.put("LivingEntity.addEffect", Map.of("IFNE after CommonHooks.canMobEffectBeApplied (falls through)", "shared MobEffectEvent$Added"));
		try (Resources resources = new Resources()) {
			NativeDualEventInjector injector = injector(resources);
			Map<String, Map<String, String>> found = new TreeMap<>();
			for (String owner : List.of("net/minecraft/client/multiplayer/chat/ChatListener", "net/minecraft/client/KeyboardHandler",
					"net/minecraft/server/level/ServerPlayer", "net/minecraft/client/Minecraft", "net/minecraft/world/entity/LivingEntity")) {
				byte[] before = resources.apply(owner + ".class");
				assertNotNull(before, owner);
				for (MethodNode method : node(injector.transform(owner.replace('/', '.'), before, null)).methods) {
					Map<String, String> verdicts = verdicts(method);
					if (!verdicts.isEmpty()) found.put(simple(owner) + "." + method.name, verdicts);
				}
			}
			assertEquals(expected.keySet(), found.keySet(), "the methods with a faithful delivery: " + found);
			for (String method : expected.keySet()) assertEquals(new TreeMap<>(expected.get(method)), found.get(method), method);
		}
	}
}
