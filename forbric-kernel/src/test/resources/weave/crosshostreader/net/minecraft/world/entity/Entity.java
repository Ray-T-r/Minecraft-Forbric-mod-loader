package net.minecraft.world.entity;public class Entity{public boolean submerged;public boolean pooled;public Entity(){}
 public boolean isEyeInFluid(net.minecraft.tags.TagKey tag){fixture.crosshostreader.Trace.add("eye:"+tag.id());return tag.id().equals("water")?submerged:pooled;}}
