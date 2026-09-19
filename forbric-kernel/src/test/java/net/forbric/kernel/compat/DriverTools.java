package net.forbric.kernel.compat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real Python processes, with bounded output collection and no dependency on the developer's environment. */
final class DriverTools {
    static final Path COMPAT = Path.of("run", "compat").toAbsolutePath();
    record Result(int exit, String output) { }

    static Result run(Map<String, String> environment, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(System.getenv().getOrDefault("PYTHON", "python3"));
        command.addAll(List.of(arguments));
        Path output = Files.createTempFile("compat-driver-output", ".txt");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile());
        for (String variable : List.of("MODRINTH_API", "EXCLUDE_MANIFEST", "EXCLUDE", "SEED", "WANT_FABRIC", "WANT_NEO", "WANT_FORGE",
                "FORBRIC_MC", "FORBRIC_VERSION", "FORBRIC_INSTANCE", "FORBRIC_WORLD", "FORBRIC_JAVA", "FORBRIC_NATIVES")) {
            builder.environment().remove(variable);
        }
        builder.environment().putAll(environment);
        Process process = builder.start();
        try {
            boolean finished = process.waitFor(45, TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            assertTrue(finished, "script timed out: " + String.join(" ", command));
            return new Result(process.exitValue(), Files.readString(output, StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(output);
        }
    }

    static Result script(String filename, Map<String, String> environment, String... arguments) throws Exception {
        List<String> all = new ArrayList<>();
        all.add(COMPAT.resolve(filename).toString());
        all.addAll(List.of(arguments));
        return run(environment, all.toArray(String[]::new));
    }
}
