package fixture.qzxmodels;

import com.mojang.serialization.Codec;

import fixture.fabricblockstatecodec.VariantCodec;

/** The same codecs as the Fabric-shaped registry stand-in, under a class and fields that share no name with it. */
public final class QzxDialects {
	public static final Codec<String> QZX_LISTS = new VariantCodec("fabric-weighted", "fabric:type");
	public static final Codec<String> QZX_SINGLE = new VariantCodec("fabric", "fabric:type");

	private QzxDialects() {
	}
}
