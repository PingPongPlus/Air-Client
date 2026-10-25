package com.coflnet.ahflipper;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.DoubleFieldControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class AhAutomationGui {

    private AhAutomationGui() {
    }

    public static Screen create(Screen parent) {
        AhAutomationConfig config = AhAutomationConfig.get();
        return YetAnotherConfigLib.createBuilder()
                .title(Component.literal("Untitled - Automations"))
                .category(ConfigCategory.createBuilder()
                        .name(Component.literal("Automations"))
                        .group(OptionGroup.createBuilder()
                                .name(Component.literal("Mob-Pathfinder"))
                                .description(OptionDescription.of(Component.literal(
                                        "Pathfindet zu Mobs/Entities/Playern mit diesem Namen und greift sie an. Client-seitig, menschliches Movement.")))
                                .option(Option.<String>createBuilder()
                                        .name(Component.literal("Ziel-Name"))
                                        .description(OptionDescription.of(Component.literal(
                                                "Name des Ziels (Teilname reicht, Gross/Klein egal). Leer = aus.")))
                                        .controller(StringControllerBuilder::create)
                                        .binding("", () -> config.targetName, value -> {
                                            config.targetName = value == null ? "" : value;
                                            AhMobPathfinder.onSettingsChanged();
                                            config.save();
                                        })
                                        .build())
                                .option(Option.<Boolean>createBuilder()
                                        .name(Component.literal("Pathfinder aktiv"))
                                        .description(OptionDescription.of(Component.literal(
                                                "Sobald das Ziel gerendert wird, wird dorthin gepathfindet.")))
                                        .controller(TickBoxControllerBuilder::create)
                                        .binding(false, () -> config.enabled, value -> {
                                            config.enabled = value;
                                            AhMobPathfinder.onSettingsChanged();
                                            config.save();
                                        })
                                        .build())
                                .option(Option.<Boolean>createBuilder()
                                        .name(Component.literal("Auto-Angriff"))
                                        .description(OptionDescription.of(Component.literal(
                                                "Linksklick-Angriff, wenn das Ziel in Reichweite ist (menschliche Klickrate).")))
                                        .controller(TickBoxControllerBuilder::create)
                                        .binding(true, () -> config.autoAttack, value -> {
                                            config.autoAttack = value;
                                            config.save();
                                        })
                                        .build())
                                .option(Option.<Boolean>createBuilder()
                                        .name(Component.literal("Sprinten erlaubt"))
                                        .controller(TickBoxControllerBuilder::create)
                                        .binding(true, () -> config.allowSprint, value -> {
                                            config.allowSprint = value;
                                            config.save();
                                        })
                                        .build())
                                .option(Option.<Double>createBuilder()
                                        .name(Component.literal("Angriffs-CPS (min)"))
                                        .controller(DoubleFieldControllerBuilder::create)
                                        .binding(1.0, () -> config.attackCpsMin, value -> {
                                            config.attackCpsMin = value;
                                            config.save();
                                        })
                                        .build())
                                .option(Option.<Double>createBuilder()
                                        .name(Component.literal("Angriffs-CPS (max)"))
                                        .controller(DoubleFieldControllerBuilder::create)
                                        .binding(2.0, () -> config.attackCpsMax, value -> {
                                            config.attackCpsMax = value;
                                            config.save();
                                        })
                                        .build())
                                .option(Option.<Double>createBuilder()
                                        .name(Component.literal("Angriffs-Reichweite"))
                                        .controller(DoubleFieldControllerBuilder::create)
                                        .binding(3.5, () -> config.reach, value -> {
                                            config.reach = value;
                                            config.save();
                                        })
                                        .build())
                                .build())
                        .build())
                .save(() -> {
                    config.save();
                    AhMobPathfinder.onSettingsChanged();
                })
                .build()
                .generateScreen(parent);
    }
}
