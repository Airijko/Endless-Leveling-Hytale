package com.airijko.endlessleveling.ui;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.data.PlayerData.PlayerProfile;
import com.airijko.endlessleveling.enums.PassiveType;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.RaceManager;
import com.airijko.endlessleveling.races.RaceDefinition;
import com.airijko.endlessleveling.races.RacePassiveDefinition;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Activating;
import static com.hypixel.hytale.server.core.ui.builder.EventData.of;

public class ProfileUIPage extends InteractiveCustomUIPage<SkillsUIPage.Data> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final PlayerDataManager playerDataManager;
    private final RaceManager raceManager;

    public ProfileUIPage(@Nonnull com.hypixel.hytale.server.core.universe.PlayerRef playerRef,
            @Nonnull CustomPageLifetime lifetime) {
        super(playerRef, lifetime, SkillsUIPage.Data.CODEC);
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        this.playerDataManager = plugin != null ? plugin.getPlayerDataManager() : null;
        this.raceManager = plugin != null ? plugin.getRaceManager() : null;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {

        ui.append("Pages/Profile/ProfilePage.ui");
        NavUIHelper.bindNavEvents(events);

        PlayerData playerData = resolvePlayerData();
        if (playerData == null) {
            ui.set("#ProfilesSummary.Text", "Player data unavailable.");
            ui.set("#EmptyStateLabel.Text", "Unable to load profiles right now.");
            return;
        }

        events.addEventBinding(Activating, "#NewProfileButton", of("Action", "profile:new"), false);

        updateSummary(ui, playerData);
        buildProfileList(ui, events, playerData);
        updateProfileDetailPanel(ui, playerData);
    }

    private PlayerData resolvePlayerData() {
        if (playerDataManager == null) {
            LOGGER.atSevere().log("ProfileUIPage: PlayerDataManager is not available");
            return null;
        }

        PlayerData data = playerDataManager.get(playerRef.getUuid());
        if (data == null) {
            LOGGER.atWarning().log("ProfileUIPage: PlayerData missing for %s", playerRef.getUuid());
            return null;
        }
        return data;
    }

    private void updateSummary(@Nonnull UICommandBuilder ui, @Nonnull PlayerData data) {
        ui.set("#ProfileTitleLabel.Text",
                "Profiles " + data.getProfileCount() + "/" + PlayerData.MAX_PROFILES);
    }

    private void buildProfileList(@Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull PlayerData data) {
        ui.clear("#ProfileCards");

        List<Map.Entry<Integer, PlayerProfile>> profiles = data.getProfiles().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();

        if (profiles.isEmpty()) {
            ui.set("#EmptyStateLabel.Text", "No profiles yet. Use NEW PROFILE to create one.");
            return;
        }

        ui.set("#EmptyStateLabel.Text", "");

        int index = 0;
        for (Map.Entry<Integer, PlayerProfile> entry : profiles) {
            int slot = entry.getKey();
            PlayerProfile profile = entry.getValue();
            boolean active = data.isProfileActive(slot);
            boolean canDelete = data.getProfileCount() > 1 && !active;

            ui.append("#ProfileCards", "Pages/Profile/ProfileRow.ui");
            String base = "#ProfileCards[" + index + "]";

            ui.set(base + " #SlotLabel.Text", "Slot " + slot);
            ui.set(base + " #ProfileName.Text", profile.getName());
            ui.set(base + " #LevelValue.Text", "Level " + profile.getLevel());
            ui.set(base + " #XpValue.Text", formatNumber(profile.getXp()) + " XP");
            ui.set(base + " #RaceValue.Text", profile.getRaceId());
            ui.set(base + " #StatusBadge.Text", active ? "ACTIVE" : "");

            ui.set(base + " #SelectButton.Text", active ? "ACTIVE" : "SELECT");
            if (!active) {
                events.addEventBinding(Activating, base + " #SelectButton",
                        of("Action", "profile:select:" + slot), false);
            }
            if (canDelete) {
                events.addEventBinding(Activating, base + " #DeleteButton",
                        of("Action", "profile:delete:" + slot), false);
            }

            index++;
        }
    }

    private void updateProfileDetailPanel(@Nonnull UICommandBuilder ui, @Nonnull PlayerData data) {
        PlayerProfile profile = resolveActiveProfile(data);
        if (profile == null) {
            clearDetailPanel(ui, "Select a profile to view stats");
            return;
        }

        int slot = data.getActiveProfileIndex();
        ui.set("#DetailTitleLabel.Text", profile.getName());
        ui.set("#DetailSubtitleLabel.Text", "Slot " + slot);
        ui.set("#DetailLevelValue.Text", String.valueOf(profile.getLevel()));
        ui.set("#DetailXpValue.Text", formatNumber(profile.getXp()) + " XP");
        ui.set("#DetailRaceValue.Text", getRaceDisplay(profile));

        ui.set("#AttributeLifeForceValue.Text", getAttributeValue(profile, SkillAttributeType.LIFE_FORCE));
        ui.set("#AttributeStrengthValue.Text", getAttributeValue(profile, SkillAttributeType.STRENGTH));
        ui.set("#AttributeDefenseValue.Text", getAttributeValue(profile, SkillAttributeType.DEFENSE));
        ui.set("#AttributeHasteValue.Text", getAttributeValue(profile, SkillAttributeType.HASTE));
        ui.set("#AttributePrecisionValue.Text", getAttributeValue(profile, SkillAttributeType.PRECISION));
        ui.set("#AttributeFerocityValue.Text", getAttributeValue(profile, SkillAttributeType.FEROCITY));
        ui.set("#AttributeStaminaValue.Text", getAttributeValue(profile, SkillAttributeType.STAMINA));
        ui.set("#AttributeIntelligenceValue.Text", getAttributeValue(profile, SkillAttributeType.INTELLIGENCE));

        List<PassiveEntry> skillEntries = collectSkillPassiveEntries(profile);
        if (skillEntries.isEmpty()) {
            ui.set("#SkillPassiveSummary.Text", "No skill passives selected");
        } else {
            ui.set("#SkillPassiveSummary.Text", skillEntries.size() + " passive(s) active");
        }
        populatePassiveEntries(ui,
                "#SkillPassiveEntries",
                "Pages/Profile/ProfileSkillPassiveEntry.ui",
                skillEntries);

        RacePassiveSummary raceSummary = buildRacePassiveSummary(profile);
        ui.set("#RacePassiveSummary.Text", raceSummary.summary());
        populatePassiveEntries(ui,
                "#RacePassiveEntries",
                "Pages/Profile/ProfileRacePassiveEntry.ui",
                raceSummary.entries());
    }

    private PlayerProfile resolveActiveProfile(@Nonnull PlayerData data) {
        Map<Integer, PlayerProfile> profiles = data.getProfiles();
        if (profiles.isEmpty()) {
            return null;
        }
        PlayerProfile profile = profiles.get(data.getActiveProfileIndex());
        if (profile != null) {
            return profile;
        }
        return profiles.values().stream().findFirst().orElse(null);
    }

    private void clearDetailPanel(@Nonnull UICommandBuilder ui, @Nonnull String subtitle) {
        ui.set("#DetailTitleLabel.Text", "Selected Profile");
        ui.set("#DetailSubtitleLabel.Text", subtitle);
        ui.set("#DetailLevelValue.Text", "--");
        ui.set("#DetailXpValue.Text", "--");
        ui.set("#DetailRaceValue.Text", "--");
        ui.set("#AttributeLifeForceValue.Text", "--");
        ui.set("#AttributeStrengthValue.Text", "--");
        ui.set("#AttributeDefenseValue.Text", "--");
        ui.set("#AttributeHasteValue.Text", "--");
        ui.set("#AttributePrecisionValue.Text", "--");
        ui.set("#AttributeFerocityValue.Text", "--");
        ui.set("#AttributeStaminaValue.Text", "--");
        ui.set("#AttributeIntelligenceValue.Text", "--");
        ui.set("#SkillPassiveSummary.Text", "No skill passives selected");
        ui.set("#RacePassiveSummary.Text", "Select a profile to view race bonuses");
        ui.clear("#SkillPassiveEntries");
        ui.clear("#RacePassiveEntries");
    }

    private String getAttributeValue(@Nonnull PlayerProfile profile, @Nonnull SkillAttributeType type) {
        int value = profile.getAttributes().getOrDefault(type, 0);
        return String.valueOf(value);
    }

    private String getRaceDisplay(@Nonnull PlayerProfile profile) {
        String raceId = profile.getRaceId();
        if (raceManager == null) {
            return raceId == null || raceId.isBlank() ? PlayerData.DEFAULT_RACE_ID : raceId;
        }
        RaceDefinition definition = raceManager.getRace(raceId);
        if (definition != null) {
            return definition.getDisplayName();
        }
        return raceId == null || raceId.isBlank() ? raceManager.getDefaultRaceId() : raceId;
    }

    private List<PassiveEntry> collectSkillPassiveEntries(@Nonnull PlayerProfile profile) {
        List<PassiveEntry> entries = new ArrayList<>();
        for (PassiveType type : PassiveType.values()) {
            int level = profile.getPassiveLevel(type);
            if (level <= 0) {
                continue;
            }
            entries.add(new PassiveEntry(type.getDisplayName(), "Lv " + level));
        }
        return entries;
    }

    private RacePassiveSummary buildRacePassiveSummary(@Nonnull PlayerProfile profile) {
        List<PassiveEntry> entries = new ArrayList<>();
        if (raceManager == null || !raceManager.isEnabled()) {
            return new RacePassiveSummary("Race bonuses unavailable", entries);
        }
        RaceDefinition definition = raceManager.getRace(profile.getRaceId());
        if (definition == null) {
            String raceId = profile.getRaceId() == null ? PlayerData.DEFAULT_RACE_ID : profile.getRaceId();
            return new RacePassiveSummary("Race: " + raceId, entries);
        }
        List<RacePassiveDefinition> passives = definition.getPassiveDefinitions();
        if (passives != null) {
            for (RacePassiveDefinition passive : passives) {
                if (passive == null || passive.type() == null) {
                    continue;
                }
                StringBuilder label = new StringBuilder(toDisplay(passive.type().name()));
                if (passive.attributeType() != null) {
                    label.append(" (" + toDisplay(passive.attributeType().name()) + ")");
                }
                String valueText = passive.value() == 0.0D
                        ? "Passive"
                        : "+" + formatNumber(passive.value());
                entries.add(new PassiveEntry(label.toString(), valueText));
            }
        }
        String summary = entries.isEmpty()
                ? definition.getDisplayName() + " bonuses active"
                : definition.getDisplayName() + " passives";
        return new RacePassiveSummary(summary, entries);
    }

    private void populatePassiveEntries(@Nonnull UICommandBuilder ui,
            @Nonnull String containerSelector,
            @Nonnull String template,
            @Nonnull List<PassiveEntry> entries) {
        ui.clear(containerSelector);
        for (int i = 0; i < entries.size(); i++) {
            PassiveEntry entry = entries.get(i);
            ui.append(containerSelector, template);
            String base = containerSelector + "[" + i + "]";
            ui.set(base + " #PassiveName.Text", entry.label());
            ui.set(base + " #PassiveValue.Text", entry.value());
        }
    }

    private String toDisplay(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        String[] parts = raw.toLowerCase(Locale.ROOT).split("_");
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    private record PassiveEntry(String label, String value) {
    }

    private record RacePassiveSummary(String summary, List<PassiveEntry> entries) {
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

        if (data.action == null || data.action.isEmpty() || !data.action.startsWith("profile:")) {
            return;
        }

        PlayerData playerData = resolvePlayerData();
        if (playerData == null) {
            playerRef.sendMessage(Message.raw("Unable to load your profiles right now.").color("#ff0000"));
            return;
        }

        ProfileActionOutcome outcome = handleProfileAction(data.action, playerData);
        if (outcome.requiresSave() && playerDataManager != null) {
            playerDataManager.save(playerData);
        }
        if (outcome.requiresRebuild()) {
            rebuild();
        }
    }

    private ProfileActionOutcome handleProfileAction(@Nonnull String action,
            @Nonnull PlayerData playerData) {
        String payload = action.substring("profile:".length());

        try {
            if ("new".equalsIgnoreCase(payload)) {
                return handleNewProfile(playerData);
            }
            if (payload.startsWith("select:")) {
                return handleSelectProfile(playerData, payload);
            }
            if (payload.startsWith("delete:")) {
                return handleDeleteRequest(playerData, payload);
            }
        } catch (Exception ex) {
            LOGGER.atSevere().withCause(ex).log("ProfileUIPage: error handling action %s", action);
            playerRef.sendMessage(Message.raw("Something went wrong handling that request.").color("#ff0000"));
        }

        return new ProfileActionOutcome(false, false);
    }

    private ProfileActionOutcome handleNewProfile(@Nonnull PlayerData playerData) {
        if (playerData.getProfileCount() >= PlayerData.MAX_PROFILES) {
            playerRef.sendMessage(Message.raw("All profile slots are already in use. Delete one first.")
                    .color("#ff9900"));
            return new ProfileActionOutcome(false, false);
        }

        int nextSlot = playerData.findNextAvailableProfileSlot();
        if (!PlayerData.isValidProfileIndex(nextSlot)) {
            playerRef.sendMessage(Message.raw("Unable to find an open slot right now.").color("#ff0000"));
            return new ProfileActionOutcome(false, false);
        }

        boolean created = playerData.createProfile(nextSlot, PlayerData.defaultProfileName(nextSlot), false, true);
        if (!created) {
            playerRef.sendMessage(Message.raw("Could not create that profile slot.").color("#ff0000"));
            return new ProfileActionOutcome(false, false);
        }

        playerRef.sendMessage(Message.raw("Created and activated profile slot " + nextSlot + ".")
                .color("#4fd7f7"));
        return new ProfileActionOutcome(true, true);
    }

    private ProfileActionOutcome handleSelectProfile(@Nonnull PlayerData playerData, @Nonnull String payload) {
        int slot = parseSlot(payload, "select:");
        if (!PlayerData.isValidProfileIndex(slot)) {
            playerRef.sendMessage(Message.raw("Profile slot must be between 1 and " + PlayerData.MAX_PROFILES + ".")
                    .color("#ff0000"));
            return new ProfileActionOutcome(false, false);
        }

        if (!playerData.hasProfile(slot)) {
            playerRef.sendMessage(Message.raw("Profile slot " + slot + " has not been created yet.")
                    .color("#ff9900"));
            return new ProfileActionOutcome(false, false);
        }

        if (playerData.isProfileActive(slot)) {
            playerRef.sendMessage(Message.raw("Profile slot " + slot + " is already active.").color("#4fd7f7"));
            return new ProfileActionOutcome(false, false);
        }

        PlayerData.ProfileSwitchResult result = playerData.switchProfile(slot);
        if (result == PlayerData.ProfileSwitchResult.SWITCHED_EXISTING) {
            playerRef.sendMessage(Message.raw(
                    "Switched to profile slot " + slot + " (" + playerData.getProfileName(slot) + ").")
                    .color("#00ff00"));
            return new ProfileActionOutcome(true, true);
        }

        playerRef.sendMessage(Message.raw("Unable to switch to that slot right now.").color("#ff0000"));
        return new ProfileActionOutcome(false, false);
    }

    private ProfileActionOutcome handleDeleteRequest(@Nonnull PlayerData playerData,
            @Nonnull String payload) {
        int slot = parseSlot(payload, "delete:");
        if (!PlayerData.isValidProfileIndex(slot)) {
            playerRef.sendMessage(Message.raw("Profile slot must be between 1 and " + PlayerData.MAX_PROFILES + ".")
                    .color("#ff0000"));
            return new ProfileActionOutcome(false, false);
        }
        if (!playerData.hasProfile(slot)) {
            playerRef.sendMessage(Message.raw("Profile slot " + slot + " is already empty.").color("#ff9900"));
            return new ProfileActionOutcome(false, false);
        }
        if (playerData.isProfileActive(slot)) {
            playerRef.sendMessage(Message.raw("Switch to a different profile before deleting slot " + slot + ".")
                    .color("#ff9900"));
            return new ProfileActionOutcome(false, false);
        }
        if (playerData.getProfileCount() <= 1) {
            playerRef.sendMessage(Message.raw("You must keep at least one profile slot.").color("#ff0000"));
            return new ProfileActionOutcome(false, false);
        }

        boolean deleted = playerData.deleteProfile(slot);
        if (!deleted) {
            playerRef.sendMessage(Message.raw("Could not delete that profile slot right now.").color("#ff0000"));
            return new ProfileActionOutcome(false, false);
        }

        playerRef.sendMessage(Message.raw("Deleted profile slot " + slot + ".").color("#4fd7f7"));
        return new ProfileActionOutcome(true, true);
    }

    private int parseSlot(@Nonnull String payload, @Nonnull String prefix) {
        try {
            return Integer.parseInt(payload.substring(prefix.length()));
        } catch (Exception ex) {
            LOGGER.atWarning().log("ProfileUIPage: invalid slot payload %s", payload);
            return -1;
        }
    }

    private String formatNumber(double value) {
        String formatted = String.format("%.2f", value);
        if (formatted.contains(".")) {
            formatted = formatted.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return formatted;
    }

    private record ProfileActionOutcome(boolean requiresSave, boolean requiresRebuild) {
    }
}
