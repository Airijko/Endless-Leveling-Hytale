package com.airijko.endlessleveling.passives;

import com.airijko.endlessleveling.enums.ArchetypePassiveType;
import com.airijko.endlessleveling.enums.DamageLayer;
import com.airijko.endlessleveling.enums.PassiveStackingStyle;
import com.airijko.endlessleveling.races.RacePassiveDefinition;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Lightweight descriptor linking an archetype passive to the layer/tag/stacking
 * metadata required by the combat pipeline.
 */
public record PassiveContributionBlueprint(DamageLayer layer,
        String tag,
        PassiveStackingStyle stackingStyle) {

    public PassiveContributionBlueprint {
        DamageLayer normalizedLayer = layer == null ? DamageLayer.BONUS : layer;
        layer = normalizedLayer;
        String resolvedTag = (tag == null || tag.isBlank()) ? "default" : tag.trim().toLowerCase(Locale.ROOT);
        tag = resolvedTag;
        stackingStyle = Objects.requireNonNullElse(stackingStyle, PassiveStackingStyle.ADDITIVE);
    }

    public static PassiveContributionBlueprint fromDefinitions(ArchetypePassiveType type,
            List<RacePassiveDefinition> definitions) {
        DamageLayer resolvedLayer = null;
        String resolvedTag = null;
        PassiveStackingStyle resolvedStacking = null;
        if (definitions != null) {
            for (RacePassiveDefinition definition : definitions) {
                if (definition == null) {
                    continue;
                }
                if (resolvedLayer == null && definition.damageLayer() != null) {
                    resolvedLayer = definition.damageLayer();
                }
                if (resolvedTag == null && definition.tag() != null) {
                    resolvedTag = definition.tag();
                }
                if (resolvedStacking == null && definition.stackingStyle() != null) {
                    resolvedStacking = definition.stackingStyle();
                }
                if (resolvedLayer != null && resolvedTag != null && resolvedStacking != null) {
                    break;
                }
            }
        }
        if (resolvedLayer == null) {
            resolvedLayer = defaultLayer(type);
        }
        if (resolvedTag == null) {
            resolvedTag = defaultTag(type);
        }
        if (resolvedStacking == null) {
            resolvedStacking = PassiveStackingStyle.defaultFor(type);
        }
        return new PassiveContributionBlueprint(resolvedLayer, resolvedTag, resolvedStacking);
    }

    private static DamageLayer defaultLayer(ArchetypePassiveType type) {
        if (type == null) {
            return DamageLayer.BONUS;
        }
        return switch (type) {
            case FIRST_STRIKE, BERZERKER, EXECUTIONER, RETALIATION -> DamageLayer.BONUS;
            default -> DamageLayer.BONUS;
        };
    }

    private static String defaultTag(ArchetypePassiveType type) {
        if (type == null) {
            return "default";
        }
        return type.getConfigKey().toLowerCase(Locale.ROOT);
    }
}
