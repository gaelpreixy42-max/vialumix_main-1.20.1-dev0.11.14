package com.vialumix.screen;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** First screen shown when the user opens Minecraft's Graphics button. */
public final class GraphicsChooserScreen extends Screen {
    private final Screen parent;

    public GraphicsChooserScreen(Screen parent) {
        super(Text.translatable("vialumix.graphics.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int width = 360;
        int x = (this.width - width) / 2;
        int y = Math.max(58, this.height / 2 - 42);

        addDrawableChild(ButtonWidget.builder(
                Text.translatable("vialumix.graphics.minecraft"),
                b -> client.setScreen(SodiumScreenBridge.openOriginal(this))
        ).dimensions(x, y, width, 28).build());

        addDrawableChild(ButtonWidget.builder(
                Text.translatable("vialumix.graphics.vialumix"),
                b -> client.setScreen(new VialumixScreen(this))
        ).dimensions(x, y + 38, width, 28).build());

        addDrawableChild(ButtonWidget.builder(
                Text.translatable("gui.done"),
                b -> close()
        ).dimensions(x, y + 82, width, 20).build());
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 25, 0xFFFFFF);
        super.render(context, mouseX, mouseY, delta);
    }
}
