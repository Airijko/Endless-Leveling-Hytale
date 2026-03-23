#!/usr/bin/env python3

path = "src/main/java/com/airijko/endlessleveling/passives/type/ArmyOfTheDeadPassive.java"

with open(path, 'r') as f:
    content = f.read()

# Enhancement 1: Add detailed logging at the applyPlayerAugmentsToSummon call site
old_call_pattern = """                applyPlayerAugmentsToSummon(summonRef, store, request.ownerUuid(), summonUuidComponent.getUuid());"""

new_call_pattern = """                applyPlayerAugmentsToSummon(summonRef, store, request.ownerUuid(), summonUuidComponent.getUuid(), request.slotIndex());"""

content = content.replace(old_call_pattern, new_call_pattern)
print("✓ Updated method call to pass slot index")

# Enhancement 2: Update method signature to add slot index
old_signature = """    private static void applyPlayerAugmentsToSummon(Ref<EntityStore> summonRef,
            Store<EntityStore> store,
            UUID ownerUuid,
            UUID summonUuid) {"""

new_signature = """    private static void applyPlayerAugmentsToSummon(Ref<EntityStore> summonRef,
            Store<EntityStore> store,
            UUID ownerUuid,
            UUID summonUuid,
            int slotIndex) {"""

content = content.replace(old_signature, new_signature)
print("✓ Updated method signature with slot index")

# Enhancement 3: Replace the entire method body with enhanced logging
old_method_body = """        if (summonRef == null || store == null || ownerUuid == null || summonUuid == null) {
            return;
        }

        try {
            EndlessLeveling plugin = EndlessLeveling.getInstance();
            if (plugin == null) {
                return;
            }

            PlayerDataManager playerDataMgr = plugin.getPlayerDataManager();
            if (playerDataMgr == null) {
                return;
            }

            PlayerData playerData = playerDataMgr.get(ownerUuid);
            if (playerData == null) {
                return;
            }

            // Get player's selected augments
            Map<String, String> selectedAugments = playerData.getSelectedAugmentsSnapshot();
            if (selectedAugments == null || selectedAugments.isEmpty()) {
                return;
            }

            // Extract augment IDs from the snapshot
            List<String> augmentIds = new ArrayList<>(selectedAugments.values());
            if (augmentIds.isEmpty()) {
                return;
            }

            // Register augments on the summon
            if (plugin.getMobAugmentExecutor() != null && plugin.getAugmentManager() != null
                    && plugin.getAugmentRuntimeManager() != null) {
                plugin.getMobAugmentExecutor().registerMobAugments(summonUuid,
                        augmentIds,
                        plugin.getAugmentManager(),
                        plugin.getAugmentRuntimeManager());
                LOGGER.atInfo().log("[ARMY_OF_THE_DEAD][AUGMENTS] Registered %d augments to summon %s from owner %s: %s",
                        augmentIds.size(), summonUuid, ownerUuid, augmentIds);
            }
        } catch (Exception e) {
            LOGGER.atWarning().withCause(e)
                    .log("[ARMY_OF_THE_DEAD] Failed to apply augments to summon %s: %s", summonUuid, e.getMessage());
        }
    }"""

new_method_body = """        if (summonRef == null || store == null || ownerUuid == null || summonUuid == null) {
            return;
        }

        try {
            EndlessLeveling plugin = EndlessLeveling.getInstance();
            if (plugin == null) {
                LOGGER.atWarning().log("[ARMY_OF_THE_DEAD][AUGMENTS] Plugin not available for summon %s slot %d", summonUuid, slotIndex);
                return;
            }

            PlayerDataManager playerDataMgr = plugin.getPlayerDataManager();
            if (playerDataMgr == null) {
                LOGGER.atWarning().log("[ARMY_OF_THE_DEAD][AUGMENTS] PlayerDataManager not available for summon %s slot %d", summonUuid, slotIndex);
                return;
            }

            PlayerData playerData = playerDataMgr.get(ownerUuid);
            if (playerData == null) {
                LOGGER.atWarning().log("[ARMY_OF_THE_DEAD][AUGMENTS] No player data found for owner %s, summon %s slot %d", ownerUuid, summonUuid, slotIndex);
                return;
            }

            // Get player's selected augments
            Map<String, String> selectedAugments = playerData.getSelectedAugmentsSnapshot();
            if (selectedAugments == null || selectedAugments.isEmpty()) {
                LOGGER.atInfo().log("[ARMY_OF_THE_DEAD][AUGMENTS] No augments selected for owner %s, summon %s slot %d will spawn without augments", ownerUuid, summonUuid, slotIndex);
                return;
            }

            // Extract augment IDs from the snapshot
            List<String> augmentIds = new ArrayList<>(selectedAugments.values());
            if (augmentIds.isEmpty()) {
                LOGGER.atInfo().log("[ARMY_OF_THE_DEAD][AUGMENTS] Augment list is empty for owner %s, summon %s slot %d", ownerUuid, summonUuid, slotIndex);
                return;
            }

            LOGGER.atFine().log("[ARMY_OF_THE_DEAD][AUGMENTS][DEBUG] Preparing to apply %d augments to summon %s slot %d from owner %s: %s",
                    augmentIds.size(), summonUuid, slotIndex, ownerUuid, augmentIds);

            // Register augments on the summon
            if (plugin.getMobAugmentExecutor() != null && plugin.getAugmentManager() != null
                    && plugin.getAugmentRuntimeManager() != null) {
                plugin.getMobAugmentExecutor().registerMobAugments(summonUuid,
                        augmentIds,
                        plugin.getAugmentManager(),
                        plugin.getAugmentRuntimeManager());
                LOGGER.atInfo().log("[ARMY_OF_THE_DEAD][AUGMENTS] ✓ Successfully registered %d augments to summon %s (slot %d) from owner %s: %s",
                        augmentIds.size(), summonUuid, slotIndex, ownerUuid, augmentIds);
            } else {
                LOGGER.atWarning().log("[ARMY_OF_THE_DEAD][AUGMENTS] Augment system components unavailable - executor=%s manager=%s runtime=%s",
                        plugin.getMobAugmentExecutor() != null,
                        plugin.getAugmentManager() != null,
                        plugin.getAugmentRuntimeManager() != null);
            }
        } catch (Exception e) {
            LOGGER.atSevere().withCause(e)
                    .log("[ARMY_OF_THE_DEAD][AUGMENTS] Failed to apply augments to summon %s slot %d: %s", summonUuid, slotIndex, e.getMessage());
        }
    }"""

content = content.replace(old_method_body, new_method_body)
print("✓ Enhanced method body with detailed logging")

with open(path, 'w') as f:
    f.write(content)

print("✓ All logging enhancements applied successfully")
