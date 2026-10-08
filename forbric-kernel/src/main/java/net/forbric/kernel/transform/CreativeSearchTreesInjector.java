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
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/**
 * Files the creative search trees vanilla's methods build where the merged creative screen reads them.
 *
 * <h2>The split</h2>
 *
 * <p>{@code SessionSearchTrees} came out of the byte merge with both families' halves. NeoForge's: the keyed
 * producers {@code updateCreativeTooltips(Provider, List, Key)} and {@code updateCreativeTags(List, Key)}, which file
 * each tree in NeoForge's {@code CreativeModeTabSearchRegistry}, and the keyed readers {@code creativeNameSearch(Key)}
 * and {@code creativeTagSearch(Key)}, which read it back — and the creative screen, which calls only these. MinecraftForge's:
 * vanilla's own two producers {@code updateCreativeTooltips(Provider, List)} and {@code updateCreativeTags(List)},
 * whose bodies loop over MinecraftForge's registry and put every tree in a private map, and the one reader of that map,
 * {@code getSearchTree(Key)}. On native NeoForge vanilla's two producers delegate to the keyed ones with the search
 * tab's keys; here they feed a map the screen never looks at.
 *
 * <p>The screen builds its own trees, through the keyed producers, but only when
 * {@code CreativeModeTabs.tryRebuildTabContents} reports that the tabs changed. A mod that rebuilds the tabs itself and
 * then refreshes the search the vanilla way — TCDCommons, Better Stats' library, does exactly that on every join —
 * therefore leaves nothing for the screen: its trees went into the private map, and the screen, finding the tabs
 * unchanged, skips its rebuild and searches NeoForge's empty default. Every search finds nothing, for the whole session.
 *
 * <h2>The repair</h2>
 *
 * <p>The three MinecraftForge bodies are replaced by calls into {@code KernelCreativeSearch}, which goes through the
 * keyed producers and readers — one store for every caller. A producer refreshes every tab with a search bar, as
 * MinecraftForge's body and the screen's own rebuild do. {@code getSearchTree} reads the same store and answers the
 * empty tree for a key nothing was built under; as merged it joined the map's default, which the merged constructor
 * creates incomplete (MinecraftForge's is a completed empty tree) — the join waits forever, or throws once a producer
 * has cancelled that shared default as the "previous" tree of a key it built for the first time.
 *
 * <p>Applied only where each body still reads MinecraftForge's store (its registry, or the {@code creativeSearch}
 * field) and the keyed methods the helper calls are present; a class already delegating — NeoForge's own — is left
 * alone. {@code -Dforbric.creativeSearchTrees=off} leaves the class as merged.
 */
public final class CreativeSearchTreesInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.creativeSearchTrees";
	static final String TARGET = "net.minecraft.client.multiplayer.SessionSearchTrees";
	static final String TREES = "net/minecraft/client/multiplayer/SessionSearchTrees";
	static final String KEY = "L" + TREES + "$Key;";
	static final String PROVIDER = "Lnet/minecraft/core/HolderLookup$Provider;";
	static final String TREE = "Lnet/minecraft/client/searchtree/SearchTree;";
	static final String HELPER = "net/forbric/kernel/runtime/KernelCreativeSearch";
	/** MinecraftForge's search-key registry, which the merged producers' bodies loop over. */
	static final String FORGE_REGISTRY = ForeignType.CREATIVE_SEARCH_REGISTRY.internal(Ecosystem.FORGE);
	/** MinecraftForge's private tree map, which the merged producers write and getSearchTree reads. */
	static final String FORGE_MAP = "creativeSearch";

	static final String NAMES = "updateCreativeTooltips";
	static final String NAMES_DESC = "(" + PROVIDER + "Ljava/util/List;)V";
	static final String TAGS = "updateCreativeTags";
	static final String TAGS_DESC = "(Ljava/util/List;)V";
	static final String READ = "getSearchTree";
	static final String READ_DESC = "(" + KEY + ")" + TREE;

	/** What each rewritten method becomes: the helper method and its descriptor, receiver first. */
	private static final String[][] REWRITES = {
			{NAMES, NAMES_DESC, "updateNames", "(L" + TREES + ";" + PROVIDER + "Ljava/util/List;)V"},
			{TAGS, TAGS_DESC, "updateTags", "(L" + TREES + ";Ljava/util/List;)V"},
			{READ, READ_DESC, "tree", "(L" + TREES + ";" + KEY + ")" + TREE}};

	/** The keyed methods the helper calls; without all four it would link to nothing. */
	private static final String[][] KEYED = {
			{NAMES, "(" + PROVIDER + "Ljava/util/List;" + KEY + ")V"},
			{TAGS, "(Ljava/util/List;" + KEY + ")V"},
			{"creativeNameSearch", "(" + KEY + ")" + TREE},
			{"creativeTagSearch", "(" + KEY + ")" + TREE}};

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric-creative-search-trees";
	}

	@Override
	public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("switched off by -D" + PROPERTY);
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"a mod that refreshes the creative search through vanilla's methods leaves the creative screen's search "
						+ "finding nothing for the whole session"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!enabled() || !TARGET.equals(className) || classBytes == null || classBytes.length == 0) return classBytes;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		List<String> rewritten = repair(node);
		if (rewritten.isEmpty()) return classBytes;
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		ForbricLog.info("[Forbric/CreativeSearch] %s.%s now file and read the creative search trees in NeoForge's "
				+ "registry, where the creative screen reads them — as merged they used MinecraftForge's private map, so a "
				+ "mod refreshing the search the vanilla way left every creative search empty", TARGET, rewritten);
		return writer.toByteArray();
	}

	/** The names of the methods rewritten; empty when the class is not the merged shape this understands. */
	static List<String> repair(ClassNode node) {
		if (!TREES.equals(node.name)) return List.of();
		for (String[] keyed : KEYED) {
			if (find(node, keyed[0], keyed[1]) == null) return List.of();
		}
		List<String> rewritten = new ArrayList<>();
		for (String[] rewrite : REWRITES) {
			MethodNode method = find(node, rewrite[0], rewrite[1]);
			if (method == null || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
					|| !readsForgeStore(method)) continue;
			if(rewrite[0].equals(READ)){if(!replaceReaderExpression(node,method))continue;}
			else replaceBody(method, rewrite[2], rewrite[3]);
			rewritten.add(rewrite[0]);
		}
		return rewritten;
	}
	/** Keep surrounding SDK/guest effects; only the disconnected map/future expression becomes a shared-store query. */
	private static boolean replaceReaderExpression(ClassNode owner,MethodNode method){
		List<AbstractInsnNode> code=java.util.Arrays.stream(method.instructions.toArray()).filter(i->i.getOpcode()>=0).toList();
		for(int p=0;p+8<code.size();p++){
			if(!(code.get(p)instanceof VarInsnNode self)||self.getOpcode()!=Opcodes.ALOAD||self.var!=0
				||!(code.get(p+1)instanceof FieldInsnNode map)||map.getOpcode()!=Opcodes.GETFIELD||!map.owner.equals(owner.name)||!map.desc.equals("Ljava/util/Map;")
				||!(code.get(p+2)instanceof VarInsnNode key)||key.getOpcode()!=Opcodes.ALOAD||key.var!=1
				||!(code.get(p+3)instanceof VarInsnNode again)||again.getOpcode()!=Opcodes.ALOAD||again.var!=0
				||!(code.get(p+4)instanceof FieldInsnNode empty)||empty.getOpcode()!=Opcodes.GETFIELD||!empty.owner.equals(owner.name)||!empty.desc.equals("Ljava/util/concurrent/CompletableFuture;")
				||!(code.get(p+5)instanceof MethodInsnNode get)||!get.owner.equals("java/util/Map")||!get.name.equals("getOrDefault")||!get.desc.equals("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;")
				||!(code.get(p+6)instanceof org.objectweb.asm.tree.TypeInsnNode future)||future.getOpcode()!=Opcodes.CHECKCAST||!future.desc.equals("java/util/concurrent/CompletableFuture")
				||!(code.get(p+7)instanceof MethodInsnNode join)||!join.owner.equals("java/util/concurrent/CompletableFuture")||!join.name.equals("join")||!join.desc.equals("()Ljava/lang/Object;")
				||!(code.get(p+8)instanceof org.objectweb.asm.tree.TypeInsnNode tree)||tree.getOpcode()!=Opcodes.CHECKCAST||!tree.desc.equals("net/minecraft/client/searchtree/SearchTree"))continue;
			InsnList query=new InsnList();query.add(new VarInsnNode(Opcodes.ALOAD,0));query.add(new VarInsnNode(Opcodes.ALOAD,1));query.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,"tree","(L"+TREES+";"+KEY+")"+TREE,false));method.instructions.insertBefore(code.get(p),query);
			for(int i=p;i<=p+8;i++)method.instructions.remove(code.get(i));method.maxStack=Math.max(method.maxStack,2);return true;
		}return false;
	}

	/** Whether this body still goes to MinecraftForge's registry or map — the merged shape, not yet rewritten. */
	private static boolean readsForgeStore(MethodNode method) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && FORGE_REGISTRY.equals(call.owner)) return true;
			if (insn instanceof FieldInsnNode field && TREES.equals(field.owner) && FORGE_MAP.equals(field.name)) return true;
		}
		return false;
	}

	/** {@code return Helper.name(this, args...)}: straight-line, so no frame, handler or local survives or is needed. */
	private static void replaceBody(MethodNode method, String helperName, String helperDesc) {
		InsnList body = new InsnList();
		body.add(new VarInsnNode(Opcodes.ALOAD, 0));
		int slot = 1;
		int stack = 1;
		for (Type argument : Type.getArgumentTypes(method.desc)) {
			body.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
			slot += argument.getSize();
			stack += argument.getSize();
		}
		body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, helperName, helperDesc, false));
		body.add(new InsnNode(Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)));
		method.instructions = body;
		method.tryCatchBlocks = new ArrayList<>();
		method.localVariables = null;
		method.visibleLocalVariableAnnotations = null;
		method.invisibleLocalVariableAnnotations = null;
		method.maxStack = Math.max(stack, 1);
		method.maxLocals = slot;
	}

	private static MethodNode find(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) {
			if (name.equals(method.name) && desc.equals(method.desc)) return method;
		}
		return null;
	}
}
