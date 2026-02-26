package com.airijko.endlessleveling.commands.subcommands;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentManager;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.airijko.endlessleveling.managers.PlayerAttributeManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.managers.SkillManager;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.entity.entities.player.movement.MovementManager;
import com.hypixel.hytale.protocol.MovementSettings;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;

public class StatTestCommand extends AbstractPlayerCommand {

    private final PlayerDataManager playerDataManager;
    private final SkillManager skillManager;
    private final PlayerAttributeManager attributeManager;
    private final AugmentManager augmentManager;
    private final com.airijko.endlessleveling.augments.AugmentRuntimeManager augmentRuntimeManager;

    public StatTestCommand() {
        super("stattest", "Test player stats");
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        this.playerDataManager = plugin.getPlayerDataManager();
        this.skillManager = plugin.getSkillManager();
        this.attributeManager = plugin.getPlayerAttributeManager();
        this.augmentManager = plugin.getAugmentManager();
        this.augmentRuntimeManager = plugin.getAugmentRuntimeManager();
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef, @Nonnull World world) {
        CompletableFuture.runAsync(() -> {
            PlayerData playerData = playerDataManager != null ? playerDataManager.get(playerRef.getUuid()) : null;
            if (playerData == null) {
                playerRef.sendMessage(Message.raw("No player data loaded. Try rejoining."));
                return;
            }

            // Fetch entity stats
            EntityStatMap entityStatMap = store.getComponent(ref, EntityStatMap.getComponentType());

            List<Message> lines = new ArrayList<>();

            lines.add(sectionHeader("Player Stats"));
            if (entityStatMap != null) {
                ensureSyncedAttributes(store, ref, playerData);
                addStatLine(lines, entityStatMap, "Health", DefaultEntityStatTypes.getHealth());
                addStatLine(lines, entityStatMap, "Oxygen", DefaultEntityStatTypes.getOxygen());
                addStatLine(lines, entityStatMap, "Stamina", DefaultEntityStatTypes.getStamina());
                addStatLine(lines, entityStatMap, "Mana", DefaultEntityStatTypes.getMana());
                addStatLine(lines, entityStatMap, "Signature Energy", DefaultEntityStatTypes.getSignatureEnergy());
                addStatLine(lines, entityStatMap, "Ammo", DefaultEntityStatTypes.getAmmo());
            } else {
                lines.add(infoLine("No stats found for the player."));
            }

            lines.add(sectionHeader("Attributes (Race + Skill + Augments)"));
            Map<String, AttributeBreakdown> attributeBreakdowns = collectAttributeBreakdowns(playerData);
            if (attributeBreakdowns.isEmpty()) {
                lines.add(infoLine("No attribute data available."));
            } else {
                attributeBreakdowns.forEach((label, breakdown) -> addAttributeLine(lines, label, breakdown));
                lines.add(sectionHeader("Totals"));
                attributeBreakdowns.forEach((label, breakdown) -> lines.add(totalLine(label, breakdown)));
            }

            lines.add(sectionHeader("Life Steal"));
            LifeStealSummary lifeSteal = collectLifeStealSummary(playerData, entityStatMap);
            lines.add(infoLine(String.format("Flat from augments: %s%%", formatDouble(lifeSteal.flatPercent()))));
            lines.add(infoLine(String.format("Scaling current: %s%% (max %s%%)",
                    formatDouble(lifeSteal.scalingCurrentPercent()),
                    formatDouble(lifeSteal.scalingMaxPercent()))));
            lines.add(infoLine(String.format("Total current: %s%% (max %s%%)",
                    formatDouble(lifeSteal.totalCurrentPercent()),
                    formatDouble(lifeSteal.totalMaxPercent()))));

            lines.add(sectionHeader("Movement"));
            MovementManager movementManager = store.getComponent(ref, MovementManager.getComponentType());
            if (movementManager != null) {
                MovementSettings settings = movementManager.getSettings();
                float walkSpeed = settings.baseSpeed * settings.forwardWalkSpeedMultiplier;
                float runSpeed = settings.baseSpeed * settings.forwardRunSpeedMultiplier;
                float sprintSpeed = settings.baseSpeed * settings.forwardSprintSpeedMultiplier;

                lines.add(movementLine("Walk Speed", walkSpeed));
                lines.add(movementLine("Run Speed", runSpeed));
                lines.add(movementLine("Sprint Speed", sprintSpeed));
            } else {
                lines.add(infoLine("No movement settings found."));
            }

            addAugmentRuntimeDebug(lines, playerData);

            lines.forEach(playerRef::sendMessage);
        }, world);
    }

    private void ensureSyncedAttributes(Store<EntityStore> store, Ref<EntityStore> ref, PlayerData playerData) {
        if (attributeManager == null || skillManager == null) {
            return;
        }

        Map<PlayerAttributeManager.AttributeSlot, Float> bonuses = new EnumMap<>(
                PlayerAttributeManager.AttributeSlot.class);
        bonuses.put(PlayerAttributeManager.AttributeSlot.LIFE_FORCE, skillManager.calculatePlayerHealth(playerData));
        bonuses.put(PlayerAttributeManager.AttributeSlot.STAMINA, skillManager.calculatePlayerStamina(playerData));
        bonuses.put(PlayerAttributeManager.AttributeSlot.FLOW,
                skillManager.calculatePlayerFlow(playerData));

        for (Map.Entry<PlayerAttributeManager.AttributeSlot, Float> entry : bonuses.entrySet()) {
            attributeManager.applyAttribute(entry.getKey(), ref, store, playerData, entry.getValue());
        }
    }

    private void addStatLine(List<Message> lines, EntityStatMap statMap, String label, int statIndex) {
        var statValue = statMap.get(statIndex);
        String valueText = statValue != null ? formatDouble(statValue.get()) : "N/A";
        lines.add(Message.raw(label + ": " + valueText).color("#7FDBFF"));
    }

    private void addAttributeLine(List<Message> lines, String label, AttributeBreakdown breakdown) {
        String raceSegment = breakdown.raceIsMultiplier()
                ? "race x" + formatDouble(breakdown.race())
                : "race=" + formatDouble(breakdown.race());
        String augmentSegment = breakdown.augment() != 0.0D
                ? ", aug=" + formatDouble(breakdown.augment())
                : "";
        String text = String.format("%s | %s, skill=%s%s", label,
                raceSegment, formatDouble(breakdown.skill()), augmentSegment);
        lines.add(Message.raw(text).color("#2ECC71"));
    }

    private Message movementLine(String label, float value) {
        return Message.raw(label + ": " + formatDouble(value)).color("#FFAB40");
    }

    private Message totalLine(String label, AttributeBreakdown breakdown) {
        String totalValue = formatDouble(breakdown.total());
        if (breakdown.totalIsMultiplier()) {
            totalValue = "x" + totalValue;
        }
        return Message.raw(label + " Total: " + totalValue).color("#00E676");
    }

    private Message infoLine(String text) {
        return Message.raw(text).color("#FF6B6B");
    }

    private Message sectionHeader(String title) {
        return Message.raw("=== " + title + " ===").color("#F5A623");
    }

    private String formatDouble(double value) {
        return String.format("%.2f", value);
    }

    private Map<String, AttributeBreakdown> collectAttributeBreakdowns(PlayerData playerData) {
        Map<String, AttributeBreakdown> breakdowns = new LinkedHashMap<>();
        if (playerData == null || attributeManager == null || skillManager == null) {
            return breakdowns;
        }

        double lifeRace = attributeManager.getRaceAttribute(playerData,
                PlayerAttributeManager.AttributeSlot.LIFE_FORCE.attributeType(), 0.0D);
        double lifeSkill = skillManager.calculatePlayerHealth(playerData);
        double lifeAug = getAugmentBonus(playerData, SkillAttributeType.LIFE_FORCE);
        breakdowns.put("Life Force",
                new AttributeBreakdown(lifeRace, lifeSkill, lifeAug, lifeRace + lifeSkill + lifeAug, false, false));

        double staminaRace = attributeManager.getRaceAttribute(playerData,
                PlayerAttributeManager.AttributeSlot.STAMINA.attributeType(), 0.0D);
        double staminaSkill = skillManager.calculatePlayerStamina(playerData);
        double staminaAug = getAugmentBonus(playerData, SkillAttributeType.STAMINA);
        breakdowns.put("Stamina",
                new AttributeBreakdown(staminaRace, staminaSkill, staminaAug, staminaRace + staminaSkill + staminaAug,
                        false,
                        false));

        double flowRace = attributeManager.getRaceAttribute(playerData,
                PlayerAttributeManager.AttributeSlot.FLOW.attributeType(), 0.0D);
        double flowSkill = skillManager.calculatePlayerFlow(playerData);
        double flowAug = getAugmentBonus(playerData, SkillAttributeType.FLOW);
        breakdowns.put("Flow", new AttributeBreakdown(flowRace, flowSkill, flowAug, flowRace + flowSkill + flowAug,
                false, false));

        SkillManager.StrengthBreakdown strength = skillManager.getStrengthBreakdown(playerData);
        double strengthAug = getAugmentBonus(playerData, SkillAttributeType.STRENGTH);
        breakdowns.put("Strength",
                new AttributeBreakdown(strength.raceMultiplier(), strength.skillValue(), strengthAug,
                        strength.totalValue(), true,
                        false));

        SkillManager.DefenseBreakdown defense = skillManager.getDefenseBreakdown(playerData);
        double defenseSkillTotal = defense.skillValue() + defense.innateValue();
        double defenseAug = getAugmentBonus(playerData, SkillAttributeType.DEFENSE);
        breakdowns.put("Defense",
                new AttributeBreakdown(defense.raceMultiplier(), defenseSkillTotal, defenseAug, defense.totalValue(),
                        true,
                        false));

        SkillManager.HasteBreakdown haste = skillManager.getHasteBreakdown(playerData);
        double hasteAug = getAugmentBonus(playerData, SkillAttributeType.HASTE);
        breakdowns.put("Haste", new AttributeBreakdown(haste.raceMultiplier(), haste.skillBonus(), hasteAug,
                haste.totalMultiplier(), true, true));

        SkillManager.PrecisionBreakdown precision = skillManager.getPrecisionBreakdown(playerData);
        double precisionAug = getAugmentBonus(playerData, SkillAttributeType.PRECISION);
        breakdowns.put("Precision",
                new AttributeBreakdown(precision.racePercent(), precision.skillPercent(), precisionAug,
                        precision.totalPercent(),
                        false, false));

        SkillManager.FerocityBreakdown ferocity = skillManager.getFerocityBreakdown(playerData);
        double ferocityAug = getAugmentBonus(playerData, SkillAttributeType.FEROCITY);
        breakdowns.put("Ferocity",
                new AttributeBreakdown(ferocity.raceValue(), ferocity.skillValue(), ferocityAug, ferocity.totalValue(),
                        false,
                        false));

        double disciplineRace = attributeManager.getRaceAttribute(playerData, SkillAttributeType.DISCIPLINE, 0.0D);
        double disciplineSkill = skillManager.calculateSkillAttributeBonus(playerData, SkillAttributeType.DISCIPLINE,
                -1);
        double disciplineAug = getAugmentBonus(playerData, SkillAttributeType.DISCIPLINE);
        breakdowns.put("Discipline",
                new AttributeBreakdown(disciplineRace, disciplineSkill, disciplineAug,
                        disciplineRace + disciplineSkill + disciplineAug, false, false));

        double sorceryRace = attributeManager.getRaceAttribute(playerData, SkillAttributeType.SORCERY, 0.0D);
        double sorcerySkill = skillManager.calculateSkillAttributeBonus(playerData, SkillAttributeType.SORCERY, -1);
        double sorceryAug = getAugmentBonus(playerData, SkillAttributeType.SORCERY);
        breakdowns.put("Sorcery",
                new AttributeBreakdown(sorceryRace, sorcerySkill, sorceryAug,
                        sorceryRace + sorcerySkill + sorceryAug, false, false));

        return breakdowns;
    }

    private void addAugmentRuntimeDebug(List<Message> lines, PlayerData playerData) {
        if (augmentRuntimeManager == null || playerData == null) {
            return;
        }
        var runtime = augmentRuntimeManager.getRuntimeState(playerData.getUuid());
        if (runtime == null) {
            return;
        }
        lines.add(sectionHeader("Augment Runtime"));

        long now = System.currentTimeMillis();
        String allAttributeBonuses = java.util.Arrays.stream(SkillAttributeType.values())
                .map(type -> type.name() + "=" + formatDouble(runtime.getAttributeBonus(type, now)))
                .collect(Collectors.joining(", "));
        lines.add(infoLine("Aug Attrs: " + allAttributeBonuses));

        var ragingState = runtime.getState(com.airijko.endlessleveling.augments.types.RagingMomentumAugment.ID);
        if (ragingState != null && ragingState.getStacks() > 0) {
            lines.add(infoLine(String.format("Raging Momentum stacks=%d expiresInMs=%d", ragingState.getStacks(),
                    Math.max(0L, ragingState.getExpiresAt() - System.currentTimeMillis()))));
        }
    }

    private LifeStealSummary collectLifeStealSummary(PlayerData playerData, EntityStatMap entityStatMap) {
        if (playerData == null || augmentManager == null) {
            return new LifeStealSummary(0.0D, 0.0D, 0.0D, 0.0D, 0.0D);
        }

        double missingHealthPercent = 0.0D;
        if (entityStatMap != null) {
            var health = entityStatMap.get(DefaultEntityStatTypes.getHealth());
            if (health != null && health.getMax() > 0f) {
                missingHealthPercent = Math.max(0.0D,
                        Math.min(1.0D, (health.getMax() - health.get()) / health.getMax()));
            }
        }

        double flatPercent = 0.0D;
        double scalingCurrentPercent = 0.0D;
        double scalingMaxPercent = 0.0D;

        for (String augmentId : playerData.getSelectedAugmentsSnapshot().values()) {
            if (augmentId == null || augmentId.isBlank()) {
                continue;
            }
            AugmentDefinition definition = augmentManager.getAugment(augmentId);
            if (definition == null) {
                continue;
            }
            Map<String, Object> passives = definition.getPassives();
            Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");

            double flatLifeSteal = AugmentValueReader.getNestedDouble(buffs, 0.0D, "life_steal", "value");
            flatPercent += flatLifeSteal * 100.0D;

            Map<String, Object> scaling = AugmentValueReader.getMap(buffs, "life_steal_scaling");
            double maxValue = Math.max(0.0D, AugmentValueReader.getDouble(scaling, "max_value", 0.0D));
            double maxMissingHealth = Math.max(0.0D,
                    AugmentValueReader.getDouble(scaling, "max_missing_health_percent", 1.0D));
            if (maxValue > 0.0D) {
                scalingMaxPercent += maxValue * 100.0D;
                double ratio = maxMissingHealth > 0.0D ? Math.min(1.0D, missingHealthPercent / maxMissingHealth) : 1.0D;
                scalingCurrentPercent += (maxValue * ratio) * 100.0D;
            }
        }

        double totalCurrentPercent = flatPercent + scalingCurrentPercent;
        double totalMaxPercent = flatPercent + scalingMaxPercent;
        return new LifeStealSummary(flatPercent, scalingCurrentPercent, scalingMaxPercent, totalCurrentPercent,
                totalMaxPercent);
    }

    private double getAugmentBonus(PlayerData playerData, SkillAttributeType attributeType) {
        if (augmentRuntimeManager == null || playerData == null || attributeType == null) {
            return 0.0D;
        }
        var runtime = augmentRuntimeManager.getRuntimeState(playerData.getUuid());
        if (runtime == null) {
            return 0.0D;
        }
        return runtime.getAttributeBonus(attributeType, System.currentTimeMillis());
    }

    private record AttributeBreakdown(double race, double skill, double augment, double total, boolean raceIsMultiplier,
            boolean totalIsMultiplier) {
    }

    private record LifeStealSummary(double flatPercent,
            double scalingCurrentPercent,
            double scalingMaxPercent,
            double totalCurrentPercent,
            double totalMaxPercent) {
    }
}
