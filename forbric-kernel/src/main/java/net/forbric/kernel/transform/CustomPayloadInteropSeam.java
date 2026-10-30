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

import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Makes NeoForge's custom-payload stream codec look its codec up through {@code PayloadInterop.findCodec}, which
 * chooses by the payload's runtime class across every ecosystem's registry — the splice the merged base already
 * carries, moved into the kernel so a base that was never merged gets it too.
 *
 * <p>The previous-generation loader's {@code MergedBaseBuilder} (its PASS 4b) rewrote the merged codec class's
 * {@code findCodec(Identifier)} at build time: NeoForge's body asks its own local map, then NeoForge's
 * {@code NetworkRegistry}, then the fallback — by id alone. Fabric API legitimately registers a DIFFERENT payload class
 * for the same vanilla id ({@code minecraft:register}/{@code unregister}), so an id-only lookup hands encode a codec
 * for the wrong class and the cast inside it fails on the first registration packet. The genuine NeoForge-patched jar
 * has never been through that builder, so on it nothing makes the choice, and nothing hands
 * {@link PostMixinCodecReturnArbitration} the seam it reconciles a guest's chosen codec against (fabric-networking's
 * {@code CustomPayloadStreamCodecMixin} wraps exactly these {@code findCodec} calls).
 *
 * <p>The class is found the way the builder found it, not by name: the one {@code NEW} in NeoForge's
 * {@code CustomPacketPayload.codec(FallbackProvider, List, ConnectionProtocol, PacketFlow)}. On the merged base that is
 * {@code CustomPacketPayload$1$forbricneo} (two anonymous classes could not keep one name through the merge); on the
 * genuine base it is {@code CustomPacketPayload$1}, which is also the class fabric-networking's mixin names. The body is
 * PASS 4b's, field for field: {@code findCodec(idToType, id, protocol, packetFlow, fallback)}, cast back to
 * {@code StreamCodec} — the helper returns {@code Object} because it is compiled without the game.
 *
 * <p>Dormant on the merged base: its {@code findCodec} already makes the call (under the old loader's owner until
 * {@code ForbricMergedBaseCompatTransformer} adopts it, which may run before or after this), so it stands down there
 * and the anchor is a HEDGE. Registered before Mixin, so the post-Mixin arbitration finds the seam in whatever shape the
 * guests leave around it. {@code -Dforbric.customPayloadInteropSeam=off}.
 */
public final class CustomPayloadInteropSeam implements ClassTransformer {
	public static final String PROPERTY = "forbric.customPayloadInteropSeam";

	static final String PAYLOAD = "net/minecraft/network/protocol/common/custom/CustomPacketPayload";
	static final String PAYLOAD_BINARY = PAYLOAD.replace('/', '.');
	/** The genuine base's name for the class, and what its anchor declares. */
	static final String GENUINE_CODEC = PAYLOAD + "$1";
	static final String INTEROP = "net/forbric/kernel/interop/PayloadInterop";
	/** What the merged base still calls until ForbricMergedBaseCompatTransformer adopts it under {@link #INTEROP}. */
	static final String LEGACY_INTEROP = "net/forbric/loader/impl/compat/ForbricCustomPayloadInterop";
	static final String FIND = "(Ljava/util/Map;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
	private static final String STREAM_CODEC = "net/minecraft/network/codec/StreamCodec";
	private static final String FALLBACK = "L" + PAYLOAD + "$FallbackProvider;";
	static final String NEO_CODEC_DESC = "(" + FALLBACK + "Ljava/util/List;Lnet/minecraft/network/ConnectionProtocol;"
			+ "Lnet/minecraft/network/protocol/PacketFlow;)L" + STREAM_CODEC + ";";
	static final String FIND_CODEC_DESC = "(Lnet/minecraft/resources/Identifier;)L" + STREAM_CODEC + ";";

	/** The context PASS 4b hands the helper, in order: field name and the descriptor it expects there. */
	private static final String[][] CONTEXT = {
			{"val$idToType", "Ljava/util/Map;"},
			{null, null}, // the id: findCodec's own argument
			{"val$protocol", "Lnet/minecraft/network/ConnectionProtocol;"},
			{"val$packetFlow", "Lnet/minecraft/network/protocol/PacketFlow;"},
			{"val$fallback", FALLBACK}};

	private final Function<String, byte[]> classBytes;
	/** The codec class's internal name once read from CustomPacketPayload; {@code ""} when it could not be found. */
	private volatile String target;

	/**
	 * @param classBytes the game's raw class bytes by resource path ({@code a/b/C.class}), to read
	 *                   {@code CustomPacketPayload} when its codec class is transformed before it
	 */
	public CustomPayloadInteropSeam(Function<String, byte[]> classBytes) {
		this.classBytes = classBytes;
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric:custom-payload-interop-seam";
	}

	@Override
	public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("switched off by -D" + PROPERTY);
		return AnchorSet.of(new AnchorSet.Anchor(GENUINE_CODEC.replace('/', '.'), AnchorSet.Severity.HEDGE,
				"on a base that was never merged, a custom payload is encoded with the codec registered for its id rather "
						+ "than its class (Fabric's minecraft:register fails to cast), and fabric-networking's codec wrap has "
						+ "no seam to be arbitrated against"));
	}

	@Override
	public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0 || className == null
				|| !className.startsWith(PAYLOAD_BINARY + "$")) {
			return bytes;
		}
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (!node.name.equals(target(node))) return bytes;
		if (!splice(node)) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Network] %s.findCodec asks PayloadInterop, which chooses a custom payload's codec by its "
				+ "class across every ecosystem — the lookup the merged base was built with", className);
		return writer.toByteArray();
	}

	/**
	 * The class NeoForge's {@code codec} overload instantiates, read once from {@code CustomPacketPayload}. Nothing
	 * when the interface cannot be read: guessing by name would splice the merged base's vanilla-shaped {@code $1},
	 * whose lookup has no protocol to hand over.
	 */
	private String target(ClassNode candidate) {
		String known = target;
		if (known != null) return known;
		byte[] payload = classBytes == null ? null : classBytes.apply(PAYLOAD + ".class");
		if (payload == null) {
			ForbricLog.warn("[Forbric/Network] could not read CustomPacketPayload while transforming %s — its codec class "
					+ "is not known, so no custom-payload codec lookup is made cross-ecosystem here",
					candidate.name.replace('/', '.'));
			return null;
		}
		known = instantiated(payload);
		target = known;
		return known;
	}

	/** The one class {@code NEW}ed in NeoForge's {@code codec(FallbackProvider, List, ConnectionProtocol, PacketFlow)}. */
	static String instantiated(byte[] payload) {
		ClassNode node = new ClassNode();
		new ClassReader(payload).accept(node, ClassReader.SKIP_FRAMES);
		String found = "";
		for (MethodNode method : node.methods) {
			if (!method.name.equals("codec") || !method.desc.equals(NEO_CODEC_DESC)) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn.getOpcode() != Opcodes.NEW) continue;
				String type = ((TypeInsnNode) insn).desc;
				if (!type.startsWith(PAYLOAD + "$")) continue;
				if (!found.isEmpty() && !found.equals(type)) {
					ForbricLog.warn("[Forbric/Network] CustomPacketPayload.codec instantiates both %s and %s — not choosing "
							+ "which one to give the cross-ecosystem codec lookup", found, type);
					return "";
				}
				found = type;
			}
		}
		return found;
	}

	/** PASS 4b's body, or false when the method already asks the helper or the class is not the codec's shape. */
	static boolean splice(ClassNode node) {
		MethodNode find = null;
		for (MethodNode method : node.methods) {
			if (method.name.equals("findCodec") && method.desc.equals(FIND_CODEC_DESC)) find = method;
		}
		if (find == null || (find.access & Opcodes.ACC_STATIC) != 0) return false;
		for (AbstractInsnNode insn = find.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC && call.name.equals("findCodec")
					&& call.desc.equals(FIND) && (call.owner.equals(INTEROP) || call.owner.equals(LEGACY_INTEROP))) {
				return false;
			}
		}
		InsnList body = new InsnList();
		for (String[] context : CONTEXT) {
			if (context[0] == null) {
				body.add(new VarInsnNode(Opcodes.ALOAD, 1));
				continue;
			}
			FieldNode field = field(node, context[0]);
			if (field == null || !field.desc.equals(context[1]) || (field.access & Opcodes.ACC_STATIC) != 0) {
				ForbricLog.warn("[Forbric/Network] %s has no %s:%s — not the codec shape the cross-ecosystem lookup is "
						+ "handed, left as it is", node.name.replace('/', '.'), context[0], context[1]);
				return false;
			}
			body.add(new VarInsnNode(Opcodes.ALOAD, 0));
			body.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, field.name, field.desc));
		}
		body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEROP, "findCodec", FIND, false));
		body.add(new TypeInsnNode(Opcodes.CHECKCAST, STREAM_CODEC));
		body.add(new InsnNode(Opcodes.ARETURN));
		find.instructions = body;
		find.tryCatchBlocks.clear();
		if (find.localVariables != null) find.localVariables.clear();
		return true;
	}

	private static FieldNode field(ClassNode node, String name) {
		for (FieldNode field : node.fields) if (field.name.equals(name)) return field;
		return null;
	}
}
