package net.forbric.kernel.transform;

import net.forbric.kernel.TestFixtures;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

@ExecutesInjector(ForgeOptionsInjector.class)
class ForgeOptionsInjectorTest {
	@TempDir Path temporary;
	@AfterEach void clearSwitch() { System.clearProperty("forbric.forgeClientInit"); }
	private final ForgeOptionsInjector transformer = new ForgeOptionsInjector();

	/**
	 * The real merged Options ends up keeping unknown key bindings for MinecraftForge's late key registration, whichever
	 * way it got there: either the merge already kept MinecraftForge's load()/save() (load() reaching the carrier's
	 * load(boolean), the map written before the constructor's first load), or the injector repairs a base that kept the
	 * other platform's. The end state is what is asserted, so a merge decision moving does not hide a broken result.
	 */
	@Test void realMergedOptionsKeepsUnknownKeysForTheCarriersLateLoader() throws Exception {
		byte[] original = real();
		byte[] result = transformer.transform(ForgeOptionsInjector.TARGET, original, null);
		assertSame(result, transformer.transform(ForgeOptionsInjector.TARGET, result, null), "idempotent");
		ClassNode after = read(result);
		assertTrue(Arrays.stream(method(after, "load", "()V").instructions.toArray()).anyMatch(i -> i instanceof MethodInsnNode c
				&& c.owner.equals(after.name) && c.name.equals("load") && c.desc.equals("(Z)V")), "load() reaches load(boolean)");
		assertTrue(Arrays.stream(method(after, "save", "()V").instructions.toArray()).anyMatch(i -> i instanceof FieldInsnNode f
				&& f.owner.equals(after.name) && f.name.equals("unknownKeys")) || result != original, "save() writes the kept keys");
		assertNotNull(method(after, "processOptions", "(Lnet/minecraft/client/Options$FieldAccess;)V"));
		for (MethodNode m : after.methods) if (m.name.equals("<init>") || m.name.equals("load") || m.name.equals("save"))
			new Analyzer<>(new BasicVerifier()).analyze(after.name, m);
		MethodNode ctor = after.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
		int store = -1, load = -1;
		for (int i = 0; i < ctor.instructions.size(); i++) {
			var instruction = ctor.instructions.get(i);
			if (store < 0 && instruction instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.name.equals("unknownKeys")) store = i;
			if (load < 0 && instruction instanceof MethodInsnNode c && c.owner.equals(after.name) && c.name.equals("load")) load = i;
		}
		assertTrue(store >= 0 && store < load, "the map exists before the constructor's first load: store=" + store + " load=" + load);
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

	/**
	 * Run, on a stand-in Options with the merged shape, against the kernel's real KernelForgeOptions compiled here from
	 * src/runtime/java: its only game type is the KeyMapping the stand-in already provides, so this needs no staged game.
	 */
	@Test void anEarlySaveRetainsUnknownF6WithoutOverwritingAnAlreadyRegisteredKey() throws Throwable {
		Path hook=Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelForgeOptions.java");
		assertTrue(Files.isRegularFile(hook),"the game-side hook's source is part of the checkout: "+hook.toAbsolutePath());
		Map<String,byte[]> classes=new HashMap<>(InjectorExecution.compile(temporary,Map.of(
			"net/forbric/kernel/runtime/KernelForgeOptions.java",Files.readString(hook),
			"net.minecraft.client.KeyMapping","""
			package net.minecraft.client;
			public class KeyMapping {
			  private final String name; public KeyMapping(String name) {this.name=name;}
			  public String getName(){return name;}
			}
			""",
			"net.minecraft.client.Options","""
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
			""")));
		byte[] original=classes.get(ForgeOptionsInjector.OPTIONS),changed=transformer.transform(ForgeOptionsInjector.TARGET,original,null);
		assertNotSame(original,changed);classes.put(ForgeOptionsInjector.OPTIONS,changed);
		ClassLoader loader=InjectorExecution.load(classes);
		assertEquals("",InjectorExecution.verify(changed,loader));
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

	private static byte[] real() throws Exception {
		Path jar=Path.of(System.getenv().getOrDefault("FORBRIC_OLD","../forbric-loader"),"run/merged-base/patched-mc-merged-26.2.jar");
		TestFixtures.requireFiles(TestFixtures.Fixture.STAGED, "staged merged base",jar);
		try(ZipFile zip=new ZipFile(jar.toFile())) {return zip.getInputStream(zip.getEntry("net/minecraft/client/Options.class")).readAllBytes();}
	}
	private static ClassNode read(byte[] bytes){ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);return node;}
	private static MethodNode method(ClassNode node,String name,String desc){return node.methods.stream().filter(m->m.name.equals(name)&&m.desc.equals(desc)).findFirst().orElseThrow();}
	private static List<Integer> opcodes(MethodNode m){return Arrays.stream(m.instructions.toArray()).filter(i->i.getOpcode()>=0).map(AbstractInsnNode::getOpcode).toList();}
}
