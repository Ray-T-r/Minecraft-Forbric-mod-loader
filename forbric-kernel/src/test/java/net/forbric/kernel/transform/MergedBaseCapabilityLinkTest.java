package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Every capability call the whole merged base makes on a vanilla owner, resolved JVMS-style up the (transformed)
 * class chain and its superinterfaces. Untransformed, the unresolved set is pinned exactly — a rebuilt base that
 * changes the answer fails here, not in a player's log; after the shim and the compat transformer it is empty.
 */
class MergedBaseCapabilityLinkTest {
	private static final Path MERGED = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"merged-base", "patched-mc-merged-26.2.jar").normalize();
	private static final Path FORGE = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"forge-runtime", "forge-runtime.jar").normalize();
	private static final Path NEO = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"neoforge-runtime", "neoforge-runtime.jar").normalize();
	private static final Set<String> NAMES = Set.of("getCapability", "invalidateCaps", "reviveCaps", "gatherCapabilities",
			"getCapabilities", "serializeCaps", "deserializeCaps", "initCapabilities", "initInternal", "serializeInternal",
			"deserializeInternal", "writeCapsToNBT", "readCapsFromNBT");
	private static final String FORGE_GET = "(Lnet/minecraftforge/common/capabilities/Capability;Lnet/minecraft/core/Direction;)Lnet/minecraftforge/common/util/LazyOptional;";

	/**
	 * Pinned from the staged base on 2026-09-20: every capability call on a root type that the merge left dangling
	 * — the four inside the roots themselves, and the super calls of the three Forge-patched overriders whose own
	 * super is a root (the other seven overriders reach a declaring intermediate class). Re-read a diff, never
	 * re-bless it.
	 */
	private static final Set<String> KNOWN_UNRESOLVED = new TreeSet<>(List.of(
			"net/minecraft/server/level/ServerLevel.getCapabilityDispatcher -> net/minecraft/server/level/ServerLevel.getCapabilities()Lnet/minecraftforge/common/capabilities/CapabilityDispatcher;",
			"net/minecraft/server/level/ServerLevel.initCapabilities -> net/minecraft/server/level/ServerLevel.gatherCapabilities()V",
			"net/minecraft/world/entity/LivingEntity.getCapability -> net/minecraft/world/entity/Entity.getCapability" + FORGE_GET,
			"net/minecraft/world/entity/LivingEntity.invalidateCaps -> net/minecraft/world/entity/Entity.invalidateCaps()V",
			"net/minecraft/world/entity/LivingEntity.reviveCaps -> net/minecraft/world/entity/Entity.reviveCaps()V",
			"net/minecraft/world/level/block/entity/BaseContainerBlockEntity.getCapability -> net/minecraft/world/level/block/entity/BlockEntity.getCapability" + FORGE_GET,
			"net/minecraft/world/level/block/entity/BaseContainerBlockEntity.invalidateCaps -> net/minecraft/world/level/block/entity/BlockEntity.invalidateCaps()V",
			"net/minecraft/world/level/block/entity/BaseContainerBlockEntity.reviveCaps -> net/minecraft/world/level/block/entity/BlockEntity.reviveCaps()V",
			"net/minecraft/world/level/block/entity/BlockEntity.lambda$loadAdditional$0 -> net/minecraft/world/level/block/entity/BlockEntity.deserializeCaps(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/nbt/CompoundTag;)V",
			"net/minecraft/world/level/block/entity/BlockEntity.onChunkUnloaded -> net/minecraft/world/level/block/entity/BlockEntity.invalidateCaps()V",
			"net/minecraft/world/level/block/entity/ChiseledBookShelfBlockEntity.getCapability -> net/minecraft/world/level/block/entity/BlockEntity.getCapability" + FORGE_GET,
			"net/minecraft/world/level/block/entity/ChiseledBookShelfBlockEntity.invalidateCaps -> net/minecraft/world/level/block/entity/BlockEntity.invalidateCaps()V",
			"net/minecraft/world/level/block/entity/ChiseledBookShelfBlockEntity.reviveCaps -> net/minecraft/world/level/block/entity/BlockEntity.reviveCaps()V",
			// Super calls into an intermediate that declares nothing of its own: they resolve up to the root.
			"net/minecraft/world/entity/vehicle/boat/ChestBoat.getCapability -> net/minecraft/world/entity/vehicle/boat/AbstractChestBoat.getCapability" + FORGE_GET,
			"net/minecraft/world/entity/vehicle/boat/ChestBoat.invalidateCaps -> net/minecraft/world/entity/vehicle/boat/AbstractChestBoat.invalidateCaps()V",
			"net/minecraft/world/entity/vehicle/boat/ChestBoat.reviveCaps -> net/minecraft/world/entity/vehicle/boat/AbstractChestBoat.reviveCaps()V",
			"net/minecraft/world/entity/vehicle/minecart/AbstractMinecartContainer.getCapability -> net/minecraft/world/entity/vehicle/minecart/AbstractMinecart.getCapability" + FORGE_GET,
			"net/minecraft/world/entity/vehicle/minecart/AbstractMinecartContainer.invalidateCaps -> net/minecraft/world/entity/vehicle/minecart/AbstractMinecart.invalidateCaps()V",
			"net/minecraft/world/entity/vehicle/minecart/AbstractMinecartContainer.reviveCaps -> net/minecraft/world/entity/vehicle/minecart/AbstractMinecart.reviveCaps()V"));

	@Test
	void untransformedTheDanglingSetIsExactlyTheKnownOne() throws Exception {
		Result result = scan(false);
		assertTrue(result.scanned > 5000, "an empty scan cannot pass: " + result.scanned);
		assertEquals(KNOWN_UNRESOLVED, result.unresolved,
				"the dangling capability calls changed — a rebuilt base moved; re-derive the shim, do not re-bless");
	}

	@Test
	void afterTheShimAndTheCompatTransformerNothingDangles() throws Exception {
		Result result = scan(true);
		assertTrue(result.scanned > 5000);
		assertEquals(List.of(), new ArrayList<>(result.unresolved),
				"capability calls in the merged base that still resolve nowhere after both transformers");
	}

	private record Result(int scanned, Set<String> unresolved) {
	}

	private static Result scan(boolean transformed) throws Exception {
		assumeTrue(Files.isRegularFile(MERGED) && Files.isRegularFile(FORGE) && Files.isRegularFile(NEO), "staged artifacts absent");
		Map<String, ClassNode> cache = new HashMap<>();
		Set<String> unresolved = new LinkedHashSet<>();
		int scanned = 0;
		try (ZipFile merged = new ZipFile(MERGED.toFile()); ZipFile forge = new ZipFile(FORGE.toFile()); ZipFile neo = new ZipFile(NEO.toFile())) {
			Loader loader = new Loader(merged, forge, neo, transformed, cache);
			Enumeration<? extends ZipEntry> entries = merged.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				if (!entry.getName().endsWith(".class")) continue;
				scanned++;
				String internal = entry.getName().substring(0, entry.getName().length() - 6);
				ClassNode node = loader.load(internal);
				if (node == null) continue;
				for (MethodNode method : node.methods) {
					for (AbstractInsnNode insn : method.instructions) {
						if (!(insn instanceof MethodInsnNode call)) continue;
						if (call.getOpcode() != Opcodes.INVOKEVIRTUAL && call.getOpcode() != Opcodes.INVOKESPECIAL) continue;
						if (!NAMES.contains(call.name) || !call.owner.startsWith("net/minecraft/")) continue;
						if ("getCapability".equals(call.name) && !FORGE_GET.equals(call.desc)) continue;   // NeoForge's own overloads
						if (!loader.resolves(call.owner, call.name, call.desc)) {
							unresolved.add(node.name + "." + method.name + " -> " + call.owner + "." + call.name + call.desc);
						}
					}
				}
			}
		}
		return new Result(scanned, new TreeSet<>(unresolved));
	}

	/** Loads (and optionally transforms) classes from the merged base, falling back to the Forge carrier. */
	private static final class Loader {
		private final ZipFile merged, forge, neo;
		private final boolean transformed;
		private final Map<String, ClassNode> cache;

		Loader(ZipFile merged, ZipFile forge, ZipFile neo, boolean transformed, Map<String, ClassNode> cache) {
			this.merged = merged;
			this.forge = forge;
			this.neo = neo;
			this.transformed = transformed;
			this.cache = cache;
		}

		ClassNode load(String internal) throws Exception {
			if (cache.containsKey(internal)) return cache.get(internal);
			byte[] bytes = read(merged, internal);
			boolean fromMerged = bytes != null;
			if (bytes == null) bytes = read(forge, internal);
			if (bytes == null) bytes = read(neo, internal);
			ClassNode node = null;
			if (bytes != null) {
				if (transformed && fromMerged) {
					String binary = internal.replace('/', '.');
					bytes = new ForgeCapabilityCompositionTransformer().transform(binary, bytes, null);
					bytes = new ForbricMergedBaseCompatTransformer().transform(binary, bytes, null);
				}
				node = new ClassNode();
				new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			}
			cache.put(internal, node);
			return node;
		}

		/** JVMS resolution: the class chain for a non-abstract implementation, then superinterfaces for a default. */
		boolean resolves(String owner, String name, String desc) throws Exception {
			for (String c = owner; c != null;) {
				ClassNode node = load(c);
				if (node == null) break;   // reached java/lang/Object (or an absent class): nothing below can declare these
				for (MethodNode m : node.methods) {
					if (m.name.equals(name) && m.desc.equals(desc)) return (m.access & Opcodes.ACC_ABSTRACT) == 0 || resolvesDefault(owner, name, desc);
				}
				c = node.superName;
			}
			return resolvesDefault(owner, name, desc);
		}

		private boolean resolvesDefault(String owner, String name, String desc) throws Exception {
			for (String c = owner; c != null;) {
				ClassNode node = load(c);
				if (node == null) return false;
				for (String iface : node.interfaces) if (defaultIn(iface, name, desc)) return true;
				c = node.superName;
			}
			return false;
		}

		private boolean defaultIn(String iface, String name, String desc) throws Exception {
			ClassNode node = load(iface);
			if (node == null) return false;
			for (MethodNode m : node.methods) {
				if (m.name.equals(name) && m.desc.equals(desc) && (m.access & Opcodes.ACC_ABSTRACT) == 0) return true;
			}
			for (String parent : node.interfaces) if (defaultIn(parent, name, desc)) return true;
			return false;
		}

		private static byte[] read(ZipFile zip, String internal) throws Exception {
			ZipEntry entry = zip.getEntry(internal + ".class");
			if (entry == null) return null;
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
