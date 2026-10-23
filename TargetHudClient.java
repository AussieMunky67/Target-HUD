package com.example.targethud;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Client-side HUD: when your crosshair is on another player, a small panel under the
 * crosshair shows their username, a health bar and the durability of each armor piece.
 */
public class TargetHudClient implements ClientModInitializer {
    private static final EquipmentSlot[] ARMOR = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final String[] ARMOR_LABEL = {"Helmet", "Chest", "Legs", "Boots"};

    private static final int PANEL_W = 130;
    private static final int PANEL_H = 70;

    @Override
    public void onInitializeClient() {
        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath("targethud", "target_info"),
            TargetHudClient::extract
        );
    }

    private static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;
        if (!(mc.crosshairPickEntity instanceof Player target) || target == mc.player) return;

        Font font = mc.font;
        int x = mc.getWindow().getGuiScaledWidth() / 2 - PANEL_W / 2;
        int y = mc.getWindow().getGuiScaledHeight() / 2 + 20;

        // panel background
        g.fill(x, y, x + PANEL_W, y + PANEL_H, 0x90000000);

        // username
        g.text(font, target.getName().getString(), x + 4, y + 4, 0xFFFFFFFF, true);

        // health bar
        float health = Math.max(0f, target.getHealth());
        float max = Math.max(1f, target.getMaxHealth());
        float absorption = target.getAbsorptionAmount();
        int barX = x + 4, barY = y + 16, barW = PANEL_W - 8, barH = 8;
        g.fill(barX, barY, barX + barW, barY + barH, 0xFF300000);
        int hpW = (int) (barW * Math.min(1f, health / max));
        int hpColor = health / max > 0.5f ? 0xFF3CC83C : (health / max > 0.25f ? 0xFFE6B400 : 0xFFE02828);
        g.fill(barX, barY, barX + hpW, barY + barH, hpColor);
        String hpText = String.format("%.1f / %.0f", health, max)
            + (absorption > 0 ? String.format(" (+%.0f)", absorption) : "");
        g.text(font, hpText, x + 4, y + 26, 0xFFFFFFFF, true);

        // armor durability
        for (int i = 0; i < ARMOR.length; i++) {
            int rowY = y + 38 + i * 8;
            ItemStack stack = target.getItemBySlot(ARMOR[i]);
            g.text(font, ARMOR_LABEL[i], x + 4, rowY, 0xFFAAAAAA, false);

            int bx = x + 44, bw = PANEL_W - 48, bh = 5;
            g.fill(bx, rowY + 2, bx + bw, rowY + 2 + bh, 0xFF202020);
            if (stack.isEmpty()) continue;

            float frac = 1f;
            if (stack.isDamageableItem() && stack.getMaxDamage() > 0) {
                frac = 1f - (float) stack.getDamageValue() / stack.getMaxDamage();
            }
            int color = frac > 0.5f ? 0xFF3CC8C8 : (frac > 0.25f ? 0xFFE6B400 : 0xFFE02828);
            g.fill(bx, rowY + 2, bx + (int) (bw * Math.max(0f, frac)), rowY + 2 + bh, color);
        }
    }
}
