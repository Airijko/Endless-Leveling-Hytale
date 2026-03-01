package com.airijko.endlessleveling.commands.subcommands;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.data.PlayerData;
import com.airijko.endlessleveling.managers.LanguageManager;
import com.airijko.endlessleveling.managers.PlayerDataManager;
import com.airijko.endlessleveling.ui.PlayerHud;
import com.airijko.endlessleveling.util.Lang;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;

public class LanguageCommand extends AbstractPlayerCommand {

    private final RequiredArg<String> localeArg = this.withRequiredArg("locale", "Language locale (example: en_US)",
            ArgTypes.STRING);

    private final PlayerDataManager playerDataManager;
    private final LanguageManager languageManager;

    public LanguageCommand() {
        super("language", "Set your personal EndlessLeveling language");
        EndlessLeveling plugin = EndlessLeveling.getInstance();
        this.playerDataManager = plugin != null ? plugin.getPlayerDataManager() : null;
        this.languageManager = plugin != null ? plugin.getLanguageManager() : null;
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected void execute(@Nonnull CommandContext commandContext,
            @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef senderRef,
            @Nonnull World world) {
        if (playerDataManager == null || languageManager == null) {
            senderRef.sendMessage(Message.raw("Language manager is unavailable.").color("#ff6666"));
            return;
        }

        PlayerData data = playerDataManager.get(senderRef.getUuid());
        if (data == null) {
            senderRef.sendMessage(Message.raw("Your player data is not loaded yet.").color("#ff6666"));
            return;
        }

        String requestedLocale = localeArg.get(commandContext);
        if (requestedLocale == null || requestedLocale.isBlank()) {
            senderRef.sendMessage(Message.raw("Usage: /skills language <locale>").color("#ffcc66"));
            return;
        }

        String normalizedRequested = normalizeLocale(requestedLocale);
        if (!languageManager.isLocaleAvailable(normalizedRequested)) {
            List<String> available = languageManager.getAvailableLocales();
            senderRef.sendMessage(Message.raw(Lang.tr(senderRef.getUuid(),
                    "command.language.invalid",
                    "Unknown locale '{0}'. Available locales: {1}",
                    normalizedRequested,
                    String.join(", ", available))).color("#ff6666"));
            return;
        }

        data.setLanguage(normalizedRequested);
        playerDataManager.save(data);
        PlayerHud.refreshAll();

        senderRef.sendMessage(Message.raw(Lang.tr(senderRef.getUuid(),
                "command.language.updated",
                "Language set to {0}.",
                data.getLanguage())).color("#6cff78"));
    }

    private String normalizeLocale(String raw) {
        if (raw == null || raw.isBlank()) {
            return "en_US";
        }
        String trimmed = raw.trim().replace('-', '_');
        String[] parts = trimmed.split("_");
        if (parts.length == 1) {
            return parts[0].toLowerCase(java.util.Locale.ROOT);
        }
        return parts[0].toLowerCase(java.util.Locale.ROOT) + "_" + parts[1].toUpperCase(java.util.Locale.ROOT);
    }
}
