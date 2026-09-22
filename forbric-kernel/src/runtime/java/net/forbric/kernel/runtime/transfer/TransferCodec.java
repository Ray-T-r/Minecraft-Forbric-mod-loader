package net.forbric.kernel.runtime.transfer;

import net.neoforged.neoforge.transfer.resource.Resource;

/** Resource identity conversion. A null conversion means unrepresentable, never "discard its metadata". */
public interface TransferCodec<F, N extends Resource> {
	N toNeo(F resource);
	F toFabric(N resource);
	boolean isFabricBlank(F resource);
	/** Fabric units per one native NeoForge unit: one for items, 81 for fluids. */
	long fabricUnits();
}
