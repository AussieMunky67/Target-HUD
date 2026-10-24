package com.example.targethud;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Client-side HUD: shows a small panel at the top-middle of the screen for the closest
 * player within range, whichever way you are facing. It shows their username,
 * a health bar and the durability numbers of their armor.
 *
 * Settings live in config/targethud.properties (created on first launch):
 *   range        = how many blocks away a player can be to show up (default 20)
 *   marginTop    = gap from the top of the screen in GUI pixels (default 2, the very top)
 *   offsetX / offsetY = nudge the panel by a few more pixels
 *   throughWalls = true to also show players behind walls (default true)
 * The file is re-read automatically every couple of seconds.
 * Press ; (rebindable in Controls) to switch the HUD on and off.
 */
public class TargetHudClient implements ClientModInitializer {
    private static final EquipmentSlot[] ARMOR = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final String[] ARMOR_LABEL = {"Helm", "Chest", "Legs", "Boots"};

    private static final int PANEL_W = 144;
    private static final int PANEL_H = 50;

    // --- config ---
    private static double range = 20;
    private static int marginTop = 2;
    private static int offsetX = 0;
    private static int offsetY = 0;
    private static boolean throughWalls = true;
    private static Path configPath;
    private static long configModified = -1;
    private static long lastConfigCheck = 0;

    // --- toggle (press ; by default, rebindable in Controls) ---
    private static boolean enabled = true;
    private static long toggledAt = 0;
    private static KeyMapping toggleKey;

    @Override
    public void onInitializeClient() {
        configPath = FabricLoader.getInstance().getConfigDir().resolve("targethud.properties");
        loadConfig();

        KeyMapping.Category category = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("targethud", "main"));
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.targethud.toggle",
            InputConstants.Type.KEYSYM,
            InputConstants.KEY_SEMICOLON,
            category
        ));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.consumeClick()) {
                enabled = !enabled;
                toggledAt = System.currentTimeMillis();
            }
        });

        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath("targethud", "target_info"),
            TargetHudClient::extract
        );
    }

    private static void loadConfig() {
        try {
            if (!Files.exists(configPath)) {
                Properties defaults = new Properties();
                defaults.setProperty("range", "20");
                defaults.setProperty("marginTop", "2");
                defaults.setProperty("offsetX", "0");
                defaults.setProperty("offsetY", "0");
                defaults.setProperty("throughWalls", "true");
                try (OutputStream out = Files.newOutputStream(configPath)) {
                    defaults.store(out, "Target HUD. range = max distance in blocks. marginTop = gap from the top of the screen in GUI pixels. offsetX/offsetY nudge the panel. throughWalls = true/false.");
                }
            }
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(configPath)) {
                p.load(in);
            }
            range = Math.max(3, Math.min(128, parseDouble(p.getProperty("range"), 20)));
            marginTop = parseInt(p.getProperty("marginTop"), 2);
            offsetX = parseInt(p.getProperty("offsetX"), 0);
            offsetY = parseInt(p.getProperty("offsetY"), 0);
            throughWalls = Boolean.parseBoolean(p.getProperty("throughWalls", "true").trim());
            configModified = Files.getLastModifiedTime(configPath).toMillis();
        } catch (IOException | RuntimeException ignored) {
            // keep the previous settings if the file can't be read
        }
    }

    private static int parseInt(String s, int fallback) {
        try {
            return s == null ? fallback : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String s, double fallback) {
        try {
            return s == null ? fallback : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void reloadIfChanged() {
        long now = System.currentTimeMillis();
        if (now - lastConfigCheck < 2000) return;
        lastConfigCheck = now;
        try {
            if (Files.exists(configPath) && Files.getLastModifiedTime(configPath).toMillis() != configModified) {
                loadConfig();
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * The closest other player within `range` blocks, whichever way you are facing.
     * With throughWalls=false, players hidden behind blocks are skipped.
     */
    private static Player findTarget(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition();
        double bestSq = range * range;
        Player best = null;

        for (Player p : mc.level.players()) {
            if (p == mc.player || p.isSpectator()) continue;

            double dSq = eye.distanceToSqr(p.getX(), p.getY() + p.getBbHeight() * 0.5, p.getZ());
            if (dSq > bestSq) continue;

            if (!throughWalls) {
                Vec3 theirEyes = new Vec3(p.getX(), p.getEyeY(), p.getZ());
                BlockHitResult wall = mc.level.clip(
                    new ClipContext(eye, theirEyes, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
                if (wall.getType() != HitResult.Type.MISS) continue;
            }

            best = p;
            bestSq = dSq;
        }
        return best;
    }

    private static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        reloadIfChanged();

        int sw = mc.getWindow().getGuiScaledWidth();

        // brief "ON / OFF" confirmation after pressing the toggle key
        if (System.currentTimeMillis() - toggledAt < 1500) {
            String msg = "Target HUD: " + (enabled ? "ON" : "OFF");
            g.text(mc.font, msg, sw / 2 - mc.font.width(msg) / 2, 6,
                   enabled ? 0xFF55FF55 : 0xFFFF5555, true);
        }
        if (!enabled) return;

        Player target = findTarget(mc);
        if (target == null) return;

        int x = sw / 2 - PANEL_W / 2 + offsetX;   // top-middle of the screen
        int y = marginTop + offsetY;
        drawPanel(g, mc.font, target, x, y);
    }

    private static int shade(int argb, float factor) {
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * factor));
        int gr = Math.min(255, (int) (((argb >> 8) & 0xFF) * factor));
        int b = Math.min(255, (int) ((argb & 0xFF) * factor));
        return 0xFF000000 | (r << 16) | (gr << 8) | b;
    }

    private static void drawPanel(GuiGraphicsExtractor g, Font font, Player target, int x, int y) {
        // no background panel: everything is drawn straight onto the game, text has a shadow

        // username, centred
        String name = target.getName().getString();
        g.text(font, name, x + (PANEL_W - font.width(name)) / 2, y, 0xFFFFFFFF, true);

        // health bar in the style of the XP bar: thin, black outline, dark track, lit top edge, small notches
        float health = Math.max(0f, target.getHealth());
        float max = Math.max(1f, target.getMaxHealth());
        float absorption = target.getAbsorptionAmount();
        float frac = Math.min(1f, health / max);

        int barW = 100, barH = 5;
        int barX = x + (PANEL_W - barW) / 2;
        int barY = y + 12;

        g.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xFF000000);   // outline
        g.fill(barX, barY, barX + barW, barY + barH, 0xFF1A1A1A);                   // track

        int fillW = (int) (barW * frac);
        if (fillW > 0) {
            int color = frac > 0.5f ? 0xFF3CC83C : (frac > 0.25f ? 0xFFE6B400 : 0xFFE02828);
            g.fill(barX, barY, barX + fillW, barY + barH, color);
            g.fill(barX, barY, barX + fillW, barY + 1, shade(color, 1.35f));                // lit top edge
            g.fill(barX, barY + barH - 1, barX + fillW, barY + barH, shade(color, 0.6f));   // darker bottom edge
        }
        for (int i = 1; i < 10; i++) {                                                      // notches every 10%
            int nx = barX + barW * i / 10;
            g.fill(nx, barY, nx + 1, barY + barH, 0x66000000);
        }

        // health number, centred under the bar
        String hpText = String.format("%.1f / %.0f", health, max)
            + (absorption > 0 ? String.format(" (+%.0f)", absorption) : "");
        g.text(font, hpText, x + (PANEL_W - font.width(hpText)) / 2, barY + barH + 3, 0xFFFFFFFF, true);

        // armor durability: numbers only, two columns
        // left column: Helm, Chest    right column: Legs, Boots
        for (int i = 0; i < ARMOR.length; i++) {
            int col = i / 2;   // 0 = left, 1 = right
            int row = i % 2;
            int labelX = x + 2 + col * 72;
            int numX = labelX + 30;
            int rowY = y + 33 + row * 9;

            ItemStack stack = target.getItemBySlot(ARMOR[i]);
            g.text(font, ARMOR_LABEL[i], labelX, rowY, 0xFFAAAAAA, true);

            if (stack.isEmpty()) {
                g.text(font, "-", numX, rowY, 0xFF999999, true);
                continue;
            }

            if (stack.isDamageableItem() && stack.getMaxDamage() > 0) {
                int maxDur = stack.getMaxDamage();
                int left = Math.max(0, maxDur - stack.getDamageValue());
                float dfrac = (float) left / maxDur;

                // green normally, yellow under 60%, orange under 40%, red under 20%
                int color;
                if (dfrac >= 0.6f) color = 0xFF3CC83C;
                else if (dfrac >= 0.4f) color = 0xFFE6D800;
                else if (dfrac >= 0.2f) color = 0xFFFF9020;
                else color = 0xFFE02828;

                g.text(font, left + "/" + maxDur, numX, rowY, color, true);
            } else {
                g.text(font, "Unbr.", numX, rowY, 0xFF3CC83C, true);
            }
        }
    }
}
