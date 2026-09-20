package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

class ForgeOptionsInjectorTest {
	@TempDir Path temporary;
	@AfterEach void clearSwitch() { System.clearProperty("forbric.forgeClientInit"); }
	private final ForgeOptionsInjector transformer = new ForgeOptionsInjector();

	@Test void realMergedRepairPreservesNeoProcessingAndTheCarrierLateLoader() throws Exception {
		byte[] original = real();
		byte[] changed = transformer.transform(ForgeOptionsInjector.TARGET, original, null);
		assertNotSame(original, changed);
		assertSame(changed, transformer.transform(ForgeOptionsInjector.TARGET, changed, null));
		ClassNode before = read(original), after = read(changed);
		assertEquals(opcodes(method(before,"load","(Z)V")),opcodes(method(after,"load","(Z)V")));
		assertEquals(opcodes(method(before,"processOptions", "(Lnet/minecraft/client/Options$FieldAccess;)V")),
				opcodes(method(after,"processOptions", "(Lnet/minecraft/client/Options$FieldAccess;)V")));
		assertEquals(List.of(Opcodes.ALOAD,Opcodes.ICONST_0,Opcodes.INVOKEVIRTUAL,Opcodes.RETURN),opcodes(method(after,"load","()V")));
		for(MethodNode m:after.methods) if(m.name.equals("<init>")||m.name.equals("load")||m.name.equals("save"))
			new Analyzer<>(new BasicVerifier()).analyze(after.name,m);
		MethodNode ctor=after.methods.stream().filter(m->m.name.equals("<init>")).findFirst().orElseThrow();
		int store=-1,load=-1;
		for(int i=0;i<ctor.instructions.size();i++) {
			var instruction=ctor.instructions.get(i);
			if(instruction instanceof FieldInsnNode f && f.getOpcode()==Opcodes.PUTFIELD && f.name.equals("unknownKeys"))store=i;
			if(instruction instanceof MethodInsnNode c && c.owner.equals(after.name)&&c.name.equals("load"))load=i;
		}
		assertTrue(store>=0&&store<load);
	}

	@Test void disabledAndDriftedClassesStayUntouched() throws Exception {
		byte[] original=real();
		System.setProperty("forbric.forgeClientInit","off");
		assertSame(original,transformer.transform(ForgeOptionsInjector.TARGET,original,null));
		System.clearProperty("forbric.forgeClientInit");
		assertSame(original,transformer.transform("example.Other",original,null));
		for(String missing:List.of("unknownKeys","loadBoolean","writer")) {
			ClassNode node=read(original);
			if(missing.equals("unknownKeys"))node.fields.removeIf(f->f.name.equals("unknownKeys"));
			if(missing.equals("loadBoolean"))node.methods.removeIf(m->m.name.equals("load")&&m.desc.equals("(Z)V"));
			if(missing.equals("writer"))for(var i:method(node,"save","()V").instructions)
				if(i instanceof MethodInsnNode c&&c.owner.equals("java/io/PrintWriter")&&c.name.equals("<init>"))c.desc="()V";
			ClassWriter out=new ClassWriter(0);node.accept(out);byte[] bytes=out.toByteArray();
			assertSame(bytes,transformer.transform(ForgeOptionsInjector.TARGET,bytes,null),missing);
		}
	}

	@Test void anEarlySaveRetainsUnknownF6WithoutOverwritingAnAlreadyRegisteredKey() throws Exception {
		Path source=temporary.resolve("net/minecraft/client");Files.createDirectories(source);
		Files.writeString(source.resolve("KeyMapping.java"),"""
			package net.minecraft.client;
			public class KeyMapping {
			  private final String name; public KeyMapping(String name) {this.name=name;}
			  public String getName(){return name;}
			}
			""");
		Files.writeString(source.resolve("Options.java"),"""
			package net.minecraft.client;
			import java.io.*;import java.util.*;
			public class Options {
			  private final Map<String,String> unknownKeys;
			  public KeyMapping[] keyMappings={new KeyMapping("key.known")};
			  public StringWriter buffer=new StringWriter();
			  public boolean captured;
			  public Options(){ load(); unknownKeys=new HashMap<>(); }
			  public void load(){}
			  public void load(boolean keysOnly){
			    if(keysOnly){unknownKeys.clear();return;}
			    captured=unknownKeys!=null;
			    unknownKeys.put("key_key.future","key.keyboard.f6");
			    unknownKeys.put("key_key.known","key.keyboard.a");
			  }
			  public interface FieldAccess {void write(String line);}
			  private void processOptions(FieldAccess access){access.write("key_key.known:key.keyboard.b");}
			  public void save(){PrintWriter writer=new PrintWriter(buffer);processOptions(writer::println);writer.close();}
			}
			""");
		assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","21","-d",temporary.toString(),
				source.resolve("Options.java").toString(),source.resolve("KeyMapping.java").toString()));
		Path classFile=source.resolve("Options.class");
		byte[] original=Files.readAllBytes(classFile),changed=transformer.transform(ForgeOptionsInjector.TARGET,original,null);
		assertNotSame(original,changed);Files.write(classFile,changed);
		try(URLClassLoader loader=new URLClassLoader(new java.net.URL[]{temporary.toUri().toURL(),Path.of("build/classes/java/runtime").toAbsolutePath().toUri().toURL()},null)){
			Class<?> options=Class.forName(ForgeOptionsInjector.TARGET,true,loader);Object instance=options.getConstructor().newInstance();
			assertEquals(true,options.getField("captured").get(instance));
			options.getMethod("save").invoke(instance);
			String text=options.getField("buffer").get(instance).toString();
			assertTrue(text.contains("key_key.future:key.keyboard.f6"),text);
			assertEquals(1,text.lines().filter(s->s.startsWith("key_key.known:")).count(),text);
			assertTrue(text.contains("key_key.known:key.keyboard.b"),text);
			assertFalse(text.contains("key.keyboard.a"),text);
			options.getMethod("load",boolean.class).invoke(instance,true);
			options.getField("buffer").set(instance,new java.io.StringWriter());
			options.getMethod("save").invoke(instance);
			assertFalse(options.getField("buffer").get(instance).toString().contains("key_key.future"));
		}
	}

	private static byte[] real() throws Exception {
		Path jar=Path.of(System.getenv().getOrDefault("FORBRIC_OLD","../forbric-loader"),"run/merged-base/patched-mc-merged-26.2.jar");
		try(ZipFile zip=new ZipFile(jar.toFile())) {return zip.getInputStream(zip.getEntry("net/minecraft/client/Options.class")).readAllBytes();}
	}
	private static ClassNode read(byte[] bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
	private static MethodNode method(ClassNode node,String name,String desc){return node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();}
	private static List<Integer> opcodes(MethodNode m){return Arrays.stream(m.instructions.toArray()).filter(i->i.getOpcode()>=0).map(AbstractInsnNode::getOpcode).toList();}
}
