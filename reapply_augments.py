#!/usr/bin/env python3

path = "src/main/java/com/airijko/endlessleveling/passives/type/ArmyOfTheDeadPassive.java"

with open(path, 'r') as f:
    content = f.read()

# Add the method call
before = """                applySummonScaling(summonRef,
                        store,
                        request.source(),
                        request.config().statInheritance(),
                        request.slotIndex());

                forceCurrentHealthToMax(summonRef, store);"""

after = """                applySummonScaling(summonRef,
                        store,
                        request.source(),
                        request.config().statInheritance(),
                        request.slotIndex());

                applyPlayerAugmentsToSummon(summonRef, store, request.ownerUuid(), summonUuidComponent.getUuid(), request.slotIndex());

                forceCurrentHealthToMax(summonRef, store);"""

content = content.replace(before, after)

# Add the method
method = '''
    /**
     * Apply player augments to a summoned minion.
     * Each summon inherits all of the caster's augments with no efficiency loss.
     * Enhanced with detailed logging for debugging.
     */
    private static void applyPlayerAugmentsToSummon(Ref<EntityStore> summonRef,
            Store<EntityStore> store,
            UUID ownerUuid,
            UUID summonUuid,
            int slotIndex) {
        if (summonRef == null || store == null || ownerUuid == null || summonUuid == null) {
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
    }

'''

marker = "    private static void applySummonMovementSpeedBonus("
content = content.replace(marker, method + "    private static void applySummonMovementSpeedBonus(")

with open(path, 'w') as f:
    f.write(content)

print("✓ Reapplied augment inheritance with enhanced logging")
