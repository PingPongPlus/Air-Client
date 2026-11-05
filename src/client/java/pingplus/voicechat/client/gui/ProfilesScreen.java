package pingplus.voicechat.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import pingplus.voicechat.client.gui.glass.GlassStyle;
import pingplus.voicechat.client.profile.ClientProfiles;
import pingplus.voicechat.client.profile.ProfileStore;

import java.util.List;

/** Native focusable controls with pagination so every saved profile remains reachable. */
public final class ProfilesScreen extends Screen {
    private final Screen parent;
    private final ClientProfiles profiles;
    private List<ProfileStore.Profile> saved = List.of();
    private String selected = "", draft = "", status = "", activeName = "None", confirmDelete = "";
    private boolean error;
    private int page, pageSize, left, top, panelWidth, panelHeight, listY, nameY;
    private EditBox name;

    public ProfilesScreen(Screen parent, ClientProfiles profiles) {
        super(Component.literal("Profiles"));
        this.parent = parent;
        this.profiles = profiles;
    }

    @Override protected void init() {
        panelWidth = Math.min(360, width - 24);
        panelHeight = Math.min(330, height - 24);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        refresh();
        int x = left + 12, inner = panelWidth - 24;
        listY = top + 48;
        pageSize = Math.max(1, (panelHeight - 178) / 24);
        int pages = Math.max(1, (saved.size() + pageSize - 1) / pageSize);
        page = Math.clamp(page, 0, pages - 1);
        for (int row = 0; row < pageSize && page * pageSize + row < saved.size(); row++) {
            var profile = saved.get(page * pageSize + row);
            String label = (profile.id().equals(selected) ? "> " : "") + profile.name();
            addRenderableWidget(Button.builder(Component.literal(label), button -> {
                selected = profile.id(); draft = profile.name(); confirmDelete = "";
                rebuildWidgets();
            }).bounds(x, listY + row * 24, inner, 20)
                    .tooltip(Tooltip.create(Component.literal(profile.name()))).build());
        }
        int navigationY = listY + pageSize * 24;
        Button previous = addRenderableWidget(Button.builder(Component.literal("Previous"), button -> {
            page--; confirmDelete = ""; rebuildWidgets();
        }).bounds(x, navigationY, 76, 20).build());
        previous.active = page > 0;
        Button next = addRenderableWidget(Button.builder(Component.literal("Next"), button -> {
            page++; confirmDelete = ""; rebuildWidgets();
        }).bounds(x + inner - 76, navigationY, 76, 20).build());
        next.active = page + 1 < pages;
        nameY = navigationY + 28;
        name = addRenderableWidget(new EditBox(font, x, nameY + 12, inner - 86, 20, Component.literal("Profile name")));
        name.setMaxLength(ProfileStore.MAX_NAME_LENGTH);
        name.setHint(Component.literal("Profile name"));
        name.setValue(draft);
        name.setResponder(value -> { draft = value; confirmDelete = ""; });
        addRenderableWidget(Button.builder(Component.literal("Create"), button -> act(() -> {
            var created = profiles.create(draft);
            selected = created.id(); draft = created.name();
            saved = profiles.store().profiles();
            page = saved.indexOf(created) / pageSize;
            return "Created " + created.name();
        })).bounds(x + inner - 80, nameY + 12, 80, 20)
                .tooltip(Tooltip.create(Component.literal("Create a profile from your current settings."))).build());
        int actionY = nameY + 38, gap = 4, buttonWidth = (inner - gap * 3) / 4;
        Button load = addRenderableWidget(Button.builder(Component.literal("Load"), button -> act(() -> {
            profiles.load(selected); return "Loaded " + selectedName();
        })).bounds(x, actionY, buttonWidth, 20)
                .tooltip(Tooltip.create(Component.literal("Replace current settings with the saved profile. Unsaved changes will be lost."))).build());
        Button save = addRenderableWidget(Button.builder(Component.literal("Save"), button -> act(() -> {
            profiles.save(selected); return "Saved " + selectedName();
        })).bounds(x + buttonWidth + gap, actionY, buttonWidth, 20)
                .tooltip(Tooltip.create(Component.literal("Overwrite the selected profile with your current settings."))).build());
        Button rename = addRenderableWidget(Button.builder(Component.literal("Rename"), button -> act(() -> {
            profiles.store().rename(selected, draft); return "Profile renamed";
        })).bounds(x + (buttonWidth + gap) * 2, actionY, buttonWidth, 20).build());
        Button delete = addRenderableWidget(Button.builder(Component.literal(!selected.isEmpty() && selected.equals(confirmDelete) ? "Confirm" : "Delete"), button -> {
            if (!selected.equals(confirmDelete)) {
                confirmDelete = selected; status = "Click Confirm to delete " + selectedName(); error = false; rebuildWidgets();
            } else act(() -> {
                String oldName = selectedName();
                profiles.store().delete(selected); selected = ""; draft = "";
                return "Deleted " + oldName;
            });
        }).bounds(x + (buttonWidth + gap) * 3, actionY, buttonWidth, 20).build());
        load.active = save.active = rename.active = delete.active = !selected.isEmpty();
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(x + inner - 80, top + panelHeight - 26, 80, 20).build());
    }

    private void refresh() {
        try {
            saved = profiles.store().profiles();
            activeName = profiles.store().activeName();
            if (saved.stream().noneMatch(p -> p.id().equals(selected))) selected = "";
        } catch (Exception e) { status = e.getMessage(); error = true; }
    }
    private String selectedName() {
        return saved.stream().filter(p -> p.id().equals(selected)).map(ProfileStore.Profile::name).findFirst().orElse("");
    }
    @FunctionalInterface private interface Action { String run() throws Exception; }
    private void act(Action action) {
        try { status = action.run(); error = false; confirmDelete = ""; }
        catch (Exception e) { status = e.getMessage() == null ? "Could not update profile" : e.getMessage(); error = true; }
        rebuildWidgets();
    }
    @Override public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float dt) {
        super.extractBackground(g, mx, my, dt);
        GlassStyle.surface(g, left, top, panelWidth, panelHeight, 1, .2f);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        super.extractRenderState(g, mx, my, dt);
        g.nextStratum();
        g.text(font, GlassStyle.label("PROFILES"), left + 12, top + 10, GlassStyle.TEXT, false);
        label(g, "Active: " + activeName, left + 12, top + 26, panelWidth - 24, GlassStyle.MUTED);
        if (saved.isEmpty()) label(g, "Save your current settings as a new profile.", left + 12, listY + 5, panelWidth - 24, GlassStyle.MUTED);
        int pages = Math.max(1, (saved.size() + pageSize - 1) / pageSize);
        g.centeredText(font, (page + 1) + " / " + pages, width / 2, listY + pageSize * 24 + 6, GlassStyle.MUTED);
        g.text(font, GlassStyle.label("Name"), left + 12, nameY, GlassStyle.MUTED, false);
        label(g, status.isEmpty() ? "Changes are saved only when you click Save." : status,
                left + 12, nameY + 64, panelWidth - 24, error ? 0xFFFF9B99 : GlassStyle.MUTED);
    }
    private void label(GuiGraphicsExtractor g, String text, int x, int y, int maxWidth, int color) {
        g.text(font, font.plainSubstrByWidth(text, maxWidth), x, y, color, false);
    }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
