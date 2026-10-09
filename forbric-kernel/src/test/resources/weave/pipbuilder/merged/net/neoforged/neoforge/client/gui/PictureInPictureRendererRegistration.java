package net.neoforged.neoforge.client.gui;

import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;

/** A stand-in for NeoForge's renderer registration: the state class its pool serves. */
public record PictureInPictureRendererRegistration<T extends PictureInPictureRenderState>(Class<T> stateClass) {
}
