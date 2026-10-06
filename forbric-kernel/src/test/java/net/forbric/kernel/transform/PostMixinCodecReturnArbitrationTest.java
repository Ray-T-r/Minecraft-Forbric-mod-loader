package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.fabricmc.api.EnvType;
import net.forbric.kernel.interop.PayloadInterop;

@ResourceLock("system-properties")
public class PostMixinCodecReturnArbitrationTest {
	private static final String LOOKUP = "fixture/Lookup";
	public static final Map<String,String> SOURCES = Map.ofEntries(
		Map.entry("net.minecraft.network.ConnectionProtocol", "package net.minecraft.network; public enum ConnectionProtocol { CONFIGURATION, PLAY }"),
		Map.entry("net.minecraft.network.protocol.PacketFlow", "package net.minecraft.network.protocol; public enum PacketFlow { SERVERBOUND, CLIENTBOUND }"),
		Map.entry("net.minecraft.network.codec.StreamCodec", "package net.minecraft.network.codec; public interface StreamCodec<B,V>{void encode(B buffer,V value);V decode(B buffer);}"),
		Map.entry("net.fabricmc.fabric.impl.networking.RegistrationPayload", "package net.fabricmc.fabric.impl.networking; public class RegistrationPayload { public RegistrationPayload(){} }"),
		Map.entry("net.minecraftforge.network.ForgePayload", "package net.minecraftforge.network; public class ForgePayload { public ForgePayload(){} }"),
		Map.entry("net.neoforged.neoforge.network.payload.MinecraftRegisterPayload", "package net.neoforged.neoforge.network.payload; public class MinecraftRegisterPayload { public MinecraftRegisterPayload(){} }"),
		Map.entry("fixture.UnknownPayload", "package fixture; public class UnknownPayload { public UnknownPayload(){} }"),
		Map.entry("fixture.Buffer", "package fixture; public class Buffer { public String by; }"),
		Map.entry("fixture.Codec", """
			package fixture;
			import net.minecraft.network.codec.StreamCodec;
			public class Codec implements StreamCodec<Object,Object>{
			 public final String by;public final Class<?> type;public int writes,reads;
			 public Codec(String by,Class<?> type){this.by=by;this.type=type;}
			 public void encode(Object buffer,Object value){type.cast(value);writes++;((Buffer)buffer).by=by;}
			 public Object decode(Object buffer){reads++;try{return type.getConstructor().newInstance();}catch(Exception e){throw new IllegalStateException(e);}}
			}
			"""),
		Map.entry("fixture.Fallback", "package fixture; public class Fallback { public int calls; public Object codec; public Object create(Object id){calls++;return codec;} }"),
		Map.entry("net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl", """
			package net.fabricmc.fabric.impl.networking;
			import java.util.*;
			public class PayloadTypeRegistryImpl{
			 public static final PayloadTypeRegistryImpl SERVERBOUND_CONFIGURATION=new PayloadTypeRegistryImpl(), CLIENTBOUND_CONFIGURATION=new PayloadTypeRegistryImpl(),SERVERBOUND_PLAY=new PayloadTypeRegistryImpl(),CLIENTBOUND_PLAY=new PayloadTypeRegistryImpl();
			 public final Map<Object,Object> entries=new HashMap<>();public Object get(Object id){return entries.get(id);}
			 public record Entry(Object type,Object codec){}
			}
			"""),
		Map.entry("net.neoforged.neoforge.network.registration.NetworkRegistry", "package net.neoforged.neoforge.network.registration; import java.util.*; public class NetworkRegistry{public static final Map<Object,Object> BUILTIN_PAYLOADS=new HashMap<>();}"),
		Map.entry("net.minecraftforge.network.NetworkRegistry", "package net.minecraftforge.network; import java.util.*; public class NetworkRegistry {public static final Map<Object,Object> CODECS=new HashMap<>();public static Object findTarget(Object id){return CODECS.containsKey(id)?id:null;} }"),
		Map.entry("net.minecraftforge.common.ForgeHooks", "package net.minecraftforge.common; public class ForgeHooks {public static Object getCustomPayloadCodec(Object id,int max){return net.minecraftforge.network.NetworkRegistry.CODECS.get(id);} }"),
		Map.entry("fixture.Lookup", """
			package fixture;
			import java.util.*;
			import net.minecraft.network.codec.StreamCodec;
			import net.minecraft.network.ConnectionProtocol;
			import net.minecraft.network.protocol.PacketFlow;
			import net.forbric.kernel.interop.PayloadInterop;
			public class Lookup {
			 public final Map<Object,Object> local;
			 public final ConnectionProtocol protocol;
			 public final PacketFlow flow;
			 public final Fallback fallback;
			 public int callbacks;public Object guest;
			 public Lookup(Map<Object,Object> local,ConnectionProtocol protocol,PacketFlow flow,Fallback fallback){this.local=local;this.protocol=protocol;this.flow=flow;this.fallback=fallback;}
			 public StreamCodec<Object,Object> lookup(Object id){
			  callbacks++;if(guest!=null)return (StreamCodec<Object,Object>)guest;
			  return (StreamCodec<Object,Object>)PayloadInterop.findCodec(local,id,protocol,flow,fallback);
			 }
			}
			""")
	);

	@AfterEach void reset(){System.clearProperty(PostMixinCodecReturnArbitration.PROPERTY);}

	@Test void guestEarlyReturnStillEncodesEachSharedIdPayloadWithItsOwnCodec(@TempDir Path work)throws Throwable{
		Map<String,byte[]> classes=InjectorExecution.compile(work,SOURCES);
		byte[] input=classes.get(LOOKUP),output=InjectorExecution.transform(new PostMixinCodecReturnArbitration(),"fixture.Lookup",input,EnvType.CLIENT);
		assertNotSame(input,output);classes.put(LOOKUP,output);ClassLoader loader=InjectorExecution.load(classes);
		assertEquals("",InjectorExecution.verify(output,loader));
		Object fabric=codec(loader,"fabric","net.fabricmc.fabric.impl.networking.RegistrationPayload"),neo=codec(loader,"neo","net.neoforged.neoforge.network.payload.MinecraftRegisterPayload"),forge=codec(loader,"forge","net.minecraftforge.network.ForgePayload");
		registries(loader,"minecraft:register",fabric,neo,forge);
		Object lookup=lookup(loader,new HashMap<>()),fallback=lookup.getClass().getField("fallback").get(lookup);
		lookup.getClass().getField("guest").set(lookup,fabric);
		Object chosen=InjectorExecution.invoke(lookup,"lookup","minecraft:register");
		for(var test:Map.of("fabric","net.fabricmc.fabric.impl.networking.RegistrationPayload","neo","net.neoforged.neoforge.network.payload.MinecraftRegisterPayload","forge","net.minecraftforge.network.ForgePayload").entrySet()){
			Object buffer=InjectorExecution.construct(loader.loadClass("fixture.Buffer")),payload=InjectorExecution.construct(loader.loadClass(test.getValue()));
			InjectorExecution.invoke(chosen,"encode",buffer,payload);assertEquals(test.getKey(),buffer.getClass().getField("by").get(buffer));
		}
		assertEquals(1,lookup.getClass().getField("callbacks").getInt(lookup));assertEquals(1,fallback.getClass().getField("calls").getInt(fallback));
		Object buffer=InjectorExecution.construct(loader.loadClass("fixture.Buffer"));
		assertEquals("net.neoforged.neoforge.network.payload.MinecraftRegisterPayload",InjectorExecution.invoke(chosen,"decode",buffer).getClass().getName());
		assertEquals(2,hookCount(node(output)));assertSame(output,new PostMixinCodecReturnArbitration().transform("fixture.Lookup",output,null));
	}

	@Test void unknownGuestCodecAndLocalAndFallbackCandidatesArePreserved(@TempDir Path work)throws Throwable{
		Map<String,byte[]> classes=InjectorExecution.compile(work,SOURCES);classes.put(LOOKUP,new PostMixinCodecReturnArbitration().transform("fixture.Lookup",classes.get(LOOKUP),null));
		ClassLoader loader=InjectorExecution.load(classes);Object unknown=codec(loader,"guest","fixture.UnknownPayload"),lookup=lookup(loader,new HashMap<>());
		lookup.getClass().getField("guest").set(lookup,unknown);Object returned=InjectorExecution.invoke(lookup,"lookup","guest:unknown");
		Object buffer=InjectorExecution.construct(loader.loadClass("fixture.Buffer")),value=InjectorExecution.construct(loader.loadClass("fixture.UnknownPayload"));
		InjectorExecution.invoke(returned,"encode",buffer,value);assertEquals("guest",buffer.getClass().getField("by").get(buffer));assertEquals("fixture.UnknownPayload",InjectorExecution.invoke(returned,"decode",buffer).getClass().getName());
		Object local=codec(loader,"local","fixture.UnknownPayload");Map<Object,Object> locals=new HashMap<>();locals.put("local:id",local);Object normal=lookup(loader,locals);
		Object normalCodec=InjectorExecution.invoke(normal,"lookup","local:id");InjectorExecution.invoke(normalCodec,"encode",buffer,value);assertEquals("local",buffer.getClass().getField("by").get(buffer));
		Object factory=normal.getClass().getField("fallback").get(normal);assertEquals(1,factory.getClass().getField("calls").getInt(factory),"normal return must reuse its unified proxy rather than call the fallback factory twice");
		Object idempotent=PayloadInterop.afterGuestCodec(locals,"local:id",normal.getClass().getField("protocol").get(normal),normal.getClass().getField("flow").get(normal),factory,normalCodec);assertSame(normalCodec,idempotent);
		assertSame(normalCodec,PayloadInterop.afterGuestCodec(new HashMap<>(),"other:id",null,null,null,normalCodec),"an Operation's complete chosen context survives changed outer operands");
		Object fallback=codec(loader,"fallback","fixture.UnknownPayload");factory.getClass().getField("codec").set(factory,fallback);
		Object fallbackResult=InjectorExecution.invoke(normal,"lookup","fallback:id");InjectorExecution.invoke(fallbackResult,"encode",buffer,value);assertEquals("fallback",buffer.getClass().getField("by").get(buffer));
	}

	@Test void mutableContextFieldOpaqueGetterAndReassignedParameterAreRejected(@TempDir Path work)throws Exception{
		byte[] original=InjectorExecution.compile(work,SOURCES).get(LOOKUP);
		ClassNode mutable=node(original);mutable.fields.stream().filter(f->f.name.equals("flow")).findFirst().orElseThrow().access&=~Opcodes.ACC_FINAL;
		byte[] bytes=bytes(mutable);assertSame(bytes,new PostMixinCodecReturnArbitration().transform("fixture.Lookup",bytes,null));
		ClassNode changed=node(original);MethodNode method=changed.methods.stream().filter(m->m.name.equals("lookup")).findFirst().orElseThrow();
		InsnList overwrite=new InsnList();overwrite.add(new VarInsnNode(Opcodes.ALOAD,1));overwrite.add(new VarInsnNode(Opcodes.ASTORE,1));method.instructions.insert(overwrite);
		bytes=bytes(changed);assertSame(bytes,new PostMixinCodecReturnArbitration().transform("fixture.Lookup",bytes,null));
		ClassNode getter=node(original);MethodNode lookup=getter.methods.stream().filter(m->m.name.equals("lookup")).findFirst().orElseThrow();
		for(AbstractInsnNode i:lookup.instructions)if(i instanceof FieldInsnNode f&&f.name.equals("flow")){lookup.instructions.set(i,new MethodInsnNode(Opcodes.INVOKEVIRTUAL,getter.name,"opaqueFlow","()Lnet/minecraft/network/protocol/PacketFlow;",false));break;}
		bytes=bytes(getter);assertSame(bytes,new PostMixinCodecReturnArbitration().transform("fixture.Lookup",bytes,null));
	}

	@Test void missingSeamAndControlSwitchMakeNoEdit(@TempDir Path work)throws Exception{
		byte[] original=InjectorExecution.compile(work,SOURCES).get(LOOKUP);System.setProperty(PostMixinCodecReturnArbitration.PROPERTY,"off");assertSame(original,new PostMixinCodecReturnArbitration().transform("fixture.Lookup",original,null));System.clearProperty(PostMixinCodecReturnArbitration.PROPERTY);
		ClassNode missing=node(original);for(MethodNode m:missing.methods)for(AbstractInsnNode i:m.instructions)if(i instanceof MethodInsnNode call&&call.name.equals("findCodec"))call.name="otherLookup";
		byte[] bytes=bytes(missing);assertSame(bytes,new PostMixinCodecReturnArbitration().transform("fixture.Lookup",bytes,null));
	}

	private static Object lookup(ClassLoader loader,Map<Object,Object> local)throws Throwable{return InjectorExecution.construct(loader.loadClass("fixture.Lookup"),local,enumValue(loader,"net.minecraft.network.ConnectionProtocol","CONFIGURATION"),enumValue(loader,"net.minecraft.network.protocol.PacketFlow","SERVERBOUND"),InjectorExecution.construct(loader.loadClass("fixture.Fallback")));}
	private static Object enumValue(ClassLoader loader,String type,String value)throws Exception{return loader.loadClass(type).getField(value).get(null);}
	private static Object codec(ClassLoader loader,String by,String payload)throws Throwable{return InjectorExecution.construct(loader.loadClass("fixture.Codec"),by,loader.loadClass(payload));}
	@SuppressWarnings("unchecked") private static void registries(ClassLoader loader,Object id,Object fabric,Object neo,Object forge)throws Throwable{
		Class<?> registry=loader.loadClass("net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl");Object owner=registry.getField("SERVERBOUND_CONFIGURATION").get(null);
		((Map<Object,Object>)owner.getClass().getField("entries").get(owner)).put(id,InjectorExecution.construct(loader.loadClass("net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl$Entry"),"fabric-type",fabric));
		((Map<Object,Object>)loader.loadClass("net.neoforged.neoforge.network.registration.NetworkRegistry").getField("BUILTIN_PAYLOADS").get(null)).put(id,neo);
		((Map<Object,Object>)loader.loadClass("net.minecraftforge.network.NetworkRegistry").getField("CODECS").get(null)).put(id,forge);
	}
	private static ClassNode node(byte[] bytes){ClassNode n=new ClassNode();new ClassReader(bytes).accept(n,0);return n;}
	private static byte[] bytes(ClassNode n){ClassWriter w=new ClassWriter(0);n.accept(w);return w.toByteArray();}
	private static int hookCount(ClassNode node){int result=0;for(MethodNode m:node.methods)for(AbstractInsnNode i:m.instructions)if(i instanceof MethodInsnNode c&&c.name.equals("afterGuestCodec"))result++;return result;}
}
