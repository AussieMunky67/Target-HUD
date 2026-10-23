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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * Client-side HUD: a small panel floats next to every player within range (default 20 blocks),
 * on the right-hand side of the player as you see them. It shows the username, a health bar
 * and the durability numbers of their armor. Players behind walls are shown unless throughWalls=false.
 *
 * Settings live in config/targethud.properties (created on first launch):
 *   range   = how many blocks away players are shown (default 20)
 *   offsetX / offsetY = nudge every panel by a few pixels
 *   throughWalls = true to show players behind walls, false to hide them (default true)
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
    private static final int MAX_PANELS = 10;   // only the closest players get a panel, to avoid clutter

    // --- config ---
    private static double range = 20;
    private static int offsetX = 0;
    private static int offsetY = 0;
    private static boolean throughWalls = true;   // true = also show players behind walls
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
                defaults.setProperty("offsetX", "0");
                defaults.setProperty("offsetY", "0");
                defaults.setProperty("throughWalls", "true");
                try (OutputStream out = Files.newOutputStream(configPath)) {
                    defaults.store(out, "Target HUD. range = max distance in blocks. offsetX/offsetY nudge the panels in pixels. throughWalls = true/false, show players behind walls.");
                }
            }
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(configPath)) {
                p.load(in);
            }
            range = Math.max(3, Math.min(128, parseInt(p.getProperty("range"), 20)));
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

    private static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        reloadIfChanged();

        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        // brief "ON / OFF" confirmation after pressing the toggle key
        if (System.currentTimeMillis() - toggledAt < 1500) {
            String msg = "Target HUD: " + (enabled ? "ON" : "OFF");
            g.text(mc.font, msg, sw / 2 - mc.font.width(msg) / 2, 20,
                   enabled ? 0xFF55FF55 : 0xFFFF5555, true);
        }
        if (!enabled) return;

        // camera basis (first person): forward, right, up
        Vec3 eye = mc.player.getEyePosition();
        Vec3 fwd = mc.player.getViewVector(1.0f);
        double hLen = Math.sqrt(fwd.x * fwd.x + fwd.z * fwd.z);
        if (hLen < 1.0E-4) return;   // looking straight up/down
        Vec3 right = new Vec3(-fwd.z / hLen, 0.0, fwd.x / hLen);
        Vec3 up = new Vec3(-fwd.x * fwd.y / hLen, hLen, -fwd.z * fwd.y / hLen);

        double tanHalf = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
        double aspect = (double) sw / sh;

        // collect the closest players in range
        List<Player> players = new ArrayList<>();
        double rangeSq = range * range;
        for (Player p : mc.level.players()) {
            if (p == mc.player || p.isSpectator()) continue;
            if (eye.distanceToSqr(p.getX(), p.getY(), p.getZ()) > rangeSq) continue;
            players.add(p);
        }
        players.sort(Comparator.comparingDouble(p -> eye.distanceToSqr(p.getX(), p.getY(), p.getZ())));
        if (players.size() > MAX_PANELS) players = players.subList(0, MAX_PANELS);

        // draw far panels first so near ones end up on top
        for (int i = players.size() - 1; i >= 0; i--) {
            Player p = players.get(i);

            // optionally hide players behind walls
            if (!throughWalls) {
                Vec3 chest = new Vec3(p.getX(), p.getEyeY(), p.getZ());
                BlockHitResult wall = mc.level.clip(
                    new ClipContext(eye, chest, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
                if (wall.getType() != HitResult.Type.MISS) continue;
            }

            // point just to the right of the player's body, at mid height
            Vec3 anchor = new Vec3(p.getX(), p.getY() + p.getBbHeight() * 0.5, p.getZ())
                .add(right.scale(p.getBbWidth() / 2.0 + 0.4));
            Vec3 d = anchor.subtract(eye);
            double depth = d.dot(fwd);
            if (depth < 0.3) continue;   // behind the camera

            double nx = d.dot(right) / (depth * tanHalf * aspect);
            double ny = d.dot(up) / (depth * tanHalf);
            int x = (int) (sw / 2.0 + nx * (sw / 2.0)) + offsetX;
            int y = (int) (sh / 2.0 - ny * (sh / 2.0)) - PANEL_H / 2 + offsetY;

            if (x > sw || x + PANEL_W < 0 || y > sh || y + PANEL_H < 0) continue;   // off screen
            drawPanel(g, mc.font, p, x, y);
        }
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
