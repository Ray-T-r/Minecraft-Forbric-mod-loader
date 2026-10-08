/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.loader.impl.launch;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyGuestContractTest {
    @TempDir Path directory;
    @Test void discoveredForeignConfigNamesDoNotReceiveSuppressionOrRelaxation()throws Exception{
        Path mods=Files.createDirectories(directory.resolve("mods"));
        jar(mods.resolve("runtime.jar"),Map.of("fabric.mod.json","{\"schemaVersion\":1,\"id\":\"forge\",\"version\":\"26.2\",\"mixins\":[\"fabric-unfamiliar.mixins.json\"]}","META-INF/mods.toml","modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\nlicense=\"test\"\n[[mods]]\nmodId=\"forge\"\nversion=\"26.2\"\n"));
        List<String> properties=List.of("forbric.suppressMixinConfigs","forbric.suppressMixins","forbric.relaxMixinOverwrites","forbric.downgradeInjectionErrors","forbric.forgeFamily");Map<String,String> previous=new HashMap<>();for(String property:properties)previous.put(property,System.getProperty(property));
        try{for(String property:properties)System.clearProperty(property);System.setProperty("forbric.suppressMixinConfigs","user-selected.json");System.setProperty("forbric.forgeFamily","forge");
            ForbricBootstrap.run(new String[]{"--gameDir",directory.toString()},"server");
            assertEquals("user-selected.json",System.getProperty("forbric.suppressMixinConfigs"));assertNull(System.getProperty("forbric.suppressMixins"));assertNull(System.getProperty("forbric.relaxMixinOverwrites"));assertNull(System.getProperty("forbric.downgradeInjectionErrors"));
        }finally{for(String property:properties){String value=previous.get(property);if(value==null)System.clearProperty(property);else System.setProperty(property,value);}}
    }
    @Test void unknownMutableStateObligationRefusesBeforeLegacyDefinitions()throws Exception{
        Path base=directory.resolve("base.jar");jar(base,Map.of(LegacyAncestorContracts.MANIFEST,"# forbric-required-ancestor-composition-v1\nunknown/Carrier\tunknown/Retained\tunknown/RemovedState\n"));
        IllegalStateException failure=assertThrows(IllegalStateException.class,()->LegacyAncestorContracts.verify(List.of(base)));assertTrue(failure.getMessage().contains("unknown/Carrier retains unknown/Retained"));assertTrue(failure.getMessage().contains("unknown/RemovedState"));
    }
    @Test void aBaseWithNoMutableAncestorObligationRemainsUsable()throws Exception{
        Path base=directory.resolve("base.jar");jar(base,Map.of(LegacyAncestorContracts.MANIFEST,"# forbric-required-ancestor-composition-v1\n"));assertDoesNotThrow(()->LegacyAncestorContracts.verify(List.of(base)));
    }
    private static void jar(Path path,Map<String,String> entries)throws Exception{try(ZipOutputStream out=new ZipOutputStream(Files.newOutputStream(path))){for(var entry:entries.entrySet()){out.putNextEntry(new ZipEntry(entry.getKey()));out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));out.closeEntry();}}}
}
