package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.airijko.endlessleveling.enums.SkillAttributeType;
import com.hypixel.hytale.logger.HytaleLogger;

import java.util.Map;

public final class FleetFootworkAugment extends YamlAugment implements AugmentHooks.OnHitAugment {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();
    public static final String ID = "fleet_footwork";
    public static final String BUFF_WINDOW_STATE_ID = ID + "_buff_window";

    private final long cooldownMillis;
    private final double healPercentOfDamage;
    private final double movementSpeedBonus;
    private final long movementDurationMillis;

    public FleetFootworkAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> empoweredHit = AugmentValueReader.getMap(passives, "empowered_hit");
        Map<String, Object> buffs = AugmentValueReader.getMap(passives, "buffs");
        Map<String, Object> movementSpeed = AugmentValueReader.getMap(buffs, "movement_speed");

        this.cooldownMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(empoweredHit, "cooldown", 8.0D));
        this.healPercentOfDamage = Math.max(0.0D,
                AugmentValueReader.getDouble(empoweredHit, "heal_percent_of_damage", 0.0D));
        this.movementSpeedBonus = AugmentValueReader.getDouble(movementSpeed, "value", 0.0D);
        this.movementDurationMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(movementSpeed, "duration", 0.0D));
    }

    @Override
    public float onHit(AugmentHooks.HitContext context) {
        if (context == null || context.getRuntimeState() == null) {
            return context != null ? context.getDamage() : 0f;
        }

        var runtime = context.getRuntimeState();
        if (!AugmentUtils.consumeCooldown(runtime, ID, getName(), cooldownMillis)) {
            return context.getDamage();
        }

        long now = System.currentTimeMillis();

        if (healPercentOfDamage > 0.0D) {
            AugmentUtils.heal(context.getAttackerStats(), context.getDamage() * healPercentOfDamage);
        }

        if (movementSpeedBonus != 0.0D) {
            AugmentUtils.setAttributeBonus(runtime,
                    ID + "_haste",
                    SkillAttributeType.HASTE,
                    movementSpeedBonus * 100.0D,
                    movementDurationMillis);
            var state = runtime.getState(BUFF_WINDOW_STATE_ID);
            state.setStacks(1);
            state.setExpiresAt(movementDurationMillis > 0L ? now + movementDurationMillis : 0L);
            state.setLastProc(now);
        }

        String playerId = context.getPlayerData() != null && context.getPlayerData().getUuid() != null
                ? context.getPlayerData().getUuid().toString()
                : "unknown";
        LOGGER.atInfo().log(
                "Fleet Footwork activated for player=%s damage=%.2f healPct=%.3f hastePct=%.3f durationMs=%d cooldownMs=%d",
                playerId,
                context.getDamage(),
                healPercentOfDamage,
                movementSpeedBonus,
                movementDurationMillis,
                cooldownMillis);

        return context.getDamage();
    }
}
