package com.vialumix.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Developer harness, active only when the environment variable VIALUMIX_AUTOTEST is set to
 * {@code vanilla}, {@code native} or {@code bliss}. It joins the world given on the command line, fixes
 * the time and weather, takes screenshots from a few fixed viewpoints into {@code vialumix_autotest/}
 * and then closes the game. Used to inspect rendering without playing manually.
 */
public final class AutoTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("vialumix-autotest");
    // x, y, z, yaw, pitch
    private static final double[][] VIEWS = {
            {341.5, 67.0, 125.5, 0, 8}, {341.5, 67.0, 125.5, 90, 8}, {341.5, 67.0, 125.5, 180, 8}, {341.5, 67.0, 125.5, 270, 8},
            {341.5, 92.0, 125.5, 0, 35}
    };

    private static final String MODE = System.getenv("VIALUMIX_AUTOTEST");
    private static final int WAIT = System.getenv("VIALUMIX_AUTOTEST_WAIT") == null ? 900 : Integer.parseInt(System.getenv("VIALUMIX_AUTOTEST_WAIT"));
    private static int ticks;
    private static int view = -1;
    private static int viewTicks;
    private static boolean started;

    private AutoTest() {}

    public static String mode() { return MODE == null || MODE.isBlank() ? null : MODE.toLowerCase(java.util.Locale.ROOT); }

    public static void register() {
        if (mode() == null) return;
        LOGGER.info("Vialumix auto-test enabled in mode '{}'.", mode());
        ClientTickEvents.END_CLIENT_TICK.register(AutoTest::tick);
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.END.register(context -> onWorldRendered(MinecraftClient.getInstance()));
    }

    private static void tick(MinecraftClient client) {
        if (client.world == null || client.player == null || client.getServer() == null) return;
        if (!started) {
            started = true;
            client.options.hudHidden = true;
            client.options.getFov().setValue(70);
            client.getServer().execute(() -> {
                var server = client.getServer();
                var source = server.getCommandSource();
                server.getCommandManager().executeWithPrefix(source, "time set " + (System.getenv("VIALUMIX_AUTOTEST_TIME") == null ? "4000" : System.getenv("VIALUMIX_AUTOTEST_TIME")));
                server.getCommandManager().executeWithPrefix(source, "weather clear 100000");
                server.getCommandManager().executeWithPrefix(source, "gamemode spectator @a");
            });
        }
        ticks++;
        if (ticks < WAIT) return; // let chunks load and the RT scene build
        if (moving) { moveTick(client); return; }
        if (view < 0) { view = 0; viewTicks = 0; teleport(client, VIEWS[0]); return; }
        viewTicks++;
        if (viewTicks == 120) flickerProbe(client, view);
        if (viewTicks == 140) screenshot(client, view);
        if (viewTicks > 150) {
            view++;
            viewTicks = 0;
            if (view >= VIEWS.length) {
                if (System.getenv("VIALUMIX_AUTOTEST_MOVE") != null) { startMove(client); return; }
                LOGGER.info("Vialumix auto-test finished."); client.scheduleStop(); return;
            }
            teleport(client, VIEWS[view]);
        }
    }

    private static void teleport(MinecraftClient client, double[] v) {
        String command = String.format(java.util.Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", v[0], v[1], v[2], v[3], v[4]);
        client.getServer().execute(() -> {
            var server = client.getServer();
            server.getCommandManager().executeWithPrefix(server.getCommandSource(), command);
        });
    }

    private static boolean moving;
    private static int moveTicks;

    /** Movement phase: fly forward while turning, taking screenshots mid-motion to catch reprojection / streaming artefacts. */
    private static void startMove(MinecraftClient client) {
        moving = true;
        moveTicks = 0;
        teleport(client, new double[]{341.5, 80.0, 125.5, 0, 12});
    }

    private static void moveTick(MinecraftClient client) {
        moveTicks++;
        if (moveTicks < 100) return; // settle at the start point
        int t = moveTicks - 100;
        var player = client.player;
        player.getAbilities().flying = true;
        player.setYaw(player.getYaw() + 1.5f);
        double yaw = Math.toRadians(player.getYaw());
        player.setVelocity(-Math.sin(yaw) * 0.6, 0.0, Math.cos(yaw) * 0.6);
        if (t % 20 == 0) LOGGER.info("MOVE t={} playerPos=({}, {}, {}) yaw={}", t, String.format("%.1f", player.getX()), String.format("%.1f", player.getY()), String.format("%.1f", player.getZ()), String.format("%.1f", player.getYaw()));
        if (t == 40 || t == 80 || t == 120 || t == 160 || t == 200) screenshotNamed(client, "move_" + (t / 40));
        if (t > 205) { LOGGER.info("Vialumix auto-test finished."); client.scheduleStop(); }
    }

    private static void screenshotNamed(MinecraftClient client, String name) {
        try {
            File dir = new File(client.runDirectory, "vialumix_autotest");
            dir.mkdirs();
            try (NativeImage image = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) {
                image.writeTo(new File(dir, mode() + "_" + name + ".png"));
            }
        } catch (Throwable error) {
            LOGGER.error("Auto-test screenshot failed", error);
        }
    }

    private static int probeFrames;
    private static int probeView;
    private static NativeImage probePrevious;

    /** Request a flicker measurement over the next two rendered frames of the current (static) view. */
    private static void flickerProbe(MinecraftClient client, int index) {
        probeFrames = 2;
        probeView = index;
    }

    /** Called at the end of every world render: compares two consecutive frames and writes an amplified diff image. */
    public static void onWorldRendered(MinecraftClient client) {
        if (probeFrames <= 0) return;
        probeFrames--;
        try {
            NativeImage a = ScreenshotRecorder.takeScreenshot(client.getFramebuffer());
            if (probePrevious == null) { probePrevious = a; return; }
            NativeImage diff = new NativeImage(a.getWidth(), a.getHeight(), false);
            double sum = 0; long n = 0;
            for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++) {
                int p = probePrevious.getColor(x, y), q = a.getColor(x, y);
                int dr = Math.abs((p & 255) - (q & 255)), dg = Math.abs(((p >> 8) & 255) - ((q >> 8) & 255)), db = Math.abs(((p >> 16) & 255) - ((q >> 16) & 255));
                sum += dr + dg + db; n += 3;
                diff.setColor(x, y, 0xFF000000 | (Math.min(255, db * 8) << 16) | (Math.min(255, dg * 8) << 8) | Math.min(255, dr * 8));
            }
            File dir = new File(client.runDirectory, "vialumix_autotest");
            dir.mkdirs();
            diff.writeTo(new File(dir, mode() + "_flicker_" + probeView + ".png"));
            diff.close();
            LOGGER.info("Auto-test flicker view {}: mean consecutive-frame difference = {} / 255", probeView, String.format("%.3f", sum / n));
            probePrevious.close();
            probePrevious = null;
        } catch (Throwable error) {
            LOGGER.error("flicker probe failed", error);
            probeFrames = 0;
            probePrevious = null;
        }
    }

    private static void screenshot(MinecraftClient client, int index) {
        try {
            File dir = new File(client.runDirectory, "vialumix_autotest");
            dir.mkdirs();
            try (NativeImage image = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) {
                File file = new File(dir, mode() + "_" + index + ".png");
                image.writeTo(file);
                LOGGER.info("Auto-test screenshot saved: {}", file);
            }
        } catch (Throwable error) {
            LOGGER.error("Auto-test screenshot failed", error);
        }
    }
}
