package pingplus.voicechat.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.function.DoubleConsumer;
import org.lwjgl.glfw.GLFW;
//test
public final class VoiceScreen extends Screen {
    private final Screen parent;
    private final VoiceConnection voice;
    private int page;
    private List<Map.Entry<UUID, String>> shown = List.of();
    private KeyMapping pendingKey;
    public VoiceScreen(Screen parent, VoiceConnection voice) { super(Component.literal("LabyMod Voice Chat")); this.parent = parent; this.voice = voice; }
    @Override protected void init() {
        int x = width / 2 - 150;
        var s = voice.settings;
        addRenderableWidget(Button.builder(Component.literal(s.enabled ? "Disable voice" : "Enable voice"), b -> {
            s.enabled = !s.enabled; s.save(); if (s.enabled) voice.connect(); else voice.disconnect(); rebuildWidgets();
        }).bounds(x, 48, 145, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reconnect"), b -> { voice.connect(); rebuildWidgets(); }).bounds(x + 155, 48, 145, 20).build());
        addRenderableWidget(Button.builder(Component.literal(s.muted ? "Microphone: muted" : "Microphone: on"), b -> {
            s.muted = !s.muted; s.save(); voice.updateProperties(); rebuildWidgets();
        }).bounds(x, 72, 145, 20).build());
        addRenderableWidget(Button.builder(Component.literal(s.deafened ? "Deafen: on" : "Deafen: off"), b -> {
            s.deafened = !s.deafened; s.save(); voice.updateProperties(); rebuildWidgets();
        }).bounds(x + 155, 72, 145, 20).build());
        slider(x, 96, 145, "Output", s.volume, 2, value -> s.volume = value);
        slider(x + 155, 96, 145, "Microphone", s.microphoneGain, 2, value -> s.microphoneGain = value);
        slider(x, 120, 145, "Range (m)", s.distance, 64, value -> s.distance = Math.max(8, value));
        addRenderableWidget(Button.builder(Component.literal("Audio devices..."), b -> minecraft.gui.setScreen(new VoiceDevicesScreen(this, voice)))
            .bounds(x + 155, 120, 145, 20).build());
        // Hotkeys direkt umstellen (statt nur über Minecraft-Steuerung)
        addRenderableWidget(Button.builder(keyLabel(VoicechatClient.openGuiKey(), "ClickGUI"), b -> {
            pendingKey = VoicechatClient.openGuiKey(); rebuildWidgets();
        }).bounds(x, 144, 145, 20).build());
        addRenderableWidget(Button.builder(keyLabel(VoicechatClient.voiceMenuKey(), "Voice menu"), b -> {
            pendingKey = VoicechatClient.voiceMenuKey(); rebuildWidgets();
        }).bounds(x + 155, 144, 145, 20).build());
        addRenderableWidget(Button.builder(keyLabel(VoicechatClient.talkKey(), "Push to talk"), b -> {
            pendingKey = VoicechatClient.talkKey(); rebuildWidgets();
        }).bounds(x, 168, 145, 20).build());
        addRenderableWidget(Button.builder(keyLabel(VoicechatClient.muteKey(), "Mute"), b -> {
            pendingKey = VoicechatClient.muteKey(); rebuildWidgets();
        }).bounds(x + 155, 168, 95, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reset keys"), b -> {
            if (VoicechatClient.openGuiKey() != null) VoicechatClient.openGuiKey().setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_RIGHT_SHIFT));
            if (VoicechatClient.voiceMenuKey() != null) VoicechatClient.voiceMenuKey().setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_V));
            if (VoicechatClient.talkKey() != null) VoicechatClient.talkKey().setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_CAPS_LOCK));
            if (VoicechatClient.muteKey() != null) VoicechatClient.muteKey().setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_M));
            pendingKey = null;
            saveKeys();
            rebuildWidgets();
        }).bounds(x + 255, 168, 45, 20).build());
        shown = voice.players.entrySet().stream().sorted(Map.Entry.comparingByValue()).map(e -> Map.entry(e.getKey(), e.getValue())).toList();
        int listTop = 216;
        int rows = Math.max(1, (height - listTop - 36) / 24);
        page = Math.min(page, Math.max(0, (shown.size() - 1) / rows));
        for (int i = page * rows; i < Math.min(shown.size(), (page + 1) * rows); i++) {
            var player = shown.get(i); UUID id = player.getKey(); int y = listTop + (i - page * rows) * 24;
            slider(x, y, 225, player.getValue(), s.volume(id), 2, value -> s.playerVolumes.put(id.toString(), value));
            addRenderableWidget(Button.builder(Component.literal(s.volume(id) == 0 ? "Unmute" : "Mute"), b -> {
                s.playerVolumes.put(id.toString(), s.volume(id) == 0 ? 1.0 : 0.0); s.save(); rebuildWidgets();
            }).bounds(x + 230, y, 70, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { page = Math.max(0, page - 1); rebuildWidgets(); }).bounds(x, height - 28, 35, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh / >"), b -> { page = (page + 1) * rows < shown.size() ? page + 1 : 0; rebuildWidgets(); }).bounds(x + 40, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(x + 155, height - 28, 145, 20).build());
    }
    private Component keyLabel(KeyMapping mapping, String name) {
        if (mapping == null) return Component.literal(name + ": -");
        if (mapping == pendingKey) return Component.literal(name + ": Press a key... (ESC = cancel)");
        return Component.literal(name + ": ").append(mapping.getTranslatedKeyMessage());
    }
    private void saveKeys() {
        try {
            minecraft.options.save();
        } catch (Exception ignored) {
        }
    }
    @Override public boolean keyPressed(KeyEvent e) {
        if (pendingKey != null) {
            if (e.key() == GLFW.GLFW_KEY_ESCAPE) {
                pendingKey = null;
                rebuildWidgets();
                return true;
            }
            try {
                pendingKey.setKey(InputConstants.getKey(e));
                saveKeys();
            } catch (Exception ignored) {
            }
            pendingKey = null;
            rebuildWidgets();
            return true;
        }
        return super.keyPressed(e);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean twice) {
        if (pendingKey != null) {
            try {
                pendingKey.setKey(InputConstants.Type.MOUSE.getOrCreate(event.button()));
                saveKeys();
            } catch (Exception ignored) {
            }
            pendingKey = null;
            rebuildWidgets();
            return true;
        }
        return super.mouseClicked(event, twice);
    }
    private void slider(int x, int y, int width, String name, double initial, double max, DoubleConsumer consumer) {
        double min = name.equals("Range (m)") ? 8 : 0;
        addRenderableWidget(new AbstractSliderButton(x, y, width, 20, Component.empty(), (initial - min) / (max - min)) {
            { updateMessage(); }
            @Override protected void updateMessage() { setMessage(Component.literal(name + ": " + Math.round((min + value * (max - min)) * (min > 0 ? 1 : 100)) + (min > 0 ? "" : "%"))); }
            @Override protected void applyValue() { consumer.accept(min + value * (max - min)); voice.settings.save(); }
        });
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);
        graphics.centeredText(font, font.plainSubstrByWidth(voice.status, width - 12), width / 2, 30, voice.connected() ? 0xFF80DD99 : 0xFFFFCC80);
        graphics.centeredText(font, pendingKey != null ? "Press a key for \"" + pendingKey.getName() + "\"... ESC cancels, click sets mouse button" : "Click a key button, then press a key or mouse button", width / 2, 192, 0xFFBBBBBB);
        if (shown.isEmpty()) graphics.centeredText(font, "No nearby voice users", width / 2, 222, 0xFFAAAAAA);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { voice.settings.save(); saveKeys(); minecraft.gui.setScreen(parent); }
}
