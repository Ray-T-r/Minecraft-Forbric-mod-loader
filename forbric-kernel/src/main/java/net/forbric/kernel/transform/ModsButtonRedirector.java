/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.transform;

import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/**
 * Points the mods button at the list that knows the whole instance.
 *
 * <p>The pause menu's mods button opens {@code net.neoforged.neoforge.client.gui.ModListScreen}, which lists
 * {@code ModList.get()} — every mod NeoForge loaded. On a NeoForge instance that is the complete answer. Here it
 * was three of sixteen, and no other family's screen does better: each reads its own loader's registry and is
 * right about it. So the button is re-pointed at {@code KernelModListScreen}, which reads {@code ModCatalog}.
 *
 * <p>BOTH families' screens are redirected, not just the one the merged button happens to call. The merged base
 * carries a lambda for each — {@code lambda$createPauseMenu$3} builds NeoForge's, {@code lambda$createPauseMenu$11}
 * builds MinecraftForge's — and which one the button is bound to is a byte-merge outcome, not a decision. Leaving
 * the loser in place would make this repair silently conditional on a merge detail that has changed before.
 *
 * <p>The rewrite is two instructions: the {@code NEW} and the {@code INVOKESPECIAL} of its {@code (Screen)}
 * constructor. Same stack shape, same descriptor, no branch and no frame — the button, its sprite, its tooltip and
 * its place in the layout are untouched, because none of them are what was wrong.
 */
public final class ModsButtonRedirector implements ClassTransformer {
	static final String KERNEL_SCREEN = "net/forbric/kernel/runtime/KernelModListScreen";
	private static final String SCREEN_CTOR = "(Lnet/minecraft/client/gui/screens/Screen;)V";

	/** The translation key the Forge families label their mods button with. */
	static final String FML_MODS_KEY = "fml.menu.mods";
	/** What it says instead. Not a translation key -- see renameTheButton. */
	static final String FORBRIC_LABEL = "Mods (Forbric)";
	/** The sprite the Forge families point their mods button at. */
	static final String FML_SPRITE_NAMESPACE = "neoforge";
	static final String FML_SPRITE_PATH = "icon/neo_logo";
	/** Ours, shipped in the kernel's own game-side jar and served to the client pack repository with it. */
	static final String FORBRIC_SPRITE_NAMESPACE = "forbric";
	static final String FORBRIC_SPRITE_PATH = "icon/forbric_logo";
	private static final String COMPONENT = "net/minecraft/network/chat/Component";
	private static final String FACTORY_DESC = "(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;";

	/**
	 * The marker a class must contain before it is worth parsing.
	 *
	 * <p>This pass used to run on a fixed list of two screens, and that list was wrong in the way a fixed list of
	 * call sites usually is. The title screen's Forge-family mods button is not built in {@code TitleScreen} at
	 * all -- it is {@code neoforge.client.gui.widget.ModsButton}, a widget whose own {@code create} builds it and
	 * whose own lambda opens the old list. So the pass reported a site re-pointed in {@code TitleScreen} (a dead
	 * one, left by the byte merge) while the button a player can actually see went on opening NeoForge's list.
	 *
	 * <p>The claim is therefore made about the instance and not about a list of files: NO class constructs a
	 * family's own mod-list screen. Every class is eligible, and the raw bytes are scanned for these markers
	 * first, because parsing every class the game loads to find a handful is a cost paid thousands of times.
	 */
	private static final byte[][] MARKERS = {
			"ModListScreen".getBytes(java.nio.charset.StandardCharsets.UTF_8),
			FML_MODS_KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8),
			FML_SPRITE_PATH.getBytes(java.nio.charset.StandardCharsets.UTF_8),
	};

	/**
	 * The two classes that may still name themselves: they ARE the screens being replaced, and rewriting their
	 * own internals would be rewriting the thing nothing is supposed to reach any more.
	 */
	private static final Set<String> EXEMPT = Set.of(
			ForeignType.MOD_LIST_SCREEN.internal(Ecosystem.NEOFORGE),
			ForeignType.MOD_LIST_SCREEN.internal(Ecosystem.FORGE));

	/** Named through {@link ForeignType} so neither family's spelling can be the one that quietly stops matching. */
	private static final Set<String> REPLACED = Set.of(
			ForeignType.MOD_LIST_SCREEN.internal(Ecosystem.NEOFORGE),
			ForeignType.MOD_LIST_SCREEN.internal(Ecosystem.FORGE));

	/**
	 * Points the button at the kernel's own icon.
	 *
	 * <p>The label alone left two identical squares on the pause menu, one of them wearing NeoForge's logo while
	 * opening a list of every ecosystem's mods -- a picture that is now wrong about what the button does, and the
	 * first thing a player reads.
	 *
	 * <p>The identifier is built from two adjacent constants ({@code ldc "neoforge"; ldc "icon/neo_logo";
	 * Identifier.fromNamespaceAndPath}) at every site, so both move together or neither does: half a rewrite is an
	 * identifier for a texture nobody ships, and a missing GUI sprite is a magenta square rather than an error.
	 * The texture itself rides in the kernel's own game-side jar, which is served to the client pack repository
	 * alongside every mod's -- a sprite is resolved through the resource manager like any other asset.
	 */
	private static int reskinTheButton(ClassNode node) {
		int reskinned = 0;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof LdcInsnNode namespace) || !FML_SPRITE_NAMESPACE.equals(namespace.cst)) continue;
				AbstractInsnNode next = insn.getNext();
				while (next != null && next.getOpcode() < 0) next = next.getNext();
				if (!(next instanceof LdcInsnNode path) || !FML_SPRITE_PATH.equals(path.cst)) continue;
				namespace.cst = FORBRIC_SPRITE_NAMESPACE;
				path.cst = FORBRIC_SPRITE_PATH;
				reskinned++;
			}
		}
		return reskinned;
	}

	/** Raw-byte constant-pool scan. Cheap, and wrong only in the direction that costs one wasted parse. */
	private static boolean carriesAMarker(byte[] classBytes) {
		for (byte[] marker : MARKERS) {
			if (indexOf(classBytes, marker) >= 0) return true;
		}
		return false;
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i <= haystack.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) {
				if (haystack[i + j] != needle[j]) continue outer;
			}
			return i;
		}
		return -1;
	}

	/**
	 * Relabels the button so a player can tell it apart from the other one.
	 *
	 * <p>On a modded instance the pause menu has more than one mods button: Mod Menu inserts its own small icon
	 * button next to the Forge family's, and the two are the same size, the same shape and both say "Mods". The
	 * redirect was measured working -- pressed in a live client, the Forge-family button opens the unified list --
	 * and still read as "nothing happened", because the button that was pressed was the other one. A rewrite
	 * nobody can tell took effect is not finished.
	 *
	 * <p>{@code Component.translatable(key)} becomes {@code Component.literal(text)}: both are static factories on
	 * the same interface with the same descriptor, so this is a constant and a method name, no stack change. A
	 * literal rather than a key of our own, because a key resolves through the active language, which is loaded
	 * from resource packs long after this class is -- an untranslated key renders as the key itself, which is
	 * worse than the name it replaces.
	 */
	private static int renameTheButton(ClassNode node) {
		int renamed = 0;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof LdcInsnNode ldc) || !FML_MODS_KEY.equals(ldc.cst)) continue;
				AbstractInsnNode next = insn.getNext();
				while (next != null && next.getOpcode() < 0) next = next.getNext();
				if (!(next instanceof MethodInsnNode call) || !COMPONENT.equals(call.owner)
						|| !"translatable".equals(call.name) || !FACTORY_DESC.equals(call.desc)) {
					continue;
				}
				ldc.cst = FORBRIC_LABEL;
				call.name = "literal";
				renamed++;
			}
		}
		return renamed;
	}

	@Override
	public String name() {
		return "forbric-mods-button";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		String internal = className.replace('.', '/');
		if (EXEMPT.contains(internal) || internal.startsWith("net/forbric/")) return classBytes;
		if (!carriesAMarker(classBytes)) return classBytes;
		try {
			ClassNode node = new ClassNode();
			new ClassReader(classBytes).accept(node, 0);
			int redirected = 0;
			for (MethodNode method : node.methods) {
				if (method.instructions == null) continue;
				for (AbstractInsnNode insn : method.instructions) {
					if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
							&& REPLACED.contains(type.desc)) {
						type.desc = KERNEL_SCREEN;
						redirected++;
					} else if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL
							&& "<init>".equals(call.name) && SCREEN_CTOR.equals(call.desc)
							&& REPLACED.contains(call.owner)) {
						call.owner = KERNEL_SCREEN;
					}
				}
			}
			int renamed = renameTheButton(node);
			renamed += reskinTheButton(node);
			if (redirected == 0 && renamed == 0) return classBytes;
			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			ForbricLog.info("[Forbric/ModsButton] %s's mods button now opens the unified list and says so (%d "
					+ "construction site(s) re-pointed, %d label(s)+icon(s) changed) — each family's own screen lists "
					+ "only its own family, which on this instance is never the whole answer", internal,
					redirected, renamed);
			return writer.toByteArray();
		} catch (RuntimeException e) {
			ForbricLog.warn("[Forbric/ModsButton] could not re-point " + internal + "'s mods button — it will open "
					+ "one family's list", e);
			return classBytes;
		}
	}
}
