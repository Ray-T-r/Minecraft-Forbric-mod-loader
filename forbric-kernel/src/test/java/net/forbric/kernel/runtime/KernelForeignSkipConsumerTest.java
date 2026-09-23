package net.forbric.kernel.runtime;
import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.lang.reflect.*;
import org.junit.jupiter.api.Test;
/** Actual compiled filter + real DataResult. The baseline consumer demonstrates the cast failure. */
class KernelForeignSkipConsumerTest {
 @Test void knownMarkerSkipsDataWhileNormalOptionalAndDecodeErrorsRemainIntact()throws Exception{
  Path game=Path.of(System.getenv().getOrDefault("FORBRIC_OLD","../forbric-loader"),"run/merged-base/patched-mc-merged-26.2.jar");
  Path compiled=Path.of("build/classes/java/runtime");org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(game)&&Files.exists(compiled),"actual game-side compilation required");
  Path mc=Path.of(System.getenv().getOrDefault("MC_DIR",System.getProperty("user.home")+"/Library/Application Support/minecraft"));
  var json=com.electronwill.nightconfig.json.JsonFormat.fancyInstance().createParser().parse(Files.newBufferedReader(mc.resolve("versions/26.2/26.2.json")));
  List<URL> urls=new ArrayList<>(List.of(compiled.toUri().toURL(),game.toUri().toURL()));
  List<? extends com.electronwill.nightconfig.core.UnmodifiableConfig> libraries=json.get("libraries");
  for(var library:libraries){String name=library.get(List.of("downloads","artifact","path"));if(name!=null&&Files.isRegularFile(mc.resolve("libraries").resolve(name)))urls.add(mc.resolve("libraries").resolve(name).toUri().toURL());}
  try(URLClassLoader loader=new URLClassLoader(urls.toArray(URL[]::new),getClass().getClassLoader())){
   Class<?> result=Class.forName("com.mojang.serialization.DataResult",true,loader),helper=Class.forName("net.forbric.kernel.runtime.KernelFabricConditions",true,loader);
   Method success=result.getMethod("success",Object.class),ifSuccess=result.getMethod("ifSuccess",Consumer.class),filter=helper.getMethod("ifSuccessWithoutAForeignSkipMarker",result,Consumer.class);
   AtomicInteger inserted=new AtomicInteger();Consumer<Object> consumer=value->{Optional<?> optional=(Optional<?>)value;if(optional.isPresent())inserted.incrementAndGet();};
   Object marker=success.invoke(null,new Object());var error=assertThrows(InvocationTargetException.class,()->ifSuccess.invoke(marker,consumer));assertInstanceOf(ClassCastException.class,error.getCause());
   filter.invoke(null,marker,consumer);assertEquals(0,inserted.get());
   Object normal=success.invoke(null,Optional.of("actual data"));assertSame(normal,filter.invoke(null,normal,consumer));assertEquals(1,inserted.get());
   Object empty=success.invoke(null,Optional.empty());assertSame(empty,filter.invoke(null,empty,consumer));assertEquals(1,inserted.get());
   Object failed=result.getMethod("error",Supplier.class).invoke(null,(Supplier<String>)()->"decode failure");assertSame(failed,filter.invoke(null,failed,consumer));assertEquals(1,inserted.get());
  }
 }
}
