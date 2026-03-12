package com.airijko.endlessleveling.races;

import java.util.Objects;

public final class RaceAscensionPathLink {

    private final String id;
    private final String name;

    public RaceAscensionPathLink(String id, String name) {
        this.id = Objects.requireNonNull(id, "Ascension path id cannot be null");
        this.name = name == null ? id : name;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
