/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import net.forbric.kernel.mixin.MixinFit;
import net.forbric.kernel.util.Caught;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Puts NeoForge's creative pager behind fabric-creative-tab-api-v1's {@code FabricCreativeModeInventoryScreen}.
 *
 * <p>fabric-api's class tweaker injects that interface into {@code CreativeModeInventoryScreen} on every client that
 * has fabric-api, and the one mixin that implements it — fabric-creative-tab-api-v1's
 * {@code CreativeModeInventoryScreenMixin} — is pinned off in {@code MergedBaseMixinCompat}: its pager fought
 * NeoForge's and hid mod tabs. So the screen implemented the interface with nothing behind it, and every call landed
 * on the interface default, {@code throw new AssertionError("Implemented by mixin")}. Not a ClassCastException: the
 * cast succeeds. owo-lib paid for it: its {@code MixinCreativeModeInventoryScreenMixin} calls {@code getCurrentPage()}
 * at the tail of {@code selectTab}, which {@code init} calls, so opening the creative inventory killed the client; and
 * its other injector, per-page tab memory at the head of Fabric's private {@code updateSelection()}, had no method to
 * attach to.
 *
 * <p>Before Mixin, the merged screen gets, when the interface is installed and Fabric's mixin is pinned:
 * <ul>
 *   <li>every interface method whose default only throws ({@link MixinFit#implementerSupplies} of the installed
 *       interface), delegating to {@code KernelCreativePager}, which answers from NeoForge's {@code pages}/
 *       {@code currentPage} — NeoForge's pager stays the only one drawn. One the bridge has no body for (a newer
 *       fabric-api) is named in a warning, never passed over in silence;</li>
 *   <li>the guest pager's selection hook, under the name the pager itself gives it — the private argument-less void
 *       method its {@code switchToPage(int)} calls ({@code FabricCreativePagerMixinAdapter.selectionHooks}; fabric-api
 *       calls it {@code updateSelection}) — so a hook another mod puts there (owo-lib's) binds with the pager's meaning.
 *       The name is read from the pager seen while configs were read, never written here;</li>
 *   <li>after each of NeoForge's two page buttons sets the page, a call that runs the selection hook for its
 *       hooks while its own body stands aside — NeoForge's buttons behave exactly as before unless a mod hooks it.
 *       A carrier whose buttons are shaped otherwise keeps the rest, and the warning says the turns go unannounced;</li>
 *   <li>{@code KernelCreativePagerScreen}, one-instruction trampolines to the private state the pager needs.</li>
 * </ul>
 *
 * <p>The added methods go after the existing ones: Mixin binds a selector without a descriptor to the FIRST method of
 * that name, and a NeoForge mod aiming at {@code getCurrentPage} means NeoForge's page-returning one. Applied only to
 * the merged shape (NeoForge's fields, setter and {@code selectTab}), never twice. {@code -Dforbric.creativePagerBridge=off}
 * leaves the screen as merged; the mixin adapter then leaves out any guest mixin that relies on the interface
 * ({@code MergedBaseMixinCompat.PINNED_CONTRACTS}) instead of letting it throw. With the pin lifted
 * ({@code -Dforbric.keepMixins}, {@code -Dforbric.mergedBaseCompat=off}) the bridge stands aside too: Fabric's mixin
 * implements the interface itself, and on a screen that already had these methods it would overwrite them (or, with
 * overwrite annotations required, fail to apply) and leave owo hooked on a {@code updateSelection} nothing calls.
 */
public final class CreativePagerBridgeInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.creativePagerBridge";
	static final String SCREEN_BINARY = "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen";
	static final String SCREEN = "net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen";
	/** The Fabric interface; also what decides whether fabric-creative-tab-api-v1 is installed at all. */
	public static final String API = "net/fabricmc/fabric/api/client/creativetab/v1/FabricCreativeModeInventoryScreen";
	static final String PAGER = "net/forbric/kernel/runtime/KernelCreativePager";
	static final String PAGER_SCREEN = "net/forbric/kernel/runtime/KernelCreativePagerScreen";
	static final String PAGE = "Lnet/neoforged/neoforge/client/gui/CreativeTabsScreenPage;";
	static final String TAB = "Lnet/minecraft/world/item/CreativeModeTab;";
	static final String LIST = "Ljava/util/List;";
	static final String BUTTON_HANDLER = "(Lnet/minecraft/client/gui/components/Button;)V";
	static final String SCREEN_ARG = "L" + PAGER_SCREEN + ";";

	/**
	 * The interface methods the bridge can back, as {@code name, descriptor, pager method or null}: null reads the
	 * static {@code selectedTab} directly. A row gets its body only while the installed interface leaves that method to
	 * its implementer. {@code switchToNextPage}/{@code switchToPreviousPage} are absent on purpose — their defaults
	 * already go through {@code getCurrentPage} and {@code switchToPage}.
	 */
	static final String[][] API_METHODS = {
			{"getCurrentPage", "()I", "currentPage"},
			{"getPageCount", "()I", "pageCount"},
			{"getTabsOnPage", "(I)" + LIST, "tabsOnPage"},
			{"getPage", "(" + TAB + ")I", "pageOf"},
			{"switchToPage", "(I)Z", "switchToPage"},
			{"hasAdditionalPages", "()Z", "hasAdditionalPages"},
			{"getSelectedTab", "()" + TAB, null},
			{"setSelectedTab", "(" + TAB + ")Z", "setSelectedTab"}};

	/**
	 * The interface members this bridge can give the screen, as {@code name + descriptor}. A guest's own body for one of
	 * them, or for its pager's selection hook, is a second pager the bridge stands in for.
	 */
	public static Set<String> interfaceMembers() {
		Set<String> out = new LinkedHashSet<>();
		for (String[] api : API_METHODS) out.add(api[0] + api[1]);
		return out;
	}

	private final Function<String, byte[]> gameClass;
	private final BooleanSupplier pinInForce;
	private final java.util.function.Supplier<Set<String>> hooks;
	private final BooleanSupplier guestControls;

	/**
	 * @param gameClass  the bytes of a game resource by internal name, or null — the interface is a mod's, not the base's
	 * @param pinInForce whether Fabric's own {@code CreativeModeInventoryScreenMixin} is left out on this boot
	 */
	public CreativePagerBridgeInjector(Function<String, byte[]> gameClass, BooleanSupplier pinInForce) {
		this(gameClass, pinInForce, net.forbric.kernel.mixin.FabricCreativePagerMixinAdapter::notedSelectionHooks);
	}

	/** @param hooks the selection hooks of the guest pagers seen on this boot, as {@code name + descriptor} */
	public CreativePagerBridgeInjector(Function<String, byte[]> gameClass, BooleanSupplier pinInForce,
			java.util.function.Supplier<Set<String>> hooks) {
		this(gameClass, pinInForce, hooks, net.forbric.kernel.mixin.FabricCreativePagerMixinAdapter::guestControlsDrawn);
	}

	/**
	 * @param guestControls whether the guest pager's own page controls are drawn on this boot
	 *                      ({@code FabricCreativePagerMixinAdapter.guestControlsDrawn}): then the carrier's stand aside
	 */
	public CreativePagerBridgeInjector(Function<String, byte[]> gameClass, BooleanSupplier pinInForce,
			java.util.function.Supplier<Set<String>> hooks, BooleanSupplier guestControls) {
		this.gameClass = gameClass;
		this.pinInForce = pinInForce;
		this.hooks = hooks;
		this.guestControls = guestControls;
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-creative-pager-bridge"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("FabricCreativeModeInventoryScreen left unimplemented with -D" + PROPERTY + "=off");
		if (!pinInForce.getAsBoolean()) {
			return AnchorSet.scanned("Fabric's CreativeModeInventoryScreenMixin is not pinned on this boot and implements the interface itself");
		}
		if (gameClass.apply(API) == null) return AnchorSet.scanned("fabric-creative-tab-api-v1 is not installed, so there is no interface to back");
		return AnchorSet.of(new AnchorSet.Anchor(SCREEN_BINARY, AnchorSet.Severity.REQUIRED,
				"every FabricCreativeModeInventoryScreen call throws AssertionError — owo-lib crashes the client when the "
						+ "creative inventory opens"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (bytes == null || bytes.length == 0 || !SCREEN_BINARY.equals(className) || !enabled() || !pinInForce.getAsBoolean()) {
			return bytes;
		}
		byte[] api = gameClass.apply(API);
		if (api == null) return bytes;
		Set<String> needed;
		try {
			needed = MixinFit.implementerSupplies(MixinFit.parse(api));
		} catch (RuntimeException unreadable) { // the chain does not catch: a throw here would fail the screen's load
			Caught.warn(unreadable, "[Forbric/CreativePager] FabricCreativeModeInventoryScreen is unreadable — the creative"
					+ " screen is left as merged");
			return bytes;
		}
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		Repair done = repair(node, needed, hooks.get(), guestControls.getAsBoolean());
		if (done == null) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/CreativePager] CreativeModeInventoryScreen answers FabricCreativeModeInventoryScreen from "
				+ "NeoForge's pager — the interface fabric-api injects had no implementation behind it, and every call threw "
				+ "AssertionError");
		if (!done.uncovered().isEmpty()) {
			ForbricLog.warn("[Forbric/CreativePager] FabricCreativeModeInventoryScreen also leaves %s to its implementer, and "
					+ "the bridge has no body for it — a call to it still throws AssertionError", done.uncovered());
		}
		if (done.carrierControlsStoodAside()) {
			ForbricLog.info("[Forbric/CreativePager] NeoForge's page controls (its two page buttons and the page label) stand "
					+ "aside: the guest pager's own controls are drawn, the only pager family this instance's mods use — its "
					+ "page is still NeoForge's, behind the Fabric interface");
		} else if (guestControls.getAsBoolean()) {
			ForbricLog.error("[Forbric/CreativePager] the guest pager's page controls are drawn, but NeoForge's on this screen "
					+ "are not the shape they were when that was decided — both sets of page controls are drawn");
		}
		if (done.hook() == null) {
			ForbricLog.info("[Forbric/CreativePager] no guest pager names a selection hook (%s) — the screen gets none, and the "
					+ "Fabric API's page turns update the selection directly", hooks.get().isEmpty() ? "none seen" : "several: " + hooks.get());
		} else if (!done.turnsAnnounced()) {
			ForbricLog.warn("[Forbric/CreativePager] NeoForge's page buttons on CreativeModeInventoryScreen are not the two the "
					+ "bridge knows — a mod hooked on the pager's %s does not see their page turns, only the Fabric API's",
					done.hook());
		}
		return writer.toByteArray();
	}

	/**
	 * What {@link #repair} did.
	 *
	 * @param uncovered      interface methods left to the implementer that the bridge has no body for
	 * @param turnsAnnounced whether NeoForge's two page buttons now run the selection hook
	 * @param hook           the selection hook the screen got, as {@code name + descriptor}; null when no pager names one
	 */
	record Repair(List<String> uncovered, boolean turnsAnnounced, String hook, boolean carrierControlsStoodAside) {
	}

	/** {@link #repair(ClassNode, Set, Set, boolean)} with the carrier's page controls kept. */
	static Repair repair(ClassNode screen, Set<String> needed, Set<String> hooks) {
		return repair(screen, needed, hooks, false);
	}

	/**
	 * Backs the interface on {@code screen}, or {@code null} when it is not the merged shape or already backed.
	 *
	 * @param needed the interface's {@link MixinFit#implementerSupplies}, as {@code name + descriptor}
	 * @param hooks  the selection hooks the guest pagers name; exactly one is given to the screen, none when it is ambiguous
	 * @param guestControls whether the guest pager's controls are drawn: then the carrier's ({@link #carrierControls}) are
	 *                      kept from running
	 */
	static Repair repair(ClassNode screen, Set<String> needed, Set<String> hooks, boolean guestControls) {
		if (!SCREEN.equals(screen.name)) return null;
		if (!field(screen, "pages", LIST, false) || !field(screen, "currentPage", PAGE, false)
				|| !field(screen, "selectedTab", TAB, true)) return null;
		if (!instanceMethod(screen, "setCurrentPage", "(" + PAGE + ")V") || !instanceMethod(screen, "selectTab", "(" + TAB + ")V")) return null;
		// Once only, and never over a body something else already gave the screen.
		if (method(screen, "forbric$updateSelection", "()V") != null) return null;
		String hook = hooks.size() == 1 ? hooks.iterator().next() : null;
		String hookName = hook == null ? null : hook.substring(0, hook.indexOf('('));
		if (hook != null && (!hook.endsWith("()V") || method(screen, hookName, "()V") != null)) return null;
		List<String[]> backed = new ArrayList<>();
		Set<String> uncovered = new LinkedHashSet<>(needed);
		for (String[] api : API_METHODS) {
			if (!uncovered.remove(api[0] + api[1])) continue; // the interface implements it itself, or has no such method
			if (method(screen, api[0], api[1]) != null) return null;
			backed.add(api);
		}

		if (!screen.interfaces.contains(API)) screen.interfaces.add(API);
		screen.interfaces.add(PAGER_SCREEN);

		for (String[] api : backed) {
			MethodNode body = new MethodNode(Opcodes.ACC_PUBLIC, api[0], api[1],
					api[0].equals("getTabsOnPage") ? "(I)Ljava/util/List<Lnet/minecraft/world/item/CreativeModeTab;>;" : null, null);
			if (api[2] == null) {
				body.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, SCREEN, "selectedTab", TAB));
			} else {
				body.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
				String arguments = api[1].substring(1, api[1].indexOf(')'));
				if (arguments.equals("I")) body.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
				else if (!arguments.isEmpty()) body.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
				body.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PAGER, api[2],
						"(" + SCREEN_ARG + arguments + ")" + api[1].substring(api[1].indexOf(')') + 1), false));
			}
			body.instructions.add(new InsnNode(returnOpcode(api[1])));
			screen.methods.add(body);
		}

		if (hookName != null) {
			MethodNode update = new MethodNode(Opcodes.ACC_PRIVATE, hookName, "()V", null, null);
			update.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			update.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PAGER, "updateSelection", "(" + SCREEN_ARG + ")V", false));
			update.instructions.add(new InsnNode(Opcodes.RETURN));
			screen.methods.add(update);
		}

		trampoline(screen, "forbric$pages", "()" + LIST, Opcodes.GETFIELD, "pages", LIST);
		trampoline(screen, "forbric$currentPage", "()" + PAGE, Opcodes.GETFIELD, "currentPage", PAGE);
		trampoline(screen, "forbric$selectedTab", "()" + TAB, Opcodes.GETSTATIC, "selectedTab", TAB);
		call(screen, "forbric$setCurrentPage", "(" + PAGE + ")V", "setCurrentPage");
		call(screen, "forbric$selectTab", "(" + TAB + ")V", "selectTab");
		if (hookName != null) {
			call(screen, "forbric$updateSelection", "()V", hookName);
		} else {
			// No hook to run: the trampoline is the pager's selection rule itself.
			MethodNode direct = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, "forbric$updateSelection", "()V", null, null);
			direct.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			direct.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PAGER, "updateSelection", "(" + SCREEN_ARG + ")V", false));
			direct.instructions.add(new InsnNode(Opcodes.RETURN));
			screen.methods.add(direct);
		}

		// The buttons are the one part a reshaped carrier can take away; the interface bodies do not depend on them.
		List<MethodInsnNode> turns = pageButtonTurns(screen);
		boolean announced = turns.size() == 2;
		if (announced) {
			for (MethodInsnNode turn : turns) {
				MethodNode owner = ownerOf(screen, turn);
				InsnList announce = new InsnList();
				announce.add(new VarInsnNode(Opcodes.ALOAD, 0));
				announce.add(new MethodInsnNode(Opcodes.INVOKESTATIC, PAGER, "pageTurned", "(" + SCREEN_ARG + ")V", false));
				owner.instructions.insert(turn, announce);
			}
		}
		boolean stoodAside = false;
		if (guestControls) {
			List<JumpInsnNode> guards = carrierControls(screen);
			if (guards != null) {
				for (JumpInsnNode guard : guards) standAside(screen, guard);
				stoodAside = true;
			}
		}
		return new Repair(List.copyOf(uncovered), announced, hook, stoodAside);
	}

	/**
	 * NeoForge's page controls, found by what they do: in {@code init}, the block that runs only when the carrier has more
	 * than one page and adds the two page buttons ({@link #pageButtonTurns}' lambdas as their press handlers) and nothing
	 * else; in {@code extractRenderState}, the block under the same condition that draws one text built from the current
	 * page's index and the page count, and nothing else. Returns each block's guard — the jump that skips it when there
	 * are not more pages than one — or null when either block is not exactly that shape.
	 */
	public static List<JumpInsnNode> carrierControls(ClassNode screen) {
		if (!SCREEN.equals(screen.name)) return null;
		Set<String> buttons = new LinkedHashSet<>();
		for (MethodInsnNode turn : pageButtonTurns(screen)) {
			MethodNode owner = ownerOf(screen, turn);
			buttons.add(owner.name + owner.desc);
		}
		if (buttons.size() != 2) return null;
		MethodNode init = method(screen, "init", "()V");
		MethodNode extract = method(screen, "extractRenderState", "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V");
		if (init == null || extract == null) return null;
		JumpInsnNode added = null, drawn = null;
		for (JumpInsnNode guard : pageCountGuards(init)) {
			if (addsOnlyPageButtons(guard, buttons)) {
				if (added != null) return null;
				added = guard;
			}
		}
		for (JumpInsnNode guard : pageCountGuards(extract)) {
			if (drawsOnlyPageLabel(guard)) {
				if (drawn != null) return null;
				drawn = guard;
			}
		}
		return added == null || drawn == null ? null : List.of(added, drawn);
	}

	/** {@code if (this.pages.size() <= 1) skip} / {@code != 1} as compiled: the jump taken when there is no second page. */
	private static List<JumpInsnNode> pageCountGuards(MethodNode method) {
		List<JumpInsnNode> guards = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (!(insn instanceof JumpInsnNode jump) || (jump.getOpcode() != Opcodes.IF_ICMPLE && jump.getOpcode() != Opcodes.IF_ICMPEQ)) continue;
			AbstractInsnNode one = previous(jump), size = previous(one), pages = previous(size), self = previous(pages);
			if (one == null || one.getOpcode() != Opcodes.ICONST_1) continue;
			if (!(size instanceof MethodInsnNode call) || !call.name.equals("size") || !call.desc.equals("()I")) continue;
			if (!(pages instanceof FieldInsnNode field) || field.getOpcode() != Opcodes.GETFIELD || !field.owner.equals(SCREEN)
					|| !field.name.equals("pages") || !field.desc.equals(LIST)) continue;
			if (!(self instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD || load.var != 0) continue;
			guards.add(jump);
		}
		return guards;
	}

	private static AbstractInsnNode previous(AbstractInsnNode insn) {
		if (insn == null) return null;
		AbstractInsnNode at = insn.getPrevious();
		while (at != null && at.getOpcode() < 0) at = at.getPrevious();
		return at;
	}

	/** The calls of one guarded block, or null when the block's code leaves it other than by falling through to its end. */
	private static List<AbstractInsnNode> block(JumpInsnNode guard) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode at = guard.getNext(); at != guard.label; at = at.getNext()) {
			if (at == null || at instanceof JumpInsnNode || at.getOpcode() == Opcodes.ATHROW
					|| (at.getOpcode() >= Opcodes.IRETURN && at.getOpcode() <= Opcodes.RETURN)) return null;
			if (at instanceof MethodInsnNode || at instanceof InvokeDynamicInsnNode || at instanceof FieldInsnNode f
					&& (f.getOpcode() == Opcodes.PUTFIELD || f.getOpcode() == Opcodes.PUTSTATIC)) out.add(at);
		}
		return out;
	}

	private static boolean addsOnlyPageButtons(JumpInsnNode guard, Set<String> buttons) {
		List<AbstractInsnNode> calls = block(guard);
		if (calls == null) return false;
		int handlers = 0, added = 0;
		for (AbstractInsnNode call : calls) {
			if (call instanceof InvokeDynamicInsnNode indy) {
				Handle handle = indy.bsmArgs.length > 1 && indy.bsmArgs[1] instanceof Handle h ? h : null;
				if (handle == null || !handle.getOwner().equals(SCREEN) || !buttons.contains(handle.getName() + handle.getDesc())) return false;
				handlers++;
			} else if (call instanceof MethodInsnNode m) {
				if (m.name.equals("addRenderableWidget")) added++;
				else if (!(m.owner.equals("net/minecraft/network/chat/Component") && m.name.equals("literal"))
						&& !m.owner.startsWith("net/minecraft/client/gui/components/Button")) return false;
			} else {
				return false;
			}
		}
		return handlers == 2 && added == 2;
	}

	private static boolean drawsOnlyPageLabel(JumpInsnNode guard) {
		List<AbstractInsnNode> calls = block(guard);
		if (calls == null) return false;
		int draws = 0;
		boolean index = false;
		for (AbstractInsnNode call : calls) {
			if (!(call instanceof MethodInsnNode m)) return false;
			switch (m.owner) {
				case "net/minecraft/client/gui/GuiGraphicsExtractor" -> draws++;
				case "java/util/List" -> {
					if (m.name.equals("indexOf")) index = true;
					else if (!m.name.equals("size")) return false;
				}
				case "java/lang/String", "java/lang/Integer", "net/minecraft/network/chat/Component", "net/minecraft/client/gui/Font" -> {
				}
				default -> {
					return false;
				}
			}
		}
		return draws == 1 && index;
	}

	/**
	 * Keeps a guarded block from running without unbalancing anything: the guard's two compared ints are dropped and its
	 * jump becomes one that is always taken. The block stays in place (so no frame changes) and simply never runs.
	 */
	private static void standAside(ClassNode screen, JumpInsnNode guard) {
		MethodNode owner = ownerOf(screen, guard);
		InsnList always = new InsnList();
		always.add(new InsnNode(Opcodes.POP2));
		always.add(new InsnNode(Opcodes.ICONST_0));
		always.add(new JumpInsnNode(Opcodes.IFEQ, guard.label));
		owner.instructions.insert(guard, always);
		owner.instructions.remove(guard);
	}

	/**
	 * The {@code setCurrentPage} call of each of NeoForge's page buttons: an instance {@code (Button)V} body — the
	 * "&lt;" and "&gt;" lambdas {@code init} hands to {@code Button.builder} — that sets the page exactly once.
	 */
	static List<MethodInsnNode> pageButtonTurns(ClassNode screen) {
		List<MethodInsnNode> turns = new ArrayList<>();
		for (MethodNode method : screen.methods) {
			if ((method.access & Opcodes.ACC_STATIC) != 0 || !method.desc.equals(BUTTON_HANDLER)) continue;
			MethodInsnNode found = null;
			int count = 0;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(SCREEN) && call.name.equals("setCurrentPage")
						&& call.desc.equals("(" + PAGE + ")V")) {
					found = call;
					count++;
				}
			}
			if (count == 1) turns.add(found);
		}
		return turns;
	}

	private static MethodNode ownerOf(ClassNode screen, AbstractInsnNode insn) {
		for (MethodNode method : screen.methods) if (method.instructions.contains(insn)) return method;
		throw new IllegalStateException("instruction outside the screen");
	}

	private static void trampoline(ClassNode screen, String name, String desc, int opcode, String field, String fieldDesc) {
		MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, name, desc, null, null);
		if (opcode == Opcodes.GETFIELD) m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		m.instructions.add(new FieldInsnNode(opcode, SCREEN, field, fieldDesc));
		m.instructions.add(new InsnNode(Opcodes.ARETURN));
		screen.methods.add(m);
	}

	private static void call(ClassNode screen, String name, String desc, String target) {
		MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, name, desc, null, null);
		m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		if (!desc.startsWith("()")) m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
		m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SCREEN, target, desc, false));
		m.instructions.add(new InsnNode(Opcodes.RETURN));
		screen.methods.add(m);
	}

	private static int returnOpcode(String desc) {
		char kind = desc.charAt(desc.indexOf(')') + 1);
		return kind == 'I' || kind == 'Z' ? Opcodes.IRETURN : Opcodes.ARETURN;
	}

	private static boolean field(ClassNode node, String name, String desc, boolean isStatic) {
		for (FieldNode f : node.fields) {
			if (f.name.equals(name) && f.desc.equals(desc)) return ((f.access & Opcodes.ACC_STATIC) != 0) == isStatic;
		}
		return false;
	}

	private static boolean instanceMethod(ClassNode node, String name, String desc) {
		MethodNode m = method(node, name, desc);
		return m != null && (m.access & Opcodes.ACC_STATIC) == 0;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode m : node.methods) {
			if (m.name.equals(name) && m.desc.equals(desc)) return m;
		}
		return null;
	}
}
