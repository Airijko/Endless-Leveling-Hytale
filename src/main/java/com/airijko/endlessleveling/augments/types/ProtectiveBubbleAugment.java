package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentRuntimeState;
import com.airijko.endlessleveling.augments.AugmentRuntimeManager.AugmentState;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;

import java.util.Map;

public final class ProtectiveBubbleAugment extends YamlAugment implements AugmentHooks.OnDamageTakenAugment {
    public static final String ID = "protective_bubble";

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
            return context != null ? context.getIncomingDamage() : 0f;
        }

        float incoming = Math.max(0f, context.getIncomingDamage());
        if (incoming <= 0f) {
            return incoming;
        }

        long now = System.currentTimeMillis();
        AugmentRuntimeState runtime = context.getRuntimeState();
        AugmentState state = runtime.getState(ID);

        if (state.getStacks() > 0 && state.getExpiresAt() > now) {
            state.setStacks(0);
            state.setExpiresAt(0L);
            return 0f;
        }

        if (!AugmentUtils.consumeCooldown(runtime, ID, getName(), cooldownMillis)) {
            return incoming;
        }

        state.setStacks(1);
        state.setExpiresAt(now + Math.max(1L, immunityWindowMillis));
        state.setStacks(0);
        state.setExpiresAt(0L);
        return 0f;
    }
}
