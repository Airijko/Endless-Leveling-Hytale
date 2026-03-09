package com.airijko.endlessleveling.augments;

import com.airijko.endlessleveling.augments.types.*;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Central factory for instantiating typed augment classes.
 */
public final class AugmentRegistry {

    private static final Map<String, Function<AugmentDefinition, Augment>> FACTORIES = Map.ofEntries(
            Map.entry(AbsoluteFocusAugment.ID, AbsoluteFocusAugment::new),
            Map.entry(ArcaneInstabilityAugment.ID, ArcaneInstabilityAugment::new),
            Map.entry(ArcaneMasteryAugment.ID, ArcaneMasteryAugment::new),
            Map.entry(BailoutAugment.ID, BailoutAugment::new),
            Map.entry(BloodEchoAugment.ID, BloodEchoAugment::new),
            Map.entry(BloodFrenzyAugment.ID, BloodFrenzyAugment::new),
            Map.entry(BloodSurgeAugment.ID, BloodSurgeAugment::new),
            Map.entry(BloodthirsterAugment.ID, BloodthirsterAugment::new),
            Map.entry(BurnAugment.ID, BurnAugment::new),
            Map.entry(ConquerorAugment.ID, ConquerorAugment::new),
            Map.entry(CriticalGuardAugment.ID, CriticalGuardAugment::new),
            Map.entry(CutdownAugment.ID, CutdownAugment::new),
            Map.entry(DrainAugment.ID, DrainAugment::new),
            Map.entry(ExecutionerAugment.ID, ExecutionerAugment::new),
            Map.entry(FleetFootworkAugment.ID, FleetFootworkAugment::new),
            Map.entry(FirstStrikeAugment.ID, FirstStrikeAugment::new),
            Map.entry(FortressAugment.ID, FortressAugment::new),
            Map.entry(FourLeafCloverAugment.ID, FourLeafCloverAugment::new),
            Map.entry(GiantSlayerAugment.ID, GiantSlayerAugment::new),
            Map.entry(GlassCannonAugment.ID, GlassCannonAugment::new),
            Map.entry(MagicBladeAugment.ID, MagicBladeAugment::new),
            Map.entry(ManaInfusionAugment.ID, ManaInfusionAugment::new),
            Map.entry(NestingDollAugment.ID, NestingDollAugment::new),
            Map.entry(OverdriveAugment.ID, OverdriveAugment::new),
            Map.entry(OverhealAugment.ID, OverhealAugment::new),
            Map.entry(PhaseRushAugment.ID, PhaseRushAugment::new),
            Map.entry(PhantomHitsAugment.ID, PhantomHitsAugment::new),
            Map.entry(PredatorAugment.ID, PredatorAugment::new),
            Map.entry(RagingMomentumAugment.ID, RagingMomentumAugment::new),
            Map.entry(RebirthAugment.ID, RebirthAugment::new),
            Map.entry(ReckoningAugment.ID, ReckoningAugment::new),
            Map.entry(SnipersReachAugment.ID, SnipersReachAugment::new),
            Map.entry(SoulReaverAugment.ID, SoulReaverAugment::new),
            Map.entry(SupersonicAugment.ID, SupersonicAugment::new),
            Map.entry(TankEngineAugment.ID, TankEngineAugment::new),
            Map.entry(TimeMasterAugment.ID, TimeMasterAugment::new),
            Map.entry(TitansMightAugment.ID, TitansMightAugment::new),
            Map.entry(TitansWisdomAugment.ID, TitansWisdomAugment::new),
            Map.entry(UndyingRageAugment.ID, UndyingRageAugment::new),
            Map.entry(VampiricStrikeAugment.ID, VampiricStrikeAugment::new),
            Map.entry(VampirismAugment.ID, VampirismAugment::new),
            Map.entry(WitherAugment.ID, WitherAugment::new));

    private AugmentRegistry() {
    }

    public static Augment create(AugmentDefinition definition) {
        if (definition == null) {
            return null;
        }
        Function<AugmentDefinition, Augment> factory = FACTORIES.get(definition.getId());
        if (factory == null) {
            return new YamlAugment(definition);
        }
        return Objects.requireNonNull(factory.apply(definition));
    }
}
