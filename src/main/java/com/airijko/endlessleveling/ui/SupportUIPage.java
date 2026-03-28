package com.airijko.endlessleveling.ui;

import javax.annotation.Nonnull;

import com.airijko.endlessleveling.EndlessLeveling;
import com.airijko.endlessleveling.security.PartnerBrandingAllowlist;
import com.airijko.endlessleveling.util.Lang;
import com.airijko.endlessleveling.util.DiscordLinkResolver;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import static com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType.Activating;
import static com.hypixel.hytale.server.core.ui.builder.EventData.of;

/**
 * Support page displaying project credit and authenticity notice.
 */
public class SupportUIPage extends InteractiveCustomUIPage<SkillsUIPage.Data> {

    public SupportUIPage(@Nonnull PlayerRef playerRef,
            @Nonnull CustomPageLifetime lifetime) {
        super(playerRef, lifetime, SkillsUIPage.Data.CODEC);
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref,
            @Nonnull UICommandBuilder ui,
            @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {

        ui.append("Pages/SupportPage.ui");
        NavUIHelper.applyNavVersion(ui, playerRef, "support",
                "Common/UI/Custom/Pages/SupportPage.ui",
                "#SupportTitle");
        NavUIHelper.bindNavEvents(events);
        events.addEventBinding(Activating, "#SupportDiscordButton", of("Action", "support:discord"), false);

        ui.set("#SupportTitleLabel.Text",
                Lang.tr(playerRef.getUuid(), "ui.support.page.title", "Support"));
        ui.set("#SupportModName.Text",
                Lang.tr(playerRef.getUuid(), "ui.support.mod_name", "Endless Leveling"));
        ui.set("#SupportDeveloperLabel.Text",
                Lang.tr(playerRef.getUuid(), "ui.support.developer", "Developer: Airijko"));
        ui.set("#SupportNotice1.Text",
                Lang.tr(playerRef.getUuid(), "ui.support.notice1",
                        "Some redistributed versions alter the UI or remove the Endless Leveling name and credit."));
        ui.set("#SupportNotice2.Text",
                Lang.tr(playerRef.getUuid(), "ui.support.notice2",
                        "These versions are not official."));
        ui.set("#SupportNotice3.Text",
                Lang.tr(playerRef.getUuid(), "ui.support.notice3",
                        "If you enjoy the mod, please support the original project and developer."));

        EndlessLeveling plugin = EndlessLeveling.getInstance();
        boolean partnerAddonDetected = isPartnerAddonDetected();
        boolean partnerAddonValid = partnerAddonDetected && plugin != null && plugin.isPartnerAddonAuthorized();

        ui.set("#SupportPartnerSectionTitle.Text",
                Lang.tr(playerRef.getUuid(), "ui.support.partner.section", "Official Endless Partners"));

        ui.set("#SupportPartnerAddonValidationLabel.Text",
                Lang.tr(playerRef.getUuid(), "ui.support.partner.valid.label", "Addon Validation"));
        if (!partnerAddonDetected) {
            ui.set("#SupportPartnerAddonValidationValue.Text",
                    Lang.tr(playerRef.getUuid(), "ui.support.partner.valid.na", "N/A (addon not present)"));
            ui.set("#SupportPartnerAddonValidationValue.Style.TextColor", "#9fb6d3");
        } else if (partnerAddonValid) {
            ui.set("#SupportPartnerAddonValidationValue.Text",
                    Lang.tr(playerRef.getUuid(), "ui.support.partner.valid.ok", "Valid (authorized)"));
            ui.set("#SupportPartnerAddonValidationValue.Style.TextColor", "#6ee7b7");
        } else {
            ui.set("#SupportPartnerAddonValidationValue.Text",
                    Lang.tr(playerRef.getUuid(), "ui.support.partner.valid.fail", "Invalid (not authorized)"));
            ui.set("#SupportPartnerAddonValidationValue.Style.TextColor", "#ff6b6b");
        }

        ui.set("#SupportPartnerServerListLabel.Text",
                Lang.tr(playerRef.getUuid(), "ui.support.partner.servers.label", "Authorized Partner Servers"));
        String partnerServerNames = PartnerBrandingAllowlist.getPartnerServerNames().isEmpty()
                ? Lang.tr(playerRef.getUuid(), "ui.support.partner.servers.none", "None")
                : String.join(", ", PartnerBrandingAllowlist.getPartnerServerNames());
        ui.set("#SupportPartnerServerListValue.Text", partnerServerNames);
        ui.set("#SupportDiscordTitle.Text",
                Lang.tr(playerRef.getUuid(), "ui.support.discord_title", "Need help?"));
        ui.set("#SupportDiscordDescription.Text",
                Lang.tr(playerRef.getUuid(), "ui.support.discord_description",
                        "For support, issues, and help, join our Discord community."));
        ui.set("#SupportDiscordButton.Text",
                Lang.tr(playerRef.getUuid(), "ui.support.discord_button", "JOIN DISCORD"));
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store,
            @Nonnull SkillsUIPage.Data data) {
        super.handleDataEvent(ref, store, data);

        if (data.action == null || data.action.isEmpty()) {
            return;
        }

        if ("support:discord".equalsIgnoreCase(data.action)) {
            String discordInviteUrl = DiscordLinkResolver.getDiscordInviteUrl();
            playerRef.sendMessage(Message.raw(
                    Lang.tr(playerRef.getUuid(), "ui.support.discord_chat_prompt",
                            "Need help or want to report an issue? Click below to join Discord."))
                    .color("#4fd7f7"));
            playerRef.sendMessage(Message
                    .raw(Lang.tr(playerRef.getUuid(), "ui.support.discord_chat_link_label", "Endless Leveling Discord"))
                    .link(discordInviteUrl)
                    .color("#6fe3ff"));
            return;
        }

        NavUIHelper.handleNavAction(data.action, ref, store, playerRef);
    }

        private boolean isPartnerAddonDetected() {
                return isAddonClassPresent("com.airijko.endlessleveling.EndlessLevelingPartnerAddon")
                        || isAddonClassPresent("com.airijko.endlessleveling.EndlessLevelingARankAddon");
        }

        private boolean isAddonClassPresent(String className) {
                try {
                        Class.forName(className);
                        return true;
                } catch (Throwable ignored) {
                        return false;
                }
        }
}
