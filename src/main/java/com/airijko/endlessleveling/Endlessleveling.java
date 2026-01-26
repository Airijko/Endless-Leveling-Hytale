package com.airijko.endlessleveling;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.util.logging.Level;
import javax.annotation.Nonnull;

/**
 * Main entry point for the Endlessleveling plugin.
 */
public class Endlessleveling extends JavaPlugin {

    public Endlessleveling(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        // Called during plugin setup phase
    }

    @Override
    protected void start() {
        // Called when the plugin is enabled
        getLogger().at(Level.INFO).log("Endlessleveling has been enabled!");
    }

    @Override
    protected void shutdown() {
        // Called when the plugin is disabled
        getLogger().at(Level.INFO).log("Endlessleveling has been disabled!");
    }
}
