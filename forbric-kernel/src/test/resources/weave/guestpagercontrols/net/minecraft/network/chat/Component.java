package net.minecraft.network.chat;
public interface Component {
    static Component literal(String text) { return () -> text; }
    String text();
    default String getVisualOrderText() { return text(); }
}
