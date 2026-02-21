package com.airijko.endlessleveling.augments.types;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.AugmentHooks;
import com.airijko.endlessleveling.augments.AugmentUtils;
import com.airijko.endlessleveling.augments.AugmentValueReader;
import com.airijko.endlessleveling.augments.YamlAugment;

import java.util.Map;

public final class ShadowstepAugment extends YamlAugment implements AugmentHooks.OnKillAugment {
    public static final String ID = "shadowstep";

    private final long cooldownMillis;

    public ShadowstepAugment(AugmentDefinition definition) {
        super(definition);
        Map<String, Object> passives = definition.getPassives();
        Map<String, Object> invis = AugmentValueReader.getMap(passives, "invisibility_on_kill");
        this.cooldownMillis = AugmentUtils
                .secondsToMillis(AugmentValueReader.getDouble(invis, "trigger_cooldown", 0.0D));
    }

    @Override
    public void onKill(AugmentHooks.KillContext context) {
        // Actual invisibility/speed effects need engine support; track cooldown only.
        AugmentUtils.markProc(context.getRuntimeState(), ID, cooldownMillis);
    }
}
