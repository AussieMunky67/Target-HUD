package com.example.targethud;
 
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
 
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
 
/**
 * Client-side HUD: when your crosshair is on another player, a small panel shows their
 * username, a health bar and the durability of each armor piece.
 *
 * Position is set in config/targethud.properties (created on first launch):
 *   position = crosshair | left | right
 *   offsetX / offsetY = extra pixels to nudge it
 *   range = how many blocks away a player can be (default 30)
 * The file is re-read automatically every couple of seconds, so you can edit it while playing.
 */
public class TargetHudClient implements ClientModInitializer {
    private static final EquipmentSlot[] ARMOR = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final String[] ARMOR_LABEL = {"Helmet", "Chest", "Legs", "Boots"};
 
    private static final int PANEL_W = 130;
    private static final int PANEL_H = 57;
 
    // --- config ---
    private static String position = "crosshair";
    private static int offsetX = -50;
    private static int offsetY = 0;
    private static double range = 30;
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
                defaults.setProperty("position", "crosshair");
                defaults.setProperty("offsetX", "0");
                defaults.setProperty("offsetY", "0");
                defaults.setProperty("range", "30");
                try (OutputStream out = Files.newOutputStream(configPath)) {
                    defaults.store(out, "Target HUD. position = crosshair | left | right. offsetX/offsetY nudge it in pixels. range = max distance in blocks.");
                }
            }
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(configPath)) {
                p.load(in);
            }
            position = p.getProperty("position", "crosshair").trim().toLowerCase();
            offsetX = parseInt(p.getProperty("offsetX"), 0);
            offsetY = parseInt(p.getProperty("offsetY"), 0);
            range = Math.max(3, Math.min(128, parseInt(p.getProperty("range"), 30)));
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
 
    /** Finds the player under the crosshair up to `range` blocks away, ignoring players behind walls. */
    private static Player findTarget(Minecraft mc, double range) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 end = eye.add(mc.player.getViewVector(1.0f).scale(range));
 
        // how far can we see before a block gets in the way?
        BlockHitResult blockHit = mc.level.clip(
            new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        double limitSq = blockHit.getType() == HitResult.Type.MISS
            ? range * range
            : eye.distanceToSqr(blockHit.getLocation());
 
        Player best = null;
        double bestSq = limitSq;
        for (Player p : mc.level.players()) {
            if (p == mc.player || p.isSpectator()) continue;
            AABB box = p.getBoundingBox().inflate(0.1);
            Optional<Vec3> hit = box.clip(eye, end);
            if (hit.isPresent()) {
                double d = eye.distanceToSqr(hit.get());
                if (d < bestSq) {
                    best = p;
                    bestSq = d;
                }
            }
        }
        return best;
    }
 
    private static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        reloadIfChanged();
 
        // brief "ON / OFF" confirmation after pressing the toggle key
        if (System.currentTimeMillis() - toggledAt < 1500) {
            String msg = "Target HUD: " + (enabled ? "ON" : "OFF");
            g.text(mc.font, msg, mc.getWindow().getGuiScaledWidth() / 2 - mc.font.width(msg) / 2, 20,
                   enabled ? 0xFF55FF55 : 0xFFFF5555, true);
        }
        if (!enabled) return;
 
        Player target = findTarget(mc, range);
        if (target == null) return;
 
        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
 
        int x;
        int y;
        switch (position) {
            case "left" -> {            // middle of the screen, against the left edge
                x = 4;
                y = sh / 2 - PANEL_H / 2;
            }
            case "right" -> {           // middle of the screen, against the right edge
                x = sw - PANEL_W - 4;
                y = sh / 2 - PANEL_H / 2;
            }
            default -> {                // just under the crosshair
                x = sw / 2 - PANEL_W / 2;
                y = sh / 2 + 20;
            }
        }
        x += offsetX;
        y += offsetY;
 
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
 
        // armor durability: coloured bar plus the actual numbers (remaining / max)
        for (int i = 0; i < ARMOR.length; i++) {
            int rowY = y + 38 + i * 10;
            ItemStack stack = target.getItemBySlot(ARMOR[i]);
            g.text(font, ARMOR_LABEL[i], x + 4, rowY, 0xFFAAAAAA, false);
 
            int bx = x + 44, bw = 60, bh = 6;
            g.fill(bx, rowY + 1, bx + bw, rowY + 1 + bh, 0xFF202020);
            if (stack.isEmpty()) {
                g.text(font, "-", bx + bw + 6, rowY, 0xFF777777, false);
                continue;
            }
 
            if (stack.isDamageableItem() && stack.getMaxDamage() > 0) {
                int maxDur = stack.getMaxDamage();
                int left = Math.max(0, maxDur - stack.getDamageValue());
                float frac = (float) left / maxDur;
 
                // green normally, yellow under 60%, orange under 40%, red under 20%
                int color;
                if (frac >= 0.6f) color = 0xFF3CC83C;
                else if (frac >= 0.4f) color = 0xFFE6D800;
                else if (frac >= 0.2f) color = 0xFFFF9020;
                else color = 0xFFE02828;
 
                g.fill(bx, rowY + 1, bx + (int) (bw * frac), rowY + 1 + bh, color);
                g.text(font, left + "/" + maxDur, bx + bw + 6, rowY, 0xFFFFFFFF, true);
            } else {
                // unbreakable armor: full green bar
                g.fill(bx, rowY + 1, bx + bw, rowY + 1 + bh, 0xFF3CC83C);
                g.text(font, "Unbreakable", bx + bw + 6, rowY, 0xFFFFFFFF, true);
            }
        }
    }
}
 
