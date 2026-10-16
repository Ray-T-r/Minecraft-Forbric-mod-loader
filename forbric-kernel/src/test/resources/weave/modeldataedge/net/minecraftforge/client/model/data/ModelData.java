package net.minecraftforge.client.model.data;

import java.util.HashMap;
import java.util.Map;

/** Stand-in for MinecraftForge's immutable model data. */
public final class ModelData {
	public static final ModelData EMPTY = new ModelData(Map.of());
	private final Map<ModelProperty<?>, Object> values;

	private ModelData(Map<ModelProperty<?>, Object> values) {
		this.values = values;
	}

	@SuppressWarnings("unchecked")
	public <T> T get(ModelProperty<T> property) {
		return (T) values.get(property);
	}

	public <T> ModelData with(ModelProperty<T> property, T value) {
		Map<ModelProperty<?>, Object> copy = new HashMap<>(values);
		copy.put(property, value);
		return new ModelData(copy);
	}
}
