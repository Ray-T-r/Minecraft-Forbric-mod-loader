package forbric.outcome;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
@Mod("forbricoutcome")
public final class OutcomeCanary {
 private boolean armed; private int ticks;
 public OutcomeCanary(){
  NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class,e->{armed=true;System.out.println("[M36Outcome] armed after server started");});
  NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class,e->{
   if(!armed)return;
   if(++ticks==1){new OutcomeTarget().run();System.out.println("[M36Outcome] first live tick complete");}
   if(ticks==3){System.out.println("[M36Outcome] reached third tick");e.getServer().halt(false);}
  });
 }
}
