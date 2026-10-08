/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Completes the tracking and query obligations of both published multipart APIs. The retained
 * native callback remains authoritative; missing map effects are added around its proved part loop.
 * Queries and debug readers compose actual identities rather than predicting an entity's ecosystem. */
public final class ForgePartTrackingInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.forgePartTracking";
	static final String SERVER_CALLBACKS = "net.minecraft.server.level.ServerLevel$EntityCallbacks";
	static final String CLIENT_CALLBACKS = ClientPartTrackingInjector.CALLBACKS;
	static final String HITBOXES = DragonPartsInjector.HITBOXES;
	static final String LEVEL = "net.minecraft.world.level.Level";
	static final String SERVER_CALLBACKS_INTERNAL = "net/minecraft/server/level/ServerLevel$EntityCallbacks";
	static final String SERVER_LEVEL = "net/minecraft/server/level/ServerLevel";
	static final String CLIENT_LEVEL = ClientPartTrackingInjector.LEVEL;
	static final String LEVEL_INTERNAL = "net/minecraft/world/level/Level";
	static final String ENTITY = "net/minecraft/world/entity/Entity";
	static final String AABB = "net/minecraft/world/phys/AABB";
	static final String PARTS_MAP = "it/unimi/dsi/fastutil/ints/Int2ObjectMap";
	static final String FORGE_PART = DragonPartsInjector.FORGE_PART;
	static final String NEO_PART = DragonPartsInjector.NEO_PART;
	static final String TRACKING_DESC = "(L" + ENTITY + ";)V";
	static final String FORGE_GET_PARTS = "()[L" + FORGE_PART + ";";
	static final String NEO_GET_PARTS = "()[L" + NEO_PART + ";";
	static final String GET_ENTITIES_DESC = "(L" + ENTITY + ";L" + AABB + ";Ljava/util/function/Predicate;)Ljava/util/List;";
	/** The added loops: MinecraftForge's own tracking, one per direction, and its own part lookup. */
	static final String TRACK = "forbric$trackForgeParts";
	static final String UNTRACK = "forbric$untrackForgeParts";
	static final String TRACK_DESC = "(L" + PARTS_MAP + ";L" + ENTITY + ";)V";
	static final String FIND = "forbric$findForgeParts";
	static final String FIND_DESC = "(L" + LEVEL_INTERNAL + ";L" + ENTITY + ";L" + AABB + ";Ljava/util/function/Predicate;Ljava/util/List;Ljava/util/Set;)V";
	/** MinecraftForge's level extension, which declares getPartEntities(). */
	static final String FORGE_LEVEL = "net/minecraftforge/common/extensions/IForgeLevel";

	private final Function<String, byte[]> gameClass;

	/** @param gameClass a game class file by internal name, read without loading it (the kernel's game resources) */
	public ForgePartTrackingInjector(Function<String, byte[]> gameClass) {
		this.gameClass = gameClass;
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-forge-part-tracking"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("MinecraftForge mods' multipart entities explicitly left as merged with -D" + PROPERTY + "=off");
		return AnchorSet.of(
				new AnchorSet.Anchor(SERVER_CALLBACKS, AnchorSet.Severity.REQUIRED,
						"a MinecraftForge mod's multipart entity throws when a server adds it to a world or removes it"),
				new AnchorSet.Anchor(CLIENT_CALLBACKS, AnchorSet.Severity.REQUIRED,
						"a MinecraftForge mod's multipart entity throws when the client stops tracking it"),
				new AnchorSet.Anchor(HITBOXES, AnchorSet.Severity.REQUIRED,
						"F3+B hitboxes throw on every frame a MinecraftForge mod's multipart entity is in view"),
				new AnchorSet.Anchor(LEVEL, AnchorSet.Severity.REQUIRED,
						"a MinecraftForge mod's multipart entity's parts cannot be aimed at or hit by projectiles"));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0) return bytes;
		boolean server = SERVER_CALLBACKS.equals(className);
		boolean client = CLIENT_CALLBACKS.equals(className);
		boolean hitboxes = HITBOXES.equals(className);
		boolean level = LEVEL.equals(className);
		if (!server && !client && !hitboxes && !level) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
		int changed = server ? serverCallbacks(node, partEntities(SERVER_LEVEL)) : client ? clientCallbacks(node, partEntities(CLIENT_LEVEL))
				: hitboxes ? hitboxes(node, gameClass) : level(node);
		if (changed <= 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info(server ? "[Forbric/Entity] the server's tracking callbacks keep a MinecraftForge mod's multipart entity's parts in "
						+ "partEntities, as MinecraftForge's do — they read only NeoForge's getParts(), which it leaves null, and threw"
				: client ? "[Forbric/Entity] the client's onTrackingEnd takes a MinecraftForge mod's multipart entity's parts out of "
						+ "partEntities, as MinecraftForge's does — it read only NeoForge's getParts(), which it leaves null, and threw"
				: hitboxes ? "[Forbric/Entity] the debug hitboxes read both native part arrays once per identity"
				: "[Forbric/Entity] Level.getEntities finds a MinecraftForge mod's multipart entity's parts through getPartEntities(), "
						+ "as MinecraftForge's does");
		return writer.toByteArray();
	}

	/**
	 * Whether {@code level} carries MinecraftForge's own {@code partEntities}; null when its class file cannot be read. A
	 * game without it — NeoForge's own — has no MinecraftForge part tracking to restore.
	 */
	private Boolean partEntities(String level) {
		byte[] bytes = gameClass.apply(level);
		if (bytes == null) return null;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE);
		for (FieldNode field : node.fields) {
			if (field.name.equals("partEntities") && field.desc.equals("L" + PARTS_MAP + ";") && (field.access & Opcodes.ACC_STATIC) == 0) return true;
		}
		return false;
	}

	/** ServerLevel$EntityCallbacks: both callbacks tolerate a null NeoForge getParts() and track MinecraftForge's parts. */
	static int serverCallbacks(ClassNode callbacks, Boolean partEntities) {
		MethodNode start = method(callbacks, "onTrackingStart", TRACKING_DESC);
		MethodNode end = method(callbacks, "onTrackingEnd", TRACKING_DESC);
		if (start == null || end == null) return declined("ServerLevel$EntityCallbacks has no onTrackingStart/onTrackingEnd(Entity)");
		// MinecraftForge's own bodies, or this edit's.
		if (!readsNeoForgeParts(start) && !readsNeoForgeParts(end) || method(callbacks, TRACK, TRACK_DESC) != null) return 0;
		if (partEntities == null) return declined("ServerLevel's class file could not be read");
		if (!partEntities) return 0;
		FieldInsnNode level = outerLevel(callbacks, SERVER_LEVEL);
		AbstractInsnNode startBlock = neoForgeBlock(start);
		AbstractInsnNode endBlock = neoForgeBlock(end);
		if (level == null || startBlock == null || endBlock == null) {
			return declined("the server's callbacks are not NeoForge's `if (entity.isMultipartEntity()) for (part : entity.getParts())`");
		}
		tolerate(start, partsReads(start).get(0));
		tolerate(end, partsReads(end).get(0));
		track(start, startBlock, callbacks.name, level, TRACK);
		track(end, endBlock, callbacks.name, level, UNTRACK);
		callbacks.methods.add(trackingLoop(TRACK, true));
		callbacks.methods.add(trackingLoop(UNTRACK, false));
		return 1;
	}

    /** Whichever canonical client direction reads Neo parts also mirrors the Forge map at its shared exit. */
    static int clientCallbacks(ClassNode callbacks,Boolean partEntities){
        if(partEntities==null)return declined("ClientLevel's class file could not be read");if(!partEntities)return 0;
        FieldInsnNode level=outerLevel(callbacks,CLIENT_LEVEL);if(level==null)return declined("client callbacks have no outer level field");
        record Plan(MethodNode method,AbstractInsnNode exit,MethodInsnNode read,String loop){}
        List<Plan> plans=new ArrayList<>();
        for(String direction:List.of("onTrackingStart","onTrackingEnd")){
            MethodNode method=method(callbacks,direction,TRACKING_DESC);String loop=direction.equals("onTrackingStart")?TRACK:UNTRACK;
            if(method==null)return declined("client callbacks have no "+direction+"(Entity)");if(calls(method,loop)||!readsNeoForgeParts(method))continue;
            // A body already reading both native arrays belongs to the companion client repair.
            if(partsReads(method).stream().anyMatch(read->read.desc.equals(FORGE_GET_PARTS)))continue;
            AbstractInsnNode exit=canonicalPartExit(method);if(exit==null)return declined("client canonical part loop has no shared normal exit: "+direction);
            plans.add(new Plan(method,exit,partsReads(method).getFirst(),loop));
        }
        for(Plan plan:plans){if(!tolerant(plan.read))tolerate(plan.method,plan.read);
            InsnList call=trackingCall(callbacks.name,level,plan.loop);plan.method.instructions.insert(plan.exit,call);
            if(method(callbacks,plan.loop,TRACK_DESC)==null)callbacks.methods.add(trackingLoop(plan.loop,plan.loop.equals(TRACK)));
        }return plans.isEmpty()?0:1;
    }
    /** All existing branches retain their target frame and reach this anchor after the canonical part case. */
    private static AbstractInsnNode canonicalPartExit(MethodNode method){
        if(neoForgeBlock(method)==null)return null;MethodInsnNode read=partsReads(method).getFirst();
        for(AbstractInsnNode at=read.getPrevious();at!=null;at=at.getPrevious())if(at instanceof MethodInsnNode asks&&asks.owner.equals(ENTITY)&&asks.name.equals("isMultipartEntity")&&next(asks)instanceof JumpInsnNode skip){
            AbstractInsnNode anchor=skip.label;while(anchor.getNext()!=null&&anchor.getNext().getOpcode()<0)anchor=anchor.getNext();return anchor;
        }return null;
    }

    /** A public Entity operation may consume the identity union of both native part APIs. */
    static int hitboxes(ClassNode renderer, Function<String,byte[]> resources) {
        int changed=0;
        for(MethodNode method:renderer.methods){
            List<MethodInsnNode> reads=partsReads(method);if(reads.isEmpty())continue;
            for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&(call.owner.equals(FORGE_PART)||call.owner.equals(NEO_PART))
                &&(call.getOpcode()!=Opcodes.INVOKEVIRTUAL || !entityMethod(resources,call.name,call.desc)))return declined("debug part operation has no shared Entity contract: "+call.owner+"."+call.name+call.desc);
            if(method.desc.contains("L"+FORGE_PART)||method.desc.contains("L"+NEO_PART)||method.tryCatchBlocks.stream().anyMatch(handler->FORGE_PART.equals(handler.type)||NEO_PART.equals(handler.type)))return declined("debug method has a part-specific public or handler contract");
            for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof FieldInsnNode field&&((field.desc.contains("L"+FORGE_PART)||field.desc.contains("L"+NEO_PART))||field.owner.equals(FORGE_PART)||field.owner.equals(NEO_PART)))return declined("debug method has a part-specific field contract");
            for(AbstractInsnNode instruction:method.instructions){
                if(instruction instanceof MethodInsnNode call && !reads.contains(call) && (call.desc.contains("L"+FORGE_PART+";") || call.desc.contains("L"+NEO_PART+";")))return declined("debug operation consumes a part-specific argument or return");
                if(instruction instanceof TypeInsnNode type && (type.desc.equals(FORGE_PART)||type.desc.equals(NEO_PART)) && type.getOpcode()!=Opcodes.CHECKCAST && type.getOpcode()!=Opcodes.ANEWARRAY)return declined("debug operation depends on a part-specific runtime type");
            }
            for(MethodInsnNode read:reads){
                boolean forge=read.desc.equals(FORGE_GET_PARTS);method.instructions.insertBefore(read,new InsnNode(forge?Opcodes.ICONST_1:Opcodes.ICONST_0));
                read.setOpcode(Opcodes.INVOKESTATIC);read.owner="net/forbric/kernel/runtime/KernelMultipartViews";read.name="parts";read.desc="(L"+ENTITY+";Z)[L"+ENTITY+";";read.itf=false;
            }
            for(AbstractInsnNode instruction:method.instructions){
                if(instruction instanceof MethodInsnNode call&&(call.owner.equals(FORGE_PART)||call.owner.equals(NEO_PART)))call.owner=ENTITY;
                else if(instruction instanceof TypeInsnNode type&&(type.getOpcode()==Opcodes.CHECKCAST||type.getOpcode()==Opcodes.ANEWARRAY))type.desc=sharedPartType(type.desc);
                else if(instruction instanceof FrameNode frame){sharedFrame(frame.local);sharedFrame(frame.stack);}
            }
            if(method.localVariables!=null)for(var local:method.localVariables){local.desc=sharedPartType(local.desc);local.signature=null;}
            changed++;
        }return changed;
    }
    private static boolean entityMethod(Function<String,byte[]> resources,String name,String descriptor){
        java.util.Set<String> seen=new java.util.HashSet<>();String owner=ENTITY;
        while(owner!=null&&seen.add(owner)){byte[] bytes=resources.apply(owner);if(bytes==null)return false;ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.SKIP_CODE|ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
            for(MethodNode method:node.methods)if(method.name.equals(name)&&method.desc.equals(descriptor)&&(method.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC))==Opcodes.ACC_PUBLIC)return true;owner=node.superName;
        }return false;
    }
    private static String sharedPartType(String type){return type==null?null:type.equals(FORGE_PART)||type.equals(NEO_PART)?ENTITY:type.replace("L"+FORGE_PART+";","L"+ENTITY+";").replace("L"+NEO_PART+";","L"+ENTITY+";");}
    private static void sharedFrame(List<Object> entries){if(entries!=null)for(int i=0;i<entries.size();i++)if(entries.get(i)instanceof String type)entries.set(i,sharedPartType(type));}

	/** Level: getEntities(Entity, AABB, Predicate) also returns the MinecraftForge parts getPartEntities() holds. */
	static int level(ClassNode level) {
		MethodNode get = method(level, "getEntities", GET_ENTITIES_DESC);
		if (get == null) return declined("Level has no getEntities(Entity, AABB, Predicate)");
		// NeoForge's own game has no getPartEntities(); MinecraftForge's own body (or this edit) already reads it.
		if (!level.interfaces.contains(FORGE_LEVEL) || calls(get, "getPartEntities") || calls(get, FIND)) return 0;
		AbstractInsnNode output = null;
		for (AbstractInsnNode insn : get.instructions) {
			if (insn.getOpcode() != Opcodes.ARETURN) continue;
			if (output != null) return declined("getEntities(Entity, AABB, Predicate) returns from more than one place");
			output = previous(insn);
		}
		if (!(output instanceof VarInsnNode list) || list.getOpcode() != Opcodes.ALOAD || list.var < 4) {
			return declined("getEntities(Entity, AABB, Predicate) does not end `return output`");
		}
        List<VarInsnNode> nativeParts = new ArrayList<>();
        for (AbstractInsnNode instruction : get.instructions) if (instruction instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST && cast.desc.equals(NEO_PART)
            && previous(cast) instanceof MethodInsnNode next && next.owner.equals("java/util/Iterator") && next.name.equals("next") && next.desc.equals("()Ljava/lang/Object;")
            && next(cast) instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE) nativeParts.add(store);
        if (nativeParts.size() > 1 || calls(get, "dragonParts") && nativeParts.size() != 1) return declined("the canonical part iteration has no unique actual element store");
        int seen = get.maxLocals++;
        InsnList initialize = new InsnList();
        initialize.add(new TypeInsnNode(Opcodes.NEW, "java/util/IdentityHashMap")); initialize.add(new InsnNode(Opcodes.DUP));
        initialize.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/IdentityHashMap", "<init>", "()V", false));
        initialize.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Collections", "newSetFromMap", "(Ljava/util/Map;)Ljava/util/Set;", false));
        initialize.add(new VarInsnNode(Opcodes.ASTORE, seen)); get.instructions.insert(initialize);
        for (VarInsnNode nativePart : nativeParts) {
            InsnList visited = new InsnList(); visited.add(new VarInsnNode(Opcodes.ALOAD, seen)); visited.add(new VarInsnNode(Opcodes.ALOAD, nativePart.var));
            visited.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z", true)); visited.add(new InsnNode(Opcodes.POP)); get.instructions.insert(nativePart, visited);
        }
        for (AbstractInsnNode instruction : get.instructions) if (instruction instanceof FrameNode frame) appendLocal(frame, seen, "java/util/Set");
        InsnList find = new InsnList();
        for (int arg = 0; arg <= 3; arg++) find.add(new VarInsnNode(Opcodes.ALOAD, arg));
        find.add(new VarInsnNode(Opcodes.ALOAD, list.var)); find.add(new VarInsnNode(Opcodes.ALOAD, seen));
        find.add(new MethodInsnNode(Opcodes.INVOKESTATIC, level.name, FIND, FIND_DESC, false));
        get.instructions.insertBefore(output, find); level.methods.add(findLoop(level.name));
		return 1;
	}

	private static boolean readsNeoForgeParts(MethodNode method) {
		return partsReads(method).stream().anyMatch(read -> read.desc.equals(NEO_GET_PARTS));
	}

	/**
	 * The receiver load of NeoForge's {@code if (entity.isMultipartEntity())}, when the method's one getParts() read is
	 * NeoForge's and sits inside that block; null when the method is not that shape.
	 */
	private static AbstractInsnNode neoForgeBlock(MethodNode method) {
		List<MethodInsnNode> reads = partsReads(method);
		if (reads.size() != 1 || !reads.get(0).desc.equals(NEO_GET_PARTS)) return null;
		MethodInsnNode read = reads.get(0);
		MethodInsnNode asks = null;
		for (AbstractInsnNode at = read.getPrevious(); at != null; at = at.getPrevious()) {
			if (at instanceof MethodInsnNode call && call.owner.equals(ENTITY) && call.name.equals("isMultipartEntity")) {
				asks = call;
				break;
			}
		}
		if (asks == null || !asks.desc.equals("()Z") || !loadsEntity(previous(asks))
				|| !(next(asks) instanceof JumpInsnNode skip) || skip.getOpcode() != Opcodes.IFEQ) return null;
		// The read is inside the block: the IFEQ jumps past it, and nothing between them branches or is branched to.
		for (AbstractInsnNode at = skip.getNext(); at != read; at = at.getNext()) {
			if (at == skip.label || at instanceof JumpInsnNode || at instanceof FrameNode
					|| at.getOpcode() == Opcodes.TABLESWITCH || at.getOpcode() == Opcodes.LOOKUPSWITCH) return null;
		}
		for (AbstractInsnNode at = read.getNext(); at != null; at = at.getNext()) {
			if (at == skip.label) return previous(asks);
		}
		return null;
	}

	/** {@code Objects.requireNonNullElse(entity.getParts(), new PartEntity[0])}: no branch, so no frame to add. */
	private static void tolerate(MethodNode method, MethodInsnNode read) {
		String part = read.desc.substring(4, read.desc.length() - 1);
		InsnList empty = new InsnList();
		empty.add(new InsnNode(Opcodes.ICONST_0));
		empty.add(new TypeInsnNode(Opcodes.ANEWARRAY, part));
		empty.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Objects", "requireNonNullElse",
				"(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
		empty.add(new TypeInsnNode(Opcodes.CHECKCAST, "[L" + part + ";"));
		method.instructions.insert(read, empty);
	}

	private static boolean tolerant(MethodInsnNode read) {
		AbstractInsnNode size = next(read);
		AbstractInsnNode array = next(size);
		return size != null && size.getOpcode() == Opcodes.ICONST_0 && array instanceof TypeInsnNode type
				&& type.getOpcode() == Opcodes.ANEWARRAY && next(array) instanceof MethodInsnNode call && call.name.equals("requireNonNullElse");
	}

	/** {@code Callbacks.loop(Level.this.partEntities, entity);} ahead of NeoForge's multipart block: straight-line code only. */
    private static InsnList trackingCall(String owner,FieldInsnNode level,String loop){
        InsnList call=new InsnList();call.add(new VarInsnNode(Opcodes.ALOAD,0));call.add(new FieldInsnNode(Opcodes.GETFIELD,level.owner,level.name,level.desc));
        call.add(new FieldInsnNode(Opcodes.GETFIELD,Type.getType(level.desc).getInternalName(),"partEntities","L"+PARTS_MAP+";"));call.add(new VarInsnNode(Opcodes.ALOAD,1));call.add(new MethodInsnNode(Opcodes.INVOKESTATIC,owner,loop,TRACK_DESC,false));return call;
    }
    private static void track(MethodNode method,AbstractInsnNode block,String owner,FieldInsnNode level,String loop){method.instructions.insertBefore(block,trackingCall(owner,level,loop));}

	/**
	 * MinecraftForge's own loop, put or remove by id:
	 * {@code if (entity.isMultipartEntity()) { PartEntity<?>[] parts = entity.getParts(); if (parts != null) for (part : parts) map.put(part.getId(), part); }}
	 */
	static MethodNode trackingLoop(String name, boolean put) {
		MethodNode loop = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, name, TRACK_DESC, null, null);
		LabelNode head = new LabelNode();
		LabelNode done = new LabelNode();
		InsnList code = loop.instructions;
		code.add(new VarInsnNode(Opcodes.ALOAD, 1));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTITY, "isMultipartEntity", "()Z", false));
		code.add(new JumpInsnNode(Opcodes.IFEQ, done));
		code.add(new VarInsnNode(Opcodes.ALOAD, 1));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, ENTITY, "getParts", FORGE_GET_PARTS, false));
		code.add(new VarInsnNode(Opcodes.ASTORE, 2));
		code.add(new VarInsnNode(Opcodes.ALOAD, 2));
		code.add(new JumpInsnNode(Opcodes.IFNULL, done));
		code.add(new InsnNode(Opcodes.ICONST_0));
		code.add(new VarInsnNode(Opcodes.ISTORE, 3));
		code.add(head);
		code.add(new FrameNode(Opcodes.F_NEW, 4, new Object[] {PARTS_MAP, ENTITY, "[L" + FORGE_PART + ";", Opcodes.INTEGER}, 0, new Object[0]));
		code.add(new VarInsnNode(Opcodes.ILOAD, 3));
		code.add(new VarInsnNode(Opcodes.ALOAD, 2));
		code.add(new InsnNode(Opcodes.ARRAYLENGTH));
		code.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
		code.add(new VarInsnNode(Opcodes.ALOAD, 0));
		code.add(new VarInsnNode(Opcodes.ALOAD, 2));
		code.add(new VarInsnNode(Opcodes.ILOAD, 3));
		code.add(new InsnNode(Opcodes.AALOAD));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FORGE_PART, "getId", "()I", false));
		if (put) {
			code.add(new VarInsnNode(Opcodes.ALOAD, 2));
			code.add(new VarInsnNode(Opcodes.ILOAD, 3));
			code.add(new InsnNode(Opcodes.AALOAD));
			code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, PARTS_MAP, "put", "(ILjava/lang/Object;)Ljava/lang/Object;", true));
		} else {
			code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, PARTS_MAP, "remove", "(I)Ljava/lang/Object;", true));
		}
		code.add(new InsnNode(Opcodes.POP));
		code.add(new IincInsnNode(3, 1));
		code.add(new JumpInsnNode(Opcodes.GOTO, head));
		code.add(done);
		code.add(new FrameNode(Opcodes.F_NEW, 2, new Object[] {PARTS_MAP, ENTITY}, 0, new Object[0]));
		code.add(new InsnNode(Opcodes.RETURN));
		loop.maxLocals = 4;
		return loop;
	}

	/**
	 * MinecraftForge's own part lookup in getEntities:
	 * {@code for (PartEntity<?> part : level.getPartEntities()) if (part != except && part.getParent() != except
	 * && selector.test(part) && bb.intersects(part.getBoundingBox())) output.add(part);}
	 */
	static MethodNode findLoop(String level) {
		MethodNode loop = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, FIND, FIND_DESC, null, null);
		LabelNode head = new LabelNode();
		LabelNode done = new LabelNode();
		Object[] locals = {LEVEL_INTERNAL, ENTITY, AABB, "java/util/function/Predicate", "java/util/List", "java/util/Set", "java/util/Iterator"};
		InsnList code = loop.instructions;
        code.add(new VarInsnNode(Opcodes.ALOAD, 5)); code.add(new VarInsnNode(Opcodes.ALOAD, 4));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "addAll", "(Ljava/util/Collection;)Z", true)); code.add(new InsnNode(Opcodes.POP));
		code.add(new VarInsnNode(Opcodes.ALOAD, 0));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, level, "getPartEntities", "()Ljava/util/Collection;", false));
		code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Collection", "iterator", "()Ljava/util/Iterator;", true));
		code.add(new VarInsnNode(Opcodes.ASTORE, 6));
		code.add(head);
		code.add(new FrameNode(Opcodes.F_NEW, locals.length, locals, 0, new Object[0]));
		code.add(new VarInsnNode(Opcodes.ALOAD, 6));
		code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
		code.add(new JumpInsnNode(Opcodes.IFEQ, done));
		code.add(new VarInsnNode(Opcodes.ALOAD, 6));
		code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true));
		code.add(new TypeInsnNode(Opcodes.CHECKCAST, FORGE_PART));
		code.add(new VarInsnNode(Opcodes.ASTORE, 7));
        code.add(new VarInsnNode(Opcodes.ALOAD, 5)); code.add(new VarInsnNode(Opcodes.ALOAD, 7));
        code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Set", "add", "(Ljava/lang/Object;)Z", true));
        code.add(new JumpInsnNode(Opcodes.IFEQ, head));
		code.add(new VarInsnNode(Opcodes.ALOAD, 7));
		code.add(new VarInsnNode(Opcodes.ALOAD, 1));
		code.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, head));
		code.add(new VarInsnNode(Opcodes.ALOAD, 7));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FORGE_PART, "getParent", "()L" + ENTITY + ";", false));
		code.add(new VarInsnNode(Opcodes.ALOAD, 1));
		code.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, head));
		code.add(new VarInsnNode(Opcodes.ALOAD, 3));
		code.add(new VarInsnNode(Opcodes.ALOAD, 7));
		code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/function/Predicate", "test", "(Ljava/lang/Object;)Z", true));
		code.add(new JumpInsnNode(Opcodes.IFEQ, head));
		code.add(new VarInsnNode(Opcodes.ALOAD, 2));
		code.add(new VarInsnNode(Opcodes.ALOAD, 7));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, FORGE_PART, "getBoundingBox", "()L" + AABB + ";", false));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, AABB, "intersects", "(L" + AABB + ";)Z", false));
		code.add(new JumpInsnNode(Opcodes.IFEQ, head));
		code.add(new VarInsnNode(Opcodes.ALOAD, 4));
		code.add(new VarInsnNode(Opcodes.ALOAD, 7));
		code.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/List", "add", "(Ljava/lang/Object;)Z", true));
		code.add(new InsnNode(Opcodes.POP));
		code.add(new JumpInsnNode(Opcodes.GOTO, head));
		code.add(done);
		code.add(new FrameNode(Opcodes.F_NEW, locals.length, locals, 0, new Object[0]));
		code.add(new InsnNode(Opcodes.RETURN));
		loop.maxLocals = 8;
		return loop;
	}

    private static void appendLocal(FrameNode frame, int slot, String type) {
        if (frame.type != Opcodes.F_NEW) throw new IllegalStateException("Part lookup frames must be expanded before adding a local");
        int slots = 0; for (Object local : frame.local) slots += local.equals(Opcodes.LONG) || local.equals(Opcodes.DOUBLE) ? 2 : 1;
        if (slots > slot) throw new IllegalStateException("Part lookup local overlaps the original frame");
        while (slots++ < slot) frame.local.add(Opcodes.TOP); frame.local.add(type);
    }

	/** The callbacks' {@code this$0}: the GETFIELD of the outer level every callback body already makes. */
	private static FieldInsnNode outerLevel(ClassNode callbacks, String levelType) {
		for (MethodNode method : callbacks.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD && field.owner.equals(callbacks.name)
						&& field.desc.equals("L" + levelType + ";")) return field;
			}
		}
		return null;
	}

	private static boolean calls(MethodNode method, String name) {
		for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode call && call.name.equals(name)) return true;
		return false;
	}

	private static List<MethodInsnNode> partsReads(MethodNode method) {
		List<MethodInsnNode> reads = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(ENTITY) && call.name.equals("getParts")
					&& (call.desc.equals(NEO_GET_PARTS) || call.desc.equals(FORGE_GET_PARTS))) reads.add(call);
		}
		return reads;
	}

	private static boolean loadsEntity(AbstractInsnNode insn) {
		return insn instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 1;
	}

	private static AbstractInsnNode previous(AbstractInsnNode insn) {
		AbstractInsnNode at = insn == null ? null : insn.getPrevious();
		while (at != null && at.getOpcode() < 0) at = at.getPrevious();
		return at;
	}

	private static AbstractInsnNode next(AbstractInsnNode insn) {
		AbstractInsnNode at = insn == null ? null : insn.getNext();
		while (at != null && at.getOpcode() < 0) at = at.getNext();
		return at;
	}

	private static int declined(String reason) {
		ForbricLog.warn("[Forbric/Entity] left a MinecraftForge mod's multipart entity as merged: %s — it may throw when a world "
				+ "adds or removes it, or its parts may not be found", reason);
		return -1;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}
}
