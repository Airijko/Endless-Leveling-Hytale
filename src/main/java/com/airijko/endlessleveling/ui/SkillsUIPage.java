package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.enums.themes.AttributeTheme;
import com.airijko.endlessleveling.managers.PlayerAttributeManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.SkillManager;
import com.airijko.endlessleveling.util.Lang;
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
        private static final SkillBinding[] SKILL_BINDINGS = java.util.Arrays.stream(AttributeTheme.values())
                        .map(theme -> new SkillBinding(theme.uiSuffix(), theme.skillsIconSelector(), theme.type()))
                        .toArray(SkillBinding[]::new);

        private final EnumMap<SkillAttributeType, Integer> previewLevels = new EnumMap<>(SkillAttributeType.class);
        private int previewSkillPoints = 0;
        private boolean previewInitialized = false;
        private static final int MIN_ATTRIBUTE_LEVEL = 0;

        private final SkillManager skillManager;
        private final PlayerDataManager playerDataManager;
        private final PlayerAttributeManager attributeManager;

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
                NavUIHelper.applyNavVersion(ui, playerRef, "skills");

                // Bind left navigation events (Profile / Skills / Party / Leaderboards /
                // Settings)
                NavUIHelper.bindNavEvents(events);

                // -----------------------------
                // UI EVENT BINDINGS (CRITICAL)
                // -----------------------------

                applyStaticLabels(ui);
                applySkillAttributeTheme(ui);

                applySkillIcons(ui);

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

                // Bind footer actions (apply / undo)
                events.addEventBinding(Activating, "#ApplySkills", of("Action", "apply"), false);
                events.addEventBinding(Activating, "#ResetSkills", of("Action", "reset"), false);

                // -----------------------------
                // UI VALUES
                // -----------------------------

                // Get PlayerData
                var player = Universe.get().getPlayer(playerRef.getUuid());
                if (player == null) {
                        ui.set("#SKILLPOINTS.Text", tr("ui.skills.unknown", "Unknown"));
                        return;
                }

                var playerData = EndlessLeveling.getInstance()
                                .getPlayerDataManager()
                                .get(playerRef.getUuid());

                if (playerData == null) {
                        ui.set("#SKILLPOINTS.Text", tr("ui.skills.unknown", "Unknown"));
                        return;
                }

                ensurePreviewState(playerData);

                applySkillValues(ui, playerData);
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
                                                        player.sendMessage(Message.raw(tr("ui.skills.message.granted",
                                                                        "You were granted skill points!"))
                                                                        .color("#ffc300"));
                                                        LOGGER.atInfo().log("Granted skill points to player %s",
                                                                        playerRef.getUuid());
                                                        syncPreviewFromPlayerData(playerData);
                                                        refreshUI = true;
                                                } else if ("reset".equalsIgnoreCase(act)) {
                                                        syncPreviewFromPlayerData(playerData);
                                                        player.sendMessage(Message
                                                                        .raw(tr("ui.skills.message.reset",
                                                                                        "Pending changes reset."))
                                                                        .color("#ff9900"));
                                                        LOGGER.atInfo().log("Pending skill changes reset for %s",
                                                                        playerRef.getUuid());
                                                        refreshUI = true;
                                                } else if ("apply".equalsIgnoreCase(act)) {
                                                        applyPreviewChanges(ref, store, playerData);
                                                        player.sendMessage(Message
                                                                        .raw(tr("ui.skills.message.applied",
                                                                                        "Skills applied!"))
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
                                                                        int requestedAdd = Math.min(amount,
                                                                                        previewSkillPoints);
                                                                        int toAdd = requestedAdd;
                                                                        boolean precisionCapped = false;
                                                                        if (type == SkillAttributeType.PRECISION) {
                                                                                toAdd = clampPrecisionAddition(
                                                                                                playerData,
                                                                                                current,
                                                                                                requestedAdd);
                                                                                precisionCapped = requestedAdd > 0
                                                                                                && toAdd < requestedAdd;
                                                                        }
                                                                        if (toAdd > 0) {
                                                                                previewLevels.put(type,
                                                                                                current + toAdd);
                                                                                previewSkillPoints -= toAdd;
                                                                                player.sendMessage(Message.raw(tr(
                                                                                                "ui.skills.message.added",
                                                                                                "Added {0} point(s) to {1}",
                                                                                                toAdd,
                                                                                                type.name()))
                                                                                                .color("#00ff00"));
                                                                                if (precisionCapped) {
                                                                                        player.sendMessage(Message
                                                                                                        .raw(tr(
                                                                                                                        "ui.skills.message.precision_capped",
                                                                                                                        "Precision crit chance capped at 100%; excess points not applied."))
                                                                                                        .color("#ffc300"));
                                                                                }
                                                                                LOGGER.atInfo().log(
                                                                                                "Added %d to %s for player %s",
                                                                                                toAdd, type.name(),
                                                                                                playerRef.getUuid());
                                                                                refreshUI = true;
                                                                        } else {
                                                                                boolean atCap = type == SkillAttributeType.PRECISION
                                                                                                && requestedAdd > 0
                                                                                                && previewSkillPoints > 0
                                                                                                && isPrecisionAtCap(
                                                                                                                playerData,
                                                                                                                current);
                                                                                Message response = atCap
                                                                                                ? Message.raw(tr(
                                                                                                                "ui.skills.error.precision_at_cap",
                                                                                                                "Precision crit chance is already 100%; remove other bonuses first."))
                                                                                                                .color("#ff0000")
                                                                                                : Message.raw(tr(
                                                                                                                "ui.skills.error.not_enough_points",
                                                                                                                "Not enough skill points"))
                                                                                                                .color("#ff0000");
                                                                                player.sendMessage(response);
                                                                                LOGGER.atSevere().log(
                                                                                                atCap
                                                                                                                ? "Precision capped at 100%% for player %s"
                                                                                                                : "Not enough skill points for add: %s",
                                                                                                playerRef.getUuid());
                                                                        }
                                                                } else if ("sub".equals(op)) {
                                                                        int originalLevel = Math.max(
                                                                                        MIN_ATTRIBUTE_LEVEL,
                                                                                        playerData.getPlayerSkillAttributeLevel(
                                                                                                        type));
                                                                        int availableToSub = Math.max(0,
                                                                                        current - originalLevel);
                                                                        int overflowRefund = 0;
                                                                        if (type == SkillAttributeType.PRECISION) {
                                                                                overflowRefund = computePrecisionOverflowRefund(
                                                                                                playerData, current);
                                                                                if (overflowRefund > 0) {
                                                                                        availableToSub = Math.max(
                                                                                                        availableToSub,
                                                                                                        Math.min(current,
                                                                                                                        overflowRefund));
                                                                                }
                                                                        }
                                                                        int toSub = Math.min(amount, availableToSub);
                                                                        if (toSub > 0) {
                                                                                previewLevels.put(type,
                                                                                                current - toSub);
                                                                                previewSkillPoints += toSub;
                                                                                player.sendMessage(Message
                                                                                                .raw(tr(
                                                                                                                "ui.skills.message.removed",
                                                                                                                "Removed {0} point(s) from {1}",
                                                                                                                toSub,
                                                                                                                type.name()))
                                                                                                .color("#ff9900"));
                                                                                LOGGER.atInfo().log(
                                                                                                "Subtracted %d from %s for player %s",
                                                                                                toSub, type.name(),
                                                                                                playerRef.getUuid());
                                                                                refreshUI = true;
                                                                        } else {
                                                                                boolean precisionBlocked = type == SkillAttributeType.PRECISION
                                                                                                && overflowRefund <= 0;
                                                                                Message response = precisionBlocked
                                                                                                ? Message.raw(tr(
                                                                                                                "ui.skills.error.precision_no_refund",
                                                                                                                "Precision crit chance is already 100% or lower; no excess points to refund."))
                                                                                                                .color("#ff0000")
                                                                                                : Message.raw(tr(
                                                                                                                "ui.skills.error.below_original",
                                                                                                                "Cannot go below original level"))
                                                                                                                .color("#ff0000");
                                                                                player.sendMessage(response);
                                                                                LOGGER.atSevere().log(
                                                                                                precisionBlocked
                                                                                                                ? "Precision refund blocked (no overflow) for %s"
                                                                                                                : "Cannot go below original level for sub: %s",
                                                                                                playerRef.getUuid());
                                                                        }
                                                                }
                                                        } catch (IllegalArgumentException iae) {
                                                                player.sendMessage(Message
                                                                                .raw(tr("ui.skills.error.invalid_attribute",
                                                                                                "Invalid attribute: {0}",
                                                                                                attrName))
                                                                                .color("#ff0000"));
                                                                LOGGER.atSevere().log("Invalid attribute: %s",
                                                                                attrName);
                                                        }
                                                }
                                        } catch (Exception e) {
                                                player.sendMessage(Message
                                                                .raw(tr("ui.skills.error.action_failed",
                                                                                "Error handling skill action"))
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
                        PlayerData latestData = EndlessLeveling.getInstance()
                                        .getPlayerDataManager()
                                        .get(playerRef.getUuid());
                        if (latestData != null) {
                                ensurePreviewState(latestData);
                                refreshSkillUi(latestData);
                        }
                        LOGGER.atInfo().log("UI updated without rebuild for player %s", playerRef.getUuid());
                }
        }

        private void refreshSkillUi(PlayerData playerData) {
                // Push only value changes to avoid scroll reset from full rebuilds.
                if (playerData == null) {
                        return;
                }
                UICommandBuilder ui = new UICommandBuilder();
                applySkillValues(ui, playerData);
                sendUpdate(ui, false);
        }

        private void applySkillValues(UICommandBuilder ui, PlayerData playerData) {
                ui.set("#SKILLPOINTS.Text", tr("ui.skills.points", "SKILL POINTS: {0}", previewSkillPoints));

                int lifeLevel = getPreviewLevel(SkillAttributeType.LIFE_FORCE);
                double lifeTotal = resolveResourcePreviewTotal(playerData, SkillAttributeType.LIFE_FORCE, lifeLevel);
                ui.set("#LifeForceLevel.Text", String.valueOf(lifeLevel));
                ui.set("#LifeForceValue.Text",
                                formatResourceDisplay(lifeTotal, tr("ui.skills.resource.health", "Health")));

                int strLevel = getPreviewLevel(SkillAttributeType.STRENGTH);
                SkillManager.StrengthBreakdown strengthPreview = skillManager.getStrengthBreakdown(playerData,
                                strLevel);
                ui.set("#StrengthLevel.Text", String.valueOf(strLevel));
                ui.set("#StrengthValue.Text",
                                tr("ui.skills.value.strength", "+{0}% Damage",
                                                formatNumber(strengthPreview.totalValue())));

                int sorcLevel = getPreviewLevel(SkillAttributeType.SORCERY);
                double sorceryTotal = skillManager.calculateSkillAttributeBonus(playerData, SkillAttributeType.SORCERY,
                                sorcLevel);
                ui.set("#SorceryLevel.Text", String.valueOf(sorcLevel));
                ui.set("#SorceryValue.Text",
                                tr("ui.skills.value.sorcery", "+{0}% Magic Damage", formatNumber(sorceryTotal)));

                int defLevel = getPreviewLevel(SkillAttributeType.DEFENSE);
                SkillManager.DefenseBreakdown defensePreview = skillManager.getDefenseBreakdown(playerData, defLevel);
                ui.set("#DefenseLevel.Text", String.valueOf(defLevel));
                ui.set("#DefenseValue.Text", tr("ui.skills.value.defense", "{0}% Reduction",
                                formatNumber(defensePreview.resistance() * 100)));

                int hasteLevel = getPreviewLevel(SkillAttributeType.HASTE);
                SkillManager.HasteBreakdown hastePreview = skillManager.getHasteBreakdown(playerData, hasteLevel);
                double hastePercent = (hastePreview.totalMultiplier() - 1.0f) * 100.0f;
                ui.set("#HasteLevel.Text", String.valueOf(hasteLevel));
                ui.set("#HasteValue.Text", tr("ui.skills.value.haste", "+{0}% Speed", formatNumber(hastePercent)));

                int precLevel = getPreviewLevel(SkillAttributeType.PRECISION);
                ui.set("#PrecisionLevel.Text", String.valueOf(precLevel));
                SkillManager.PrecisionBreakdown precisionPreview = skillManager != null
                                ? skillManager.getPrecisionBreakdown(playerData, precLevel)
                                : null;
                double precisionTotal = precisionPreview != null
                                ? precisionPreview.totalPercent()
                                : Math.min(100.0D, getPrecisionPreviewPercent(playerData, precLevel));
                ui.set("#PrecisionValue.Text",
                                tr("ui.skills.value.precision", "{0}% Crit Chance", formatNumber(precisionTotal)));

                int ferLevel = getPreviewLevel(SkillAttributeType.FEROCITY);
                SkillManager.FerocityBreakdown ferocityPreview = skillManager.getFerocityBreakdown(playerData,
                                ferLevel);
                ui.set("#FerocityLevel.Text", String.valueOf(ferLevel));
                ui.set("#FerocityValue.Text",
                                tr("ui.skills.value.ferocity", "+{0}% Crit Damage",
                                                formatNumber(ferocityPreview.totalValue())));

                int stamLevel = getPreviewLevel(SkillAttributeType.STAMINA);
                double staminaTotal = resolveResourcePreviewTotal(playerData, SkillAttributeType.STAMINA, stamLevel);
                ui.set("#StaminaLevel.Text", String.valueOf(stamLevel));
                ui.set("#StaminaValue.Text",
                                formatResourceDisplay(staminaTotal, tr("ui.skills.resource.stamina", "Stamina")));

                int flowLevel = getPreviewLevel(SkillAttributeType.FLOW);
                double flowTotal = resolveResourcePreviewTotal(playerData, SkillAttributeType.FLOW,
                                flowLevel);
                ui.set("#FlowLevel.Text", String.valueOf(flowLevel));
                ui.set("#FlowValue.Text", formatResourceDisplay(flowTotal, tr("ui.skills.resource.flow", "Flow")));

                int discLevel = getPreviewLevel(SkillAttributeType.DISCIPLINE);
                double discBonus = skillManager.getDisciplineXpBonusPercent(discLevel);
                ui.set("#DisciplineLevel.Text", String.valueOf(discLevel));
                ui.set("#DisciplineValue.Text",
                                tr("ui.skills.value.discipline", "+{0}% XP Gain", formatNumber(discBonus)));
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

        private double getPrecisionPreviewPercent(@Nonnull PlayerData playerData, int previewLevel) {
                if (skillManager != null) {
                        SkillManager.PrecisionBreakdown breakdown = skillManager.getPrecisionBreakdown(playerData,
                                        previewLevel);
                        if (breakdown != null) {
                                return breakdown.racePercent() + breakdown.skillPercent();
                        }
                }
                double racePercent = attributeManager != null
                                ? attributeManager.getRaceAttribute(playerData, SkillAttributeType.PRECISION, 0.0D)
                                : 0.0D;
                return racePercent;
        }

        private int clampPrecisionAddition(@Nonnull PlayerData playerData, int currentLevel, int requestedPoints) {
                if (requestedPoints <= 0) {
                        return 0;
                }
                if (skillManager == null) {
                        return requestedPoints;
                }
                double perPoint = skillManager.getSkillAttributeConfigValue(SkillAttributeType.PRECISION);
                if (perPoint <= 0.0D) {
                        return requestedPoints;
                }
                double remaining = 100.0D - getPrecisionPreviewPercent(playerData, currentLevel);
                if (remaining <= 0.0D) {
                        return 0;
                }
                int maxByPercent = (int) Math.floor((remaining + 1e-6D) / perPoint);
                if (maxByPercent <= 0) {
                        return 0;
                }
                return Math.min(requestedPoints, maxByPercent);
        }

        private boolean isPrecisionAtCap(@Nonnull PlayerData playerData, int currentLevel) {
                return getPrecisionPreviewPercent(playerData, currentLevel) >= 99.999D;
        }

        private int computePrecisionOverflowRefund(@Nonnull PlayerData playerData, int currentLevel) {
                if (skillManager == null) {
                        return 0;
                }
                double perPoint = skillManager.getSkillAttributeConfigValue(SkillAttributeType.PRECISION);
                if (perPoint <= 0.0D) {
                        return 0;
                }
                double overflow = getPrecisionPreviewPercent(playerData, currentLevel) - 100.0D;
                if (overflow <= 0.0D) {
                        return 0;
                }
                int points = (int) Math.ceil((overflow - 1e-6D) / perPoint);
                if (points <= 0) {
                        points = 1;
                }
                return Math.min(currentLevel, points);
        }

        private String formatResourceDisplay(double total, String label) {
                if (total <= 0.0D) {
                        return tr("ui.skills.value.resource", "{0} {1}", 0, label);
                }
                return tr("ui.skills.value.resource", "{0} {1}", formatNumber(total), label);
        }

        private String formatNumber(double value) {
                String formatted = String.format("%.2f", value);
                if (formatted.contains(".")) {
                        formatted = formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
                }
                return formatted;
        }

        private String tr(String key, String fallback, Object... args) {
                return Lang.tr(playerRef.getUuid(), key, fallback, args);
        }

        private void applySkillAttributeTheme(@Nonnull UICommandBuilder ui) {
                for (AttributeTheme theme : AttributeTheme.values()) {
                        ui.set(theme.skillsLabelSelector() + ".Text", tr(theme.labelKey(), theme.labelFallback()));
                        ui.set(theme.skillsLabelSelector() + ".Style.TextColor", theme.labelColor());
                        ui.set(theme.skillsValueSelector() + ".Style.TextColor", theme.valueColor());
                        ui.set(theme.skillsLevelPrefixSelector() + ".Style.TextColor", theme.profileLevelColor());
                        ui.set(theme.skillsLevelSelector() + ".Style.TextColor", theme.profileLevelColor());
                        ui.set(theme.skillsDescriptionSelector() + ".Text",
                                        tr(theme.descriptionKey(), theme.descriptionFallback()));
                        ui.set(theme.skillsDescriptionSelector() + ".Style.TextColor", theme.raceNoteColor());
                }
        }

        private void applyStaticLabels(@Nonnull UICommandBuilder ui) {
                ui.set("#SkillsTitleLabel.Text", tr("ui.skills.page.title", "Skill Attributes"));
                ui.set("#SkillsIntroText.Text",
                                tr("ui.skills.page.subtitle", "Allocate points to shape your combat identity."));
                ui.set("#SkillsFooterHint.Text",
                                tr("ui.skills.page.footer_hint",
                                                "Undo to revert pending tweaks, apply to lock them in."));
                ui.set("#ResetSkills.Text", tr("ui.skills.page.undo", "UNDO"));
                ui.set("#ApplySkills.Text", tr("ui.skills.page.apply", "APPLY"));

                String lv = tr("ui.skills.level_prefix", "Lv.");
                for (AttributeTheme theme : AttributeTheme.values()) {
                        ui.set(theme.skillsLevelPrefixSelector() + ".Text", lv);
                }
        }

        private void applySkillIcons(UICommandBuilder ui) {
                for (AttributeTheme theme : AttributeTheme.values()) {
                        String selector = theme.skillsIconSelector();
                        String iconItemId = theme.iconItemId();
                        if (iconItemId == null || iconItemId.isBlank()) {
                                ui.set(selector + ".Visible", false);
                                continue;
                        }

                        ui.set(selector + ".ItemId", iconItemId.trim());
                        ui.set(selector + ".Visible", true);
                }
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

        private record SkillBinding(String uiPrefix, String iconSelector, SkillAttributeType attribute) {
        }

}
