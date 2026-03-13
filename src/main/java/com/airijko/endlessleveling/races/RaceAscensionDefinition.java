package com.airijko.endlessleveling.races;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class RaceAscensionDefinition {

    private final String id;
    private final String stage;
    private final String path;
    private final boolean finalForm;
    private final boolean singleRouteOnly;
    private final RaceAscensionRequirements requirements;
    private final List<RaceAscensionPathLink> nextPaths;

    public RaceAscensionDefinition(String id,
            String stage,
            String path,
            boolean finalForm,
            boolean singleRouteOnly,
            RaceAscensionRequirements requirements,
            List<RaceAscensionPathLink> nextPaths) {
        this.id = Objects.requireNonNull(id, "Ascension id cannot be null");
        this.stage = stage == null || stage.isBlank() ? "base" : stage;
        this.path = path == null || path.isBlank() ? "none" : path;
        this.finalForm = finalForm;
        this.singleRouteOnly = singleRouteOnly;
        this.requirements = requirements == null ? RaceAscensionRequirements.none() : requirements;
        List<RaceAscensionPathLink> links = nextPaths == null ? new ArrayList<>() : new ArrayList<>(nextPaths);
        this.nextPaths = Collections.unmodifiableList(links);
    }

    public static RaceAscensionDefinition baseFallback(String id) {
        return new RaceAscensionDefinition(id,
                "base",
                "none",
                false,
                true,
                RaceAscensionRequirements.none(),
                Collections.emptyList());
    }

    public String getId() {
        return id;
    }

    public String getStage() {
        return stage;
    }

    public String getPath() {
        return path;
    }

    public boolean isFinalForm() {
        return finalForm;
    }

    public boolean isSingleRouteOnly() {
        return singleRouteOnly;
    }

    public RaceAscensionRequirements getRequirements() {
        return requirements;
    }

    public List<RaceAscensionPathLink> getNextPaths() {
        return nextPaths;
    }
}
