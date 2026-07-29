package net.minecraft.network;

import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.resources.Identifier;

public class Connection {
	public final Set<Identifier> channels = new LinkedHashSet<>();
}
