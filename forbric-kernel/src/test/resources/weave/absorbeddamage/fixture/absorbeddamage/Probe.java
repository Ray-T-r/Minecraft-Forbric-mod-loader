package fixture.absorbeddamage;
/** One hit per case: what the guest saw, and what the health and the absorption became. */
public final class Probe{
 public void probe(){
  StringBuilder out=new StringBuilder();
  Object[][]cases={{"plain",0f,4f,1f},{"halved",0f,4f,0.5f},{"absorbed-halved",2f,4f,0.5f},{"absorbed-plain",2f,4f,1f},{"all-absorbed",6f,4f,0.5f}};
  for(Object[]c:cases){
   org.quill.mend.Mend.factor=(Float)c[3];
   net.minecraft.world.entity.LivingEntity body=new net.minecraft.world.entity.LivingEntity();body.absorption=(Float)c[1];
   Trace.LINES.clear();body.hit(new net.minecraft.server.level.ServerLevel(),new net.minecraft.world.damagesource.DamageSource("generic"),(Float)c[2]);
   out.append(out.length()==0?"":" | ").append(c[0]).append(": ").append(String.join(", ",Trace.LINES)).append(", end health=").append(body.health).append(" absorption=").append(body.absorption);
  }
  System.out.println("[AbsorbedDamage] "+out);
 }
}
