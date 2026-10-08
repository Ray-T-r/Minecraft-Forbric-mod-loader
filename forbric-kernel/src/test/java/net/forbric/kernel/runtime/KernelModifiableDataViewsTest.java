package net.forbric.kernel.runtime;
import static org.junit.jupiter.api.Assertions.*;
import java.net.URL;
import java.util.*;
import org.junit.jupiter.api.Test;
import net.forbric.api.VirtualProperties;
import net.forbric.kernel.classloading.ForbricClassLoader;
/** Executable native SDK objects, with no game initialization needed for their immutable record inputs. */
class KernelModifiableDataViewsTest {
    @Test void nativeOriginalIdentityAndLiveCanonicalChangesArePreserved()throws Exception{
        List<URL> urls=StagedGameClassLoader.urls();
        try(var parent=new java.net.URLClassLoader(urls.toArray(URL[]::new),getClass().getClassLoader());
            var loader=new ForbricClassLoader(urls.toArray(URL[]::new),parent)){
            for(String[] view:List.of(new String[]{"ModifiableBiomeInfo","BiomeInfo","biome", "getModifiedBiomeInfo"},new String[]{"ModifiableStructureInfo","StructureInfo","structure","getModifiedStructureInfo"})){
                String prefix="net.neoforged.neoforge.common.world."+view[0];Class<?> sdk=Class.forName(prefix,true,loader),record=Class.forName(prefix+"$"+view[1],true,loader);
                var recordCtor=record.getConstructors()[0];Object original=recordCtor.newInstance(new Object[recordCtor.getParameterCount()]);Object source=sdk.getConstructor(record).newInstance(original);
                Class<?> factory=Class.forName("net.forbric.kernel.runtime.KernelModifiableDataViews",true,loader);Object projected=factory.getMethod(view[2],sdk).invoke(null,source);
                Class<?> base=projected.getClass().getSuperclass();String originalGetter=view[3].replace("Modified","Original");
                Object stableOriginal=base.getMethod(originalGetter).invoke(projected);assertSame(stableOriginal,base.getMethod(originalGetter).invoke(projected));assertSame(stableOriginal,base.getMethod("get").invoke(projected));assertNull(base.getMethod(view[3]).invoke(projected));
                Object updated=recordCtor.newInstance(new Object[recordCtor.getParameterCount()]);set(sdk,view[3],record,source,updated);
                Object changed=base.getMethod(view[3]).invoke(projected);assertNotNull(changed);assertSame(changed,base.getMethod("get").invoke(projected));assertSame(stableOriginal,base.getMethod(originalGetter).invoke(projected));
                Object updatedAgain=recordCtor.newInstance(new Object[recordCtor.getParameterCount()]);set(sdk,view[3],record,source,updatedAgain);assertNotSame(changed,base.getMethod("get").invoke(projected),"a new canonical snapshot replaces the old projected snapshot");
                set(sdk,view[3],record,source,null);assertNull(base.getMethod(view[3]).invoke(projected));assertSame(stableOriginal,base.getMethod("get").invoke(projected));
            }
        }
    }
    @SuppressWarnings("unchecked") private static void set(Class<?> sdk,String getter,Class<?> record,Object target,Object value){VirtualProperties.set(sdk,getter,(Class<Object>)record,target,value);}
}
