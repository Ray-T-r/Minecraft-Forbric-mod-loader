package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;
import net.forbric.kernel.transform.HopperFabricStorageInjector;
import net.forbric.kernel.mixin.MixinNullableCompositeCallback;

/** Upstream callback bytecode executes: Native alternatives win, transaction/cancellation runs once, old bridge off. */
class MixinNullableCompositeWeaveTest {
	@TempDir Path work;
	@Test void theActualUpstreamCallbacksTransferOnceAndKeepNativeAlternatives()throws Exception{
		Map<String,String> sources=new LinkedHashMap<>();
		sources.put("net.minecraft.core.Direction","package net.minecraft.core;public enum Direction{UP,DOWN;public Direction getOpposite(){return this==UP?DOWN:UP;}}");
		sources.put("net.minecraft.core.BlockPos","package net.minecraft.core;public record BlockPos(double x,double y,double z){public BlockPos relative(Direction d){return new BlockPos(x,y+(d==Direction.UP?1:-1),z);}public static BlockPos containing(double x,double y,double z){return new BlockPos(x,y,z);}}");
		sources.put("net.minecraft.world.Container","package net.minecraft.world;public interface Container{Object item();void item(Object value);}");
		sources.put("net.minecraft.world.level.block.state.BlockState","package net.minecraft.world.level.block.state;public class BlockState{}");
		sources.put("net.neoforged.neoforge.transfer.ResourceHandler","package net.neoforged.neoforge.transfer;public class ResourceHandler{}");
		sources.put("net.minecraft.world.level.Level","package net.minecraft.world.level;public class Level{public net.minecraft.world.Container container;public net.neoforged.neoforge.transfer.ResourceHandler handler;public net.fabricmc.fabric.api.transfer.v1.storage.Storage fabric;public boolean success=true;public int lookups,transactions,nativeCalls,kernelCalls;public net.minecraft.world.level.block.state.BlockState getBlockState(net.minecraft.core.BlockPos pos){return new net.minecraft.world.level.block.state.BlockState();}}");
		sources.put("net.minecraft.world.level.block.entity.Hopper","package net.minecraft.world.level.block.entity;public interface Hopper extends net.minecraft.world.Container{double getLevelX();double getLevelY();double getLevelZ();boolean isGridAligned();}");
		sources.put("net.neoforged.neoforge.transfer.item.ContainerOrHandler","package net.neoforged.neoforge.transfer.item;public record ContainerOrHandler(net.minecraft.world.Container container,net.neoforged.neoforge.transfer.ResourceHandler itemHandler){public boolean isEmpty(){return container==null&&itemHandler==null;}}");
		sources.put("net.neoforged.neoforge.transfer.item.VanillaInventoryCodeHooks","package net.neoforged.neoforge.transfer.item;public class VanillaInventoryCodeHooks{public static boolean insertHook(net.minecraft.world.level.block.entity.HopperBlockEntity hopper,ContainerOrHandler found){audit.Probe.level.nativeCalls++;return true;}public static boolean extractHook(net.minecraft.world.level.block.entity.Hopper hopper,net.neoforged.neoforge.transfer.ResourceHandler handler){audit.Probe.level.nativeCalls++;return true;}}");
		sources.put("net.fabricmc.fabric.api.transfer.v1.storage.Storage","package net.fabricmc.fabric.api.transfer.v1.storage;public interface Storage{Object item();void item(Object value);}");
		sources.put("net.fabricmc.fabric.api.transfer.v1.item.ItemVariant","package net.fabricmc.fabric.api.transfer.v1.item;public class ItemVariant{}");
		sources.put("net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext","package net.fabricmc.fabric.api.transfer.v1.transaction;public interface TransactionContext{}");
		sources.put("net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup","package net.fabricmc.fabric.api.lookup.v1.block;public interface BlockApiLookup{Object find(net.minecraft.world.level.Level level,net.minecraft.core.BlockPos pos,Object direction);}");
		sources.put("net.fabricmc.fabric.api.transfer.v1.item.ItemStorage","package net.fabricmc.fabric.api.transfer.v1.item;public class ItemStorage{public static final net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup SIDED=(level,pos,direction)->{level.lookups++;return level.fabric;};}");
		sources.put("net.fabricmc.fabric.api.transfer.v1.item.ContainerStorage","package net.fabricmc.fabric.api.transfer.v1.item;public interface ContainerStorage extends net.fabricmc.fabric.api.transfer.v1.storage.Storage{static ContainerStorage of(net.minecraft.world.Container container,net.minecraft.core.Direction direction){return new ContainerStorage(){public Object item(){return container.item();}public void item(Object value){container.item(value);}};}}");
		sources.put("net.fabricmc.fabric.api.transfer.v1.storage.StorageUtil","package net.fabricmc.fabric.api.transfer.v1.storage;public class StorageUtil{public static long move(Storage from,Storage to,java.util.function.Predicate<net.fabricmc.fabric.api.transfer.v1.item.ItemVariant> predicate,long max,net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext transaction){audit.Probe.level.transactions++;if(!audit.Probe.level.success||from.item()==null||!predicate.test(new net.fabricmc.fabric.api.transfer.v1.item.ItemVariant()))return 0;Object value=from.item();from.item(null);to.item(value);return 1;}}");
		sources.put("net.forbric.kernel.runtime.transfer.KernelFabricHopperStorage","package net.forbric.kernel.runtime.transfer;public class KernelFabricHopperStorage{public static boolean insert(Object level,Object pos,Object hopper){((net.minecraft.world.level.Level)level).kernelCalls++;return false;}public static int extract(Object level,Object hopper){((net.minecraft.world.level.Level)level).kernelCalls++;return -1;}}");
		String header="package net.minecraft.world.level.block.entity;import net.minecraft.world.level.Level;import net.minecraft.core.*;import net.minecraft.world.*;import net.minecraft.world.level.block.state.BlockState;import net.neoforged.neoforge.transfer.item.*;public class HopperBlockEntity implements Hopper{private Direction facing=Direction.UP;private Object value=\"item\";public Object item(){return value;}public void item(Object value){this.value=value;}public double getLevelX(){return 0;}public double getLevelY(){return 0;}public double getLevelZ(){return 0;}public boolean isGridAligned(){return true;}";
		String original=header+"public static boolean ejectItems(Level world,BlockPos pos,HopperBlockEntity hopper){Container c=getAttachedContainer(world,pos,hopper);return c!=null;}private static Container getAttachedContainer(Level world,BlockPos pos,HopperBlockEntity hopper){return getContainerAt(world,pos.relative(hopper.facing));}private static Container getContainerAt(Level world,BlockPos pos){return world.container;}public static boolean suckInItems(Level world,Hopper hopper){BlockPos pos=BlockPos.containing(hopper.getLevelX(),hopper.getLevelY()+1,hopper.getLevelZ());BlockState state=world.getBlockState(pos);Container c=getSourceContainer(world,hopper,pos,state);return c!=null;}private static Container getSourceContainer(Level world,Hopper hopper,BlockPos pos,BlockState state){return world.container;}}";
		String current=header+"public static boolean ejectItems(Level world,BlockPos pos,HopperBlockEntity hopper){ContainerOrHandler c=getContainerOrHandlerAt(world,pos.relative(hopper.facing),hopper.facing.getOpposite());if(c.isEmpty())return false;return VanillaInventoryCodeHooks.insertHook(hopper,c);}private static ContainerOrHandler getContainerOrHandlerAt(Level world,BlockPos pos,Direction direction){return new ContainerOrHandler(world.container,world.handler);}public static boolean suckInItems(Level world,Hopper hopper){BlockPos pos=BlockPos.containing(hopper.getLevelX(),hopper.getLevelY()+1,hopper.getLevelZ());BlockState state=world.getBlockState(pos);ContainerOrHandler c=getSourceContainerOrHandler(world,hopper,pos,state);if(c.container()!=null){world.nativeCalls++;return true;}if(c.itemHandler()!=null)return VanillaInventoryCodeHooks.extractHook(hopper,c.itemHandler());if(hopper.isGridAligned())return false;return false;}private static ContainerOrHandler getSourceContainerOrHandler(Level world,Hopper hopper,BlockPos pos,BlockState state){return new ContainerOrHandler(world.container,world.handler);}}";
		sources.put("net.minecraft.world.level.block.entity.HopperBlockEntity",original);
		sources.put("audit.Probe","""
				package audit;
				import net.minecraft.world.level.Level;import net.minecraft.world.level.block.entity.*;import net.minecraft.core.BlockPos;
				public class Probe{public static Level level;public void run(){
				 for(String mode:new String[]{"insert","extract","failed","absent","container","handler"}){
				  level=new Level();HopperBlockEntity hopper=new HopperBlockEntity();
				  if(!mode.equals("absent"))level.fabric=new net.fabricmc.fabric.api.transfer.v1.storage.Storage(){Object value=mode.equals("extract")?"source":null;public Object item(){return value;}public void item(Object v){value=v;}};
				  if(mode.equals("extract"))hopper.item(null);if(mode.equals("failed"))level.success=false;
				  if(mode.equals("container"))level.container=new HopperBlockEntity();if(mode.equals("handler"))level.handler=new net.neoforged.neoforge.transfer.ResourceHandler();
				  boolean result=mode.equals("extract")?HopperBlockEntity.suckInItems(level,hopper):HopperBlockEntity.ejectItems(level,new BlockPos(0,0,0),hopper);
				  System.out.println("[NullableActual] mode="+mode+" result="+result+" lookups="+level.lookups+" transactions="+level.transactions+" native="+level.nativeCalls+" kernel="+level.kernelCalls+" item="+hopper.item());
				 }
				}}
				""");
		List<Path> common=write(sources);Path referenceJar=WeaveHarness.fixture(work,"native",common,Map.of(),List.of("-g"));byte[] reference;
		try(ZipFile jar=new ZipFile(referenceJar.toFile())){reference=jar.getInputStream(jar.getEntry("net/minecraft/world/level/block/entity/HopperBlockEntity.class")).readAllBytes();}
		sources.put("net.minecraft.world.level.block.entity.HopperBlockEntity",current);common=write(sources);
		Path api=Path.of(System.getProperty("forbric.fabricApi"));net.forbric.kernel.TestFixtures.requireFiles(net.forbric.kernel.TestFixtures.Fixture.STAGED,"actual upstream callback required",api);byte[] upstream=null;
		try(ZipFile outer=new ZipFile(api.toFile())){var module=outer.stream().filter(e->e.getName().startsWith("META-INF/jars/fabric-transfer-api-v1-")).findFirst().orElseThrow();try(ZipInputStream nested=new ZipInputStream(outer.getInputStream(module))){for(var entry=nested.getNextEntry();entry!=null;entry=nested.getNextEntry())if(entry.getName().equals("net/fabricmc/fabric/mixin/transfer/HopperBlockEntityMixin.class"))upstream=nested.readAllBytes();}}
		assertNotNull(upstream);Path mixin=work.resolve("actual-mixin.class");Files.write(mixin,upstream);Path binary=work.resolve("native.bin"),index=work.resolve("index.tsv");Files.write(binary,reference);Files.writeString(index,"# forbric-native-reference-v1\nnet/minecraft/world/level/block/entity/HopperBlockEntity\t"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(reference))+"\n");
		String config="nullable.mixins.json";Path json=work.resolve(config);Files.writeString(json,"{\"required\":true,\"compatibilityLevel\":\"JAVA_25\",\"package\":\"net.fabricmc.fabric.mixin.transfer\",\"mixins\":[\"HopperBlockEntityMixin\"],\"injectors\":{\"defaultRequire\":1}}");
		Path mutation=work.resolve("Mutation.java");Files.writeString(mutation,"package audit.mutation;import net.neoforged.neoforge.transfer.item.ContainerOrHandler;import org.spongepowered.asm.mixin.Mixin;import org.spongepowered.asm.mixin.injection.*;import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;@Mixin(ContainerOrHandler.class)public class Mutation{@Inject(method=\"isEmpty\",at=@At(\"HEAD\"),cancellable=true)private void change(CallbackInfoReturnable<Boolean>ci){ci.setReturnValue(false);}}");
		common.add(mutation);Path mutationConfig=work.resolve("changed-record.mixins.json");Files.writeString(mutationConfig,"{\"required\":true,\"package\":\"audit.mutation\",\"mixins\":[\"Mutation\"]}");
		Path fixture=WeaveHarness.fixture(work,"actual",common,Map.of(config,json,"changed-record.mixins.json",mutationConfig,"net/fabricmc/fabric/mixin/transfer/HopperBlockEntityMixin.class",mixin,"META-INF/forbric/native-reference/FABRIC/index.tsv",index,"META-INF/forbric/native-reference/FABRIC/net/minecraft/world/level/block/entity/HopperBlockEntity.class.bin",binary),List.of("-g"));
		fixture=withActualFallback(fixture);
		WeaveHarness.Result result=WeaveHarness.run(work,"on",fixture,List.of(new WeaveHarness.Config(config,"nullableactual",Ecosystem.FABRIC)),List.of(),EnvType.SERVER,"audit.Probe","run",Map.of());
		for(String expected:List.of("mode=insert result=true lookups=1 transactions=1 native=0 kernel=0 item=null","mode=extract result=true lookups=1 transactions=1 native=0 kernel=0 item=source","mode=failed result=false lookups=1 transactions=1 native=0 kernel=0 item=item","mode=absent result=false lookups=1 transactions=0 native=0 kernel=0 item=item","mode=container result=true lookups=0 transactions=0 native=1 kernel=0 item=item","mode=handler result=true lookups=0 transactions=0 native=1 kernel=0 item=item"))assertTrue(result.printed(expected),result.describe());
		WeaveHarness.Result changed=WeaveHarness.run(work,"changed-record",fixture,List.of(new WeaveHarness.Config(config,"nullableactual",Ecosystem.FABRIC),new WeaveHarness.Config("changed-record.mixins.json","nativechange",Ecosystem.NEOFORGE)),List.of(),EnvType.SERVER,"audit.Probe","run",Map.of());
		assertTrue(changed.printed("mode=insert result=true lookups=0 transactions=0 native=1 kernel=0 item=item"),changed.describe());
		assertTrue(changed.printed("source fallback callback was not executed"),changed.describe());
		assertTrue(result.findings().stream().noneMatch(f->f.confirmedRequired()),result.describe());WeaveHarness.assertWovenAndVerified(result,"net/minecraft/world/level/block/entity/HopperBlockEntity",fixture);
		WeaveHarness.Result off=WeaveHarness.run(work,"off",fixture,List.of(new WeaveHarness.Config(config,"nullableactual",Ecosystem.FABRIC)),List.of(),EnvType.SERVER,"audit.Probe","run",Map.of(MixinNullableCompositeCallback.PROPERTY,"off"));
		assertTrue(off.printed("mode=insert result=false lookups=0 transactions=0 native=0 kernel=1 item=item"),off.describe());assertFalse(off.printed("mode=insert result=true"),off.describe());
	}
	private Path withActualFallback(Path original)throws Exception{
		Path patched=work.resolve("with-fallback.jar");String name="net/minecraft/world/level/block/entity/HopperBlockEntity.class";
		try(ZipFile input=new ZipFile(original.toFile());java.util.jar.JarOutputStream output=new java.util.jar.JarOutputStream(Files.newOutputStream(patched))){for(var entry:input.stream().toList()){byte[] bytes=input.getInputStream(entry).readAllBytes();if(entry.getName().equals(name)){byte[] changed=new HopperFabricStorageInjector().transform(name.substring(0,name.length()-6).replace('/','.'),bytes,null);assertNotSame(bytes,changed,"the real conditional boot fallback must be present in this fixture");bytes=changed;}output.putNextEntry(new java.util.jar.JarEntry(entry.getName()));output.write(bytes);output.closeEntry();}}
		return patched;
	}
	private List<Path> write(Map<String,String> sources)throws Exception{List<Path> files=new ArrayList<>();for(var entry:sources.entrySet()){Path file=work.resolve("src").resolve(entry.getKey().replace('.','/')+".java");Files.createDirectories(file.getParent());Files.writeString(file,entry.getValue());files.add(file);}return files;}
}
