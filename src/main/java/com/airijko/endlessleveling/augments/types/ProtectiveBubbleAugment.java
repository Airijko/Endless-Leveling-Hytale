package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;
import com.hypixel.hytale.logger.HytaleLogger;

import java.util.Map;

public final class ProtectiveBubbleAugment extends YamlAugment implements AugmentHooks.OnDamageTakenAugment {
    public static final String ID = "protective_bubble";
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final long cooldownMillis;
    private final long immunityWindowMillis;

    public ProtectiveBubbleAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> bubble = AugmentValueReader.getMap(passives, "immunity_bubble");
        this.cooldownMillis = AugmentUtils.secondsToMillis(AugmentValueReader.getDouble(bubble, "cooldown", 25.0D));
        this.immunityWindowMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(bubble, "immunity_window", 0.25D));
    }

    @Override
    public float onDamageTaken(AugmentHooks.DamageTakenContext context) {
        if (context == null || context.getRuntimeState() == null) {
            LOGGER.atFine().log("ProtectiveBubble skip: missing context/runtime");
            return context != null ? context.getIncomingDamage() : 0f;
        }

        float incoming = Math.max(0f, context.getIncomingDamage());
        if (incoming <= 0f) {
            LOGGER.atFine().log("ProtectiveBubble skip: non-positive incoming damage player=%s incoming=%.3f",
                    context.getPlayerData() != null ? context.getPlayerData().getUuid() : "unknown",
                    incoming);
            return incoming;
        }

        long now = System.currentTimeMillis();
        AugmentRuntimeState runtime = context.getRuntimeState();
        AugmentState state = runtime.getState(ID);
        var cooldown = runtime.getCooldown(ID);

        // After the shield is consumed, remain fully immune for the configured
        // post-hit window.
        if (state.getStacks() > 0 && state.getExpiresAt() > now) {
            LOGGER.atFine().log("ProtectiveBubble immunity active: player=%s incoming=%.3f remainingMs=%d",
                    context.getPlayerData() != null ? context.getPlayerData().getUuid() : "unknown",
                    incoming,
                    state.getExpiresAt() - now);
            return 0f;
        }

        if (state.getStacks() > 0 && state.getExpiresAt() <= now) {
            LOGGER.atFine().log("ProtectiveBubble immunity expired: player=%s",
                    context.getPlayerData() != null ? context.getPlayerData().getUuid() : "unknown");
            state.clear();
        }

        // "Shield up" state is represented by cooldown readiness.
        if (cooldown != null && cooldown.getExpiresAt() > now) {
            LOGGER.atFine().log("ProtectiveBubble shield unavailable: player=%s incoming=%.3f cooldownRemainingMs=%d",
                    context.getPlayerData() != null ? context.getPlayerData().getUuid() : "unknown",
                    incoming,
                    cooldown.getExpiresAt() - now);
            return incoming;
        }

        // First damage while shield is up is fully prevented. This consumes the
        // shield, starts cooldown, and grants a short post-hit immunity window.
        if (cooldownMillis > 0L) {
            runtime.setCooldown(ID, getName(), now + cooldownMillis);
        }
        state.setLastProc(now);
        state.setStacks(1);
        state.setExpiresAt(now + Math.max(1L, immunityWindowMillis));
        LOGGER.atInfo().log("ProtectiveBubble triggered: player=%s blocked=%.3f cooldownMs=%d immunityMs=%d",
                context.getPlayerData() != null ? context.getPlayerData().getUuid() : "unknown",
                incoming,
                cooldownMillis,
                immunityWindowMillis);
        return 0f;
    }
}
