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
 * Client-side HUD: shows a small panel at the top-middle of the screen for the one player
 * you are looking at (with a generous aim area around them). It shows their username,
 * a health bar and the durability numbers of their armor.
 *
 * Settings live in config/targethud.properties (created on first launch):
 *   range        = how many blocks away you can target a player (default 20)
 *   aimRadius    = extra blocks around a player's hitbox that still count (default 2.5, a 5x5x5 area)
 *   topMargin    = gap from the top of the screen in GUI pixels (default 30, roughly 3 cm)
 *   offsetX / offsetY = nudge the panel by a few more pixels
 *   throughWalls = true to also target players behind walls (default true)
 * The file is re-read automatically every couple of seconds.
 * Press ; (rebindable in Controls) to switch the HUD on and off.
 */
public class TargetHudClient implements ClientModInitializer {
    private static final EquipmentSlot[] ARMOR = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final String[] ARMOR_LABEL = {"Helm", "Chest", "Legs", "Boots"};
 
    private static final int PANEL_W = 156;
    private static final int PANEL_H = 60;
 
    // --- config ---
    private static double range = 20;
    private static double aimRadius = 2.5;
    private static int topMargin = 30;
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
                defaults.setProperty("aimRadius", "2.5");
                defaults.setProperty("topMargin", "30");
                defaults.setProperty("offsetX", "0");
                defaults.setProperty("offsetY", "0");
                defaults.setProperty("throughWalls", "true");
                try (OutputStream out = Files.newOutputStream(configPath)) {
                    defaults.store(out, "Target HUD. range = max distance in blocks. aimRadius = extra blocks around a player that still count as looking at them. topMargin = gap from the top of the screen in GUI pixels. offsetX/offsetY nudge the panel. throughWalls = true/false.");
                }
            }
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(configPath)) {
                p.load(in);
            }
            range = Math.max(3, Math.min(128, parseDouble(p.getProperty("range"), 20)));
            aimRadius = Math.max(0, Math.min(10, parseDouble(p.getProperty("aimRadius"), 2.5)));
            topMargin = parseInt(p.getProperty("topMargin"), 30);
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
     * The player you are looking at: anyone whose (enlarged) hitbox the line of sight passes through,
     * up to `range` blocks away. If several qualify, the one closest to the exact centre of your view wins.
     */
    private static Player findTarget(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 dir = mc.player.getViewVector(1.0f);
        Vec3 end = eye.add(dir.scale(range));
 
        double limitSq = range * range;
        if (!throughWalls) {
            BlockHitResult blockHit = mc.level.clip(
                new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (blockHit.getType() != HitResult.Type.MISS) {
                limitSq = eye.distanceToSqr(blockHit.getLocation());
            }
        }
 
        Player best = null;
        double bestOffAxisSq = Double.MAX_VALUE;
        for (Player p : mc.level.players()) {
            if (p == mc.player || p.isSpectator()) continue;
 
            AABB box = p.getBoundingBox().inflate(aimRadius);
            Optional<Vec3> hit = box.clip(eye, end);
            if (hit.isEmpty()) continue;
            if (eye.distanceToSqr(hit.get()) > limitSq) continue;
 
            // how far is the player's centre from the middle of your view?
            Vec3 centre = new Vec3(p.getX(), p.getY() + p.getBbHeight() * 0.5, p.getZ());
            Vec3 toPlayer = centre.subtract(eye);
            double along = toPlayer.dot(dir);
            if (along <= 0) continue;
            double offAxisSq = toPlayer.lengthSqr() - along * along;
            if (offAxisSq < bestOffAxisSq) {
                best = p;
                bestOffAxisSq = offAxisSq;
            }
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
        int y = topMargin + offsetY;
        drawPanel(g, mc.font, target, x, y);
    }
 
    private static void drawPanel(GuiGraphicsExtractor g, Font font, Player target, int x, int y) {
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
 
        // armor durability: numbers only, two columns
        // left column: Helm, Chest    right column: Legs, Boots
        for (int i = 0; i < ARMOR.length; i++) {
            int col = i / 2;   // 0 = left, 1 = right
            int row = i % 2;
            int labelX = x + 4 + col * 76;
            int numX = labelX + 32;
            int rowY = y + 38 + row * 10;
 
            ItemStack stack = target.getItemBySlot(ARMOR[i]);
            g.text(font, ARMOR_LABEL[i], labelX, rowY, 0xFFAAAAAA, false);
 
            if (stack.isEmpty()) {
                g.text(font, "-", numX, rowY, 0xFF777777, false);
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
 
                g.text(font, left + "/" + maxDur, numX, rowY, color, true);
            } else {
                g.text(font, "Unbr.", numX, rowY, 0xFF3CC83C, true);
            }
        }
    }
}
