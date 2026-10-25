package com.coflnet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import CoflCore.classes.Position;
import CoflCore.classes.Settings;
import CoflCore.commands.models.HotkeyRegister;
import CoflCore.configuration.Config;
import CoflCore.configuration.GUIType;
import com.coflnet.gui.BinGUI;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.DetectedVersion;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.client.gui.components.PopupScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.nbt.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.util.*;
import net.minecraft.core.BlockPos;
import org.lwjgl.glfw.GLFW;

import com.coflnet.gui.RenderUtils;
import com.coflnet.gui.cofl.CoflBinGUI;
import com.coflnet.gui.cofl.CoflSettingsScreen;
import com.coflnet.gui.tfm.TfmBinGUI;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.Message;
import com.mojang.brigadier.arguments.StringArgumentType;

import CoflCore.CoflCore;
import CoflCore.CoflSkyCommand;
import CoflCore.commands.Command;
import CoflCore.commands.CommandType;
import CoflCore.commands.RawCommand;
import CoflCore.commands.models.FlipData;
import CoflCore.commands.models.ModListData;
import CoflCore.handlers.DescriptionHandler;
import CoflCore.handlers.EventRegistry;
import CoflCore.network.QueryServerCommands;
import CoflCore.network.WSClientWrapper;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.Holder;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Team;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.core.NonNullList;
import net.minecraft.world.InteractionResult;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import oshi.util.tuples.Pair;

public class CoflModClient implements ClientModInitializer {
    public static final String targetVersion = "26.2";
    public static final int InventorysizeWithOffHand = 5 * 9 + 1;
    // Private-use marker rendered with a zero-width custom font so text_Tunnels
    // can match it without showing a missing-glyph box in chat.
    static final String TEXT_TUNNELS_MESSAGE_PREFIX = "\uE000";
    static final Identifier TEXT_TUNNELS_MESSAGE_FONT = Identifier.fromNamespaceAndPath("coflmod", "hidden_marker");
    public static Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();
    private static boolean keyPressed = false;
    private static boolean coflInfoShown = false;
    private static int counter = 0;
    private static final KeyMapping.Category SKYCOFL_CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("coflnet", "skycofl"));
    private static final KeyMapping.Category SKYCOFL_UNCHANGEABLE_CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("coflnet", "skycofl_unchangeable"));
    public static KeyMapping bestflipsKeyBinding;
    public static KeyMapping uploadItemKeyBinding;
    public static KeyMapping openSettingsKeyBinding;
    public static List<KeyMapping> additionalKeyBindings = new ArrayList<KeyMapping>();
    public static Map<KeyMapping, HotkeyRegister> keybindingsToHotkeys = new HashMap<KeyMapping, HotkeyRegister>();
    public static final Set<String> knownIds = ConcurrentHashMap.newKeySet();
    public static Pair<String, String> lastScoreboardUploaded = new Pair<>("","0");
    private static final Set<String> DONUT_VALUABLE_ENCHANTS = Set.of(
            "sharpness", "protection", "efficiency", "fortune", "looting",
            "unbreaking", "mending", "silk_touch", "power", "infinity",
            "flame", "punch", "thorns", "fire_aspect", "knockback",
            "smite", "bane_of_arthropods", "sweeping", "respiration",
            "aqua_affinity", "depth_strider", "frost_walker", "soul_speed",
            "swift_sneak", "feather_falling", "blast_protection", "fire_protection",
            "projectile_protection", "luck_of_the_sea", "lure", "channeling",
            "impaling", "loyalty", "riptide", "multishot", "piercing", "quick_charge");

    private String username = "";
    private String lastCheckedUsername = ""; // Track last username to detect account switches
    private static volatile String lastNbtRequest = "";
    private boolean uploadedScoreboard = false;
    private static boolean popupShown = false;
    public static Position posToUpload = null;
    public static CoflModClient instance;
    public static SignBlockEntity sign = null;
    public static String pendingBazaarSearch = null;
    public static boolean flipperChatOnlyMode = false;

    // Scoreboard dirty flag - set by ScoreboardMixin when packets arrive
    private static volatile boolean scoreboardDirty = false;
    private static volatile long lastScoreboardProcessMs = 0L;
    private static final long SCOREBOARD_PROCESS_INTERVAL_MS = 250L;

    // Staggered refresh tracking: inventory name -> last request time
    private static final Map<String, Long> lastRefreshTimePerInventory = new ConcurrentHashMap<>();
    private static final Map<String, Long> lastDescriptionLoadRequestByMenu = new ConcurrentHashMap<>();
    private static final long REFRESH_THROTTLE_MS = 500; // 0.5 seconds minimum between requests
    private static final long DESCRIPTION_LOAD_TRIGGER_DEBOUNCE_MS = 250;
    private static final AtomicBoolean connectionStartInProgress = new AtomicBoolean(false);
    private static final ExecutorService connectionLifecycleExecutor =
    Executors.newSingleThreadExecutor(Thread.ofVirtual().name("cofl-connection-", 0).factory());
    private static final Set<Integer> uploadedMapIds = ConcurrentHashMap.newKeySet();
    private static volatile long lastMapUploadCheckMs = 0L;
    private static final long MAP_UPLOAD_CHECK_INTERVAL_MS = 1000L;
    private static volatile long lastHoveredMapUploadCheckMs = 0L;
    private static final long HOVERED_MAP_UPLOAD_INTERVAL_MS = 250L;
    private static final SecureRandom connectConfirmationRandom = new SecureRandom();
    private static final long UNTRUSTED_CONNECT_CONFIRMATION_TIMEOUT_MS = 30_000L;
    private static volatile String pendingUntrustedConnectToken;
    private static volatile String[] pendingUntrustedConnectArgs;
    private static volatile long pendingUntrustedConnectExpiresAtMs;
    private static volatile ServerContext currentServerContext = ServerContext.UNKNOWN;

    // Maps new UUIDs to original UUID when items update with new UUIDs but same title
    // This allows finding descriptions loaded for the original UUID when hovering an item with updated UUID
    public static final Map<String, String> uuidToOriginalUuid = new ConcurrentHashMap<>();

    private enum ServerContext {
        UNKNOWN(null),
        SKYBLOCK(null),
        DONUT("donut");

        private final String requestValue;

        ServerContext(String requestValue) {
            this.requestValue = requestValue;
        }

        private boolean isSupported() {
            return this != UNKNOWN;
        }
    }

    public class TooltipMessage implements  Message{
        private final String text;

        public TooltipMessage(String text) {
            this.text = text != null ? text : "";
        }
        @Override
        public String getString() {
            return text;
        }
    }

    @Override
    public void onInitializeClient() {
        instance = this;
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            connectionLifecycleExecutor.shutdownNow();
            ClientRuntimeLifecycle.stop();
        });
        username = Minecraft.getInstance().getUser().getName();
        lastCheckedUsername = username; // Initialize with current username
        Path configDir = FabricLoader.getInstance().getConfigDir();
        CoflCore cofl = new CoflCore();
        cofl.init(configDir);
        cofl.registerEventFile(new EventSubscribers());
        ensureTextTunnelsConfig();
        com.coflnet.flipper.FlipperMod.init();
        com.coflnet.ahflipper.AhFlipperMod.init();
        com.coflnet.lowballing.LowballingMod.init();
        com.coflnet.ahflipper.AhAutomationConfig.load();
        ClientTickEvents.END_CLIENT_TICK.register(com.coflnet.ahflipper.AhMobPathfinder::onClientTick);

        ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
            RenderUtils.init();
        });

        bestflipsKeyBinding = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "keybinding.coflmod.bestflips",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                SKYCOFL_CATEGORY));
        uploadItemKeyBinding = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "keybinding.coflmod.uploaditem",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_I,
                SKYCOFL_CATEGORY));
        openSettingsKeyBinding = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "keybinding.coflmod.opensettings",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            SKYCOFL_CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Process scoreboard updates if dirty flag is set (set by ScoreboardMixin)
            if (scoreboardDirty && client.player != null) {
                long now = System.currentTimeMillis();
                if (now - lastScoreboardProcessMs >= SCOREBOARD_PROCESS_INTERVAL_MS) {
                    scoreboardDirty = false;
                    lastScoreboardProcessMs = now;
                    try {
                        processScoreboardUpdate();
                    } catch (Exception e) {
                        System.out.println("Error processing scoreboard update: " + e.getMessage());
                    }
                }
            }

            if (isDonutServerContext()) {
                long now = System.currentTimeMillis();
                if (now - lastMapUploadCheckMs >= MAP_UPLOAD_CHECK_INTERVAL_MS) {
                    lastMapUploadCheckMs = now;
                    tryUploadViewedMapData(client);
                }
            }

            if (bestflipsKeyBinding.isDown()) {
                if (counter == 0) {
                    EventRegistry.onOpenBestFlip(username, true);
                }
                if (counter < 2)
                    counter++;
            } else {
                counter = 0;
            }

            if(uploadItemKeyBinding.consumeClick())
                handleGetHoveredItem(client);

            if (openSettingsKeyBinding.consumeClick()) {
                CoflSkyCommand.processCommand(new String[]{"get", "json"}, username);
                try {
                    client.gui.setScreen(CoflSettingsScreen.create(client.gui.screen()));
                } catch (Throwable t) {
                    sendChatMessage("§cFailed to open settings GUI. YACL is missing or failed to load (please add the lastest version of YACL mod to your mods).");
                    System.out.println("Failed to open settings GUI: " + t.getMessage());
                    t.printStackTrace();
                }
            }

            if (additionalKeyBindings == null) return;
            try{
                for (KeyMapping additionalKeyBinding : additionalKeyBindings) {
                    if(additionalKeyBinding.consumeClick()){
                        String keyName = keybindingsToHotkeys.get(additionalKeyBinding).Name;
                        String toAppend = getContextToAppend(client.player.getInventory().getItem(client.player.getInventory().getSelectedSlot()));

                        System.out.println("Exec hotkey "+ keyName + toAppend);
                        CoflSkyCommand.processCommand(new String[]{"hotkey", keyName+toAppend},
                                Minecraft.getInstance().getUser().getName());
                    }
                }
            } catch (ConcurrentModificationException e) {
                System.out.println("Additional Keybindings currently in use somewhere else, retrying...");
            }
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerContext detectedServerContext = detectServerContext(null);
            applyServerContext(detectedServerContext);
            lastScoreboardProcessMs = 0L;
            if (detectedServerContext.isSupported()) {
                System.out.println("Connected to " + detectedServerContext.name().toLowerCase(Locale.ROOT));

                // Ensure the SkyCofl tunnel is present in text_Tunnels' config before
                // text_Tunnels loads its server-specific config.
                if (detectedServerContext == ServerContext.SKYBLOCK) {
                    ensureTextTunnelsConfig();
                }

                // Delay startup slightly so the join flow can finish before background
                // initialization begins.
                scheduleAutoStartAfterJoin();
            }
            // reset cached data for different island
            DescriptionHandler.emptyTooltipData();
            uploadedScoreboard = false;
            EventSubscribers.positions = null;
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            applyServerContext(ServerContext.UNKNOWN);
            WSClientWrapper wrapper = CoflCore.Wrapper;
            if (wrapper != null && wrapper.isRunning) {
                System.out.println("Disconnected from server");
                stopConnectionAsync();
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            registerDefaultCommands(dispatcher, "cofl");
            registerDefaultCommands(dispatcher, "cl");



            dispatcher.register(ClientCommands.literal("fc")
                .executes(context -> {
                    // /fc with no arguments (toggles the chat server side)
                    CoflSkyCommand.processCommand(new String[]{"chat"}, username);
                    return 1;
                })
                .then(ClientCommands.literal("toggle")
                    .executes(context -> {
                        // /fc toggle - toggles flipper chat only mode
                        flipperChatOnlyMode = !flipperChatOnlyMode;
                        String message = flipperChatOnlyMode ?
                            "§aFlipper Chat Only Mode enabled. All messages will be sent to flipper chat." :
                            "§cFlipper Chat Only Mode disabled.";
                        sendChatMessage(message);
                        return 1;
                    })
                )
                .then(ClientCommands.argument("args", StringArgumentType.greedyString())
                    .suggests((context, builder) -> {
                        String[] suggestions = {":tableflip:", ":sad:", ":smile:", ":grin:", ":heart:", ":skull:", ":airplane:", ":check:", "<3",
                                ":star:", ":yes:", ":no:", ":java:", ":arrow", ":shrug:", "o/", ":123:", ":totem:", ":typing:",
                                ":maths:", ":snail:", ":thinking:", ":gimme:", ":wizard:", ":pvp:", ":peace:", ":oof:", ":puffer:",
                                ":yey:", ":cat:", ":dab:", ":dj:", ":snow:", ":^_^:", ":^-^:", ":sloth:", ":cute:", ":dog:",
                                ":fyou:", ":angwyflip:", ":snipe:", ":preapi:", ":tm:", ":r:", ":c:", ":crown:", ":fire:",
                                ":sword:", ":shield:", ":cross:", ":star1:", ":star2:", ":star3:", ":star4:", ":rich:", ":boop:",
                                ":yay:", ":gg:"};
                        String input = context.getInput();
                        String[] inputParts = input.split(" ");
                        String currentWord = inputParts.length > 0 ? inputParts[inputParts.length - 1] : "";

                        for (String suggestion : suggestions) {
                            if (suggestion.toLowerCase().startsWith(currentWord.toLowerCase())) {
                                builder.suggest(suggestion, new TooltipMessage("Will be replaced with the emoji"));
                            }
                        }
                        return builder.buildFuture();
                    })
                    .executes(context -> {
                        String[] args = context.getArgument("args", String.class).split(" ");
                        String[] newArgs = new String[args.length + 1];
                        System.arraycopy(args, 0, newArgs, 1, args.length);
                        newArgs[0] = "chat";
                        CoflSkyCommand.processCommand(newArgs, username);
                        return 1;
                    })
                )
            );
        });

        // General screen event to check for account switches in menus
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            // Only check for account switches when not connected to a server
            if (client.player == null) {
                checkAndHandleAccountSwitch();
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof ContainerScreen gcs && CoflCore.config.purchaseOverlay != null && gcs.getTitle() != null ) {
                // System.out.println(gcs.getTitle().getString());
                if (!(client.gui.screen() instanceof BinGUI) && isBINAuction(gcs)) {
                    if (CoflCore.config.purchaseOverlay == GUIType.COFL) client.gui.setScreen(new CoflBinGUI(gcs));
                    if (CoflCore.config.purchaseOverlay == GUIType.TFM) client.gui.setScreen(new TfmBinGUI(gcs));
                }
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof AbstractContainerScreen<?> hs) {
                knownIds.clear();
                loadDescriptionsForInv(hs);
                if(!uploadedScoreboard)
                {
                    uploadScoreboard();
                    uploadTabList();
                    uploadedScoreboard = true;
                }
            }
        });

        ItemTooltipCallback.EVENT.register((stack, tooltipContext, tooltipType, lines) -> {
            String stackId = getIdFromStack(stack);

            // Check if this UUID maps to an original UUID that has descriptions
            String lookupId = uuidToOriginalUuid.getOrDefault(stackId, stackId);

            if (!knownIds.contains(stackId) && !knownIds.contains(lookupId)
                    && Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen<?> hs) {

                if(!stack.isEmpty() && !stackId.equals("Go Back;1"))
                    loadDescriptionsForInv(hs);
                knownIds.add(stackId);
                System.out.println("Missing descriptions for " + stackId);
                return;
            }

            // Try to get descriptions using the original UUID if mapped
            DescriptionHandler.DescModification[] tooltips = DescriptionHandler.getTooltipData(lookupId);
            if(tooltips == null && !lookupId.equals(stackId))
                tooltips = DescriptionHandler.getTooltipData(stackId); // fallback to current UUID
            if(tooltips == null)
                return;

            var text = stack.get(DataComponents.LORE);
            List<Component> ogLoreLines = text == null ? new ArrayList<>() : text.lines();

            for (DescriptionHandler.DescModification tooltip : tooltips) {
                switch (tooltip.type) {
                    case "APPEND":
                        lines.add(Component.literal(tooltip.value + " "));
                        break;
                    case "REPLACE":
                        if (tooltip.line < 0 || tooltip.line >= lines.size() || tooltip.line >= ogLoreLines.size()) {
                            System.out.println("Invalid line index: " + tooltip.line + " for tooltip: " + tooltip.value);
                            continue; // Skip if the line index is invalid
                        }
                        int targetLine = tooltip.line;
                        if(targetLine > 0
                                && targetLine + 1 < ogLoreLines.size()
                                && ChatFormatting.stripFormatting(ogLoreLines.get(targetLine).toString()).equals(ChatFormatting.stripFormatting(lines.get(targetLine).toString()))) {
                            System.out.println("lines differ `" + ChatFormatting.stripFormatting(ogLoreLines.get(targetLine + 1).toString()) + "` to `" + ChatFormatting.stripFormatting(lines.get(targetLine).toString())  + "`");
                            targetLine++; // assume another mod added a line and move this down
                        }
                        lines.remove(targetLine);
                        lines.add(targetLine, Component.literal(tooltip.value));
                        break;
                    case "INSERT":
                        int insertAt = Math.max(0, Math.min(tooltip.line, lines.size()));
                        lines.add(insertAt, Component.literal(tooltip.value));
                        break;
                    case "DELETE":
                        if (tooltip.line < 0 || tooltip.line >= lines.size()) {
                            System.out.println("Invalid delete line index: " + tooltip.line);
                            continue;
                        }
                        lines.remove(tooltip.line);
                        break;
                    case "HIGHLIGHT":
                        // handled in mixin
                        break;
                    default:
                        System.out.println("Unknown type: " + tooltip.type);
                }
            }

            // Add sell protection warnings to tooltips
            addSellProtectionTooltip(stack, lines);
        });

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("coflnet", "countdown_hud"), (drawContext, tickCounter) -> {
            if (EventSubscribers.showCountdown && EventSubscribers.countdownData != null
                    && (Minecraft.getInstance().gui.screen() == null
                            || Minecraft.getInstance().gui.screen() instanceof ChatScreen)) {
                int heightPercentage = EventSubscribers.countdownData.getHeightPercentage();
                int widthPercentage = EventSubscribers.countdownData.getWidthPercentage();
                int screenWidth = drawContext.guiWidth();
                int screenHeight = drawContext.guiHeight();

                int x = (screenWidth * widthPercentage) / 100;
                int y = (screenHeight * heightPercentage) / 100;

                RenderUtils.drawStringWithShadow(
                        drawContext,
                        EventSubscribers.countdownData.getPrefix()
                                + getStringFromDouble(EventSubscribers.getCountdown(), EventSubscribers.countdownData.getMaxPrecision()),
                        x,
                        y,
                        0xFFFFFFFF, EventSubscribers.countdownData.getScale().intValue());
            }
        });

        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            String messageText = message.getString();
            // Skip backend processing for our own display messages to avoid a feedback loop.
            if (!messageText.startsWith(TEXT_TUNNELS_MESSAGE_PREFIX)) {
                EventRegistry.onChatMessage(messageText);
                // iterate over all components of the message
                String previousHover = null;
                for (Component component : message.getSiblings()) {
                    if(component.getStyle().getHoverEvent() != null
                            && component.getStyle().getHoverEvent() instanceof HoverEvent.ShowText hest) {
                        String text = hest.value().getString();
                        if (text.equals(previousHover))
                            continue; // skip if the text is the same as the previous one, different colored text often has the same hover text
                        previousHover = text;
                        EventRegistry.onChatMessage(hest.value().getString());
                    }
                }
                if(EventRegistry.shouldBlockChatMessage(messageText))
                    return false;
            }
            return true;
        });

        ScreenEvents.AFTER_INIT.register((minecraftClient, screen, i, i1) -> {
            if(!(Minecraft.getInstance().gui.screen() instanceof TitleScreen)) return;

            // Check for account switches in the title screen
            checkAndHandleAccountSwitch();

            if (!popupShown && !checkVersionCompability()) {
                popupShown = true;
                Screen currentScreen = Minecraft.getInstance().gui.screen();
                Minecraft.getInstance().gui.setScreen(
                        new PopupScreen.Builder(currentScreen, Component.literal("Warning"))
                                .addButton(Component.literal("Modrinth"), popupScreen -> {
                                    Util.getPlatform().openUri("https://modrinth.com/mod/skycofl/versions");
                                })
                                .addButton(Component.literal("Curseforge"), popupScreen -> {
                                    Util.getPlatform().openUri("https://www.curseforge.com/minecraft/mc-mods/skycofl/files/all?page=1&pageSize=20");
                                })
                                .addButton(Component.literal("dismiss"), popupScreen -> popupScreen.onClose())
                                .addMessage(Component.literal(
                                        "This version of the SkyCofl mod is meant for use in Minecraft "+
                                                targetVersion+" and likely won't work on this version."+
                                                "\nYou can find other versions of SkyCofl here:"
                                ))
                                .build()
                );
            }
        });

        UseBlockCallback.EVENT.register((playerEntity, world, hand, blockHitResult) -> {
            if(world.getBlockEntity(blockHitResult.getBlockPos()) instanceof RandomizableContainerBlockEntity lcbe){
                System.out.println("Lootable opened, saving position of lootable Block...");
                BlockPos pos = blockHitResult.getBlockPos();
                posToUpload = new Position(pos.getX(), pos.getY(), pos.getZ());
            }

            return InteractionResult.PASS;
        });
    }

    /**
     * Called by ScoreboardMixin when scoreboard packets are received.
     * Sets a dirty flag that will be processed on the next tick.
     * This batches multiple rapid updates into a single processing call.
     */
    public static void markScoreboardDirty() {
        scoreboardDirty = true;
    }

    /**
     * Processes scoreboard updates when the dirty flag is set.
     * Called from the tick event to ensure thread safety.
     */
    private static void processScoreboardUpdate() {
        try {
            String[] scores = getScoreboard().toArray(new String[0]);
            ServerContext detectedServerContext = detectServerContext(scores);
            applyServerContext(detectedServerContext);
            if (scores == null || scores.length < 7)
                return;
            Pair<String,String> newData = getRelevantLinesFromScoreboard(scores);

            // Only upload if scoreboard has actually changed
            if (newData.getA().equals(lastScoreboardUploaded.getA()) &&
                newData.getB().equals(lastScoreboardUploaded.getB())
                || newData.getA().contains(" (+")) // additive updates are ignored, a second later the correct new value will be shown
                    return;

            WSClientWrapper wrapper = CoflCore.Wrapper;
            if (wrapper == null || !wrapper.isRunning) {
                if (currentServerContext.isSupported() && instance != null) {
                    instance.autoStart();
                }
            }
            uploadScoreboard();
        } catch (Exception e) {
            System.out.println("Error processing scoreboard update: " + e.getMessage());
        }
    }

    private void autoStart(){
        WSClientWrapper wrapper = CoflCore.Wrapper;
        if ((wrapper != null && wrapper.isRunning) || connectionStartInProgress.get()
                || CoflCore.config == null || !CoflCore.config.autoStart
                || !currentServerContext.isSupported())
            return;
        String currentUsername = Minecraft.getInstance().getUser().getName();
        if (!currentUsername.equals(username)) {
            System.out.println("Account changed before joining server: " + username + " -> " + currentUsername);
            username = currentUsername;
            lastCheckedUsername = currentUsername;
        }

        startConnectionAsync(username);
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(5000); // wait 5 seconds for the scoreboard to be populated
                WSClientWrapper w = CoflCore.Wrapper;
                if(w == null || !w.isRunning)
                    return;
                uploadScoreboardAndTabList();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
    }

    private void scheduleAutoStartAfterJoin() {
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Minecraft client = Minecraft.getInstance();
            if (client == null || client.player == null) {
                return;
            }
            autoStart();
        });
    }

    private static String getStringFromDouble(double seconds, int currentPrecision) {
        String render;

        if (seconds > 100) {
            render = String.valueOf((int) seconds);
        } else {
            render = String.format(Locale.US, "%.3f", seconds).substring(0, currentPrecision);
            if(render.charAt(render.length() - 1) == '.')
                render = render.substring(0, currentPrecision -1);
        }

        return render + "s";
    }

    private void registerDefaultCommands(CommandDispatcher<FabricClientCommandSource> dispatcher, String name) {
        dispatcher.register(ClientCommands.literal(name)
                .executes(context -> {
                    Minecraft client = Minecraft.getInstance();
                    if (!coflInfoShown) {
                        coflInfoShown = true;
                        sendChatMessage("§6§l=== SkyCofl Mod ===");
                        sendChatMessage("§7Powered by §bsky.coflnet.com §7- real-time AH data,");
                        sendChatMessage("§7price checking, flip finding, and more.");
                        sendChatMessage("§eUse §a/cofl help §efor a list of available commands.");
                        sendChatMessage("§7Commands have §aautocompletion§7 - start typing to see suggestions.");
                        sendChatMessage("§7Hover over suggestions for §amore information§7.");
                    }
                    // Open YACL settings UI if available (scheduled to next tick so chat screen closes first)
                    client.execute(() -> {
                        try {
                            CoflSkyCommand.processCommand(new String[]{"get", "json"}, username);
                            client.gui.setScreen(CoflSettingsScreen.create(client.gui.screen()));
                        } catch (Throwable t) {
                            sendChatMessage("§7Install §eYACL §7mod to access the settings GUI with §a/cofl§7.");
                        }
                    });
                    return 1;
                })
                .then(ClientCommands.argument("args", StringArgumentType.greedyString())
                .suggests((context, builder) -> {
                    String input = context.getInput();
                    String[] inputArgs = input.split(" ");;
                    String currentWord = inputArgs.length > 0 ? inputArgs[inputArgs.length - 1] : "";

                    // Check if the command is "s" or "set" and suggest specific subcommands
                    if (inputArgs.length == 2 && (input.contains("set ") || input.equals("s "))
                        || inputArgs.length == 3 && (inputArgs[1].equals("s") || inputArgs[1].equals("set"))) {
                        if("sellprotectionenabled".contains(currentWord.toLowerCase()) || currentWord.equals("set"))
                            builder.suggest("set sellProtectionEnabled", new Message() {
                                @Override
                                public String getString() {
                                    return "Enable or disable sell protection (true/false)";
                                }
                            });
                        if("sellprotectionthreshold".contains(currentWord.toLowerCase()) || currentWord.equals("set"))
                            builder.suggest("set sellProtectionThreshold", new Message() {
                                @Override
                                public String getString() {
                                    return "Set max coin amount before sell protection blocks sells (e.g. 1000, 2k, 3m)";
                                }
                            });
                        if("angrycoopprotectionenabled".contains(currentWord.toLowerCase()) || currentWord.equals("set"))
                            builder.suggest("set angryCoopProtectionEnabled", new Message() {
                                @Override
                                public String getString() {
                                    return "Enable or disable angry co-op protection (true/false)";
                                }
                            });
                        for (Settings suggestion : CoflCore.config.knownSettings) {
                            if (suggestion.getSettingKey().toLowerCase().contains(currentWord.toLowerCase()) || currentWord.equals("set") || currentWord.equals("s")) {
                                String settingInfo = suggestion.getSettingInfo();
                                if (settingInfo == null) {
                                    builder.suggest("set " + suggestion.getSettingKey());
                                } else {
                                    builder.suggest("set " + suggestion.getSettingKey(), new Message() {
                                        @Override
                                        public String getString() {
                                            return settingInfo;
                                        }
                                    });
                                }
                            }
                        }
                    } else if(inputArgs.length > 3)
                        return builder.buildFuture();
                    else {
                        if(CoflCore.config.knownCommands == null)
                        {
                            System.out.println("No known commands loaded yet, cannot suggest");
                            return builder.buildFuture();
                        }
                        for (String suggestion : CoflCore.config.knownCommands.keySet()) {
                            if (suggestion.toLowerCase().startsWith(currentWord.toLowerCase())
                                    || inputArgs.length == 1 // just /cofl should show all
                            ){
                                String messageText = CoflCore.config.knownCommands.get(suggestion);
                                if(messageText == null)
                                    builder.suggest(suggestion);
                                else
                                    builder.suggest(suggestion, new Message() {
                                        @Override
                                        public String getString() {
                                            // Replace line breaks with spaces for single-line display
                                            String[] parts = messageText.split("\n");
                                            return parts.length > 0 ? parts[0] : "";
                                        }
                                    });
                            }
                        }
                    }
                    return builder.buildFuture();
                })
                .executes(context -> {
                    String[] args = context.getArgument("args", String.class).split(" ");

                    // Handle sell protection commands locally
                    if (args.length >= 2 && args[0].equals("set")) {
                        if (args[1].equals("sellProtectionEnabled")) {
                            if (args.length >= 3) {
                                boolean enabled = args[2].equalsIgnoreCase("true") || args[2].equals("1");
                                com.coflnet.config.SellProtectionManager.setEnabled(enabled);
                                sendChatMessage("§aSell Protection " + (enabled ? "enabled" : "disabled"));
                                return 1;
                            } else {
                                sendChatMessage("§cUsage: /cofl set sellProtectionEnabled <true/false>");
                                return 1;
                            }
                        } else if (args[1].equals("sellProtectionThreshold")) {
                            if (args.length >= 3) {
                                try {
                                    long amount = parseAmountString(args[2]);
                                    com.coflnet.config.SellProtectionManager.setMaxAmount(amount);
                                    sendChatMessage("§aSell Protection max amount set to " + formatCoins(amount) + " coins");
                                    return 1;
                                } catch (NumberFormatException e) {
                                    sendChatMessage("§cInvalid number: " + args[2] + ". Use formats like: 1000, 2k, 3m, 1.5b");
                                    return 1;
                                }
                            } else {
                                sendChatMessage("§cUsage: /cofl set sellProtectionThreshold <amount>");
                                sendChatMessage("§7Examples: 1000, 2k, 3m, 1.5b");
                                return 1;
                            }
                        } else if (args[1].equals("angryCoopProtectionEnabled")) {
                            if (args.length >= 3) {
                                boolean enabled = args[2].equalsIgnoreCase("true") || args[2].equals("1");
                                com.coflnet.config.AngryCoopProtectionManager.setEnabled(enabled);
                                sendChatMessage("§aAngry Co-op Protection " + (enabled ? "enabled" : "disabled"));
                                return 1;
                            } else {
                                sendChatMessage("§cUsage: /cofl set angryCoopProtectionEnabled <true/false>");
                                return 1;
                            }
                        }
                    } else if (args.length >= 1 && args[0].equals("sellprotection")) {
                        if (args.length == 1) {
                            // Show current settings
                            com.coflnet.config.CoflModConfig config = com.coflnet.config.SellProtectionManager.getConfig();
                            sendChatMessage("§6=== Sell Protection Settings ===");
                            sendChatMessage("§7Enabled: " + (config.sellProtectionEnabled ? "§aYes" : "§cNo"));
                            sendChatMessage("§7Max Amount: §6" + formatCoins(config.sellProtectionThreshold) + " coins");
                            sendChatMessage("§7Usage: §e/cofl set sellProtectionEnabled <true/false>");
                            sendChatMessage("§7Usage: §e/cofl set sellProtectionThreshold <amount>");
                            sendChatMessage("§7Examples: §e1000§7, §e2k§7, §e3m§7, §e1.5b");
                            return 1;
                        }
                    } else if (args.length >= 1 && args[0].equals("angrycoop")) {
                        if (args.length == 1) {
                            com.coflnet.config.CoflModConfig config = com.coflnet.config.AngryCoopProtectionManager.getConfig();
                            sendChatMessage("§6=== Angry Co-op Protection Settings ===");
                            sendChatMessage("§7Enabled: " + (config.angryCoopProtectionEnabled ? "§aYes" : "§cNo"));
                            sendChatMessage("§7Usage: §e/cofl set angryCoopProtectionEnabled <true/false>");
                            return 1;
                        }
                    }

                    // Special internal-only commands
                    if (args.length >= 1 && args[0].equalsIgnoreCase("bazaarsearch")) {
                        if (args.length >= 2) {
                            // Join remaining args as the search term
                            String[] part = new String[args.length - 1];
                            System.arraycopy(args, 1, part, 0, part.length);
                            String searchTerm = String.join(" ", part);
                            searchInBazaarSmart(searchTerm);
                            return 1;
                        } else {
                            sendChatMessage("§cUsage: /cofl bazaarsearch <item>");
                            return 1;
                        }
                    }

                    if (handlePendingConnectConfirmation(args, username)) {
                        return 1;
                    }

                    if (queueUntrustedConnectConfirmation(args)) {
                        return 1;
                    }

                    // Pass to CoflSkyCommand for other commands
                    CoflSkyCommand.processCommand(args, username);
                    return 1;
                })));
    }

    private void handleGetHoveredItem(Minecraft client) {
         uploadItem(client.player.getInventory().getItem(client.player.getInventory().getSelectedSlot()));
    }

    public static void uploadItem(ItemStack hoveredStack) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || hoveredStack == null) return;

        scheduleMapUpload(client, hoveredStack);

        RawCommand data = new RawCommand("hotkey", gson.toJson("upload_item" + getContextToAppend(hoveredStack)));
        WSClientWrapper wrapper = CoflCore.Wrapper;
        if (wrapper != null) wrapper.SendMessage(data);
    }

    public static void maybeUploadHoveredMapContent(ItemStack hoveredStack) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null || hoveredStack == null)
            return;

        long now = System.currentTimeMillis();
        if (now - lastHoveredMapUploadCheckMs < HOVERED_MAP_UPLOAD_INTERVAL_MS)
            return;

        lastHoveredMapUploadCheckMs = now;
        scheduleMapUpload(client, hoveredStack);
    }

    private static void tryUploadViewedMapData(Minecraft client) {
        if (client == null || client.player == null || client.level == null)
            return;

        scheduleMapUpload(client, client.player.getMainHandItem());
        scheduleMapUpload(client, client.player.getOffhandItem());
    }

    private static void scheduleMapUpload(Minecraft client, ItemStack stack) {
        if (!isDonutServerContext() || client == null || client.level == null || stack == null || stack.isEmpty())
            return;

        if (stack.getItem() != Items.FILLED_MAP)
            return;

        String itemId = "minecraft:filled_map";

        Integer mapId = extractMapIdFromStack(stack);
        if (mapId == null)
            return;

        // Fast path: avoid reflective color extraction for map ids already uploaded.
        if (uploadedMapIds.contains(mapId))
            return;

        byte[] mapColors = getMapColorsReflectively(stack, client.level);
        if (mapColors == null || mapColors.length == 0)
            return;

        // Upload each effective map id at most once per client session.
        if (!uploadedMapIds.add(mapId))
            return;

        int colorHash = Arrays.hashCode(mapColors);
        byte[] colorsCopy = Arrays.copyOf(mapColors, mapColors.length);
        String displayName = stack.getHoverName().getString();
        String username = client.getUser().getName();
        Thread.startVirtualThread(() -> uploadMapContent(mapId, colorsCopy, itemId, displayName, username, colorHash));
    }

    private static void uploadMapContent(int mapId, byte[] mapColors, String itemId, String displayName, String username, int colorHash) {
        JsonObject body = new JsonObject();
        body.addProperty("colorsBase64", Base64.getEncoder().encodeToString(mapColors));
        body.addProperty("width", 128);
        body.addProperty("height", 128);
        body.addProperty("hash", Integer.toHexString(colorHash));
        body.addProperty("itemId", itemId);
        body.addProperty("displayName", displayName);
        body.addProperty("source", "skyblockmodfabric");

        String response = QueryServerCommands.PostRequest(getDonutApiBaseUrl() + "/api/donut/maps/" + mapId, body.toString(), username);
        if (response == null)
            System.out.println("[CoflModClient] Map upload failed for map " + mapId + ", not retrying to avoid backend spam.");
    }

    private static String getDonutApiBaseUrl() {
        if (Config.BaseUrl != null && Config.BaseUrl.contains("localhost"))
            return "http://localhost:8000";

        return "https://donut.coflnet.com";
    }

    private static Integer extractMapIdFromStack(ItemStack stack) {
        CompoundTag itemNbt = extractSingleStackNbt(stack);
        if (itemNbt == null)
            return null;

        CompoundTag components = itemNbt.getCompound("components").orElse(null);
        if (components == null)
            return null;

        Integer directMapId = components.getInt("minecraft:map_id").orElse(null);
        if (directMapId != null)
            return directMapId;

        CompoundTag customData = components.getCompound("minecraft:custom_data").orElse(null);
        if (customData == null)
            return null;

        CompoundTag publicBukkitValues = customData.getCompound("PublicBukkitValues").orElse(null);
        if (publicBukkitValues == null)
            return null;

        Integer copyId = publicBukkitValues.getInt("minecraft:copyid").orElse(null);
        if (copyId != null)
            return copyId;

        return publicBukkitValues.getInt("minecraft:map_id").orElse(null);
    }

    private static CompoundTag extractSingleStackNbt(ItemStack stack) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || stack == null || stack.isEmpty())
            return null;

        NonNullList<ItemStack> singleItem = NonNullList.create();
        singleItem.add(stack);
        CompoundTag root = writeNbt(new CompoundTag(), singleItem, client.player.registryAccess());
        ListTag items = root.getList("i").orElse(null);
        if (items == null || items.isEmpty())
            return null;

        return items.getCompound(0).orElse(null);
    }

    private static byte[] getMapColorsReflectively(ItemStack stack, Object level) {
        Object mapState = invokeMapStateGetter(stack, level);
        if (mapState == null)
            return null;

        Object colors = getFieldValue(mapState, "colors", "field_122");
        return colors instanceof byte[] bytes ? bytes : null;
    }

    private static Object invokeMapStateGetter(ItemStack stack, Object level) {
        String[] candidateClassNames = new String[] {
                "net.minecraft.world.item.MapItem",
                "net.minecraft.world.item.FilledMapItem",
                "net.minecraft.item.FilledMapItem"
        };
        String[] candidateMethodNames = new String[] { "getSavedData", "getMapState" };

        for (String className : candidateClassNames) {
            try {
                Class<?> owner = Class.forName(className);
                for (String methodName : candidateMethodNames) {
                    for (java.lang.reflect.Method method : owner.getDeclaredMethods()) {
                        if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())
                                || !method.getName().equals(methodName)
                                || method.getParameterCount() != 2) {
                            continue;
                        }

                        Class<?>[] parameterTypes = method.getParameterTypes();
                        if (!parameterTypes[0].isAssignableFrom(stack.getClass())
                                || !parameterTypes[1].isAssignableFrom(level.getClass())) {
                            continue;
                        }

                        method.setAccessible(true);
                        return method.invoke(null, stack, level);
                    }
                }
            } catch (ClassNotFoundException ignored) {
                // Try the next candidate name.
            } catch (ReflectiveOperationException e) {
                System.out.println("[CoflModClient] Failed to resolve map state: " + e.getMessage());
            }
        }

        return null;
    }

    private static Object getFieldValue(Object target, String... fieldNames) {
        for (String fieldName : fieldNames) {
            try {
                Field field = target.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Try the next candidate field name.
            } catch (IllegalAccessException e) {
                System.out.println("[CoflModClient] Failed to read field " + fieldName + ": " + e.getMessage());
                return null;
            }
        }

        return null;
    }

    private static String getContextToAppend(ItemStack hoveredStack) {
        String toAppend = "";
        Minecraft client = Minecraft.getInstance();
        if (client.player == null)
            return "";

        NonNullList<ItemStack> mockList = NonNullList.create();
        mockList.add(hoveredStack);
        return "|" + inventoryToNBT(mockList);
    }

    private static void uploadTabList() {
        Command<String[]> data = new Command<>(CommandType.uploadTab, CoflModClient.getTabList().toArray(new String[0]));
        if (CoflCore.Wrapper != null)
            CoflCore.Wrapper.SendMessage(data);
    }

    private static void uploadScoreboard() {
        String[] scores = CoflModClient.getScoreboard().toArray(new String[0]);
        lastScoreboardUploaded = getRelevantLinesFromScoreboard(scores);
        Command<String[]> data = new Command<>(CommandType.uploadScoreboard, scores);
        if(CoflCore.Wrapper != null)
            CoflCore.Wrapper.SendMessage(data);
    }

    /**
     * Public method to upload both scoreboard and tab list.
     * Called by EventSubscribers when the backend signals it's ready (OnLoggedIn event).
     */
    public static void uploadScoreboardAndTabList() {
        runOnClientThread(() -> {
            uploadScoreboard();
            uploadTabList();
        });
    }

    public static FlipData popFlipData() {
        FlipData fd = EventSubscribers.flipData;
        EventSubscribers.flipData = null;
        return fd;
    }

    public static SoundEvent findByName(String name) {
        SoundEvent result = SoundEvents.NOTE_BLOCK_BELL.value();

        for (Field f : SoundEvents.class.getDeclaredFields()) {
            if (f.getName().equalsIgnoreCase(name)) {
                try {
                    try {
                        result = (SoundEvent) f.get(SoundEvent.class);
                    } catch (ClassCastException e) {
                        result = (SoundEvent) ((Holder.Reference) f.get(Holder.Reference.class)).value();
                    }
                } catch (IllegalAccessException e) {
                    System.out.println("SoundEvent inaccessible. This shouldn't happen");
                }
                break;
            }
        }
        return result;
    }

    public static NonNullList<ItemStack> inventoryToItemStacks(Inventory inventory) {
        NonNullList<ItemStack> itemStacks = NonNullList.create();

        for (int i = 0; i < inventory.getContainerSize(); i++) {
            itemStacks.add(inventory.getItem(i));
        }

        return itemStacks;
    }

    public static String inventoryToNBT(Inventory inventory) {
        return inventoryToNBT(inventoryToItemStacks(inventory));
    }

    public static String[] getItemIdsFromInventory(Inventory inventory) {
        return getItemIdsFromInventory(inventoryToItemStacks(inventory));
    }

    public static String inventoryToNBT(NonNullList<ItemStack> itemStacks) {
        CompoundTag nbtCompound = new CompoundTag();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Player player = Minecraft.getInstance().player;

        try {
            nbtCompound = writeNbt(nbtCompound, itemStacks, player.registryAccess());
            NbtIo.writeCompressed(nbtCompound, baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());

        } catch (IOException e) {
            e.printStackTrace();
        }
        return "";
    }

    public static CompoundTag writeNbt(CompoundTag nbt, NonNullList<ItemStack> stacks, HolderLookup.Provider registries) {
        ListTag nbtList = new ListTag();

        for(int i = 0; i < stacks.size(); ++i) {
            ItemStack itemStack = (ItemStack)stacks.get(i);
            if (!itemStack.isEmpty()) {
                CompoundTag nbtCompound = new CompoundTag();
                nbtCompound.putByte("Slot", (byte)i);
                nbtList.add((Tag)ItemStack.CODEC.encode(itemStack, registries.createSerializationContext(NbtOps.INSTANCE), nbtCompound).getOrThrow());
            } else {
                // If the stack is empty, we can still add an empty CompoundTag to keep the slot index and give the backend an easier time figuring out the structure
                CompoundTag nbtCompound = new CompoundTag();
                nbtList.add(nbtCompound);
            }
        }

        if (!nbtList.isEmpty()) {
            nbt.put("i", nbtList);
        }

        return nbt;
    }

    public static String[] getItemIdsFromInventory(NonNullList<ItemStack> itemStacks) {
        ArrayList<String> res = new ArrayList<>();

        for (int i = 0; i < itemStacks.size(); i++) {
            ItemStack stack = itemStacks.get(i);
            if (stack.getItem() != Items.AIR) {
                String id = getIdFromStack(stack);
                knownIds.add(id);
                res.add(id);
            } else
                res.add("EMPTY_SLOT_" + i); // Add a placeholder for empty slots
        }

        return res.toArray(String[]::new);
    }

    public static String getUuidFromStack(ItemStack stack) {
        // O(1) direct component access instead of O(n) iteration + toString + contains
        var customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null || customData.isEmpty()) return null;
        CompoundTag tag = customData.copyTag();
        // Fast path: string uuid (most common case, no serialization needed)
        String uuid = tag.getString("uuid").orElse(null);
        if (uuid != null) return uuid;
        // Slow path: complex uuid type (object/list) — use NBT directly, not Gson+SNBT
        Tag uuidTag = tag.get("uuid");
        if (uuidTag == null) return null;
        return uuidTag.toString();
    }

    public static String getIdFromStack(ItemStack stack) {
        String itemName = stack.getCustomName() == null ? stack.getItem().getName(stack).getString() : stack.getCustomName().getString();
        if(itemName.contains("BUY") || itemName.contains("SELL"))
        {
            // bazaar order, separate by price per unit as well
            for (Component line : stack.get(DataComponents.LORE).lines()) {
                if(line.getString().contains("Price per unit"))
                {
                    return itemName + line.getString();
                }
            }
        }
        if (isDonutServerContext()) {
            String donutItemKey = getDonutItemKeyFromStack(stack);
            if (donutItemKey != null)
                return donutItemKey;
        }
        String uuid = getUuidFromStack(stack);
        if (uuid != null)
            return uuid;
        // If "id" is not present, use the item's name
        return itemName + ";" + stack.getCount();
    }

    private static String getDonutItemKeyFromStack(ItemStack stack) {
        CompoundTag itemNbt = extractSingleStackNbt(stack);
        if (itemNbt == null)
            return null;

        String itemId = itemNbt.getString("id").orElse("");
        if (itemId.isBlank())
            return null;

        ArrayList<String> keyParts = new ArrayList<>();
        keyParts.add(itemId.toLowerCase(Locale.ROOT));

        CompoundTag components = itemNbt.getCompound("components").orElse(null);
        addDonutEnchantments(keyParts, components, "minecraft:enchantments");
        addDonutEnchantments(keyParts, components, "minecraft:stored_enchantments");
        addDonutTrim(keyParts, components);

        Integer mapId = extractMapIdFromStack(stack);
        if (mapId != null)
            keyParts.add("mapId:" + mapId);

        int countBucket = getDonutCountBucket(stack.getCount());
        if (countBucket > 1)
            keyParts.add("count:" + countBucket);

        return String.join("|", keyParts);
    }

    private static void addDonutEnchantments(ArrayList<String> keyParts, CompoundTag components, String componentName) {
        if (components == null)
            return;

        CompoundTag enchantComponent = components.getCompound(componentName).orElse(null);
        if (enchantComponent == null)
            return;

        CompoundTag levels = enchantComponent.getCompound("levels").orElse(enchantComponent);
        ArrayList<String> enchantParts = new ArrayList<>();
        for (String enchantKey : levels.keySet()) {
            int level = levels.getInt(enchantKey).orElse(0);
            String normalizedEnchant = normalizeDonutKeyValue(enchantKey);
            if (level >= 3 && DONUT_VALUABLE_ENCHANTS.contains(normalizedEnchant))
                enchantParts.add(normalizedEnchant + ":" + level);
        }

        enchantParts.sort(String::compareTo);
        keyParts.addAll(enchantParts);
    }

    private static void addDonutTrim(ArrayList<String> keyParts, CompoundTag components) {
        if (components == null)
            return;

        CompoundTag trim = components.getCompound("minecraft:trim").orElse(null);
        if (trim == null)
            return;

        String pattern = readDonutTrimValue(trim, "pattern");
        String material = readDonutTrimValue(trim, "material");
        if (pattern != null || material != null)
            keyParts.add("trim:" + (pattern == null ? "none" : pattern) + ":" + (material == null ? "none" : material));
    }

    private static String readDonutTrimValue(CompoundTag trim, String key) {
        String directValue = trim.getString(key).orElse(null);
        if (directValue != null && !directValue.isBlank())
            return normalizeDonutKeyValue(directValue);

        CompoundTag nested = trim.getCompound(key).orElse(null);
        if (nested == null)
            return null;

        String assetId = nested.getString("asset_id").orElse(null);
        if (assetId != null && !assetId.isBlank())
            return normalizeDonutKeyValue(assetId);

        String id = nested.getString("id").orElse(null);
        return id == null || id.isBlank() ? null : normalizeDonutKeyValue(id);
    }

    private static String normalizeDonutKeyValue(String value) {
        String normalized = value.replace("\"", "").trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("minecraft:"))
            normalized = normalized.substring("minecraft:".length());
        return normalized;
    }

    private static int getDonutCountBucket(int count) {
        if (count <= 0) return 1;
        if (count >= 64) return 64;
        if (count >= 16) return 16;
        if (count >= 4) return 4;
        return 1;
    }

    public void loadDescriptionsForInv(AbstractContainerScreen screen) {
        if (screen == null || !shouldTriggerDescriptionLoad(screen)) {
            return;
        }

        String menuSlot = Minecraft.getInstance().player.getInventory().getItem(8).getComponents().toString();
        if (!isDonutServerContext()
            && !menuSlot.contains("minecraft:custom_data=>{id:\"SKYBLOCK_MENU\"}")
            && !menuSlot.contains("Scaffolding") && !menuSlot.contains("Quiver")
            && !menuSlot.contains("Your Score Summary") // dungeon completion
            )
            return;
        Thread.startVirtualThread(() -> {
            NonNullList<ItemStack> itemStacks = screen.getMenu().getItems();
            String title = screen.getTitle().getString();
            try {
                Thread.sleep(100);
                for (int i = 0; i < 20; i++) {
                    if(itemStacks.size() <= InventorysizeWithOffHand || !itemStacks.get(itemStacks.size() - InventorysizeWithOffHand).isEmpty())
                        break;
                    Thread.sleep(50); // wait for the screen to load
                    System.out.println("Waiting for item stacks to load...");
                    itemStacks = screen.getMenu().getItems();
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            try {
                AbstractContainerScreen currentScreen = Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen ? (AbstractContainerScreen) Minecraft.getInstance().gui.screen() : null;
                if (currentScreen == null || currentScreen.getMenu() != screen.getMenu()){
                    System.out.println("Inventory changed already, not refreshing descriptions");
                    return; // inventory changed, don't refresh
                }
                String[] visibleItems = getItemIdsFromInventory(itemStacks);
                loadDescriptionsForItems(title, itemStacks);
                boolean refresh = false;
                if(title.contains("Auctions"))
                {
                    for (ItemStack itemStack : itemStacks) {
                        if(itemStack.get(DataComponents.LORE) == null)
                            continue;
                        for (Component line : itemStack.get(DataComponents.LORE).lines()) {
                            if(line.getString().contains("Refreshing..."))
                            {
                                refresh = true;
                                break;
                            }
                        }
                    }
                    if(refresh)
                        Thread.sleep(500); // wait extra for names to load
                }
                Thread.sleep(1000);
                // check all items in the inventory for descriptions
                String[] itemIds = getItemIdsFromInventory(screen.getMenu().getItems());
                List<String> visibleList = Arrays.asList(visibleItems);
                for (String itemId : itemIds) {
                    if (!visibleList.contains(itemId) && !itemId.startsWith("EMPTY_SLOT_")) {
                        refresh = true;
                        break;
                    }
                }
                if (refresh) {
                    currentScreen = Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen ? (AbstractContainerScreen) Minecraft.getInstance().gui.screen() : null;
                    if (currentScreen == null || currentScreen.getMenu() != screen.getMenu()){
                        System.out.println("Inventory changed, not refreshing descriptions");
                        return; // inventory changed, don't refresh
                        }
                    if(!title.equals(screen.getTitle().getString()))
                    {
                        System.out.println("Title changed, not refreshing descriptions");
                        return;
                    }
                    System.out.println("Refreshing descriptions for inventory: " + title);
                    loadDescriptionsForItems(title, itemStacks);
                }
            } catch (Exception e) {
                e.printStackTrace();
                System.out.println("Failed to load descriptions for inventory: " + e + " "
                        + inventoryToNBT(itemStacks));
            }
        });
    }

    private static boolean shouldTriggerDescriptionLoad(AbstractContainerScreen screen) {
        String title = screen.getTitle().getString();
        String menuKey = title + "#" + System.identityHashCode(screen.getMenu());
        long now = System.currentTimeMillis();
        Long previous = lastDescriptionLoadRequestByMenu.put(menuKey, now);
        if (previous != null && (now - previous) < DESCRIPTION_LOAD_TRIGGER_DEBOUNCE_MS) {
            return false;
        }
        return true;
    }

    public static void loadDescriptionsForItems(String title, NonNullList<ItemStack> items)
    {
        String userName = Minecraft.getInstance().getUser().getName();
        String nbtString = inventoryToNBT(items);
        if(nbtString.equals(lastNbtRequest)) {
            return;
        }
        lastNbtRequest = nbtString;

        // Check if we should throttle this request
        long currentTime = System.currentTimeMillis();
        Long lastRefreshTime = lastRefreshTimePerInventory.get(title);

        if (lastRefreshTime != null && (currentTime - lastRefreshTime) < REFRESH_THROTTLE_MS) {
            // Too soon since last refresh, schedule the request to be made after the throttle period
            long delayMs = REFRESH_THROTTLE_MS - (currentTime - lastRefreshTime);
            System.out.println("Throttling refresh for inventory: " + title + " (wait " + delayMs + "ms)");
            Thread.startVirtualThread(() -> {
                try {
                    Thread.sleep(delayMs);
                    // Request with current inventory state (in case it updated)
                    NonNullList<ItemStack> currentItems = NonNullList.create();
                    AbstractContainerScreen currentScreen = Minecraft.getInstance().gui.screen() instanceof AbstractContainerScreen
                        ? (AbstractContainerScreen) Minecraft.getInstance().gui.screen()
                        : null;
                    if (currentScreen != null && currentScreen.getTitle().getString().equals(title)) {
                        currentItems.addAll(currentScreen.getMenu().getItems());
                    } else {
                        // Inventory changed, use the items we have
                        currentItems = items;
                    }
                        String currentNbt = inventoryToNBT(currentItems);
                        String[] visibleItems = getItemIdsFromInventory(currentItems);
                    DescriptionHandler.loadDescriptionForInventory(
                            visibleItems,
                            title,
                            currentNbt,
                            userName,
                            posToUpload
                    );
                    lastRefreshTimePerInventory.put(title, System.currentTimeMillis());
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            });
            return;
        }

        // Update last refresh time and make the request
        lastRefreshTimePerInventory.put(title, currentTime);
        String[] visibleItems = getItemIdsFromInventory(items);
        DescriptionHandler.loadDescriptionForInventory(
                visibleItems,
                title,
                nbtString,
                userName,
                posToUpload
        );
    }

    private static List<String> getScoreboard() {
        ObjectArrayList<String> scoreboardAsText = new ObjectArrayList<>();
        if (Minecraft.getInstance() == null || Minecraft.getInstance().level == null) {
            System.out.println("Minecraft or world is null, cannot get scoreboard.");
            return scoreboardAsText;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            System.out.println("Player is null, cannot get scoreboard.");
            return scoreboardAsText;
        }

        Scoreboard scoreboard = Minecraft.getInstance().level.getScoreboard();
        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.BY_ID.apply(1));

        for (ScoreHolder scoreHolder : scoreboard.getTrackedPlayers()) {
            if (scoreboard.getPlayerScoreInfo(scoreHolder, objective) == null)
                continue;
            net.minecraft.world.scores.PlayerTeam team = scoreboard.getPlayersTeam(scoreHolder.getScoreboardName());

            if (team != null) {
                String strLine = team.getPlayerPrefix().getString() + team.getPlayerSuffix().getString();

                if (!strLine.trim().isEmpty()) {
                    String formatted = ChatFormatting.stripFormatting(strLine);
                    scoreboardAsText.add(formatted);
                }
            }
        }

        if (objective != null) {
            scoreboardAsText.add(objective.getFormattedDisplayName().getString());
            Collections.reverse(scoreboardAsText);
        }
        return scoreboardAsText;
    }

    private static List<String> getTabList() {
        List<String> tabList = new ArrayList<>();
        if (Minecraft.getInstance() == null || Minecraft.getInstance().level == null) {
            System.out.println("Minecraft or world is null, cannot get tab list.");
            return tabList;
        }

        Minecraft client = Minecraft.getInstance();
        ClientPacketListener networkHandler = client.getConnection();

        if (networkHandler != null) {
            for (PlayerInfo playerListEntry : networkHandler.getOnlinePlayers()) {
                if (playerListEntry != null) {
                    // Get the display name (the text shown in the tab list)
                    if (playerListEntry.getTabListDisplayName() != null) {
                        String displayName = playerListEntry.getTabListDisplayName().getString();
                        tabList.add(displayName);
                    } else {
                        String playerName = playerListEntry.getProfile().name();
                        tabList.add(playerName);
                    }
                }
            }
        }
        return tabList;
    }

    public static boolean isBINAuction(ContainerScreen gcs) {
        return (BinGUI.isAuctionInit(gcs) || BinGUI.isAuctionConfirming(gcs));
    }

    public static boolean isOwnAuction(ContainerScreen gcs) {
        ItemStack stack = gcs.getMenu().getContainer().getItem(31);
        return (BinGUI.isAuctionInit(gcs) && (stack.getItem() == Items.STAINED_GLASS_PANE.gray() || stack.getItem() == Items.GOLD_BLOCK));
    }

    /**
     * Determines if the given screen is the bazaar.
     * @param gcs instance of {@link ContainerScreen}
     * @return {@code true} if the screen is the bazaar, otherwise {@code false}
     */
    public static boolean isBazaar(ContainerScreen gcs) {
        String title = gcs.getTitle().getString();
        return title.contains("Bazaar") && gcs.getMenu().getContainer().getContainerSize() == 9 * 6;
    }

    /**
     * Automatically searches for an item in the bazaar.
     * This function clicks on the "Search" item, fills in the search content, and closes the sign.
     *
     * Usage examples:
     * - searchInBazaar("Iron Ingot") - searches for iron ingots
     * - searchInBazaar("Enchanted Diamond") - searches for enchanted diamonds
     *
     * The function will:
     * 1. Verify the player is currently on the bazaar
     * 2. Find the search item in the GUI (typically a name tag or paper)
     * 3. Click on the search item to open the sign editor
     * 4. Fill in the search term automatically
     * 5. Close the sign to execute the search
     *
     * @param searchTerm the item name to search for
     */
    public static void searchInBazaar(String searchTerm) {
        Minecraft client = Minecraft.getInstance();
        if (!(client.gui.screen() instanceof ContainerScreen gcs)) {
            System.out.println("Current screen is not a container screen");
            return;
        }

        if (!isBazaar(gcs)) {
            System.out.println("Current screen is not the bazaar");
            return;
        }

        // Find the "Search" item in the bazaar GUI
        int searchSlot = findSearchSlotInBazaar(gcs);
        if (searchSlot == -1) {
            System.out.println("Could not find Search item in bazaar");
            return;
        }

        System.out.println("Found Search item at slot: " + searchSlot + ", searching for: " + searchTerm);

        // Store the search term for the sign editing
        pendingBazaarSearch = searchTerm;

        // Click on the search item
        clickSlotInContainer(gcs, searchSlot);
    }

    /**
     * Enhanced bazaar search that also handles item name cleaning.
     * @param rawItemName the raw item name that may need cleaning
     */
    public static void searchInBazaarSmart(String rawItemName) {
        // Clean the item name (remove formatting codes, etc.)
        String cleanedName = cleanItemNameForSearch(rawItemName);
        searchInBazaar(cleanedName);
    }

    /**
     * Cleans an item name for bazaar search by removing Minecraft formatting codes and other artifacts.
     * @param rawName the raw item name
     * @return cleaned item name suitable for bazaar search
     */
    private static String cleanItemNameForSearch(String rawName) {
        if (rawName == null) return "";

        // Remove Minecraft formatting codes (§ followed by any character)
        String cleaned = rawName.replaceAll("§.", "");

        // Remove common prefixes/suffixes that might interfere with search
        cleaned = cleaned.replaceAll("\\[.*?\\]", ""); // Remove brackets
        cleaned = cleaned.replaceAll("\\(.*?\\)", ""); // Remove parentheses

        // Trim whitespace
        cleaned = cleaned.trim();

        return cleaned;
    }

    /**
     * Finds the slot containing the "Search" item in the bazaar.
     * @param gcs the bazaar container screen
     * @return the slot index of the search item, or -1 if not found
     */
    private static int findSearchSlotInBazaar(ContainerScreen gcs) {
        for (int i = 0; i < gcs.getMenu().getContainer().getContainerSize(); i++) {
            ItemStack stack = gcs.getMenu().getContainer().getItem(i);

            // Skip empty slots
            if (stack.isEmpty()) {
                continue;
            }

            // Check for common search item types
            if (stack.getItem() == Items.NAME_TAG ||
                stack.getItem() == Items.PAPER ||
                stack.getItem() == Items.WRITABLE_BOOK ||
                stack.getItem() == Items.COMPASS) {

                // Check if the item has a custom name containing "Search"
                if (stack.getCustomName() != null) {
                    String customName = stack.getCustomName().getString().toLowerCase();
                    if (customName.contains("search")) {
                        return i;
                    }
                }

                // Check lore for search functionality
                if (stack.get(DataComponents.LORE) != null) {
                    for (Component line : stack.get(DataComponents.LORE).lines()) {
                        String loreText = line.getString().toLowerCase();
                        if (loreText.contains("search") || loreText.contains("find") || loreText.contains("look for")) {
                            return i;
                        }
                    }
                }
            }
        }

        // Fallback: look for any item with "search" in the name or lore
        for (int i = 0; i < gcs.getMenu().getContainer().getContainerSize(); i++) {
            ItemStack stack = gcs.getMenu().getContainer().getItem(i);

            if (stack.isEmpty()) continue;

            if (stack.getCustomName() != null) {
                String customName = stack.getCustomName().getString().toLowerCase();
                if (customName.contains("search")) {
                    return i;
                }
            }

            if (stack.get(DataComponents.LORE) != null) {
                for (Component line : stack.get(DataComponents.LORE).lines()) {
                    String loreText = line.getString().toLowerCase();
                    if (loreText.contains("search") && (loreText.contains("click") || loreText.contains("use"))) {
                        return i;
                    }
                }
            }
        }

        return -1;
    }

    /**
     * Clicks a slot in a container screen.
     * @param gcs the container screen
     * @param slotId the slot index to click
     */
    private static void clickSlotInContainer(ContainerScreen gcs, int slotId) {
        Minecraft client = Minecraft.getInstance();
        Player player = client.player;

        client.gameMode.handleContainerInput(
                gcs.getMenu().containerId,
                slotId,
                0,
                ContainerInput.PICKUP,
                player
        );
    }

    private boolean checkVersionCompability() {
        try {
            String v = net.minecraft.SharedConstants.getCurrentVersion().id();
            boolean b = v.compareTo(targetVersion) == 0;

            return b;
        } catch (NoSuchMethodError e){
            return false;
        }
    }

    private static Pair<String, String> getRelevantLinesFromScoreboard(String[] scores){
        String leftVal = "";
        String rightVal = "null";

        for (String score : scores) {
            if (score.startsWith("Purse: ") || score.startsWith("Piggy: ")) leftVal = score;
            if (score.startsWith(" ⏣ ")) rightVal = score;
        }

        return new Pair<>(leftVal, rightVal);
    }

    public static String findPriceSuggestion(){
        for (DescriptionHandler.DescModification descMod : getExtraSlotDescMod()) {
            System.out.println(descMod.type+"|"+descMod.value);
            if (descMod.type.compareTo("SUGGEST") == 0) return descMod.value;
        }

        return "";
    }

    public static DescriptionHandler.DescModification[] getExtraSlotDescMod(){
        return DescriptionHandler.getInfoDisplay();
    }

    public static void setHotKeys(HotkeyRegister[] keys) {
        additionalKeyBindings.clear();
        keybindingsToHotkeys.clear();
        for (int i = 0; i < keys.length; i++) {
            int keyIndex = getKeyIndex(keys[i].DefaultKey.toUpperCase());

            HotkeyRegister hotkey = keys[i];
            KeyMapping keyBinding = new KeyMapping(hotkey.Name, keyIndex, SKYCOFL_UNCHANGEABLE_CATEGORY);
            additionalKeyBindings.add(keyBinding);
            keybindingsToHotkeys.put(keyBinding, hotkey);
            System.out.println("Registered Key: " + hotkey.Name + " with key " + hotkey.DefaultKey.toUpperCase() + " (" +keyIndex+")");
            //KeyMappingHelper.registerKeyMapping(additionalKeyBindings.get(i));
        }
    }

    public static int getKeyIndex(String name){
        int result = -1;
        String prefix = "GLFW_KEY_";
        for (Field f : GLFW.class.getDeclaredFields()) {
            if (f.getName().startsWith(prefix) && f.getName().substring(prefix.length()).equals(name)) {
                try {
                    result = (int) f.get(int.class);
                } catch (IllegalAccessException e) {
                    System.out.println("Key inaccessible. This shouldn't happen");
                }
                break;
            }
        }

        return result;
    }

    /**
     * Adds sell protection warnings to item tooltips
     */
    private static void addSellProtectionTooltip(ItemStack stack, List<Component> lines) {
        try {
            // Check if sell protection is enabled
            if (!com.coflnet.config.SellProtectionManager.isEnabled()) {
                return;
            }

            // Check if we're in a screen with "➜" in title
            Minecraft client = Minecraft.getInstance();
            if (client.gui.screen() instanceof AbstractContainerScreen<?> screen) {
                String screenTitle = screen.getTitle().getString();
                if (!screenTitle.contains("➜")) {
                    return;
                }
            } else {
                return;
            }

            String itemName = "";
            if (stack.getCustomName() != null) {
                itemName = stack.getCustomName().getString();
            } else {
                itemName = stack.getItem().getName(stack).getString();
            }

            // Get threshold for comparison
            long threshold = com.coflnet.config.SellProtectionManager.getMaxAmount();
            String formattedThreshold = formatCoins(threshold);

            // Extract coin amount and only show warning if over threshold
            long sellAmount = 0;
            boolean shouldShowWarning = false;

            if (itemName.contains("Sell Instantly")) {
                sellAmount = com.coflnet.utils.SellAmountParser.extractSellInstantlyAmountFromTooltip(lines);
                // Only show warning if we successfully parsed an amount and it's over threshold
                // Don't show warning for the default protection amount (Long.MAX_VALUE)
                shouldShowWarning = sellAmount > threshold && sellAmount != com.coflnet.utils.SellAmountParser.getDefaultProtectionAmount();

                if (shouldShowWarning) {
                    lines.add(Component.literal(""));
                    lines.add(Component.literal("§c⚠ §lSell Protection §c⚠"));
                    lines.add(Component.literal("§7Left clicks blocked if > §6" + formattedThreshold + " coins"));
                    lines.add(Component.literal("§bHold Ctrl§7 to override."));
                    lines.add(Component.literal("§8/cofl set sellProtectionThreshold <amount>"));
                }
            } else if (itemName.contains("Sell Sacks Now") || itemName.contains("Sell Inventory Now")) {
                sellAmount = com.coflnet.utils.SellAmountParser.extractInventorySackAmountFromTooltip(lines);
                // Only show warning if we successfully parsed an amount and it's over threshold
                // Don't show warning for the default protection amount (Long.MAX_VALUE)
                shouldShowWarning = sellAmount > threshold && sellAmount != com.coflnet.utils.SellAmountParser.getDefaultProtectionAmount();

                if (shouldShowWarning) {
                    lines.add(Component.literal(""));
                    lines.add(Component.literal("§c⚠ §lSell Protection §c⚠"));
                    lines.add(Component.literal("§7All clicks blocked if > §6" + formattedThreshold + " coins"));
                    lines.add(Component.literal("§bHold Ctrl§7 to override."));
                    lines.add(Component.literal("§8/cofl set sellProtectionThreshold <amount>"));
                }
            }
        } catch (Exception e) {
            System.out.println("[CoflModClient] addSellProtectionTooltip failed: " + e.getMessage());
        }
    }

    /**
     * Parses amount strings with k/m/b suffixes (e.g., "2k", "3m", "1.5b")
     * @param amountStr the string to parse (e.g., "1000", "2k", "3m", "1.5b")
     * @return the parsed amount as a long
     * @throws NumberFormatException if the string cannot be parsed
     */
    private static long parseAmountString(String amountStr) throws NumberFormatException {
        if (amountStr == null || amountStr.trim().isEmpty()) {
            throw new NumberFormatException("Empty amount string");
        }

        String input = amountStr.trim().toLowerCase();

        // Handle plain numbers first
        if (input.matches("^[0-9]+$")) {
            return Long.parseLong(input);
        }

        // Handle numbers with decimal points (e.g., "1.5")
        if (input.matches("^[0-9]+\\.[0-9]+$")) {
            return (long) (Double.parseDouble(input));
        }

        // Handle suffixed numbers
        if (input.matches("^[0-9]+\\.?[0-9]*[kmb]$")) {
            char suffix = input.charAt(input.length() - 1);
            String numberPart = input.substring(0, input.length() - 1);

            double value;
            try {
                value = Double.parseDouble(numberPart);
            } catch (NumberFormatException e) {
                throw new NumberFormatException("Invalid number part: " + numberPart);
            }

            switch (suffix) {
                case 'k':
                    return (long) (value * 1_000);
                case 'm':
                    return (long) (value * 1_000_000);
                case 'b':
                    return (long) (value * 1_000_000_000);
                default:
                    throw new NumberFormatException("Invalid suffix: " + suffix);
            }
        }

        // If we get here, the format is not recognized
        throw new NumberFormatException("Invalid format: " + amountStr + ". Use formats like: 1000, 2k, 3m, 1.5b");
    }

    private static String formatCoins(long coins) {
        if (coins >= 1000000000) {
            return String.format(java.util.Locale.US, "%.1fB", coins / 1000000000.0);
        } else if (coins >= 1000000) {
            return String.format(java.util.Locale.US, "%.1fM", coins / 1000000.0);
        } else if (coins >= 1000) {
            return String.format(java.util.Locale.US, "%.1fK", coins / 1000.0);
        } else {
            return String.valueOf(coins);
        }
    }

    private static void sendChatMessage(String message) {
        displayModMessage(Component.literal(message));
    }

    private static void sendChatComponent(Component message) {
        displayModMessage(message);
    }

    /**
     * Displays a mod-originated message in the chat HUD.
     * When text_Tunnels is installed the message is routed through
     * ClientReceiveMessageEvents.ALLOW_GAME so text_Tunnels records its gui-tick
     * in the matching tunnel's time set, enabling per-tunnel filtering.
     */
    public static void displayModMessage(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        client.execute(() -> {
            if (!isTextTunnelsInstalled()) {
                client.gui.chatListener().handleSystemMessage(message, false);
                return;
            }
            Component prefixed = prefixCompatMessage(message);
            // Manually fire ALLOW_GAME so text_Tunnels can record this message's
            // gui-tick in the matching tunnel set. Return value is ignored because
            // our own messages must always be displayed.
            ClientReceiveMessageEvents.ALLOW_GAME.invoker().allowReceiveGameMessage(prefixed, false);
            client.gui.chatListener().handleSystemMessage(prefixed, false);
        });
    }

    static Component prefixCompatMessage(Component message) {
        if (message == null || !isTextTunnelsInstalled()) {
            return message;
        }

        String plainMessage = message.getString();
        if (plainMessage.startsWith(TEXT_TUNNELS_MESSAGE_PREFIX)) {
            return message;
        }

        return Component.empty()
            .append(Component.literal(TEXT_TUNNELS_MESSAGE_PREFIX)
                .withStyle(style -> style.withFont(new FontDescription.Resource(TEXT_TUNNELS_MESSAGE_FONT))))
            .append(message);
    }

    public static boolean isTextTunnelsInstalled() {
        return FabricLoader.getInstance().isModLoaded("text_tunnels");
    }

    /**
    /**
     * Ensures a SkyCofl tunnel entry exists in text_Tunnels' config JSON for all
     * known Hypixel server IPs (mc.hypixel.net, hypixel.net, alpha.hypixel.net).
     * Runs synchronously so the config is written before text_Tunnels loads its
     * server-specific configuration on join.
     * <p>
     * Silently skips if text_Tunnels is not installed or the entry already exists.
     * On completion it triggers a live config-reload via reflection so the change
     * takes effect without restarting.
     */
    private static void ensureTextTunnelsConfig() {
        if (!isTextTunnelsInstalled()) return;
        try {
            Path configFile = FabricLoader.getInstance().getConfigDir().resolve("textTunnels.json");
            JsonObject root;
            if (Files.exists(configFile)) {
                root = JsonParser.parseString(Files.readString(configFile)).getAsJsonObject();
            } else {
                root = new JsonObject();
            }
            if (!root.has("serversConfigs")) {
                root.add("serversConfigs", new JsonArray());
            }
            JsonArray serversConfigs = root.getAsJsonArray("serversConfigs");
            boolean modified = false;
            for (String ip : new String[]{"mc.hypixel.net", "hypixel.net", "alpha.hypixel.net"}) {
                modified |= ensureServerHasSkyCoflTunnel(serversConfigs, ip);
            }
            if (modified) {
                Files.writeString(configFile, new GsonBuilder().setPrettyPrinting().create().toJson(root));
                System.out.println("[CoflMod] Added SkyCofl tunnel to text_Tunnels config");
                triggerTextTunnelsConfigReload();
            }
        } catch (Exception e) {
            System.out.println("[CoflMod] Failed to update text_Tunnels config: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static boolean ensureServerHasSkyCoflTunnel(JsonArray serversConfigs, String ip) {
        JsonObject serverEntry = null;
        for (JsonElement el : serversConfigs) {
            if (el.isJsonObject()) {
                JsonObject obj = el.getAsJsonObject();
                if (obj.has("ip") && ip.equals(obj.get("ip").getAsString())) {
                    serverEntry = obj;
                    break;
                }
            }
        }
        if (serverEntry == null) {
            serverEntry = new JsonObject();
            serverEntry.addProperty("name", "Hypixel");
            serverEntry.addProperty("ip", ip);
            serverEntry.addProperty("enabled", true);
            serverEntry.add("tunnelConfigs", new JsonArray());
            serversConfigs.add(serverEntry);
        }
        JsonArray tunnelConfigs = serverEntry.has("tunnelConfigs")
                ? serverEntry.getAsJsonArray("tunnelConfigs")
                : new JsonArray();
        String expectedReceivePrefix = java.util.regex.Pattern.quote(TEXT_TUNNELS_MESSAGE_PREFIX);
        for (JsonElement el : tunnelConfigs) {
            if (el.isJsonObject() && "SkyCofl".equals(
                    el.getAsJsonObject().has("name")
                            ? el.getAsJsonObject().get("name").getAsString() : null)) {
                JsonObject existing = el.getAsJsonObject();
                boolean modified = false;
                if (!existing.has("receivePrefix") || !expectedReceivePrefix.equals(existing.get("receivePrefix").getAsString())) {
                    existing.addProperty("receivePrefix", expectedReceivePrefix);
                    modified = true;
                }
                if (!existing.has("sendPrefix") || !"/cofl chat ".equals(existing.get("sendPrefix").getAsString())) {
                    existing.addProperty("sendPrefix", "/cofl chat ");
                    modified = true;
                }
                if (!existing.has("enabled") || !existing.get("enabled").getAsBoolean()) {
                    existing.addProperty("enabled", true);
                    modified = true;
                }
                return modified;
            }
        }
        JsonObject tunnel = new JsonObject();
        tunnel.addProperty("enabled", true);
        tunnel.addProperty("name", "SkyCofl");
        // Regex that matches messages prefixed with our invisible marker.
        tunnel.addProperty("receivePrefix", expectedReceivePrefix);
        // Marker: ChatScreenMixin intercepts this prefix before it reaches the network
        tunnel.addProperty("sendPrefix", "/cofl chat ");
        tunnelConfigs.add(tunnel);
        serverEntry.add("tunnelConfigs", tunnelConfigs);
        return true;
    }

    /**
     * Best-effort: triggers a live reload of text_Tunnels' config by calling its
     * ConfigManager.init() + Text_tunnels.configUpdated() via reflection.
     * Logs a warning if the reflection fails (e.g. API has changed).
     */
    private static void triggerTextTunnelsConfigReload() {
        try {
            Class<?> configManager = Class.forName("org.olim.text_tunnels.config.ConfigManager");
            // Call ConfigManager.init() which internally calls HANDLER.load()
            configManager.getDeclaredMethod("init").invoke(null);
            Class<?> textTunnels = Class.forName("org.olim.text_tunnels.Text_tunnels");
            textTunnels.getDeclaredMethod("configUpdated").invoke(null);
        } catch (Exception e) {
            System.out.println("[CoflMod] Failed to trigger text_Tunnels config reload (API may have changed): " + e.getMessage());
        }
    }

    private static boolean handlePendingConnectConfirmation(String[] args, String username) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("confirmconnect")) {
            return false;
        }

        if (args.length != 2) {
            sendChatMessage("§cUsage: /cofl confirmconnect <token>");
            return true;
        }

        if (pendingUntrustedConnectArgs == null || pendingUntrustedConnectToken == null) {
            sendChatMessage("§cNo pending untrusted connection to confirm.");
            return true;
        }

        if (isPendingUntrustedConnectExpired()) {
            clearPendingUntrustedConnect();
            sendChatMessage("§cThe untrusted connection confirmation expired. Run /cofl connect again if you still want to continue.");
            return true;
        }

        if (!pendingUntrustedConnectToken.equals(args[1])) {
            sendChatMessage("§cInvalid confirmation token. Run /cofl connect again to get a new confirmation button.");
            return true;
        }

        String[] confirmedArgs = pendingUntrustedConnectArgs;
        String confirmedDestination = decodeConnectDestination(confirmedArgs[1]);
        String confirmedHost = extractConnectHost(confirmedDestination);
        clearPendingUntrustedConnect();
        sendChatMessage("§eConfirmed untrusted connection to §c" + getDisplayedConnectTarget(confirmedDestination, confirmedHost) + "§e.");
        CoflSkyCommand.processCommand(confirmedArgs, username);
        return true;
    }

    private static boolean queueUntrustedConnectConfirmation(String[] args) {
        if (args.length != 2 || !args[0].equalsIgnoreCase("connect")) {
            return false;
        }

        String destination = decodeConnectDestination(args[1]);
        String host = extractConnectHost(destination);
        if (isTrustedConnectHost(host)) {
            clearPendingUntrustedConnect();
            return false;
        }

        String confirmationToken = generateConnectConfirmationToken();
        pendingUntrustedConnectToken = confirmationToken;
        pendingUntrustedConnectArgs = Arrays.copyOf(args, args.length);
        pendingUntrustedConnectExpiresAtMs = System.currentTimeMillis() + UNTRUSTED_CONNECT_CONFIRMATION_TIMEOUT_MS;

        String displayedTarget = getDisplayedConnectTarget(destination, host);
        sendChatMessage("§c⚠ Warning: §e" + displayedTarget + " §cis not a §bcoflnet.com §cserver.");
        sendChatMessage("§cThat server can reuse your authentication against the main SkyCofl instance.");
        sendChatMessage("§cYou can lose all CoflCoins and get banned, only continue if you trust this server.");
        sendChatMessage("§eThe connection was blocked. Click the confirmation button below within 30 seconds if you want to continue.");
        sendChatComponent(Component.literal("[Confirm untrusted connection]")
                .withStyle(style -> style
                        .withColor(ChatFormatting.RED)
                        .withBold(true)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.RunCommand("/cofl confirmconnect " + confirmationToken))
                        .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                                "Connect to " + displayedTarget + " anyway\n"
                                        + "This confirmation expires in 30 seconds.")))));
        return true;
    }

    private static String generateConnectConfirmationToken() {
        byte[] tokenBytes = new byte[24];
        connectConfirmationRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    private static boolean isPendingUntrustedConnectExpired() {
        return pendingUntrustedConnectExpiresAtMs > 0L
                && System.currentTimeMillis() > pendingUntrustedConnectExpiresAtMs;
    }

    private static void clearPendingUntrustedConnect() {
        pendingUntrustedConnectToken = null;
        pendingUntrustedConnectArgs = null;
        pendingUntrustedConnectExpiresAtMs = 0L;
    }

    private static String getDisplayedConnectTarget(String destination, String host) {
        if (host != null) {
            return host;
        }
        if (destination == null || destination.isBlank()) {
            return "unknown target";
        }
        return destination;
    }

    private static String decodeConnectDestination(String rawDestination) {
        if (rawDestination == null) {
            return null;
        }

        String trimmedDestination = rawDestination.trim();
        if (trimmedDestination.isEmpty() || trimmedDestination.contains("://")) {
            return trimmedDestination;
        }

        try {
            return new String(Base64.getDecoder().decode(trimmedDestination), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return trimmedDestination;
        }
    }

    private static String extractConnectHost(String destination) {
        String normalizedDestination = normalizeConnectHost(destination);
        if (normalizedDestination == null) {
            return null;
        }

        try {
            URI uri = URI.create(normalizedDestination.contains("://") ? normalizedDestination : "wss://" + normalizedDestination);
            String parsedHost = normalizeConnectHost(uri.getHost());
            if (parsedHost != null) {
                return parsedHost;
            }
        } catch (IllegalArgumentException ignored) {
            // Fall back to manual parsing below.
        }

        String hostCandidate = normalizedDestination;
        int pathIndex = hostCandidate.indexOf('/');
        if (pathIndex >= 0) {
            hostCandidate = hostCandidate.substring(0, pathIndex);
        }

        int queryIndex = hostCandidate.indexOf('?');
        if (queryIndex >= 0) {
            hostCandidate = hostCandidate.substring(0, queryIndex);
        }

        int fragmentIndex = hostCandidate.indexOf('#');
        if (fragmentIndex >= 0) {
            hostCandidate = hostCandidate.substring(0, fragmentIndex);
        }

        int credentialsIndex = hostCandidate.lastIndexOf('@');
        if (credentialsIndex >= 0) {
            hostCandidate = hostCandidate.substring(credentialsIndex + 1);
        }

        if (hostCandidate.startsWith("[")) {
            int closingBracketIndex = hostCandidate.indexOf(']');
            if (closingBracketIndex > 0) {
                hostCandidate = hostCandidate.substring(1, closingBracketIndex);
            }
        } else {
            int portSeparatorIndex = hostCandidate.indexOf(':');
            if (portSeparatorIndex >= 0) {
                hostCandidate = hostCandidate.substring(0, portSeparatorIndex);
            }
        }

        return normalizeConnectHost(hostCandidate);
    }

    private static boolean isTrustedConnectHost(String host) {
        String normalizedHost = normalizeConnectHost(host);
        return normalizedHost != null
                && (normalizedHost.equals("coflnet.com") || normalizedHost.endsWith(".coflnet.com"));
    }

    private static String normalizeConnectHost(String value) {
        if (value == null) {
            return null;
        }

        String normalizedValue = value.trim().toLowerCase(Locale.ROOT);
        while (normalizedValue.endsWith(".")) {
            normalizedValue = normalizedValue.substring(0, normalizedValue.length() - 1);
        }

        return normalizedValue.isEmpty() ? null : normalizedValue;
    }

    private static boolean isDonutServerContext() {
        return currentServerContext == ServerContext.DONUT;
    }

    private static void applyServerContext(ServerContext serverContext) {
        if (serverContext == null) {
            return;
        }
        currentServerContext = serverContext;
        Config.ServerContext = serverContext.requestValue;
    }

    private static ServerContext detectServerContext(String[] scores) {
        if (containsDonutScoreboard(scores)) {
            return ServerContext.DONUT;
        }
        if (containsHypixelScoreboard(scores)) {
            return ServerContext.SKYBLOCK;
        }
        return detectServerContextFromConnection();
    }

    private static ServerContext detectServerContextFromConnection() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.getCurrentServer() == null || client.getCurrentServer().ip == null) {
            return ServerContext.UNKNOWN;
        }
        String serverIp = client.getCurrentServer().ip.toLowerCase(Locale.ROOT);
        if (serverIp.contains("hypixel.net")) {
            return ServerContext.SKYBLOCK;
        }
        if (serverIp.contains("donut") || serverIp.contains("donutsmp")) {
            return ServerContext.DONUT;
        }
        return ServerContext.UNKNOWN;
    }

    private static boolean containsHypixelScoreboard(String[] scores) {
        if (scores == null) {
            return false;
        }
        for (String score : scores) {
            if (normalizeScoreboardLine(score).endsWith("hypixel.net")) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsDonutScoreboard(String[] scores) {
        if (scores == null) {
            return false;
        }
        for (String score : scores) {
            String normalizedScore = normalizeScoreboardLine(score);
            if ((normalizedScore.contains("donut") && normalizedScore.contains("smp"))
                    || normalizedScore.contains("donutsmp")) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeScoreboardLine(String score) {
        String stripped = ChatFormatting.stripFormatting(score);
        if (stripped == null) {
            return "";
        }
        return stripped.replace('\u00A0', ' ').trim().toLowerCase(Locale.ROOT);
    }

    private static void runOnClientThread(Runnable action) {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.execute(action);
        }
    }

    private static void runConnectionLifecycleTask(String actionName, Runnable action) {
        connectionLifecycleExecutor.execute(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                System.out.println("Failed to " + actionName + ": " + t.getMessage());
                t.printStackTrace();
            }
        });
    }

    private static void startConnectionAsync(String currentUsername) {
        String usernameSnapshot = currentUsername;
        if (!connectionStartInProgress.compareAndSet(false, true)) {
            return;
        }
        runConnectionLifecycleTask("start CoflCore connection", () -> {
            try {
                CoflSkyCommand.start(usernameSnapshot);
            } finally {
                connectionStartInProgress.set(false);
            }
        });
    }

    private static void stopConnectionAsync() {
        runConnectionLifecycleTask("stop CoflCore connection", () -> {
            WSClientWrapper currentWrapper = CoflCore.Wrapper;
            if (currentWrapper != null && currentWrapper.isRunning) {
                currentWrapper.stop();
            }
        });
    }

    /**
     * Check if the Minecraft account has changed and update SkyCoflCore connection if needed.
     * This handles runtime account switches from other mods.
     * Can only be called in the main menu (not while connected to a server).
     */
    private void checkAndHandleAccountSwitch() {
        String currentUsername = Minecraft.getInstance().getUser().getName();

        // Check if username has changed
        if (!currentUsername.equals(lastCheckedUsername) && !lastCheckedUsername.isEmpty()) {
            System.out.println("Detected account switch from " + lastCheckedUsername + " to " + currentUsername);

            // If CoflCore is running, we need to restart it with the new username
            WSClientWrapper wrapper = CoflCore.Wrapper;
            if (wrapper != null && wrapper.isRunning) {
                System.out.println("Restarting CoflCore connection for new account: " + currentUsername);
                username = currentUsername;
                startConnectionAsync(username);
            } else {
                // Just update the username for next time
                username = currentUsername;
            }
        }

        // Update last checked username
        lastCheckedUsername = currentUsername;
    }

    public static void cacheMods() {
        ModListData modListData = new ModListData();
        for (net.fabricmc.loader.api.ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            String modName = mod.getMetadata().getName();
            String modId = mod.getMetadata().getId();
            if (modName.startsWith("Fabric ") || modId.startsWith("fabric")) {
                continue;
            }
            modListData.addModname(modName);
            modListData.addFilename(modName);
            modListData.addFilename(modId);
        }
        WSClientWrapper wrapper = CoflCore.Wrapper;
        if (wrapper != null) wrapper.SendMessage(new RawCommand("foundMods", new Gson().toJson(modListData)));
    }
}
