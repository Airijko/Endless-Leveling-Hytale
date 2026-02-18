package com.airijko.endlessleveling.classes;

import com.airijko.endlessleveling.enums.ClassWeaponType;
import com.hypixel.hytale.server.core.inventory.ItemStack;

/**
 * Utility helpers for deriving weapon categories from player equipment.
 */
public final class ClassWeaponResolver {

    private static volatile WeaponConfig weaponConfig = WeaponConfig.empty();

    private ClassWeaponResolver() {
    }

    /** Configure resolver with optional weapons.yml mapping. */
    public static void configure(WeaponConfig config) {
        weaponConfig = config == null ? WeaponConfig.empty() : config;
    }

    public static ClassWeaponType resolve(ItemStack stack) {
        if (stack == null || ItemStack.isEmpty(stack)) {
            return ClassWeaponType.UNARMED;
        }
        String itemId = stack.getItemId();
        ClassWeaponType detected = weaponConfig.resolve(itemId);
        if (detected == null) {
            detected = ClassWeaponType.fromItemId(itemId);
        }
        return detected != null ? detected : ClassWeaponType.UNARMED;
    }
}
