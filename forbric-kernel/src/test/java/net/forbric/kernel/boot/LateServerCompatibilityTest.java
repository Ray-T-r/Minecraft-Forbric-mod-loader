package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.forbric.api.*;
import net.forbric.kernel.ui.CompatibilityDecision;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.ResourceLock;

@ResourceLock("ModCatalog") @ResourceLock("system-properties")
class LateServerCompatibilityTest {
 @BeforeEach @AfterEach void reset(){CompatibilityFindings.reset();CompatibilityDecision.reset();LateServerCompatibility.reset();System.clearProperty(CompatibilityDecision.PROPERTY);ModCatalog.publish(List.of());}
 @Test void aLateRequiredFindingRequestsOneNormalHaltAtTheNextBoundary(){
  Object server=new Object();AtomicInteger stopped=new AtomicInteger();System.setProperty(CompatibilityDecision.PROPERTY,"strict");
  LateServerCompatibility.tick(server,true,stopped::incrementAndGet);record(true,CompatibilityFinding.Confidence.CONFIRMED);
  assertEquals(0,stopped.get(),"recording from an event must not halt or exit");
  LateServerCompatibility.tick(server,true,stopped::incrementAndGet);LateServerCompatibility.tick(server,true,stopped::incrementAndGet);assertEquals(1,stopped.get());
 }
 @Test void integratedServerDefersToClientDecision(){record(true,CompatibilityFinding.Confidence.CONFIRMED);AtomicInteger stopped=new AtomicInteger();LateServerCompatibility.tick(new Object(),false,stopped::incrementAndGet);assertEquals(0,stopped.get());}
 @Test void headlessAskIsStrictAndExplicitContinueRetainsTheEvidence(){
  record(true,CompatibilityFinding.Confidence.CONFIRMED);AtomicInteger stopped=new AtomicInteger();LateServerCompatibility.tick(new Object(),true,stopped::incrementAndGet);assertEquals(1,stopped.get());
  System.setProperty(CompatibilityDecision.PROPERTY,"continue");Object server=new Object();LateServerCompatibility.tick(server,true,stopped::incrementAndGet);assertEquals(1,stopped.get());assertEquals(1,CompatibilityFindings.confirmedRequired().size());
  System.setProperty(CompatibilityDecision.PROPERTY,"strict");LateServerCompatibility.tick(server,true,stopped::incrementAndGet);assertEquals(2,stopped.get(),"a prior continue cannot make strict acceptance pass");
 }
 @Test void suspicionAndOptionalLossDoNotStopTheServer(){AtomicInteger stopped=new AtomicInteger();Object server=new Object();record(true,CompatibilityFinding.Confidence.SUSPECTED);LateServerCompatibility.tick(server,true,stopped::incrementAndGet);record(false,CompatibilityFinding.Confidence.CONFIRMED);LateServerCompatibility.tick(server,true,stopped::incrementAndGet);assertEquals(0,stopped.get());}
 private void record(boolean required,CompatibilityFinding.Confidence confidence){CompatibilityFindings.record(new CompatibilityFinding("late","probe","test feature","test",confidence,required,"late test",List.of()));}
}
