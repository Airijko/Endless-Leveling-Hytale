package com.airijko.endlessleveling.ui;

import com.airijko.endlessleveling.augments.AugmentDefinition;
import com.airijko.endlessleveling.augments.types.CommonAugment;
import com.airijko.endlessleveling.enums.PassiveCategory;

import java.util.Locale;

/**
 * Shared mapper for augment card/info presentation so name/icon/common-stat
 * behavior stays consistent across pages.
 */
public final class AugmentPresentationMapper {

    @FunctionalInterface
    public interface Translator {
        String tr(String key, String fallback, Object... args);
    }

    private final Translator translator;

    public AugmentPresentationMapper(Translator translator) {
        this.translator = translator != null ? translator : (key, fallback, args) -> fallback;
    }

    public AugmentPresentationData map(AugmentDefinition definition, String augmentId) {
        String safeId = augmentId == null ? "" : augmentId;
        String iconItemId = resolveIconItemId(definition);
        String displayName = definition != null && definition.getName() != null ? definition.getName() : "";

        CommonAugment.CommonStatOffer commonStatOffer = CommonAugment.parseStatOfferId(augmentId);
        if (definition != null && commonStatOffer != null && CommonAugment.ID.equalsIgnoreCase(definition.getId())) {
            String attributeKey = commonStatOffer.attributeKey();
            displayName = tr("ui.augments.common_stat.card_name", "{0}", formatCommonStatDisplayName(attributeKey));
            iconItemId = SkillAttributeIconResolver.resolveByConfigKey(attributeKey, iconItemId);
        }

        return new AugmentPresentationData(safeId, displayName, iconItemId, commonStatOffer);
    }

    public String resolveIconItemId(AugmentDefinition definition) {
        PassiveCategory category = definition != null ? definition.getCategory() : PassiveCategory.PASSIVE_STAT;
        if (category == null) {
            category = PassiveCategory.PASSIVE_STAT;
        }
        String iconItemId = category.getIconItemId();
        return iconItemId == null || iconItemId.isBlank() ? "Ingredient_Ice_Essence" : iconItemId;
    }

    public String formatCommonStatDisplayName(String attributeKey) {
        if (attributeKey == null || attributeKey.isBlank()) {
            return tr("ui.augments.effect.label.common", "Common Stat");
        }

        String normalized = attributeKey.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        String[] parts = normalized.split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.toString();
    }

    private String tr(String key, String fallback, Object... args) {
        return translator.tr(key, fallback, args);
    }

    public record AugmentPresentationData(String augmentId,
            String displayName,
            String iconItemId,
            CommonAugment.CommonStatOffer commonStatOffer) {
    }
}
