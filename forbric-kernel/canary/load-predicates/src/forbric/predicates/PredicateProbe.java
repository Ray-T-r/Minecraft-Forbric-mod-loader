package forbric.predicates;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.GsonBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Three configured features in this mod's data: one gated by a lithostitched predicate that fails (an absent mod), one
 * by a predicate that holds (this mod), one with none. Which ones the server's registry holds says whether
 * lithostitched's predicate was read.
 */
@Mod("forbricpredicates")
public final class PredicateProbe {
	public PredicateProbe(IEventBus bus) {
		NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> {
			var server = event.getServer();
			try {
				var features = server.registryAccess().lookupOrThrow(Registries.CONFIGURED_FEATURE);
				Map<String, Object> report = new LinkedHashMap<>();
				report.put("phase", System.getProperty("forbric.predicatePhase"));
				for (String name : new String[] {"gated", "kept", "plain"}) {
					report.put(name, features.get(ResourceKey.create(Registries.CONFIGURED_FEATURE,
							Identifier.fromNamespaceAndPath("forbricpredicates", name))).isPresent());
				}
				Path path = Path.of(System.getProperty("forbric.predicateProbe"));
				Files.createDirectories(path.toAbsolutePath().getParent());
				Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(report));
				System.out.println("[M50Predicate] RESULT " + report);
			} catch (Exception failure) {
				failure.printStackTrace();
			} finally {
				server.halt(false);
			}
		});
	}
}
