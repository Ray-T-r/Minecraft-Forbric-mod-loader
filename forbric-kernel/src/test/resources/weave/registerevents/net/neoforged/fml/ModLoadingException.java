package net.neoforged.fml;

/** Stand-in: what NeoForge's ModContainer.acceptEvent and ModLoader.waitForFuture throw. */
public class ModLoadingException extends RuntimeException {
	public ModLoadingException(String issues, Throwable cause) {
		super(issues, cause);
	}
}
