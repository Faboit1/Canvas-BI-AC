package io.canvasmc.canvas.anticheat;

import io.canvasmc.canvas.WorldConfig;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The settings behind {@link WorldConfig.Anticheat#preset}, from the cheapest that still protects to the strongest.
 * <p>
 * The levels add protection in this order: storage hiding, invisible players, anti-bud, health obfuscation and the
 * underground view distance first, since they are cheap and protect bases; then anti-ESP and section anti-freecam;
 * then per-block anti-freecam and ray traced anti-xray. The top levels trace farther, more often and with higher
 * limits. Every level hides storage, so no level gives away bases.
 */
public final class AnticheatPresets {

    private static final ConcurrentHashMap<WorldConfig.Anticheat, WorldConfig.Anticheat> APPLIED = new ConcurrentHashMap<>();

    private AnticheatPresets() {
    }

    /**
     * @return the options of the configured preset, with the block and entity lists of the given options. Cached per
     * configuration, reloading the configuration creates new options
     */
    public static WorldConfig.Anticheat apply(final WorldConfig.Anticheat configured) {
        final WorldConfig.Anticheat ret = APPLIED.get(configured);
        if (ret != null) {
            return ret;
        }
        if (APPLIED.size() > 64) {
            // configurations of earlier reloads
            APPLIED.clear();
        }
        return APPLIED.computeIfAbsent(configured, AnticheatPresets::create);
    }

    private static WorldConfig.Anticheat create(final WorldConfig.Anticheat configured) {
        final WorldConfig.Anticheat ret = new WorldConfig.Anticheat();
        ret.preset = configured.preset;
        // the lists say what to protect, not how hard, and the filler is a matter of taste
        ret.rayTraceAntiXray.rayTraceBlocks = configured.rayTraceAntiXray.rayTraceBlocks;
        ret.antiFreecam.filler = configured.antiFreecam.filler;
        ret.antiEsp.excludedEntityTypes = configured.antiEsp.excludedEntityTypes;
        ret.antiEsp.respectVanillaNameTags = configured.antiEsp.respectVanillaNameTags;
        ret.antiStorageEsp.blocks = configured.antiStorageEsp.blocks;

        final Level level = Level.of(configured.preset);
        ret.obfuscatePlayerHealth = true;

        final WorldConfig.Anticheat.AntiStorageEsp storage = ret.antiStorageEsp;
        storage.enabled = true;
        storage.rayTraceDistance = level.pick(32, 48, 64, 80, 96, 128);
        storage.rayTraceThirdPerson = level.atLeast(Level.HIGH);
        storage.alwaysVisibleDistance = 3.0;
        storage.maxTracedBlocksPerTick = level.pick(128, 512, 2048, 4096, 8192, Integer.MAX_VALUE);
        storage.updateIntervalTicks = level.pick(20, 10, 10, 5, 2, 1);

        final WorldConfig.Anticheat.InvisiblePlayers invisible = ret.invisiblePlayers;
        invisible.enabled = true;
        invisible.anonymize = true;

        final WorldConfig.Anticheat.AntiBud bud = ret.antiBud;
        bud.enabled = true;
        bud.revealDistance = level.pick(16, 16, 16, 16, 12, 10);

        final WorldConfig.Anticheat.UndergroundViewDistance underground = ret.undergroundViewDistance;
        underground.enabled = true;
        underground.viewDistance = level.pick(4, 4, 4, 4, 4, 3);

        final WorldConfig.Anticheat.AntiFreecam freecam = ret.antiFreecam;
        freecam.enabled = level.atLeast(Level.MEDIUM);
        freecam.perBlock = level.atLeast(Level.HIGH);
        freecam.revealMargin = 8.0;
        freecam.updateIntervalTicks = level.pick(20, 20, 10, 5, 2, 1);
        freecam.maxResendsPerTick = level.pick(4, 4, 8, 12, 16, 32);
        freecam.hideParticles = true;

        final WorldConfig.Anticheat.AntiEsp esp = ret.antiEsp;
        esp.enabled = level.atLeast(Level.MEDIUM);
        esp.hidePlayers = true;
        esp.alwaysVisibleDistance = 4.0;
        esp.maxTraceDistance = level.pick(64, 64, 96, 128, 160, 192);
        esp.rayTraceThirdPerson = level.atLeast(Level.HIGH);
        esp.visibleRecheckIntervalTicks = level.pick(8, 8, 4, 3, 2, 1);
        esp.hideGraceTicks = 10;
        esp.hideProjectiles = level.atLeast(Level.MAX);
        esp.limitLocatorBar = true;

        final WorldConfig.Anticheat.RayTraceAntiXray xray = ret.rayTraceAntiXray;
        xray.enabled = level.atLeast(Level.HIGH);
        xray.rayTraceDistance = level.pick(64, 64, 64, 80, 96, 128);
        xray.rayTraceThirdPerson = level.atLeast(Level.EXTRA);
        xray.frustumCulling = true;
        xray.maxRayTraceBlockCountPerChunk = level.pick(100, 100, 100, 200, 400, 1000);
        xray.rehideBlocks = level.atLeast(Level.MAX);
        xray.rehideDistance = level.atLeast(Level.MAX) ? 64.0 : Double.POSITIVE_INFINITY;
        xray.updateIntervalTicks = level.pick(10, 10, 10, 5, 2, 1);
        return ret;
    }

    private enum Level {
        LOW, MEDIUM, HIGH, EXTRA, MAX, ULTRA;

        static Level of(final WorldConfig.Anticheat.Preset preset) {
            return switch (preset) {
                case CUSTOM, LOW -> LOW;
                case MEDIUM -> MEDIUM;
                case HIGH -> HIGH;
                case EXTRA -> EXTRA;
                case MAX -> MAX;
                case ULTRA -> ULTRA;
            };
        }

        boolean atLeast(final Level level) {
            return this.ordinal() >= level.ordinal();
        }

        int pick(final int low, final int medium, final int high, final int extra, final int max, final int ultra) {
            return switch (this) {
                case LOW -> low;
                case MEDIUM -> medium;
                case HIGH -> high;
                case EXTRA -> extra;
                case MAX -> max;
                case ULTRA -> ultra;
            };
        }
    }
}
