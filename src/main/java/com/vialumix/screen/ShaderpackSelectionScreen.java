package com.vialumix.screen;

import com.vialumix.client.VialumixClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.Selectable;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ElementListWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Lists shaderpacks discovered by Iris and lets the user choose one for the next Apply action. */
public final class ShaderpackSelectionScreen extends Screen {
    private final Screen parent;
    private final Consumer<String> onSelect;
    private final List<String> packs;
    private final List<PackEntry> entries = new ArrayList<>();
    private String selectedPack;
    private PackList packList;

    public ShaderpackSelectionScreen(Screen parent, String selectedPack, Consumer<String> onSelect) {
        super(Text.translatable("vialumix.shaderpack.select"));
        this.parent = parent;
        this.selectedPack = selectedPack == null ? "" : selectedPack;
        this.onSelect = onSelect;
        this.packs = new ArrayList<>(VialumixClient.shaderpacks().packs());
    }

    @Override
    protected void init() {
        int listTop = 48;
        int listBottom = height - 100;
        packList = new PackList(client, width, Math.max(80, listBottom - listTop), listTop, listBottom, 28);
        packList.setRenderBackground(false);
        packList.setRenderHorizontalShadows(false);

        entries.clear();
        if (VialumixClient.shaderpacks().canDisablePacks()) entries.add(new PackEntry(""));
        for (String pack : packs) {
            entries.add(new PackEntry(pack));
        }
        for (PackEntry entry : entries) {
            packList.addPackEntry(entry);
        }
        addDrawableChild(packList);

        int buttonWidth = Math.min(180, width - 40);
        int x = (width - buttonWidth) / 2;
        int y = height - 76;
        addDrawableChild(ButtonWidget.builder(Text.translatable("vialumix.shaderpack.choose"), button -> {
            onSelect.accept(selectedPack);
            client.setScreen(parent);
        }).dimensions(x, y, buttonWidth, 20).build());

        int actionWidth = Math.min(140, (width - 48) / 2);
        int actionGap = 8;
        int actionX = (width - (actionWidth * 2 + actionGap)) / 2;
        int actionY = height - 30;
        if (VialumixClient.shaderpacks().canDisablePacks()) {
            addDrawableChild(ButtonWidget.builder(Text.translatable("vialumix.shaderpack.disable"), button -> {
                selectedPack = "";
                onSelect.accept("");
                client.setScreen(parent);
            }).dimensions(actionX, actionY, actionWidth, 20).build());
        }
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), button -> client.setScreen(parent))
                .dimensions(actionX + actionWidth + actionGap, actionY, actionWidth, 20).build());
    }

    private void select(String pack) {
        selectedPack = pack;
        for (PackEntry entry : entries) {
            entry.updateMessage();
        }
    }

    @Override
    public void close() {
        client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 20, 0xFFFFFF);
        if (VialumixClient.shaderpacks().usesRadianceBackend()) {
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("vialumix.shaderpack.mcvr_notice"), width / 2, 36, 0xFFCC66);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private final class PackList extends ElementListWidget<PackEntry> {
        private PackList(MinecraftClient client, int width, int height, int top, int bottom, int itemHeight) {
            super(client, width, height, top, bottom, itemHeight);
            setLeftPos((width - Math.min(420, width - 40)) / 2);
        }

        @Override
        public int getRowWidth() {
            return Math.min(420, width - 40);
        }

        private void addPackEntry(PackEntry entry) {
            addEntry(entry);
        }
    }

    private final class PackEntry extends ElementListWidget.Entry<PackEntry> {
        private final String pack;
        private final ButtonWidget button;

        private PackEntry(String pack) {
            this.pack = pack;
            this.button = ButtonWidget.builder(Text.empty(), ignored -> {
                        select(pack);
                        onSelect.accept(pack);
                        client.setScreen(parent);
                    })
                    .dimensions(0, 0, 300, 22)
                    .build();
            updateMessage();
        }

        private void updateMessage() {
            String name = pack.isEmpty()
                    ? Text.translatable("vialumix.shaderpack.none").getString()
                    : VialumixClient.shaderpacks().displayName(pack);
            String marker = selectedPack.equals(pack) ? "✓ " : "";
            button.setMessage(Text.literal(marker + name));
        }

        @Override
        public List<? extends Element> children() {
            return List.of(button);
        }

        @Override
        public List<? extends Selectable> selectableChildren() {
            return List.of(button);
        }

        @Override
        public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight,
                           int mouseX, int mouseY, boolean hovered, float delta) {
            button.setPosition(x, y + 2);
            button.setWidth(entryWidth);
            button.render(context, mouseX, mouseY, delta);
        }
    }
}
