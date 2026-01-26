package com.airijko.endlessleveling.ui;

import javax.annotation.Nonnull;

import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.Endlessleveling;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.managers.LevelingManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.SkillManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Profile page showing the player's amplified stats and current skill levels.
 */
public class ProfileUIPage extends InteractiveCustomUIPage<SkillsUIPage.Data> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    public ProfileUIPage(@Nonnull com.hypixel.hytale.server.core.universe.PlayerRef playerRef,
            @Nonnull CustomPageLifetime lifetime) {
        super(playerRef, lifetime, SkillsUIPage.Data.CODEC);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {

        ui.append("Pages/ProfilePage.ui");
        NavUIHelper.bindNavEvents(events);

        var universePlayer = Universe.get().getPlayer(playerRef.getUuid());
        if (universePlayer == null) {
            LOGGER.atWarning().log("ProfileUIPage.build: Universe player is null for %s", playerRef.getUuid());
            ui.set("#PlayerNameValue.Text", "Unknown");
            ui.set("#PlayerLevelValue.Text", "Level ?");
            ui.set("#PlayerXpValue.Text", "0 / 0");
            ui.set("#SkillPointsValue.Text", "0");
            return;
        }

        Endlessleveling plugin = Endlessleveling.getInstance();
        if (plugin == null) {
            LOGGER.atSevere().log("ProfileUIPage.build: Endless_Leveling_Hytale instance is null");
            return;
        }

        PlayerDataManager playerDataManager = plugin.getPlayerDataManager();
        LevelingManager levelingManager = plugin.getLevelingManager();
        SkillManager skillManager = plugin.getSkillManager();

        if (playerDataManager == null || levelingManager == null || skillManager == null) {
            LOGGER.atSevere().log("ProfileUIPage.build: One or more managers are null");
            return;
        }

        PlayerData playerData = playerDataManager.get(playerRef.getUuid());
        if (playerData == null) {
            LOGGER.atWarning().log("ProfileUIPage.build: PlayerData is null for %s", playerRef.getUuid());
            ui.set("#PlayerNameValue.Text", universePlayer.getUsername());
            ui.set("#PlayerLevelValue.Text", "Level ?");
            ui.set("#PlayerXpValue.Text", "0 / 0");
            ui.set("#SkillPointsValue.Text", "0");
            return;
        }

        // -----------------------------
        // PLAYER OVERVIEW
        // -----------------------------
        int level = playerData.getLevel();
        double xp = playerData.getXp();
        double xpForNext = levelingManager.getXpForNextLevel(level);

        ui.set("#PlayerNameValue.Text", playerData.getPlayerName() + " Profile");
        ui.set("#PlayerLevelValue.Text", "Level " + level);

        if (Double.isInfinite(xpForNext)) {
            ui.set("#PlayerXpValue.Text", formatNumber(xp) + " XP (MAX)");
        } else {
            ui.set("#PlayerXpValue.Text", formatNumber(xp) + " / " + formatNumber(xpForNext) + " XP");
        }

        ui.set("#SkillPointsValue.Text", String.valueOf(playerData.getSkillPoints()));

        // -----------------------------
        // AMPLIFIED SKILL STATS
        // -----------------------------

        // Life Force (bonus health)
        int lifeLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.LIFE_FORCE);
        float bonusHealth = skillManager.calculatePlayerHealth(playerData);
        ui.set("#LifeForceLevel.Text", String.valueOf(lifeLevel));
        ui.set("#LifeForceValue.Text", formatNumber(bonusHealth) + " Health");

        // Strength (bonus damage percent)
        int strLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.STRENGTH);
        float bonusStrength = skillManager.calculatePlayerStrength(playerData);
        ui.set("#StrengthLevel.Text", String.valueOf(strLevel));
        ui.set("#StrengthValue.Text", "+" + formatNumber(bonusStrength) + "% Damage");

        // Defense (damage reduction percent)
        int defLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.DEFENSE);
        float resistance = skillManager.calculatePlayerDefense(playerData); // 0.0 - 0.8
        ui.set("#DefenseLevel.Text", String.valueOf(defLevel));
        ui.set("#DefenseValue.Text", formatNumber(resistance * 100.0) + "% Reduction");

        // Haste (movement speed percent)
        int hasteLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.HASTE);
        double hastePerPercent = skillManager.getSkillAttributeConfigValue(SkillAttributeType.HASTE);
        double hasteBonus = hasteLevel * hastePerPercent;
        ui.set("#HasteLevel.Text", String.valueOf(hasteLevel));
        ui.set("#HasteValue.Text", "+" + formatNumber(hasteBonus) + "% Speed");

        // Precision (crit chance percent)
        int precLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.PRECISION);
        float critChance = skillManager.calculatePlayerPrecision(playerData) * 100.0F;
        ui.set("#PrecisionLevel.Text", String.valueOf(precLevel));
        ui.set("#PrecisionValue.Text", formatNumber(critChance) + "% Crit Chance");

        // Ferocity (crit damage percent)
        int ferLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.FEROCITY);
        float ferocity = skillManager.calculatePlayerFerocity(playerData); // already in percent
        ui.set("#FerocityLevel.Text", String.valueOf(ferLevel));
        ui.set("#FerocityValue.Text", formatNumber(ferocity) + "% Crit Damage");

        // Stamina (bonus stamina)
        int stamLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.STAMINA);
        float bonusStamina = skillManager.calculatePlayerStamina(playerData);
        ui.set("#StaminaLevel.Text", String.valueOf(stamLevel));
        ui.set("#StaminaValue.Text", formatNumber(bonusStamina) + " Stamina");

        // Intelligence (bonus mana)
        int intLevel = playerData.getPlayerSkillAttributeLevel(SkillAttributeType.INTELLIGENCE);
        float bonusIntelligence = skillManager.calculatePlayerIntelligence(playerData);
        ui.set("#IntelligenceLevel.Text", String.valueOf(intLevel));
        ui.set("#IntelligenceValue.Text", formatNumber(bonusIntelligence) + " Mana");
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull SkillsUIPage.Data data) {
        super.handleDataEvent(ref, store, data);

        if (data.action != null && !data.action.isEmpty()) {
            if (NavUIHelper.handleNavAction(data.action, ref, store, playerRef)) {
                return;
            }
        }
    }

    private String formatNumber(double value) {
        String formatted = String.format("%.2f", value);
        if (formatted.contains(".")) {
            formatted = formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return formatted;
    }
}
