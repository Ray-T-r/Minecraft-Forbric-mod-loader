package net.forbric.kernel.boot;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class M9CompatibilityReportContractTest {
 @TempDir Path root;
 @Test void actualGateRejectsContinuationRequiredLossMissingAndStaleReports()throws Exception {
  Path dir=Files.createDirectory(root.resolve(".forbric-kernel")),report=dir.resolve("compatibility-report.json");
  assertEquals(1,run(0),"a missing report cannot pass");
  Files.writeString(report,"{\"schemaVersion\":1,\"policy\":\"STRICT\",\"confirmedRequired\":0,\"findings\":[],\"catalogFailures\":[]}");
  assertEquals(0,run(0));assertEquals(1,run(Long.MAX_VALUE),"previous-run evidence cannot pass");
  Files.writeString(report,Files.readString(report).replace("STRICT","CONTINUE"));assertEquals(1,run(0));
  Files.writeString(report,"{\"schemaVersion\":1,\"policy\":\"STRICT\",\"confirmedRequired\":1,\"findings\":[{\"id\":\"lost\",\"confidence\":\"CONFIRMED\",\"required\":true}]}");assertEquals(1,run(0));
  Files.writeString(report,Files.readString(report).replace("\"confirmedRequired\":1","\"confirmedRequired\":0"));assertEquals(1,run(0),"a contradictory aggregate is not a clean report");
 }
 private int run(long start)throws Exception {
  String script=Files.readString(Path.of("run/gate-m9-client.sh"));String begin="# M9_COMPATIBILITY_REPORT_BEGIN",end="# M9_COMPATIBILITY_REPORT_END";
  String section=script.substring(script.indexOf(begin)+begin.length(),script.indexOf(end));
  ProcessBuilder builder=new ProcessBuilder("bash","-c","FAIL=0\n"+section+"\nexit \"$FAIL\"");builder.environment().put("RUNDIR",root.toString());builder.environment().put("COMPAT_STARTED_NS",Long.toString(start));
  Process p=builder.redirectErrorStream(true).redirectOutput(root.resolve("gate-check.log").toFile()).start();assertTrue(p.waitFor(20,java.util.concurrent.TimeUnit.SECONDS));return p.exitValue();
 }
}
