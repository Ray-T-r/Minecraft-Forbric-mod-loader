package net.forbric.kernel.compat;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class PickModsTest {
    @TempDir Path temp;
    private static final byte[] JAR = "fixture jar bytes".getBytes(StandardCharsets.UTF_8);

    @Test void seededSelectionIsDeterministicAndExcludesPreviousManifest() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Path excluded = temp.resolve("previous.json");
            Files.writeString(excluded, "[{\"slug\":\"mod-0\"},{\"slug\":\"mod-1\"},{\"slug\":\"mod-2\"}]");
            Map<String, String> environment = Map.of("MODRINTH_API", fixture.api(), "SEED", "20260919",
                    "EXCLUDE_MANIFEST", excluded.toString(), "WANT_FABRIC", "4", "WANT_NEO", "0", "WANT_FORGE", "0");
            for (String run : new String[]{"one", "two"}) {
                var result = DriverTools.script("pick_mods.py", environment, temp.resolve(run).resolve("mods").toString(), "--resolve-only");
                assertEquals(0, result.exit(), result.output());
            }
            String first = Files.readString(temp.resolve("one/manifest.json"));
            assertEquals(first, Files.readString(temp.resolve("two/manifest.json")));
            for (int i = 0; i < 3; i++) assertFalse(first.contains("\"slug\": \"mod-" + i + "\""), first);
            assertEquals(4, first.split("\"kind\": \"picked\"", -1).length - 1);
            assertEquals(0, fixture.downloads.get(), "--resolve-only must never fetch jars");
            assertFalse(Files.exists(temp.resolve("one/mods")));
        }
    }

    @Test void namedResolutionReportsUnavailableAndNeverSubstitutesNeoForgeForForge() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var result = DriverTools.script("pick_mods.py", Map.of("MODRINTH_API", fixture.api()),
                    temp.resolve("mods").toString(), "--slugs", "mod-3=fabric,absent=forge,mod-4=forge", "--resolve-only");
            assertEquals(2, result.exit(), result.output());
            assertTrue(result.output().contains("RESOLVED picked mod-3=fabric"), result.output());
            assertTrue(result.output().contains("UNAVAILABLE absent=forge"), result.output());
            assertTrue(result.output().contains("UNAVAILABLE mod-4=forge"), result.output());
            assertEquals(0, fixture.downloads.get());
        }
    }

    @Test void downloadChecksDigestAndSanitizesWindowsFilenames() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var result = DriverTools.script("pick_mods.py", Map.of("MODRINTH_API", fixture.api()),
                    temp.resolve("mods").toString(), "--slugs", "mod-3=fabric");
            assertEquals(0, result.exit(), result.output());
            assertArrayEquals(JAR, Files.readAllBytes(temp.resolve("mods/mod-3_.jar")));
            assertTrue(Files.readString(temp.resolve("manifest.json")).contains("\"sha1_ok\": true"));
            fixture.corrupt = true;
            result = DriverTools.script("pick_mods.py", Map.of("MODRINTH_API", fixture.api()),
                    temp.resolve("bad/mods").toString(), "--slugs", "mod-3=fabric");
            assertEquals(2, result.exit(), result.output());
            assertTrue(result.output().contains("hash/size mismatch"), result.output());
            assertFalse(Files.exists(temp.resolve("bad/mods/mod-3_.jar")));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger downloads = new AtomicInteger();
        volatile boolean corrupt;
        Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(JAR));
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                byte[] body;
                if (path.startsWith("/files/")) {
                    downloads.incrementAndGet();
                    body = corrupt ? new byte[]{0} : JAR;
                } else if (path.equals("/v2/search")) {
                    String hits = IntStream.range(0, 8).mapToObj(i -> "{\"slug\":\"mod-" + i + "\",\"project_id\":\"mod-" + i + "\"}")
                            .collect(Collectors.joining(","));
                    body = ("{\"total_hits\":8,\"hits\":[" + hits + "]}").getBytes(StandardCharsets.UTF_8);
                } else if (path.endsWith("/version")) {
                    String slug = path.split("/")[3];
                    String version = slug.equals("absent") ? "[]" : "[{\"project_id\":\"" + slug + "\",\"id\":\"" + slug
                            + "-v\",\"version_number\":\"1\",\"loaders\":[\"fabric\"],\"game_versions\":[\"26.2\"],\"files\":[{\"primary\":true,"
                            + "\"filename\":\"" + slug + "?.jar\",\"size\":" + JAR.length + ",\"hashes\":{\"sha1\":\"" + hash
                            + "\"},\"url\":\"" + api().replace("/v2", "") + "/files/" + slug + ".jar\"}]}]";
                    body = version.getBytes(StandardCharsets.UTF_8);
                } else {
                    body = "{}".getBytes(StandardCharsets.UTF_8);
                }
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
        }
        String api() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/v2"; }
        @Override public void close() { server.stop(0); }
    }
}
