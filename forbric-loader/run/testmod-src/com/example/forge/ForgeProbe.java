package com.example.forge;
import net.minecraft.world.phys.Vec3;
public class ForgeProbe {
  public Vec3 origin;
  public ForgeProbe(){ System.out.println(">>> [ForgeProbe] Forge @Mod-style class RUNNING; my game field type resolves to: " + Vec3.class.getName()); }
}
