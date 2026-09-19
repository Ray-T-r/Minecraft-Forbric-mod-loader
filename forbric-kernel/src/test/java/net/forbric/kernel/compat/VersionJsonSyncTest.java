package net.forbric.kernel.compat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VersionJsonSyncTest {
    @TempDir Path temp;

    @Test void updatesFourArtifactsByCoordinatePreservingOrderAndUnrelatedLibrary() throws Exception {
        Path json = fixture();
        Path original = temp.resolve("original.json");
        Files.copy(json, original);
        var result = DriverTools.script("version-json-sync.py", Map.of(), arguments(json, false));
        assertEquals(0, result.exit(), result.output());
        result = DriverTools.run(Map.of(), "-c", """
                import hashlib,json,pathlib,sys
                before=json.load(open(sys.argv[1])); after=json.load(open(sys.argv[2]))
                assert list(before)==list(after)
                assert [x['name'] for x in before['libraries']]==[x['name'] for x in after['libraries']]
                assert before['libraries'][2]==after['libraries'][2]
                for row in after['libraries']:
                    if row['name'].startswith('test:'):
                        name=row['name'].split(':')[1]
                        data=(pathlib.Path(sys.argv[2]).parent/(name+'.jar')).read_bytes()
                        assert row['downloads']['artifact']['sha1']==hashlib.sha1(data).hexdigest()
                        assert row['downloads']['artifact']['size']==len(data)
                        row['downloads']['artifact']['sha1']='old'
                        row['downloads']['artifact']['size']=99
                assert before==after
                """, original.toString(), json.toString());
        assertEquals(0, result.exit(), result.output());
    }

    @Test void missingCoordinateExitsTwoWithoutWritingAnyPartialChanges() throws Exception {
        Path json = fixture();
        byte[] original = Files.readAllBytes(json);
        var result = DriverTools.script("version-json-sync.py", Map.of(), arguments(json, true));
        assertEquals(2, result.exit(), result.output());
        assertTrue(result.output().contains("missing library name(s): test:missing"), result.output());
        assertArrayEquals(original, Files.readAllBytes(json));
    }

    private Path fixture() throws Exception {
        StringBuilder libraries = new StringBuilder();
        for (String name : List.of("a", "b", "other", "c", "d")) {
            if (!libraries.isEmpty()) libraries.append(',');
            libraries.append("{\"name\":\"").append(name.equals("other") ? "external:" : "test:")
                    .append(name).append(":77\",\"downloads\":{\"artifact\":{\"path\":\"keep/this.jar\",\"sha1\":\"old\",\"size\":99}},\"rules\":[{\"action\":\"allow\"}]}");
            Files.writeString(temp.resolve(name + ".jar"), "bytes " + name);
        }
        Path json = temp.resolve("profile.json");
        Files.writeString(json, "{\"id\":\"fixture\",\"libraries\":[" + libraries + "],\"arguments\":{\"game\":[\"unchanged\"]}}");
        return json;
    }

    private String[] arguments(Path json, boolean missing) {
        List<String> arguments = new ArrayList<>(List.of(json.toString()));
        for (String name : List.of("a", "b", "c", "d")) {
            arguments.add("--artifact");
            arguments.add("test:" + (missing && name.equals("d") ? "missing" : name) + "=" + temp.resolve(name + ".jar"));
        }
        return arguments.toArray(String[]::new);
    }
}
