package net.minecraft.client.gui.components;
public class Button {
    public interface OnPress { void onPress(Button button); }
    public final String label;
    private final OnPress onPress;
    public Button(String label, OnPress onPress) { this.label = label; this.onPress = onPress; }
    public void press() { onPress.onPress(this); }
    public static Builder builder(net.minecraft.network.chat.Component label, OnPress onPress) { return new Builder(label.text(), onPress); }
    public static class Builder {
        private final String label; private final OnPress onPress;
        Builder(String label, OnPress onPress) { this.label = label; this.onPress = onPress; }
        public Builder pos(int x, int y) { return this; }
        public Builder size(int w, int h) { return this; }
        public Button build() { return new Button(label, onPress); }
    }
}
