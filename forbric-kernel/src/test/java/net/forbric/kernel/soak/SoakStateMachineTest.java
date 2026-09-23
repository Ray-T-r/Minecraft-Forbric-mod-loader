package net.forbric.kernel.soak;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import net.forbric.kernel.soak.SoakStateMachine.*;
class SoakStateMachineTest {
 private Config config(boolean control,long seconds){return new Config(seconds,control,20,2,2,0,0,0,5);}
 private Sample sample(long now,int server,int tick,int point,boolean paused,boolean occupied){boolean[] loaded=new boolean[6];if(point>=0)loaded[point]=true;return new Sample(now,server,tick,tick,paused,occupied,point,loaded);}
 @Test void releaseCannotBeShortenedByAccident(){assertThrows(IllegalArgumentException.class,()->config(false,7199));assertDoesNotThrow(()->config(false,7200));}
 @Test void idleClockDoesNotEarnActiveTime(){var m=new SoakStateMachine(config(true,1),0);m.joined(sample(0,1,0,-1,false,true));m.observe(sample(4_000_000_000L,1,0,-1,false,true));assertEquals(0,m.activeNanos());assertEquals(0,m.actualTicks());assertEquals(Kind.STOP,m.heartbeat(6_000_000_000L).kind());assertEquals(State.FAILED,m.state());}
 @Test void pausedAndUnoccupiedCountersDoNotEarnSimulation(){for(boolean pause:new boolean[]{true,false}){var m=new SoakStateMachine(config(true,1),0);m.joined(sample(0,1,0,-1,false,true));m.observe(sample(1_000_000_000L,1,20,-1,pause,pause));assertEquals(0,m.activeNanos());assertEquals(0,m.actualTicks());}}
 @Test void activeClockIsCappedByActualTicks(){var m=new SoakStateMachine(config(true,1),0);m.joined(sample(0,1,0,-1,false,true));m.observe(sample(4_000_000_000L,1,1,-1,false,true));assertEquals(50_000_000L,m.activeNanos());assertEquals(1,m.actualTicks());assertFalse(m.activityComplete());}
 @Test void aDifferentServerOrRegressedCounterFails(){for(boolean different:new boolean[]{true,false}){var m=new SoakStateMachine(config(true,1),0);m.joined(sample(0,1,20,-1,false,true));m.observe(sample(1_000_000_000L,different?2:1,different?40:19,-1,false,true));assertEquals(State.FAILED,m.state());}}
 @Test void fullRouteUnloadsReloadsAndReopensBeforeAControlCanPass(){
  var m=new SoakStateMachine(config(true,1),0);long now=0;
  for(int server=1;server<=2;server++){
   m.joined(sample(now,server,0,-1,false,true));int tick=0,point=-1;
   for(int i=0;i<200&&m.state()==State.RUNNING;i++){
    now+=1_000_000_000L;tick+=20;Action action=m.observe(sample(now,server,tick,point,false,true));if(action.kind()==Kind.MOVE)point=action.point();
   }
   assertEquals(State.WAIT_DISCONNECT,m.state());m.disconnected(now);Action a=m.heartbeat(now);
   assertEquals(server==1?Kind.OPEN_WORLD:Kind.STOP,a.kind());
  }
  assertEquals(State.FINISHED,m.state());assertTrue(m.activityComplete());assertEquals(2,m.sessions());
  for(long n:m.unloads())assertTrue(n>0);for(long n:m.reloads())assertTrue(n>0);
 }
 @Test void missingChunkActivityCannotBeReplacedByElapsedTime(){var m=new SoakStateMachine(config(true,1),0);m.joined(sample(0,1,0,-1,false,true));for(int i=1;i<=5;i++)m.observe(sample(i*1_000_000_000L,1,i*20,-1,false,true));assertTrue(m.activeNanos()>=1_000_000_000L);assertFalse(m.activityComplete());}
 @Test void closingAndOpeningHaveBoundedTimeouts(){var m=new SoakStateMachine(config(true,1),0);assertEquals(Kind.STOP,m.heartbeat(6_000_000_000L).kind());assertEquals(State.FAILED,m.state());}
 @Test void aProbeThatIsNeverReachedFailsInsteadOfWaitingForTheLauncher(){var m=new SoakStateMachine(config(true,1),0);m.joined(sample(0,1,0,-1,false,true));Action a=m.observe(sample(1_000_000_000L,1,20,-1,false,true));assertEquals(Kind.MOVE,a.kind());
  for(int i=2;i<=6&&m.state()==State.RUNNING;i++)m.observe(sample(i*1_000_000_000L,1,i*20,-1,false,true));
  assertEquals(State.RUNNING,m.state(),"still inside the arrival bound");assertEquals(Kind.NONE,m.heartbeat(6_000_000_000L).kind(),"ticks advance, so the stall timeout cannot see it");
  assertEquals(Kind.STOP,m.observe(sample(7_000_000_000L,1,140,-1,false,true)).kind());assertEquals(State.FAILED,m.state());assertTrue(m.failure().contains("probe 0"));}
}
