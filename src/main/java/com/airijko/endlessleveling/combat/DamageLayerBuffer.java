package com.airijko.endlessleveling.combat;

import com.airijko.endlessleveling.enums.DamageLayer;
import com.airijko.endlessleveling.enums.PassiveStackingStyle;
import com.hypixel.hytale.logger.HytaleLogger;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregates tagged damage modifiers per layer so the combat listener can
 * process them in a predictable order.
 */
public class DamageLayerBuffer {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClassFull();

    private final EnumMap<DamageLayer, LayerState> layers = new EnumMap<>(DamageLayer.class);

    public void addPercent(DamageLayer layer, String tag, double percent, PassiveStackingStyle stackingStyle) {
        if (percent == 0.0D) {
            return;
        }
        DamageLayer normalizedLayer = layer == null ? DamageLayer.BONUS : layer;
        LayerState state = layers.computeIfAbsent(normalizedLayer, key -> new LayerState());
        String normalizedTag = normalizeTag(tag, normalizedLayer);
        state.addPercent(normalizedTag, percent, stackingStyle);
    }

    public void addFlat(DamageLayer layer, double amount) {
        if (amount == 0.0D) {
            return;
        }
        DamageLayer normalizedLayer = layer == null ? DamageLayer.BONUS : layer;
        LayerState state = layers.computeIfAbsent(normalizedLayer, key -> new LayerState());
        state.addFlat(amount);
    }

    public float apply(DamageLayer layer, float inputDamage) {
        LayerState state = layers.get(layer == null ? DamageLayer.BONUS : layer);
        if (state == null || inputDamage <= 0f) {
            return inputDamage;
        }
        return state.apply(inputDamage);
    }

    private static String normalizeTag(String tag, DamageLayer layer) {
        if (tag == null || tag.isBlank()) {
            return layer.name().toLowerCase(Locale.ROOT);
        }
        return tag.trim().toLowerCase(Locale.ROOT);
    }

    private static final class LayerState {
        private final Map<String, TagState> tags = new LinkedHashMap<>();
        private double flatBonus;

        void addPercent(String tag, double percent, PassiveStackingStyle stackingStyle) {
            TagState state = tags.computeIfAbsent(tag,
                    key -> new TagState(stackingStyle == null ? PassiveStackingStyle.ADDITIVE : stackingStyle));
            state.add(percent, stackingStyle);
        }

        void addFlat(double amount) {
            this.flatBonus += amount;
        }

        float apply(float inputDamage) {
            double percentTotal = tags.values().stream().mapToDouble(TagState::value).sum();
            float scaled = (float) (inputDamage * (1.0D + percentTotal));
            return scaled + (float) flatBonus;
        }
    }

    private static final class TagState {
        private final PassiveStackingStyle stackingStyle;
        private double value;

        TagState(PassiveStackingStyle stackingStyle) {
            this.stackingStyle = Objects.requireNonNullElse(stackingStyle, PassiveStackingStyle.ADDITIVE);
        }

        void add(double incoming, PassiveStackingStyle requestedStyle) {
            if (requestedStyle != null && requestedStyle != stackingStyle) {
                LOGGER.atWarning().log(
                        "Mismatched stacking styles for tag; using %s but received %s",
                        stackingStyle,
                        requestedStyle);
            }
            value = stackingStyle.combine(value, incoming);
        }

        double value() {
            return value;
        }
    }
}
