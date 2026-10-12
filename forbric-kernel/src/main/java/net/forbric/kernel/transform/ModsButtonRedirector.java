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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.mixin.NativeGameReferences;
import net.forbric.kernel.util.ByteScan;
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
	private static final String SCREEN = "Lnet/minecraft/client/gui/screens/Screen;";
	private static final String SCREEN_CTOR = "(" + SCREEN + ")V";
	/**
	 * A family may open its list through a static factory instead of {@code new}.
	 *
	 * <p>NeoForge 26.2.0.88 does: {@code ModsButton}'s press lambda is
	 * {@code Gui.setScreen(ModListScreen.create(screen))}, where {@code .38-beta} had {@code new ModListScreen(...)}
	 * in {@code PauseScreen} itself. Only the construction moved -- the label key and the sprite are unchanged --
	 * so a pass that handled the {@code NEW} alone would have gone on relabelling the button and repainting its
	 * icon while it still opened NeoForge's own list. That is worse than not firing at all, because the button
	 * then lies about what it does.
	 *
	 * <p>The rewritten call returns {@code KernelModListScreen} rather than {@code Screen}: every use of the
	 * original return value accepts a {@code Screen}, and the narrower type verifies wherever the wider one would
	 * plus wherever the family's own type is required.
	 */
	private static final String FACTORY = "create";

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
	/**
	 * A name NeoForge used to have, and no longer does.
	 *
	 * <p>Between 26.2.0.38-beta and 26.2.0.88 NeoForge moved {@code client.gui.ModListScreen} to
	 * {@code client.gui.modlist.ModListScreen}. {@link ForeignType#MOD_LIST_SCREEN} names the CURRENT one, which
	 * is right and is what the carrier ships — but a mod compiled against the older NeoForge still emits
	 * {@code new net/neoforged/neoforge/client/gui/ModListScreen(Screen)}, and on this instance that class simply
	 * does not exist. It is not a degraded button: titlescreenfixer's mixin puts that constructor inside
	 * {@code TitleScreen}, so the client died in {@code Minecraft.<init>} with
	 * {@code NoClassDefFoundError: net/neoforged/neoforge/client/gui/ModListScreen} before reaching a screen.
	 *
	 * <p>Re-pointing the old name costs nothing — every construction of it was going to open a mods list, and the
	 * kernel's unified one is the answer this instance wants anyway — and it turns a dead client into a working
	 * button. Deliberately NOT in {@code ForeignType}: that table pairs the two ecosystems' CURRENT names, and a
	 * historical alias with no counterpart on the other side would break the pairing that makes it checkable.
	 */
	private static final String NEOFORGE_PRE_88_MOD_LIST_SCREEN =
			"net/neoforged/neoforge/client/gui/ModListScreen";

	private static final Set<String> REPLACED = Set.of(
			ForeignType.MOD_LIST_SCREEN.internal(Ecosystem.NEOFORGE),
			ForeignType.MOD_LIST_SCREEN.internal(Ecosystem.FORGE),
			NEOFORGE_PRE_88_MOD_LIST_SCREEN);

	/**
	 * Another class as the chain defines it, without defining it: what tells a widget factory's own class opens a
	 * mod list. Null for a class the loader cannot find, which leaves that call unrecognised and its menu as it is.
	 */
	private final Function<String, ClassNode> classes;

	public ModsButtonRedirector() {
		this(NativeGameReferences::current);
	}

	ModsButtonRedirector(Function<String, ClassNode> classes) {
		this.classes = classes;
	}

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
	/**
	 * Whether the class mentions any of the markers, in ONE pass over its bytes.
	 *
	 * <p>It used to be a pass per marker, and the answer is almost always no — every class the game loads reaches
	 * this transformer and a handful mention the mods screen — so the common case walked the whole class three
	 * times to say so. Each position is now tested against every marker before moving on, which is the same
	 * comparisons over a third of the memory traffic.
	 */
	static boolean carriesAMarker(byte[] classBytes) {
		return ByteScan.containsAny(classBytes, MARKERS);
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

	/**
	 * Keeps one mods button in a menu that adds one per family.
	 *
	 * <p>A base that keeps both families' edits of one menu method carries both families' mods buttons in it. The
	 * merged {@code PauseScreen.createPauseMenu} adds NeoForge's ({@code ModsButton.create(this)} in the icon row)
	 * AND MinecraftForge's (a full-width {@code fml.menu.mods} button under Options), where each family's own body
	 * adds one. Once this pass points every family's button at the one unified list they are the same button
	 * twice: a player saw "Mods (Forbric)" as an icon and again as a row of its own.
	 *
	 * <p>A mods button is recognised by what pressing it does, never by whose it is: a lambda of this class whose
	 * body opens a family's mod list (or the unified one), or a static factory {@code X f(Screen)} whose own class
	 * {@code X} opens one. In a method with two or more, the first in instruction order stays, and each later one's
	 * whole statement goes: from the last instruction before it where the operand stack is empty up to the next,
	 * provided that span holds no branch, switch, return, throw, local store and no label anything refers to — so
	 * the stack and the locals after it are exactly what they were before it and no frame changes. A span that
	 * fails any of that is left in place and reported: a second button is a cosmetic fault, a broken method is not.
	 */
	private int keepOneModsButtonPerMethod(ClassNode node) {
		int dropped = 0;
		for (MethodNode method : node.methods) {
			if (method.instructions == null || method.instructions.size() == 0) continue;
			List<AbstractInsnNode> sites = modsButtonSites(node, method);
			if (sites.size() < 2) continue;
			Frame<BasicValue>[] frames;
			try {
				frames = new Analyzer<>(new BasicInterpreter()).analyze(node.name, method);
			} catch (AnalyzerException unreadable) {
				ForbricLog.warn("[Forbric/ModsButton] %s.%s adds %d mods buttons, but its stack could not be read (%s) "
						+ "— left as it is, and the menu shows its mods button more than once", node.name, method.name,
						sites.size(), unreadable.getMessage());
				continue;
			}
			AbstractInsnNode[] insns = method.instructions.toArray();
			Set<LabelNode> referenced = referencedLabels(method);
			int first = indexOf(insns, sites.get(0));
			List<int[]> spans = new ArrayList<>();
			for (int i = 1; i < sites.size(); i++) {
				int[] span = statement(insns, frames, referenced, indexOf(insns, sites.get(i)));
				if (span == null || (span[0] <= first && first < span[1])) continue;
				boolean overlaps = false;
				for (int[] other : spans) overlaps |= span[0] < other[1] && other[0] < span[1];
				if (!overlaps) spans.add(span);
			}
			for (int[] span : spans) {
				for (int k = span[0]; k < span[1]; k++) {
					if (insns[k].getOpcode() >= 0) method.instructions.remove(insns[k]);
				}
			}
			dropped += spans.size();
			int left = sites.size() - 1 - spans.size();
			if (!spans.isEmpty()) {
				ForbricLog.info("[Forbric/ModsButton] %s.%s added %d mods buttons — the merge kept each family's, and "
						+ "every one opens the same unified list — kept the first and dropped %d, so the menu shows it "
						+ "once", node.name, method.name, sites.size(), spans.size());
			}
			if (left > 0) {
				ForbricLog.warn("[Forbric/ModsButton] %s.%s adds %d mods buttons and %d of the extra ones could not be "
						+ "dropped safely (their statement branches, stores a local or holds a jump target) — the menu "
						+ "shows its mods button more than once", node.name, method.name, sites.size(), left);
			}
		}
		return dropped;
	}

	/** The instructions in {@code method} that build a mods button, in instruction order. */
	private List<AbstractInsnNode> modsButtonSites(ClassNode node, MethodNode method) {
		List<AbstractInsnNode> sites = new ArrayList<>();
		List<MethodInsnNode> factories = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof InvokeDynamicInsnNode indy) {
				MethodNode body = lambdaBody(node, indy);
				if (body != null && body != method && opensAModList(body)) sites.add(insn);
			} else if (insn instanceof MethodInsnNode call && isWidgetFactory(node, call)) {
				factories.add(call);
			}
		}
		// The factory's class is read only where it could make a second button: the common case is a method with
		// none, and reading a class runs the transform chain over it.
		if (sites.size() + factories.size() < 2) return List.of();
		for (MethodInsnNode call : factories) {
			ClassNode owner = classes == null ? null : classes.apply(call.owner);
			if (owner != null && opensAModList(owner)) sites.add(call);
		}
		if (sites.size() < 2) return List.of();
		sites.sort((a, b) -> Integer.compare(method.instructions.indexOf(a), method.instructions.indexOf(b)));
		return sites;
	}

	/** {@code X f(Screen)} on another class {@code X}: a widget built for the screen it sits on. */
	private static boolean isWidgetFactory(ClassNode node, MethodInsnNode call) {
		if (call.getOpcode() != Opcodes.INVOKESTATIC || call.owner.equals(node.name)
				|| REPLACED.contains(call.owner) || KERNEL_SCREEN.equals(call.owner)) return false;
		Type[] args = Type.getArgumentTypes(call.desc);
		Type result = Type.getReturnType(call.desc);
		return args.length == 1 && SCREEN.equals(args[0].getDescriptor()) && result.getSort() == Type.OBJECT
				&& call.owner.equals(result.getInternalName());
	}

	/** The synthetic body a lambda of this class runs, or null when it is not a lambda of this class. */
	private static MethodNode lambdaBody(ClassNode node, InvokeDynamicInsnNode indy) {
		if (!"java/lang/invoke/LambdaMetafactory".equals(indy.bsm.getOwner()) || indy.bsmArgs.length < 2
				|| !(indy.bsmArgs[1] instanceof Handle impl) || !node.name.equals(impl.getOwner())) return null;
		for (MethodNode candidate : node.methods) {
			if (candidate.name.equals(impl.getName()) && candidate.desc.equals(impl.getDesc())) return candidate;
		}
		return null;
	}

	private static boolean opensAModList(ClassNode node) {
		for (MethodNode method : node.methods) {
			if (method.instructions != null && opensAModList(method)) return true;
		}
		return false;
	}

	/** Whether the method constructs a family's mod list or the unified one, by either shape. */
	private static boolean opensAModList(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
					&& (REPLACED.contains(type.desc) || KERNEL_SCREEN.equals(type.desc))) return true;
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
					&& FACTORY.equals(call.name) && call.desc.startsWith("(" + SCREEN + ")")
					&& (REPLACED.contains(call.owner) || KERNEL_SCREEN.equals(call.owner))) return true;
		}
		return false;
	}

	/**
	 * The statement around instruction {@code at}, as {@code [start, end)} indexes: from the last instruction at or
	 * before it entered with an empty operand stack to the first one after it that is. Null when no such span exists
	 * or when removing it could change anything but the widget it builds.
	 */
	private static int[] statement(AbstractInsnNode[] insns, Frame<BasicValue>[] frames, Set<LabelNode> referenced, int at) {
		int start = at;
		while (start >= 0 && !emptyStackAt(insns, frames, start)) start--;
		int end = at + 1;
		while (end < insns.length && !emptyStackAt(insns, frames, end)) end++;
		if (start < 0 || end >= insns.length) return null;
		for (int k = start; k < end; k++) {
			AbstractInsnNode insn = insns[k];
			if (insn instanceof LabelNode label && referenced.contains(label)) return null;
			if (insn instanceof FrameNode) return null;
			int op = insn.getOpcode();
			if (op < 0) continue;
			if (frames[k] == null || insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode
					|| insn instanceof LookupSwitchInsnNode || insn instanceof IincInsnNode) return null;
			if ((op >= Opcodes.IRETURN && op <= Opcodes.RETURN) || op == Opcodes.ATHROW || op == Opcodes.RET
					|| op == Opcodes.MONITORENTER || op == Opcodes.MONITOREXIT) return null;
			if (insn instanceof VarInsnNode && op >= Opcodes.ISTORE && op <= Opcodes.ASTORE) return null;
		}
		return new int[] {start, end};
	}

	private static boolean emptyStackAt(AbstractInsnNode[] insns, Frame<BasicValue>[] frames, int k) {
		return insns[k].getOpcode() >= 0 && frames[k] != null && frames[k].getStackSize() == 0;
	}

	/** Every label a branch, a switch or an exception range refers to. */
	private static Set<LabelNode> referencedLabels(MethodNode method) {
		Set<LabelNode> out = new HashSet<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof JumpInsnNode jump) out.add(jump.label);
			else if (insn instanceof TableSwitchInsnNode table) {
				out.add(table.dflt);
				out.addAll(table.labels);
			} else if (insn instanceof LookupSwitchInsnNode lookup) {
				out.add(lookup.dflt);
				out.addAll(lookup.labels);
			}
		}
		if (method.tryCatchBlocks != null) {
			for (TryCatchBlockNode block : method.tryCatchBlocks) {
				out.add(block.start);
				out.add(block.end);
				out.add(block.handler);
			}
		}
		return out;
	}

	private static int indexOf(AbstractInsnNode[] insns, AbstractInsnNode target) {
		for (int i = 0; i < insns.length; i++) {
			if (insns[i] == target) return i;
		}
		return -1;
	}

	@Override
	public String name() {
		return "forbric-mods-button";
	}

	@Override
	public AnchorSet anchors() {
		// Scans every class for its markers rather than naming targets, because the button's construction site
		// has already moved once between carrier versions -- from TitleScreen to a widget class of NeoForge's
		// own. Which classes carry it is therefore not something to write down.
		return AnchorSet.scanned("finds its call sites by constant-pool marker, because they have already moved "
				+ "between carrier versions once");
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
			// Before the redirect: a dropped duplicate's label and construction are then not counted as landed.
			int dropped = keepOneModsButtonPerMethod(node);
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
					} else if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
							&& FACTORY.equals(call.name) && REPLACED.contains(call.owner)
							&& ("(" + SCREEN + ")L" + call.owner + ";").equals(call.desc)) {
						call.owner = KERNEL_SCREEN;
						call.desc = "(" + SCREEN + ")L" + KERNEL_SCREEN + ";";
						redirected++;
					}
				}
			}
			int renamed = renameTheButton(node);
			renamed += reskinTheButton(node);
			if (redirected == 0 && renamed == 0 && dropped == 0) return classBytes;
			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			if (redirected == 0 && renamed == 0) return writer.toByteArray();
			// The two halves are reported separately because they are separate claims, and conflating them is
			// how this line came to say something false. "Opens the unified list" is true only when a
			// construction site was re-pointed; a class that carries the label but builds its screen elsewhere --
			// which is exactly what TitleScreen did once, while the real construction had moved to NeoForge's own
			// ModsButton widget -- got the same sentence with a zero in it. A relabelled button that still opens
			// one family's list is worse than an unchanged one: it tells the player something untrue.
			if (redirected > 0 && renamed > 0) {
				ForbricLog.info("[Forbric/ModsButton] %s's mods button now opens the unified list and says so (%d "
						+ "construction site(s) re-pointed, %d label(s)+icon(s) changed) — each family's own screen "
						+ "lists only its own family, which on this instance is never the whole answer", internal,
						redirected, renamed);
			} else if (redirected > 0) {
				ForbricLog.info("[Forbric/ModsButton] %s's mods button now opens the unified list (%d construction "
						+ "site(s) re-pointed); its label is set elsewhere", internal, redirected);
			} else {
				ForbricLog.warn("[Forbric/ModsButton] relabelled %s's mods button (%d label(s)+icon(s)) but found "
						+ "NO construction site to re-point — if the button is built in this class at all, it "
						+ "still opens one family's list under a label that now claims otherwise", internal,
						renamed);
			}
			return writer.toByteArray();
		} catch (RuntimeException e) {
			ForbricLog.warn("[Forbric/ModsButton] could not re-point " + internal + "'s mods button — it will open "
					+ "one family's list", e);
			return classBytes;
		}
	}
}
