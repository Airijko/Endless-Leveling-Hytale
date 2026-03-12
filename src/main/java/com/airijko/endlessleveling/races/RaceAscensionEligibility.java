package com.airijko.endlessleveling.races;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class RaceAscensionEligibility {

    private final boolean eligible;
    private final List<String> blockers;

    public RaceAscensionEligibility(boolean eligible, List<String> blockers) {
        this.eligible = eligible;
        List<String> copy = blockers == null ? new ArrayList<>() : new ArrayList<>(blockers);
        this.blockers = Collections.unmodifiableList(copy);
    }

    public static RaceAscensionEligibility allowed() {
        return new RaceAscensionEligibility(true, Collections.emptyList());
    }

    public static RaceAscensionEligibility denied(List<String> blockers) {
        return new RaceAscensionEligibility(false, blockers);
    }

    public boolean isEligible() {
        return eligible;
    }

    public List<String> getBlockers() {
        return blockers;
    }
}
