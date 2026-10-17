package forbric.entitycallbacks;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.BedBlock;
/**
 * A NeoForge mod's own bed that keeps vanilla's occupancy: it inherits NeoForge's default {@code setBedOccupied}, which writes
 * vanilla's OCCUPIED property with {@code Level.setBlock}, as a Fabric mod's own bed inherits vanilla's write.
 */
public final class InheritingBed extends BedBlock {
 public InheritingBed(Properties properties){super(DyeColor.BLUE,properties);}
}
