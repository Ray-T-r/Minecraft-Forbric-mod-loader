package forbric.behavior;

import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.storage.ValueInput;

/** A real registered entity type with deterministic spawn predicates and an observed, real super finalizer. */
public final class ProbeMob extends Mob {
    public int finalizations, worldTicks;
    public SpawnGroupData finalizedWith;
    public ValueInput loadedInput;
    public ProbeMob(EntityType<? extends ProbeMob> type, Level level) {
        super(type, level); setNoAi(true); setNoGravity(true); setInvulnerable(true);
        if (WorldProbe.activeSpawner != null) WorldProbe.created.add(this);
    }
    @Override protected void readAdditionalSaveData(ValueInput input) {
        loadedInput = input; super.readAdditionalSaveData(input);
    }
    @Override public boolean checkSpawnRules(LevelAccessor level, EntitySpawnReason reason) { return true; }
    @Override public boolean checkSpawnObstruction(LevelReader level) { return true; }
    @Override public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty,
                                                  EntitySpawnReason reason, SpawnGroupData data) {
        finalizations++; finalizedWith = data;
        super.finalizeSpawn(level, difficulty, reason, data);
        return WorldProbe.RETURNED_DATA; // Both native BaseSpawner callers discard this result.
    }
    @Override public void tick() { worldTicks++; super.tick(); }
}
