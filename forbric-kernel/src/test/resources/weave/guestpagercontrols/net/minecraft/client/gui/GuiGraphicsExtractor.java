package net.minecraft.client.gui;
public class GuiGraphicsExtractor {
    public final java.util.List<String> drawn = new java.util.ArrayList<>();
    public void text(Font font, String text, int x, int y, int color) { drawn.add(text); }
}
