package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.managers.PlayerAttributeManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.SkillManager;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Activating;
import static com.hypixel.hytale.server.core.ui.builder.EventData.of;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.EnumMap;

public class SkillsUIPage extends InteractiveCustomUIPage<SkillsUIPage.Data> {
        private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
        private static final EnumMap<SkillAttributeType, String> ATTRIBUTE_DESCRIPTIONS = new EnumMap<>(
                        SkillAttributeType.class);
        private static final SkillBinding[] SKILL_BINDINGS = {
                        new SkillBinding("LifeForce", SkillAttributeType.LIFE_FORCE),
                        new SkillBinding("Strength", SkillAttributeType.STRENGTH),
                        new SkillBinding("Defense", SkillAttributeType.DEFENSE),
                        new SkillBinding("Haste", SkillAttributeType.HASTE),
                        new SkillBinding("Precision", SkillAttributeType.PRECISION),
                        new SkillBinding("Ferocity", SkillAttributeType.FEROCITY),
                        new SkillBinding("Stamina", SkillAttributeType.STAMINA),
                        new SkillBinding("Intelligence", SkillAttributeType.INTELLIGENCE) };

        private final EnumMap<SkillAttributeType, Integer> previewLevels = new EnumMap<>(SkillAttributeType.class);
        private int previewSkillPoints = 0;
        private boolean previewInitialized = false;
        private static final int MIN_ATTRIBUTE_LEVEL = 0;

        private final SkillManager skillManager;
        private final PlayerDataManager playerDataManager;
        private final PlayerAttributeManager attributeManager;

        static {
                ATTRIBUTE_DESCRIPTIONS.put(SkillAttributeType.LIFE_FORCE,
                                "Boosts your total health so you can tank more hits.");
                ATTRIBUTE_DESCRIPTIONS.put(SkillAttributeType.STRENGTH,
                                "Multiplies weapon and ability damage outputs.");
                ATTRIBUTE_DESCRIPTIONS.put(SkillAttributeType.DEFENSE,
                                "Cuts down incoming damage through resistances.");
                ATTRIBUTE_DESCRIPTIONS.put(SkillAttributeType.HASTE, "Speeds up movement and combat animations.");
                ATTRIBUTE_DESCRIPTIONS.put(SkillAttributeType.PRECISION,
                                "Raises critical hit chance for every attack.");
                ATTRIBUTE_DESCRIPTIONS.put(SkillAttributeType.FEROCITY, "Adds bonus damage to each critical strike.");
                ATTRIBUTE_DESCRIPTIONS.put(SkillAttributeType.STAMINA,
                                "Expands stamina for dodges, blocks, and bursts.");
                ATTRIBUTE_DESCRIPTIONS.put(SkillAttributeType.INTELLIGENCE,
                                "Increases mana so spells and abilities stay online longer.");
        }

        public SkillsUIPage(@Nonnull PlayerRef playerRef, @Nonnull CustomPageLifetime lifetime) {
                super(playerRef, lifetime, Data.CODEC);
                EndlessLeveling plugin = EndlessLeveling.getInstance();
                this.skillManager = plugin.getSkillManager();
                this.playerDataManager = plugin.getPlayerDataManager();
                this.attributeManager = plugin.getPlayerAttributeManager();
        }

        @Override
        public void build(
                        @Nonnull Ref<EntityStore> ref,
                        @Nonnull UICommandBuilder ui,
                        @Nonnull UIEventBuilder events,
                        @Nonnull Store<EntityStore> store) {

                // Load UI
                ui.append("Pages/SkillsPage.ui");

                // Bind left navigation events (Profile / Skills / Party / Leaderboards /
                // Settings)
                NavUIHelper.bindNavEvents(events);

                // -----------------------------
                // UI EVENT BINDINGS (CRITICAL)
                // -----------------------------

                ui.set("#LifeForceLabel.Text", "Life Force");
                ui.set("#LifeForceDescription.Text", getDescription(SkillAttributeType.LIFE_FORCE));
                ui.set("#StrengthLabel.Text", "Strength");
                ui.set("#StrengthDescription.Text", getDescription(SkillAttributeType.STRENGTH));
                ui.set("#DefenseLabel.Text", "Defense");
                ui.set("#DefenseDescription.Text", getDescription(SkillAttributeType.DEFENSE));
                ui.set("#HasteLabel.Text", "Haste");
                ui.set("#HasteDescription.Text", getDescription(SkillAttributeType.HASTE));
                ui.set("#PrecisionLabel.Text", "Precision");
                ui.set("#PrecisionDescription.Text", getDescription(SkillAttributeType.PRECISION));
                ui.set("#FerocityLabel.Text", "Ferocity");
                ui.set("#FerocityDescription.Text", getDescription(SkillAttributeType.FEROCITY));
                ui.set("#StaminaLabel.Text", "Stamina");
                ui.set("#StaminaDescription.Text", getDescription(SkillAttributeType.STAMINA));
                ui.set("#IntelligenceLabel.Text", "Intelligence");
                ui.set("#IntelligenceDescription.Text", getDescription(SkillAttributeType.INTELLIGENCE));

                // Bind all skill buttons in a loop for clarity and maintainability
                for (SkillBinding binding : SKILL_BINDINGS) {
                        String plus1Id = "#" + binding.uiPrefix() + "Plus1";
                        String plus5Id = "#" + binding.uiPrefix() + "Plus5";
                        String minus1Id = "#" + binding.uiPrefix() + "Minus1";
                        String attrName = binding.attribute().name();
                        LOGGER.atInfo().log("Binding event: %s -> add:%s", plus1Id, attrName);
                        LOGGER.atInfo().log("Binding event: %s -> add:%s:5", plus5Id, attrName);
                        LOGGER.atInfo().log("Binding event: %s -> sub:%s", minus1Id, attrName);
                        events.addEventBinding(Activating, plus1Id, of("Action", "add:" + attrName), false);
                        events.addEventBinding(Activating, plus5Id, of("Action", "add:" + attrName + ":5"), false);
                        events.addEventBinding(Activating, minus1Id, of("Action", "sub:" + attrName), false);
                }

                // Footer buttons
                events.addEventBinding(Activating, "#ResetSkills", of("Action", "reset"), false);
                events.addEventBinding(Activating, "#ApplySkills", of("Action", "apply"), false);

                // Get PlayerData
                var player = Universe.get().getPlayer(playerRef.getUuid());
                if (player == null) {
                        ui.set("#SKILLPOINTS.Text", "Unknown");
                        return;
                }

                var playerData = EndlessLeveling.getInstance()
                                .getPlayerDataManager()
                                .get(playerRef.getUuid());

                if (playerData == null) {
                        ui.set("#SKILLPOINTS.Text", "Unknown");
                        return;
                }

                ensurePreviewState(playerData);

                // -----------------------------
                // UI VALUES
                // -----------------------------

                ui.set("#SKILLPOINTS.Text", "SKILL POINTS: " + previewSkillPoints);

                int lifeLevel = getPreviewLevel(SkillAttributeType.LIFE_FORCE);
                double lifeTotal = resolveResourcePreviewTotal(playerData, SkillAttributeType.LIFE_FORCE, lifeLevel);
                ui.set("#LifeForceLevel.Text", String.valueOf(lifeLevel));
                ui.set("#LifeForceValue.Text", formatResourceDisplay(lifeTotal, "Health"));

                int strLevel = getPreviewLevel(SkillAttributeType.STRENGTH);
                SkillManager.StrengthBreakdown strengthPreview = skillManager.getStrengthBreakdown(playerData,
                                strLevel);
                ui.set("#StrengthLevel.Text", String.valueOf(strLevel));
                ui.set("#StrengthValue.Text",
                                "+" + formatNumber(strengthPreview.totalValue()) + "% Damage");

                int defLevel = getPreviewLevel(SkillAttributeType.DEFENSE);
                SkillManager.DefenseBreakdown defensePreview = skillManager.getDefenseBreakdown(playerData, defLevel);
                ui.set("#DefenseLevel.Text", String.valueOf(defLevel));
                ui.set("#DefenseValue.Text", formatNumber(defensePreview.resistance() * 100) + "% Reduction");

                int hasteLevel = getPreviewLevel(SkillAttributeType.HASTE);
                SkillManager.HasteBreakdown hastePreview = skillManager.getHasteBreakdown(playerData, hasteLevel);
                double hastePercent = (hastePreview.totalMultiplier() - 1.0f) * 100.0f;
                ui.set("#HasteLevel.Text", String.valueOf(hasteLevel));
                ui.set("#HasteValue.Text", "+" + formatNumber(hastePercent) + "% Speed");

                int precLevel = getPreviewLevel(SkillAttributeType.PRECISION);
                double precPer = skillManager.getSkillAttributeConfigValue(SkillAttributeType.PRECISION);
                ui.set("#PrecisionLevel.Text", String.valueOf(precLevel));
                ui.set("#PrecisionValue.Text", formatNumber(precLevel * precPer) + "% Crit Chance");

                int ferLevel = getPreviewLevel(SkillAttributeType.FEROCITY);
                double ferPer = skillManager.getSkillAttributeConfigValue(SkillAttributeType.FEROCITY);
                ui.set("#FerocityLevel.Text", String.valueOf(ferLevel));
                ui.set("#FerocityValue.Text", "+" + formatNumber(ferLevel * ferPer) + "% Crit Damage");

                int stamLevel = getPreviewLevel(SkillAttributeType.STAMINA);
                double staminaTotal = resolveResourcePreviewTotal(playerData, SkillAttributeType.STAMINA, stamLevel);
                ui.set("#StaminaLevel.Text", String.valueOf(stamLevel));
                ui.set("#StaminaValue.Text", formatResourceDisplay(staminaTotal, "Stamina"));

                int intLevel = getPreviewLevel(SkillAttributeType.INTELLIGENCE);
                double intelligenceTotal = resolveResourcePreviewTotal(playerData, SkillAttributeType.INTELLIGENCE,
                                intLevel);
                ui.set("#IntelligenceLevel.Text", String.valueOf(intLevel));
                ui.set("#IntelligenceValue.Text", formatResourceDisplay(intelligenceTotal, "Mana"));
        }

        @Override
        public void handleDataEvent(
                        @Nonnull Ref<EntityStore> ref,
                        @Nonnull Store<EntityStore> store,
                        @Nonnull Data data) {
                super.handleDataEvent(ref, store, data);

                // First, handle navigation actions (switching between pages)
                if (data.action != null && !data.action.isEmpty()) {
                        if (NavUIHelper.handleNavAction(data.action, ref, store, playerRef)) {
                                return;
                        }
                }

                if (data.action == null) {
                        LOGGER.atSevere().log(
                                        "handleDataEvent: Data.action is null. Possible codec or event binding issue.");
                } else if (data.action.isEmpty()) {
                        LOGGER.atSevere().log(
                                        "handleDataEvent: Data.action is empty string. Button event sent no action value.");
                } else {
                        LOGGER.atInfo().log("handleDataEvent called: data.action=%s", data.action);
                }

                boolean refreshUI = false;

                if (data != null && data.action != null && !data.action.isEmpty()) {
                        var player = Universe.get().getPlayer(playerRef.getUuid());
                        if (player != null) {
                                var playerData = EndlessLeveling.getInstance()
                                                .getPlayerDataManager()
                                                .get(playerRef.getUuid());

                                if (playerData != null) {
                                        ensurePreviewState(playerData);
                                        String act = data.action;
                                        LOGGER.atInfo().log("Action received: %s", act);
                                        try {
                                                if ("grant".equalsIgnoreCase(act)) {
                                                        skillManager.addSkillPoints(playerData);
                                                        player.sendMessage(Message.raw("You were granted skill points!")
                                                                        .color("#ffc300"));
                                                        LOGGER.atInfo().log("Granted skill points to player %s",
                                                                        playerRef.getUuid());
                                                        syncPreviewFromPlayerData(playerData);
                                                        refreshUI = true;
                                                } else if ("reset".equalsIgnoreCase(act)) {
                                                        syncPreviewFromPlayerData(playerData);
                                                        player.sendMessage(Message.raw("Pending changes reset.")
                                                                        .color("#ff9900"));
                                                        LOGGER.atInfo().log("Pending skill changes reset for %s",
                                                                        playerRef.getUuid());
                                                        refreshUI = true;
                                                } else if ("apply".equalsIgnoreCase(act)) {
                                                        applyPreviewChanges(ref, store, playerData);
                                                        player.sendMessage(Message.raw("Skills applied!")
                                                                        .color("#00ff00"));
                                                        LOGGER.atInfo().log("Applied previewed skills for %s",
                                                                        playerRef.getUuid());
                                                        refreshUI = true;
                                                } else if (act.matches("(add|sub):(\\w+):(\\d+)")
                                                                || act.matches("(add|sub):(\\w+)")) {
                                                        String[] parts = act.split(":");
                                                        String op = parts[0];
                                                        String attrName = parts[1];
                                                        int amount = 1;
                                                        if (parts.length > 2) {
                                                                try {
                                                                        amount = Integer.parseInt(parts[2]);
                                                                } catch (Exception ignore) {
                                                                        LOGGER.atSevere().log(
                                                                                        "Failed to parse amount: %s",
                                                                                        parts[2]);
                                                                }
                                                        }
                                                        try {
                                                                SkillAttributeType type = SkillAttributeType
                                                                                .valueOf(attrName);
                                                                int current = getPreviewLevel(type);
                                                                if ("add".equals(op)) {
                                                                        int toAdd = Math.min(amount,
                                                                                        previewSkillPoints);
                                                                        if (toAdd > 0) {
                                                                                previewLevels.put(type,
                                                                                                current + toAdd);
                                                                                previewSkillPoints -= toAdd;
                                                                                player.sendMessage(Message.raw("Added "
                                                                                                + toAdd
                                                                                                + " point(s) to "
                                                                                                + type.name())
                                                                                                .color("#00ff00"));
                                                                                LOGGER.atInfo().log(
                                                                                                "Added %d to %s for player %s",
                                                                                                toAdd, type.name(),
                                                                                                playerRef.getUuid());
                                                                                refreshUI = true;
                                                                        } else {
                                                                                player.sendMessage(Message.raw(
                                                                                                "Not enough skill points")
                                                                                                .color("#ff0000"));
                                                                                LOGGER.atSevere().log(
                                                                                                "Not enough skill points for add: %s",
                                                                                                playerRef.getUuid());
                                                                        }
                                                                } else if ("sub".equals(op)) {
                                                                        int originalLevel = Math.max(
                                                                                        MIN_ATTRIBUTE_LEVEL,
                                                                                        playerData.getPlayerSkillAttributeLevel(
                                                                                                        type));
                                                                        int availableToSub = Math.max(0,
                                                                                        current - originalLevel);
                                                                        int toSub = Math.min(amount, availableToSub);
                                                                        if (toSub > 0) {
                                                                                previewLevels.put(type,
                                                                                                current - toSub);
                                                                                previewSkillPoints += toSub;
                                                                                player.sendMessage(Message
                                                                                                .raw("Removed " + toSub
                                                                                                                + " point(s) from "
                                                                                                                + type.name())
                                                                                                .color("#ff9900"));
                                                                                LOGGER.atInfo().log(
                                                                                                "Subtracted %d from %s for player %s",
                                                                                                toSub, type.name(),
                                                                                                playerRef.getUuid());
                                                                                refreshUI = true;
                                                                        } else {
                                                                                player.sendMessage(Message.raw(
                                                                                                "Cannot go below original level")
                                                                                                .color("#ff0000"));
                                                                                LOGGER.atSevere().log(
                                                                                                "Cannot go below original level for sub: %s",
                                                                                                playerRef.getUuid());
                                                                        }
                                                                }
                                                        } catch (IllegalArgumentException iae) {
                                                                player.sendMessage(Message
                                                                                .raw("Invalid attribute: " + attrName)
                                                                                .color("#ff0000"));
                                                                LOGGER.atSevere().log("Invalid attribute: %s",
                                                                                attrName);
                                                        }
                                                }
                                        } catch (Exception e) {
                                                player.sendMessage(Message.raw("Error handling skill action")
                                                                .color("#ff0000"));
                                                LOGGER.atSevere().withCause(e).log(
                                                                "Exception handling skill action: %s",
                                                                act);
                                        }
                                } else {
                                        LOGGER.atSevere().log("PlayerData is null for player %s", playerRef.getUuid());
                                }
                        } else {
                                LOGGER.atSevere().log("Player is null for playerRef %s", playerRef.getUuid());
                        }
                }
                if (refreshUI) {
                        rebuild();
                        LOGGER.atInfo().log("UI rebuilt for player %s", playerRef.getUuid());
                }
        }

        private void ensurePreviewState(PlayerData playerData) {
                if (!previewInitialized) {
                        syncPreviewFromPlayerData(playerData);
                }
        }

        private void syncPreviewFromPlayerData(PlayerData playerData) {
                previewLevels.clear();
                for (SkillAttributeType type : SkillAttributeType.values()) {
                        previewLevels.put(type, playerData.getPlayerSkillAttributeLevel(type));
                }
                previewSkillPoints = playerData.getSkillPoints();
                previewInitialized = true;
        }

        private int getPreviewLevel(SkillAttributeType type) {
                return previewLevels.getOrDefault(type, MIN_ATTRIBUTE_LEVEL);
        }

        private void applyPreviewChanges(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                        @Nonnull PlayerData playerData) {
                previewLevels.forEach(playerData::setPlayerSkillAttributeLevel);
                playerData.setSkillPoints(previewSkillPoints);
                playerDataManager.save(playerData);
                if (ref != null && store != null) {
                        boolean applied = skillManager.applyAllSkillModifiers(ref, store, playerData);
                        if (!applied) {
                                LOGGER.atFine().log("applyPreviewChanges: modifiers deferred for %s",
                                                playerRef.getUuid());
                                var retrySystem = EndlessLeveling.getInstance().getPlayerRaceStatSystem();
                                if (retrySystem != null) {
                                        retrySystem.scheduleRetry(playerData.getUuid());
                                }
                        }
                } else {
                        LOGGER.atWarning().log("applyPreviewChanges: missing ref/store for player %s",
                                        playerRef.getUuid());
                }
        }

        private double resolveResourcePreviewTotal(@Nonnull PlayerData playerData,
                        @Nonnull SkillAttributeType type,
                        int previewLevel) {
                double raceBase = 0.0D;
                if (attributeManager != null) {
                        raceBase = attributeManager.getRaceAttribute(playerData, type, 0.0D);
                }
                double skillBonus = skillManager != null
                                ? skillManager.calculateSkillAttributeBonus(playerData, type, previewLevel)
                                : 0.0D;
                double total = raceBase + skillBonus;
                return total > 0.0D ? total : 0.0D;
        }

        private String formatResourceDisplay(double total, String label) {
                if (total <= 0.0D) {
                        return "--";
                }
                return formatNumber(total) + " " + label;
        }

        private String formatNumber(double value) {
                String formatted = String.format("%.2f", value);
                if (formatted.contains(".")) {
                        formatted = formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
                }
                return formatted;
        }

        private String getDescription(SkillAttributeType type) {
                return ATTRIBUTE_DESCRIPTIONS.getOrDefault(type, "");
        }

        // --------------------------------------------------
        // UI DATA MODEL
        // --------------------------------------------------
        public static class Data {

                public String action;

                // Optional search query used by pages that support filtering lists (e.g. party
                // invites).
                public String searchQuery;

                public Data() {
                        this.action = "";
                        this.searchQuery = null;
                }

                public static final BuilderCodec<Data> CODEC = BuilderCodec.builder(Data.class, Data::new)
                                .append(new KeyedCodec<>("Action", Codec.STRING),
                                                (d, v) -> d.action = v,
                                                d -> d.action)
                                .add()
                                .append(new KeyedCodec<>("@SearchQuery", Codec.STRING),
                                                (d, v) -> d.searchQuery = v,
                                                d -> d.searchQuery)
                                .add()
                                .build();
        }

        private record SkillBinding(String uiPrefix, SkillAttributeType attribute) {
        }

}
