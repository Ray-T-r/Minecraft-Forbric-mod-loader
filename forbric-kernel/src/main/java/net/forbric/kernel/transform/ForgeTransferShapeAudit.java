package net.forbric.kernel.transform;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * FINAL, post-Mixin certificates for the reviewed Forge implementations and their transfer-critical helpers.
 * ItemStack is checked by reachable critical methods, NOT whole-class identity: unrelated tooltip/use mixins may
 * remain. The selected method set follows same-class calls and lambda handles from the actual 26.2 bytecode.
 * Fingerprints include executable instructions, member descriptors/access and referenced fields; debug/frame
 * metadata is excluded. Unknown changes remove the marker and deny writes until explicitly reviewed.
 *
 * <p>Call certify after every other class transformation, immediately before defineClass. Native registry lookups,
 * immutable component-value contracts and JDK/fastutil collections are read-only contract boundaries. The lazy
 * ItemStack capability constructor is pinned with its provider classes: it parks the supplier rather than firing
 * attachment callbacks. Custom Item overrides of the capacity/component helpers are separately declined at use.
 */
public final class ForgeTransferShapeAudit {
	private ForgeTransferShapeAudit() { }
	public static final String MARKER = "forbric$auditedTransferSnapshot";
	public static final Set<String> NON_TRANSFER_FABRIC_INTERFACES = Set.of(
			"net/fabricmc/fabric/api/item/v1/FabricItemStack", "net/fabricmc/fabric/api/item/v1/FabricItem",
			"net/fabricmc/fabric/mixin/transfer/ItemStackAccessor", "net/fabricmc/fabric/impl/item/ItemExtensions",
			"net/fabricmc/fabric/impl/transfer/item/ItemVariantCache");
	private static final Map<String, Set<String>> ROOTS = Map.of(
			"net.minecraft.world.item.ItemStack", Set.of("<init>", "isEmpty", "getCount", "getMaxStackSize", "grow", "shrink", "copy", "copyWithCount", "setCount", "getComponentsPatch", "isSameItemSameComponents", "getPopTime", "setPopTime", "typeHolder", "getItem", "getComponents", "getPrototype", "getOrDefault", "get"),
			"net.minecraft.world.item.Item", Set.of("builtInRegistryHolder", "components", "getDefaultMaxStackSize", "computeDefaultResource"),
			"net.minecraftforge.items.ItemHandlerHelper", Set.of("canItemStacksStack", "copyStackWithSize"),
			"net.neoforged.neoforge.common.extensions.IItemExtension", Set.of("getMaxStackSize"));
	private static final Map<String, String> AUDITED = Map.ofEntries(
			Map.entry("net.minecraftforge.items.ItemStackHandler", "be15e7bbc1b280544259474b1cc4eddd66fc6a17f0451486259e1ce4e56bef93"),
			Map.entry("net.minecraftforge.items.ItemHandlerHelper", "ad43a680f428c4302b0163475ff2f7b92c3ddd752b8d572f70883fc623ad2d0d"),
			Map.entry("net.minecraftforge.fluids.capability.templates.FluidTank", "02e1047767b92e0a462b4389f1e68fc7c76e942384d221181234a0437ad35604"),
			Map.entry("net.minecraftforge.fluids.FluidStack", "87cd29310cdb4e2ec664585e20361e48d66945847eda6f7c8e6f86d4f3c57262"),
			Map.entry("net.minecraftforge.common.capabilities.CapabilityProvider", "caea2630c926db9524090cef952d2e31925f68297e23022ca9c62087e4b65ddb"),
			Map.entry("net.minecraftforge.common.capabilities.CapabilityProvider$ItemStacks", "a8819ec238eacbeda5c62d3f0674f2e9dc5389d651348b743c32039ae77f042d"),
			Map.entry("net.minecraft.world.item.ItemStack", "430a01cec3588807d3e0726e60dddfd21c0c6076157d2f9306e6a298697ed718"),
			Map.entry("net.minecraft.world.item.Item", "8c056287f55105172282acf6b74bfd94055060e8acfb0bf2fa4788bb4bd9fb1e"),
			Map.entry("net.minecraft.core.NonNullList", "366927e03949e7622b9484fb23c9d20dc2db0d75d1886b9ebe067c209d91e326"),
			Map.entry("net.minecraft.core.component.PatchedDataComponentMap", "ec5db24df9dc4efa43a4aa2eca90cb80fc3e22c3efceaa7958de8aca2855d8a4"),
			Map.entry("net.minecraft.core.component.DataComponentPatch", "f928a3dd368d608ab5a2eb36299cee1d2395010dffa62b7acacf4392140905fd"),
			Map.entry("net.minecraft.core.component.DataComponentHolder", "0fd6a2b373f4eda5cc83153ab696a5e7a7427c932166545d143798d3ded301b2"),
			Map.entry("net.minecraft.core.component.DataComponentGetter", "e86a858ee320a0b6bfc583fdb9dfd747d58d5176109ddb24725092da528a2d6c"),
			Map.entry("net.minecraft.nbt.CompoundTag", "95517a0b2c8dd3ead429407f35f480f16f389c1a9ee07fc18247138782086e83"),
			Map.entry("net.neoforged.neoforge.transfer.item.ItemResource", "5e684403e1c0bccf9771146f56c7b434f6a025e6424a09989bd68a7d4b6247be"),
			Map.entry("net.neoforged.neoforge.transfer.fluid.FluidResource", "1ecc381a45ce0c90ae66faa02037b50c14f50f17c76fb1e2a4a334eadc3a3466"),
			Map.entry("net.neoforged.neoforge.common.MutableDataComponentHolder", "beaa9c59bd4505bad17e5da5e51bcdb8571cef110c0825d8178bf9d42fb14d52"),
			Map.entry("net.neoforged.neoforge.transfer.resource.DataComponentHolderResource", "a86fd4ab64d88829d617c1a77658e0cc82c31582356f5032d7e4c97c567aeb6e"),
			Map.entry("net.neoforged.neoforge.common.extensions.IItemExtension", "7040f6660c671ecf6a8dadf0911a83dfef146128c20bf61a5cbb1c21efc00654"));
	public static final List<String> ITEM_HELPERS = List.of("net.minecraftforge.items.ItemHandlerHelper", "net.minecraft.world.item.ItemStack", "net.minecraft.world.item.Item", "net.minecraft.core.NonNullList", "net.minecraft.core.component.PatchedDataComponentMap", "net.minecraft.core.component.DataComponentPatch", "net.minecraft.core.component.DataComponentHolder", "net.minecraft.core.component.DataComponentGetter", "net.minecraftforge.common.capabilities.CapabilityProvider", "net.minecraftforge.common.capabilities.CapabilityProvider$ItemStacks", "net.neoforged.neoforge.transfer.item.ItemResource", "net.neoforged.neoforge.transfer.resource.DataComponentHolderResource", "net.neoforged.neoforge.common.MutableDataComponentHolder", "net.neoforged.neoforge.common.extensions.IItemExtension");
	public static final List<String> FLUID_HELPERS = List.of("net.minecraftforge.fluids.FluidStack", "net.minecraft.nbt.CompoundTag", "net.minecraft.core.component.DataComponentPatch", "net.minecraft.core.component.DataComponentHolder", "net.minecraft.core.component.DataComponentGetter", "net.neoforged.neoforge.transfer.fluid.FluidResource", "net.neoforged.neoforge.transfer.resource.DataComponentHolderResource");
	private static final Map<String, String> DECLINED = new ConcurrentHashMap<>();
	public static String declined(String name) { return DECLINED.getOrDefault(name, "the final definition did not receive a transfer-shape certificate"); }

	public static byte[] certify(String name, byte[] bytes) {
		String expected = AUDITED.get(name); if (expected == null || bytes == null) return bytes;
		String actual = fingerprint(bytes); boolean approved = expected.equals(actual);
		dump(name, bytes, expected, actual);
		if (approved) DECLINED.remove(name); else DECLINED.put(name, "transfer-critical bytecode differs from the reviewed 26.2 shape (" + actual + ")");
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
		boolean hadMarker = node.methods.removeIf(method -> method.name.equals(MARKER));
		if (!approved && !hadMarker) return bytes;
		if (approved) {
			MethodNode marker = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, MARKER, "()V", null, null);
			marker.visitCode(); marker.visitInsn(Opcodes.RETURN); marker.visitMaxs(0, 0); marker.visitEnd(); node.methods.add(marker);
		}
		ClassWriter writer = new ClassWriter(0); node.accept(writer); return writer.toByteArray();
	}
	/** Explicit local verification artifact only; disabled by default and never included in a kernel jar. */
	private static void dump(String name, byte[] bytes, String expected, String actual) {
		String directory = System.getProperty("forbric.transferShapeDump");
		if (directory == null || directory.isBlank()) return;
		try {
			Path output = Path.of(directory).resolve(name.replace('.', '/') + ".class");
			Files.createDirectories(output.getParent()); Files.write(output, bytes);
			Files.writeString(output.resolveSibling(output.getFileName() + ".audit.txt"),
					"reviewed=" + expected + "\nobserved=" + actual + "\n");
		} catch (IOException | RuntimeException failure) {
			net.forbric.kernel.util.ForbricLog.warn("[Forbric/TransferAudit] could not dump %s: %s", name, failure.toString());
		}
	}
	static String fingerprint(byte[] bytes) {
		ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		node.methods.removeIf(method -> method.name.equals(MARKER));
		canonicalizeErasedClassSignature(node);
		normalizeKnownNonTransferRepair(node);
		Set<String> roots = ROOTS.get(node.name.replace('/', '.'));
		if (roots != null) {
			boolean cachedVariantRead = readsOnlyCachedVariant(node);
			Set<String> selected = selected(node, roots); node.methods.removeIf(method -> !selected.contains(method.name + method.desc));
			Set<String> fields = new HashSet<>();
			for (var method : node.methods) for (var instruction : method.instructions)
				if (instruction instanceof FieldInsnNode field && field.owner.equals(node.name)) fields.add(field.name + field.desc);
			node.fields.removeIf(field -> !fields.contains(field.name + field.desc));
			node.methods.sort(Comparator.comparing(method -> method.name + method.desc));
			node.fields.sort(Comparator.comparing(field -> field.name + field.desc));
			node.visibleAnnotations = null; node.invisibleAnnotations = null; node.innerClasses.clear();
			if (node.nestMembers != null) node.nestMembers.clear();
			// These fixture interfaces do not override the audited state operations. Check the cache getter
			// BEFORE pruning methods/fields, and keep its interface in the hash if its read-only body drifted.
			node.interfaces.removeIf(contract -> NON_TRANSFER_FABRIC_INTERFACES.contains(contract)
					&& (!contract.equals("net/fabricmc/fabric/impl/transfer/item/ItemVariantCache") || cachedVariantRead));
		}
		ClassWriter writer = new ClassWriter(0); node.accept(writer);
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(writer.toByteArray())); }
		catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
	}
	/**
	 * The fixture adds a cache interface to Item, not to ItemStack. Its sole method reads an already-initialized
	 * immutable variant. Neither Item construction (equipment/damage hooks) nor a cache getter is an override of
	 * components/stack limits. Require the exact field read, so adding this interface cannot conceal a callback,
	 * lazy mutation, or replacement of one of the critical operations behind an apparently harmless interface.
	 */
	private static boolean readsOnlyCachedVariant(ClassNode node) {
		if (!node.name.equals("net/minecraft/world/item/Item")) return false;
		String descriptor = "Lnet/fabricmc/fabric/api/transfer/v1/item/ItemVariant;";
		MethodNode getter = node.methods.stream().filter(method -> method.name.equals("fabric_getCachedItemVariant")
				&& method.desc.equals("()" + descriptor)).findFirst().orElse(null);
		if (getter == null || (getter.access & Opcodes.ACC_PUBLIC) == 0 || !getter.tryCatchBlocks.isEmpty()
				|| (getter.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED)) != 0) return false;
		if (node.fields.stream().noneMatch(field -> field.name.equals("cachedItemVariant") && field.desc.equals(descriptor)
				&& (field.access & Opcodes.ACC_STATIC) == 0)) return false;
		var code = java.util.Arrays.stream(getter.instructions.toArray()).filter(instruction -> instruction.getOpcode() >= 0).toList();
		return code.size() == 3 && code.get(0) instanceof VarInsnNode self && self.getOpcode() == Opcodes.ALOAD && self.var == 0
				&& code.get(1) instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD && field.owner.equals(node.name)
				&& field.name.equals("cachedItemVariant") && field.desc.equals(descriptor) && code.get(2).getOpcode() == Opcodes.ARETURN;
	}
	/**
	 * Mixin materializes a Signature even on a non-generic class: Lsuper;Liface;... . It duplicates the erased
	 * superclass/interface table, which we already inspect (and whose added known interfaces are checked again
	 * against their actual runtime Class). This does not erase type variables, generic arguments or a different
	 * hierarchy. In the real FAPI fixture this was the ONLY difference in ItemStack's complete reviewed method set.
	 */
	private static void canonicalizeErasedClassSignature(ClassNode node) {
		if (node.signature == null || node.superName == null) return;
		StringBuilder erased = new StringBuilder("L").append(node.superName).append(';');
		for (String contract : node.interfaces) erased.append('L').append(contract).append(';');
		if (node.signature.contentEquals(erased)) node.signature = null;
	}
	/**
	 * The kernel adds exactly this missing convenience factory. The reviewed CompoundTag has no builder method,
	 * so none of its unchanged copy/equality/mutation methods can call it. Strip only the exact four-instruction
	 * repair; a changed copy method, constructor, factory body, or any other added method remains in the digest.
	 */
	private static void normalizeKnownNonTransferRepair(ClassNode node) {
		if (!node.name.equals("net/minecraft/nbt/CompoundTag")) return;
		node.methods.removeIf(method -> {
			String builder = "net/minecraftforge/common/util/INBTBuilder$Builder";
			if (!method.name.equals("builder") || !method.desc.equals("()L" + builder + ";")
					|| method.access != (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC) || !method.tryCatchBlocks.isEmpty()) return false;
			var code = java.util.Arrays.stream(method.instructions.toArray()).filter(instruction -> instruction.getOpcode() >= 0).toList();
			return code.size() == 4 && code.get(0) instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && type.desc.equals(builder)
					&& code.get(1).getOpcode() == Opcodes.DUP
					&& code.get(2) instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESPECIAL && call.owner.equals(builder)
					&& call.name.equals("<init>") && call.desc.equals("()V") && !call.itf && code.get(3).getOpcode() == Opcodes.ARETURN;
		});
	}
	private static Set<String> selected(ClassNode node, Set<String> roots) {
		Set<String> selected = new HashSet<>();
		for (var method : node.methods) if (roots.contains(method.name)) selected.add(method.name + method.desc);
		boolean changed;
		do {
			changed = false;
			for (var method : node.methods) if (selected.contains(method.name + method.desc)) for (var instruction : method.instructions) {
				if (instruction instanceof MethodInsnNode call && call.owner.equals(node.name)) changed |= selected.add(call.name + call.desc);
				if (instruction instanceof InvokeDynamicInsnNode dynamic) for (Object argument : dynamic.bsmArgs)
					if (argument instanceof Handle handle && handle.getOwner().equals(node.name)) changed |= selected.add(handle.getName() + handle.getDesc());
			}
		} while (changed);
		return selected;
	}
}
