package io.canvasmc.canvas;

import io.canvasmc.canvas.configuration.ConfigurationProvider;
import io.canvasmc.canvas.configuration.Part;
import io.canvasmc.canvas.configuration.Resolver;
import io.canvasmc.canvas.configuration.Style;
import io.canvasmc.canvas.configuration.Undocumented;
import io.canvasmc.canvas.configuration.Validator;
import io.canvasmc.canvas.util.CanonicalReference;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.threadedregions.TickRegions;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@NullMarked
public class WorldConfig extends Part {

    // all constants for configurations go here

    public static final String DEFAULT_TPSBAR_FORMAT =
        "<gradient:blue:aqua><b>TPS:</b></gradient> <tps>  <dark_gray>-</dark_gray>  " +
            "<gradient:blue:aqua><b>MSPT:</b></gradient> <mspt>  <dark_gray>-</dark_gray>  " +
            "<gradient:blue:aqua><b>Util:</b></gradient> <util>  <dark_gray>-</dark_gray>  " +
            "<gradient:blue:aqua><b>Players:</b></gradient> <players>";
    public static final String DEFAULT_RAMBAR_FORMAT =
        "<gradient:green:dark_green><b>RAM:</b></gradient> <used>/<xmx> <dark_gray>(</dark_gray><percent><dark_gray>%)</dark_gray>";

    // note that Canvas core utilities and loggers and such should go in the global configuration class, as this one
    // doesn't entirely seem that appropriate for that sort of stuff

    // we have a logger internally here for level-config related things, and should not be used globally. the global
    // config class should be the logger publicly used

    private static final Logger LOGGER = LoggerFactory.getLogger("CanvasWorlds");

    private static final Path BASE_FILE = Path.of("config/canvas-worlds.yml").toAbsolutePath().normalize();

    // for the default configuration, we do need a solid configuration for this or else the patchable
    // variant will fail to load, so we load this in the static block

    static {
        //noinspection ResultOfMethodCallIgnored
        GlobalConfiguration.getInstance(); // preload global

        try {
            reload();
        } catch (final Throwable thrown) {
            // we at least need to make sure this is logged
            LOGGER.error("Couldn't load Canvas worlds default configuration", thrown);
            throw thrown;
        }
    }

    public static void reload() {
        ConfigurationProvider.buildSolidConfiguration(
            BASE_FILE,
            WorldConfig::new,
            GlobalConfiguration.CHAR_LIM,
            new Resolver<>() {
                @Override
                public void onDiffAdd(final String fullyQualifiedName) {
                    LOGGER.info("Added new world configuration option, \"{}\"", fullyQualifiedName);
                }

                @Override
                public void onDiffRemove(final String fullyQualifiedName) {
                    LOGGER.warn("World configuration option \"{}\" no longer exists and is now removed.", fullyQualifiedName);
                }

                @Override
                public void onFinishLoad(final WorldConfig instance) {

                    // validate the configuration so users don't end up doing a stupid
                    Validator.validateObject(instance);

                    // note that we do not do anything else on post load for the default
                    // configuration file, only patchable instances need post load

                }
            },
            Style.create()
                .literal("Worlds default configuration file for CanvasMC").endLine()
                .blank()
                .wordWrap(
                    "This is the defaults for the per-world configuration file for CanvasMC.",
                    "Each option can be overridden by the patch variant in each dimension folder. You are",
                    "free to modify, add, or remove comments as you please."
                ).endLine()
                .blank()
                .wordWrap(
                    "You may refresh this configuration at runtime using the \"/canvas reload\" command, however",
                    "it is not recommended to do this during production, as this can cause issues like unexpected crashes",
                    "or unintended behavior."
                ).endLine()
                .blank()
                .wordWrap(
                    "All defaults for the options provided in this configuration are configured for upstream",
                    "compatibility over performance. You must do some manual configuration to get some of the performance",
                    "benefits Canvas provides."
                ).endLine()
                .blank()
                .wordWrap(
                    "If you have questions about certain configuration options please reach out in our discord"
                ).endLine()
                .literal("https://canvasmc.io/discord")
                .compile(60)
        );

        // on reload, if the server started, we need to swap out the configs
        if (TickRegions.hasStarted()) {
            for (final ServerLevel level : MinecraftServer.getServer().getAllLevels()) {

                // this will swap the config with the new patchable variant
                // it mimics the startup process of the patchable configs

                level.reloadCanvasConfig();
            }
        }
    }

    public static WorldConfig buildForLevel(final ServerLevel level, final ResourceKey<Level> dimension) {

        // we build it as a patch here, and from here we can set the level properly
        final WorldConfig[] result = new WorldConfig[1];

        ConfigurationProvider.buildPatchableConfiguration(
            MinecraftServer.getServer().storageSource.getDimensionPath(dimension).resolve("canvas-patch.yml"),
            BASE_FILE,
            WorldConfig::new,
            instance -> {
                LOGGER.info("Loaded Canvas config patch for level {}", dimension.identifier());

                result[0] = instance;

                instance.onLoad(level);
            },
            Style.create()
                .literal("Patch configuration file for world " + dimension.identifier()).endLine()
                .blank()
                .wordWrap(
                    "This configuration file can be used to override the values in the default configuration",
                    "for worlds defined in \"/config/canvas-worlds.yml\""
                ).endLine()
                .blank()
                .wordWrap(
                    "To override values in this, just copy the same option path to override the value. Think of",
                    "the values you place in here for each option as a replacement for the default one for this world specifically"
                )
                .compile(60)
        );

        return result[0];
    }

    private void onLoad(final ServerLevel level) {

        // validate the object here too, because some users may do
        // something stupid in the patch variant

        Validator.validateObject(this);

        // basically we just need to parse the loadChunks strings into identifiers
        // and then from there we can access all entity types and use that as the predicate
        final EntityType<?>[] entityTypes = entities.projectiles.loadChunks.stream()
            .map(Identifier::parse)
            .map(BuiltInRegistries.ENTITY_TYPE::getValue)
            .toList().toArray(new EntityType<?>[0]);

        if (entityTypes.length > 0) {
            LOGGER.info("Set {} projectile types to load chunks in {}", entityTypes.length, level.dimension().identifier().toDebugFileName());
        }

        // set the predicate now
        entities.projectiles.compiledPredicate.setValue((projectile) -> {
            // this is an immutable array, so realistically this is fine?
            for (final EntityType<?> entityType : entityTypes) {
                if (projectile.is(entityType)) return true;
            }
            // if nothing matched, it doesn't have an override
            return false;
        });

        if (blocks.spawner.minSpawnDelay > blocks.spawner.maxSpawnDelay) {
            throw new IllegalArgumentException("min-spawn-delay must be less than or equal to max spawn delay");
        }
    }

    {
        option("regionBars").docs("Region resource bars config. You can toggle these on players with the \"/regionbar\" command");
    }

    public RegionBars regionBars = new RegionBars();
    public static class RegionBars extends Part {

        {
            option("enableTpsBar").docs("Enables a regionized TPS-Bar implementation for Canvas.");
            option("tpsBarFormat")
                .docs(
                    "MiniMessage-formatted line for the TPS bar. Placeholders are <tps>, <mspt>, <util>, and <players>.",
                    "Legacy tokens(%tps%, %mspt%, %util%, %players%) are also accepted and auto-converted."
                ).greedyString();
            option("enableRamBar").docs("Enables a regionized RAM-Bar implementation for Canvas.");
            option("ramBarFormat")
                .docs(
                    "MiniMessage-formatted line for the RAM bar. Placeholders are <used>, <xmx>, <percent>.",
                    "Legacy tokens(%used%, %xmx%, %percent%) are also accepted and auto-converted."
                ).greedyString();
        }

        public boolean enableTpsBar = true;
        public String tpsBarFormat = DEFAULT_TPSBAR_FORMAT;

        public boolean enableRamBar = true;
        public String ramBarFormat = DEFAULT_RAMBAR_FORMAT;
    }

    public Visuals visuals = new Visuals();
    @Undocumented("Doesn't require docs.")
    public static class Visuals extends Part {

        {
            option("particles")
                .docs(
                    "All options unless explicitly specified otherwise are unnecessary packets",
                    "sent to the client and can be safely disabled without Vanilla deviation"
                );
        }

        public boolean hideFlamesOnEntitiesWithFireResistance = false;
        public boolean hideFlamesOnEntitiesWithInvisibility = false;

        public Particles particles = new Particles();
        @Undocumented("Doesn't require docs.")
        public static class Particles extends Part {

            {
                option("disableFallParticles").docs("Note that when enabled this option breaks Vanilla visual compatibility");
                option("disableNewCombatParticles").docs("Note that when enabled this option breaks Vanilla visual compatibility");
            }

            public boolean disableSprintParticles = false;
            public boolean disableFallParticles = false;
            public boolean disableDeathParticles = false;
            public boolean disableEffectParticles = false;
            public boolean disableWaterSplashParticles = false;
            public boolean disableBubbleColumnParticles = false;
            public boolean disableNewCombatParticles = false;
        }

        {
            option("dontTrackPlayersInEntityTracking").docs("This makes players not able to see other players in this world when enabled");
        }

        // useful for AFK worlds
        public boolean dontTrackPlayersInEntityTracking = false;
    }

    {
        option("chainEndCrystalExplosions").docs("When enabled, this chains end crystal explosions instead of executing them all in 1 tick");
        option("disableSnowLightChecks").docs("Disables snow light checks, so snow layers never melt");
        option("disableGrassLightChecks").docs("Disables grass light checks, so grass always spreads despite being in darkness");
    }

    public boolean chainEndCrystalExplosions = false;
    public boolean disableSnowLightChecks = false;
    public boolean disableGrassLightChecks = false;

    public Farming farming = new Farming();
    @Undocumented("Doesn't require docs.")
    public static class Farming extends Part {

        {
            option("disableFarmlandTrampling").docs("Makes falling on farmland not turn it back to dirt");
            option("cropsIgnoreLightCheck").docs("Makes crops ignore sunlight requirements when planting");
        }

        public boolean farmlandAlwaysMoist = false;
        public boolean disableLeafDecay = false;
        public boolean cropsIgnoreLightCheck = false;
        public boolean disableFarmlandTrampling = false;
        public boolean sugarCaneBonemealable = false;
        public boolean netherWartBonemealable = false;
    }

    public Entities entities = new Entities();
    public static class Entities extends Part {

        {
            option("fastOrbs")
                .docs(
                    "Removes the XP pickup delay and uses a faster merging system. Can be very useful for",
                    "heavy XP farms. This changes how orbs are merged, allowing 1 orb to contain an infinite",
                    "amount of merged experience and then be collected instantly, much faster than the Vanilla",
                    "equivalent. This also fixes \"ghost orbs\", since instead of increasing the count, this",
                    "increases the value of the orb"
                );

            option("entityCollisionMode")
                .docs(
                    Style.wrap("The entity collision mode for the server")
                        .defineEnum(EntityCollisionMode.class, (mode) -> switch (mode) {
                            case VANILLA -> "Default, all entities have collisions";
                            case ONLY_PUSHABLE_PLAYERS_SMALL ->
                                "Only players are pushable by entities, searching in a small radius";
                            case ONLY_PUSHABLE_PLAYERS_LARGE ->
                                "Only players are pushable by entities, searching in the normal radius";
                            case NO_COLLISIONS -> "Disables entity collisions entirely";
                        })
                );
        }

        public boolean fastOrbs = false;

        public ItemEntities itemEntities = new ItemEntities();
        public static class ItemEntities extends Part {

            {
                option("itemEntityVelocityOnDeathFactor")
                    .docs(
                        "A multiplied value for the velocity of item entities dropped on death. The smaller the value,",
                        "the less it spreads out. The larger the value, the more it spreads out"
                    ).greaterThanOrEqualTo(0.0F);
                option("itemEntitiesWaitTwoSecondsForMergeCheckAlways")
                    .docs(
                        "Item entity merge checks during the tick are always intervaled by 2 seconds unless the item is moving,",
                        "of which then this interval is 2 ticks. This forces the interval to always be 2 ticks, reducing the amount",
                        "of times item entities check to merge"
                    );
            }

            @Undocumented("Doesn't require docs.")
            public boolean itemEntitiesImmuneToExplosions = false;
            @Undocumented("Doesn't require docs.")
            public boolean itemEntitiesImmuneToLightning = false;
            public double itemEntityVelocityOnDeathFactor = 1.0D;
            public boolean itemEntitiesWaitTwoSecondsForMergeCheckAlways = false;
        }

        public EntityCollisionMode entityCollisionMode = EntityCollisionMode.VANILLA;
        public enum EntityCollisionMode {
            VANILLA,
            ONLY_PUSHABLE_PLAYERS_LARGE,
            ONLY_PUSHABLE_PLAYERS_SMALL,
            NO_COLLISIONS;

            private static final EntityCollisionMode[] VALUES = values();
            private final int id;

            EntityCollisionMode() {
                this.id = ordinal();
            }

            public static EntityCollisionMode fromOrdinal(int ordinal) {
                if (ordinal < 0 || ordinal >= VALUES.length) {
                    return VANILLA;
                }
                return VALUES[ordinal];
            }

            public int getId() {
                return this.id;
            }

            public boolean onlyPlayersPushable() {
                return id == ONLY_PUSHABLE_PLAYERS_LARGE.id || id == ONLY_PUSHABLE_PLAYERS_SMALL.id;
            }

            public boolean allEntitiesCanBePushed() {
                return id == VANILLA.id;
            }

            public boolean noCollisions() {
                return id == NO_COLLISIONS.id;
            }

            public boolean isLargePushRange() {
                return id == ONLY_PUSHABLE_PLAYERS_LARGE.id;
            }
        }

        public Projectiles projectiles = new Projectiles();
        public static class Projectiles extends Part {

            {
                option("loadChunks").docs("Specify which projectiles should load chunks when moving. Only works when thrown by players");
                option("crossRegionRedirectableProjectileDeflection")
                    .docs(
                        Style.wrap(
                            "Restores Vanilla redirect behavior for arrow hits on redirectable projectiles",
                            "like wind charges and fireballs across region threads."
                        )
                        .blank()
                        .wordWrap(
                            "It is recommended to set \"max-arrow-despawn-invulnerability: disabled\"",
                            "in paper-world-defaults.yml to prevent arrows from despawning"
                        )
                    );
            }

            @Undocumented("Doesn't require docs.")
            public int maxProjectileChunkLoadsPerTick = 10;
            @Undocumented("Doesn't require docs.")
            public int maxProjectileChunkLoadsPerProjectileBeforeRemoval = 10;
            public List<String> loadChunks = new ArrayList<>();
            public boolean crossRegionRedirectableProjectileDeflection = false;

            private final CanonicalReference<Predicate<Projectile>> compiledPredicate = new CanonicalReference<>();

            public Predicate<Projectile> getDoesProjectileLoadChunksOverridePredicate() {
                return compiledPredicate.value();
            }
        }

        {
            option("skeletonAimAccuracy").docs("Defines the inaccuracy of skeleton bow shots. 14 is Vanilla, higher is more inaccurate and lower is more accurate");
            option("villagers")
                .docs(
                    "Options regarding villagers. The options for reducing POI search ranges shrink the search radius(in blocks)",
                    "from 48 to 16, which can help improve tick times with little Vanilla deviation, however this will prevent Villagers",
                    "from acquiring POIs between 17-48 blocks away"
                );
        }

        public double skeletonAimAccuracy = 14.0D;

        public Villagers villagers = new Villagers();
        @Undocumented("Doesn't require docs.")
        public static class Villagers extends Part {

            {
                option("villagerAcquirePoiTasksLoadChunks")
                    .docs("Whether the server should allow Villagers to load unloaded chunks for Villagers to locate POIs");
            }

            public boolean villagerAcquirePoiTasksLoadChunks = true;
            public boolean reduceJobSitePoiSearchRange = false;
            public boolean reduceHomePoiSearchRange = false;
            public boolean reduceMeetingPointPoiSearchRange = false;
        }

        @Undocumented("Doesn't require docs.")
        public boolean experienceOrbsAreFireResistant = false;
        @Undocumented("Doesn't require docs.")
        public boolean experienceOrbsImmuneToExplosions = false;
    }

    public Combat combat = new Combat();
    public static class Combat extends Part {

        {
            option("restoreOldAttackDelayMechanics").docs("Restores 1.8 attack delay mechanics");
            option("imitateSwordBlocking").docs("Restores 1.8 sword blocking mechanics. Might not work for <1.21.4 clients");
        }

        public boolean restoreOldAttackDelayMechanics = false;
        public boolean imitateSwordBlocking = false;

        public Mace mace = new Mace();
        public static class Mace extends Part {

            {
                option("ignoreFallDistance").docs("Removes the fall distance amplifier from maces");
                option("fallDistanceLimit").docs("The limit before fall distance scaling stops working for mace damage bonuses");
            }

            public boolean ignoreFallDistance = false;
            public double fallDistanceLimit = -1.0D;
        }

        {
            option("criticalHitMultiplier").docs("Configures the damage modifier per critical hit");
            option("removeRedDeathAnimation").docs("Removes the red death animation seen on entities when killed");
            option("useLegacyBlastProtection").docs("Restores the blast protection logic from before 1.21");
        }

        @Undocumented("Doesn't require docs.")
        public boolean disableSweepingEdge = false;
        @Undocumented("Doesn't require docs.")
        public boolean disableCritsWhileSprinting = false;
        @Undocumented("Doesn't require docs.")
        public boolean allowFishingRodsToPullEntities = true;
        public float criticalHitMultiplier = 1.5F;
        public boolean removeRedDeathAnimation = false;
        public boolean useLegacyBlastProtection = false;
        @Undocumented("Doesn't require docs.")
        public boolean snowballCanKnockbackPlayers = false;
        @Undocumented("Doesn't require docs.")
        public boolean eggCanKnockbackPlayers = false;
    }

    public Blocks blocks = new Blocks();
    public static class Blocks extends Part {

        {
            option("spawner")
                .docs(
                    Style.create().wordWrap(
                        "All integer options in this section of the configuration are only applied on creation of the spawner",
                        "instances in question. This is due to plugins being able to modify these values too at runtime via Paper",
                        "API. Any already generated spawners will not get this configuration applied, it will only apply to new ones"
                    ).blank()
                    .literal("The options in question are:")
                    .wordWrap(
                        "\"min-spawn-delay\", \"max-spawn-delay\", \"spawn-count\", \"max-nearby-entities\",",
                        "\"required-player-range\", \"spawn-range\""
                    )
                );
        }

        @Undocumented("Doesn't require docs.")
        public boolean chestsCanOpenWithFullBlockAbove = false;
        @Undocumented("Doesn't require docs.")
        public boolean fullChiseledBookShelvesCountAsValidEnchantPowerSources = false;

        public Spawner spawner = new Spawner();
        public static class Spawner extends Part {

            {
                option("minSpawnDelay").docs("The minimum delay between spawner spawns")
                    .greaterThanOrEqualTo(0.0F);
                option("maxSpawnDelay").docs("The maximum delay between spawner spawns")
                    .greaterThanOrEqualTo(0.0F);
                option("spawnCount").docs("The amount of entities a spawner spawns per cycle")
                    .greaterThanOrEqualTo(0.0F);
                option("maxNearbyEntities").docs("The maximum amount of nearby entities before the spawner stops ticking")
                    .greaterThanOrEqualTo(0.0F);
                option("requiredPlayerRange").docs("The required player range for spawners to activate")
                    .greaterThanOrEqualTo(0.0F);
                option("spawnRange").docs("The maximum position range for spawned entities")
                    .greaterThanOrEqualTo(0.0F);
                option("disableMaxNearbyEntitiesCheck").docs("Disables the spawner max nearby entities check");
                option("spawnedEntitiesHaveNoCollision").docs("Disables collisions for entities spawned by spawners");
            }

            public int minSpawnDelay = 200;
            public int maxSpawnDelay = 800;
            public int spawnCount = 4;
            public int maxNearbyEntities = 6;
            public int requiredPlayerRange = 16;
            public int spawnRange = 4;
            public boolean disableMaxNearbyEntitiesCheck = false;
            public boolean spawnedEntitiesHaveNoCollision = false;
        }
    }

    {
        option("waypointUpdateScale")
            .docs(
                "Controls how quickly Canvas' waypoints system falls off with distance between players.",
                "You can read more about how this new system works and play around with this configuration",
                "here: https://docs.canvasmc.io/canvas/info/waypoints/"
            );
        option("disableCriterionTrigger").docs("Disables all criterion triggers. Advancements will not work!");
        option("cactusCheckSurvivalBeforeGrowth").docs("Check if a cactus can survive before growing. Heavily optimizes cacti farms");
        option("enableSuffocationOptimization")
            .docs(
                "Optimizes the suffocation check by selectively skipping the check in a way that still appears Vanilla"
            );
    }

    {
        option("anticheat")
            .docs(
                Style.wrap(
                    "Native, server-side visibility protections. Everything here is computed from the player's",
                    "real position on the server, so client side mods (x-ray, ESP, freecam) only ever receive",
                    "what the player could legitimately see. All checks run asynchronously on the anticheat",
                    "worker threads (see \"anticheat\" in canvas-server.yml) and never block region threads."
                ).blank()
                .wordWrap(
                    "Bypass permissions: \"canvas.anticheat.bypass.antixray\", \"canvas.anticheat.bypass.antiesp\",",
                    "\"canvas.anticheat.bypass.antifreecam\" and \"canvas.anticheat.bypass.undergroundviewdistance\",",
                    "or \"canvas.anticheat.bypass\" for all of them. They are not granted to operators by default.",
                    "Spectators always bypass anti-esp and anti-freecam"
                )
            );
    }

    public Anticheat anticheat = new Anticheat();
    public static class Anticheat extends Part {

        {
            option("rayTraceAntiXray")
                .docs(
                    Style.wrap(
                        "Ray traced anti-xray, a native port of RayTraceAntiXray by stonar96. Requires Paper anti-xray",
                        "to be enabled with \"engine-mode: 1\" in the paper world configuration. Ores (and other configured",
                        "blocks) that are exposed to air are hidden when the chunk is sent and are only revealed once",
                        "the server can trace a line of sight to them from the player's eyes. Block entities of hidden",
                        "blocks (chests, spawners, ...) are stripped from the chunk packet too"
                    )
                );
        }

        public RayTraceAntiXray rayTraceAntiXray = new RayTraceAntiXray();
        public static class RayTraceAntiXray extends Part {

            {
                option("enabled").docs("Whether ray traced anti-xray is enabled in this world");
                option("rayTraceThirdPerson").docs("Also trace from the third person front and back camera positions. More expensive, but avoids late reveals for players in F5");
                option("rayTraceDistance")
                    .docs(
                        "Blocks further than this many blocks from the player's eyes are not traced and keep their",
                        "current (hidden or revealed) state"
                    ).greaterThan(0.0F);
                option("rehideBlocks").docs("Whether revealed blocks that are no longer visible are hidden again. If false, a block stays revealed until its chunk is resent");
                option("rehideDistance").docs("Revealed blocks at or beyond this distance are treated as invisible and are hidden again (only when \"rehide-blocks\" is enabled)").greaterThanOrEqualTo(0.0F);
                option("maxRayTraceBlockCountPerChunk").docs("Maximum number of exposed blocks hidden and traced per chunk. Counting starts at the bottom of the world").greaterThanOrEqualTo(0.0F);
                option("rayTraceBlocks")
                    .docs(
                        "Blocks that are hidden while exposed to air and ray traced, for example \"minecraft:diamond_ore\".",
                        "If empty, the \"hidden-blocks\" list from the paper anti-xray configuration is used"
                    );
                option("frustumCulling")
                    .docs(
                        "Only reveal blocks in front of the player. Prevents revealing ores behind the player's back, which",
                        "is what freecam style cheats rely on, at the cost of a short delay when turning around quickly"
                    );
                option("updateIntervalTicks").docs("How often, in ticks, the visibility of hidden blocks is recomputed for a player that isn't moving. Moving players are updated every tick").greaterThan(0.0F);
            }

            public boolean enabled = false;
            public boolean rayTraceThirdPerson = false;
            public double rayTraceDistance = 80.0D;
            public boolean rehideBlocks = false;
            public double rehideDistance = Double.POSITIVE_INFINITY;
            public int maxRayTraceBlockCountPerChunk = 100;
            public List<String> rayTraceBlocks = new ArrayList<>();
            public boolean frustumCulling = true;
            public int updateIntervalTicks = 10;
        }

        {
            option("antiEsp")
                .docs(
                    Style.wrap(
                        "Ray traced anti-ESP. Entities that the server can't trace a line of sight to are not sent to the",
                        "player at all, so wallhack/ESP/radar mods have nothing to show. Visibility is hooked directly into",
                        "the entity tracker, which means hidden entities are removed and re-added through the exact same",
                        "code path as entities entering and leaving tracking range"
                    )
                );
        }

        public AntiEsp antiEsp = new AntiEsp();
        public static class AntiEsp extends Part {

            {
                option("enabled").docs("Whether anti-ESP is enabled in this world");
                option("alwaysVisibleDistance").docs("Entities whose hitbox is within this many blocks of the player's eyes are always visible").greaterThanOrEqualTo(0.0F);
                option("maxTraceDistance")
                    .docs(
                        "Entities further than this many blocks away are always visible. Use this to bound the cost on servers",
                        "with large entity tracking ranges"
                    ).greaterThan(0.0F);
                option("rayTraceThirdPerson").docs("Also trace from the third person front and back camera positions, so players using F5 don't see entities pop in");
                option("hidePlayers")
                    .docs(
                        "Whether players are hidden behind walls. Note: vanilla clients render the name tag of non-sneaking",
                        "players through walls, so hiding players also hides those name tags. See \"respect-vanilla-name-tags\""
                    );
                option("respectVanillaNameTags")
                    .docs(
                        "If true, entities whose name tag would be rendered through walls by a vanilla client are never",
                        "hidden: players that aren't sneaking, aren't invisible to the viewer, are within 64 blocks and are",
                        "allowed by scoreboard team rules, and entities with an always visible custom name (holograms). This",
                        "keeps vanilla behavior exactly, but means those entities aren't protected from ESP"
                    );
                option("hideProjectiles")
                    .docs(
                        "Whether projectiles (arrows, tridents, fireballs, ...) are hidden. They are fast, so hiding them can",
                        "delay them visually. A player's own projectiles and fishing bobber are always visible to them"
                    );
                option("visibleRecheckIntervalTicks")
                    .docs(
                        "How often, in ticks, entities that are currently visible are re-checked. Hidden entities are",
                        "re-checked every tick while they or the player move, and every other tick otherwise"
                    ).greaterThan(0.0F);
                option("hideGraceTicks")
                    .docs(
                        "How many ticks an entity stays visible after the line of sight to it was lost. Prevents flickering",
                        "at corners and makes peeking behave smoothly"
                    ).greaterThanOrEqualTo(0.0F);
                option("limitLocatorBar")
                    .docs(
                        "Whether the locator bar only gets the exact position of players (and other waypoint transmitting",
                        "entities) the player can currently see, and only their direction otherwise. Without this the locator",
                        "bar works as a radar for hidden players and everyone within view distance"
                    );
                option("excludedEntityTypes")
                    .docs(
                        "Entity types that are never hidden, for example \"minecraft:armor_stand\". Bosses, lightning, end",
                        "crystals, area effect clouds, leash knots, and display and interaction entities that aren't riding",
                        "another entity are never hidden anyway"
                    );
            }

            public boolean enabled = false;
            public double alwaysVisibleDistance = 4.0D;
            public double maxTraceDistance = 128.0D;
            public boolean rayTraceThirdPerson = true;
            public boolean hidePlayers = true;
            public boolean respectVanillaNameTags = false;
            public boolean hideProjectiles = false;
            public int visibleRecheckIntervalTicks = 4;
            public int hideGraceTicks = 10;
            public boolean limitLocatorBar = true;
            public List<String> excludedEntityTypes = new ArrayList<>();

            private final java.util.concurrent.atomic.AtomicReference<java.util.@Nullable Set<EntityType<?>>> excludedEntityTypesCache = new java.util.concurrent.atomic.AtomicReference<>();

            /**
             * The entity types of {@link #excludedEntityTypes}, parsed once per loaded configuration. Unknown ids are
             * logged and ignored.
             */
            public java.util.Set<EntityType<?>> compiledExcludedEntityTypes() {
                final java.util.Set<EntityType<?>> compiled = this.excludedEntityTypesCache.get();
                if (compiled != null) {
                    return compiled;
                }
                final java.util.Set<EntityType<?>> ret = new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>();
                for (final String name : this.excludedEntityTypes) {
                    final Identifier id = Identifier.tryParse(name);
                    final EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
                    if (type == null) {
                        LOGGER.warn("Ignoring unknown entity type \"{}\" in the anti-ESP excluded entity types", name);
                    } else {
                        ret.add(type);
                    }
                }
                final java.util.Set<EntityType<?>> witness = this.excludedEntityTypesCache.compareAndExchange(null, ret);
                return witness == null ? ret : witness;
            }
        }

        {
            option("antiFreecam")
                .docs(
                    Style.wrap(
                        "Anti-freecam. The server runs the same section occlusion graph the vanilla client uses for",
                        "culling, from the player's real position, and only sends the real contents of chunk sections the",
                        "player could possibly see. All other sections are sent as solid stone (and without light, block",
                        "entities or block updates), so moving the camera away from the body through walls reveals nothing.",
                        "Because the server's graph is always a superset of what the client renders, legit players never see",
                        "the hidden sections. Sections at or above the surface of a chunk are always sent, they are open to",
                        "the sky anyway, so only what lies underground is protected"
                    )
                );
        }

        public AntiFreecam antiFreecam = new AntiFreecam();
        public static class AntiFreecam extends Part {

            {
                option("enabled").docs("Whether anti-freecam is enabled in this world");
                option("revealMargin")
                    .docs(
                        "Sections within this many blocks of the player's eyes are always sent, in addition to everything",
                        "reachable through the occlusion graph from there. Larger values hide latency for fast moving players"
                    ).between(2.0F, 32.0F);
                option("maxResendsPerTick").docs("Maximum amount of chunks re-sent to a single player per tick to reveal newly visible sections").greaterThan(0.0F);
                option("updateIntervalTicks")
                    .docs(
                        "How often, in ticks, the occlusion graph of a player that doesn't move is checked for block changes",
                        "that open up new lines of sight. Moving players, newly sent chunks and blocks changing between opaque",
                        "and transparent are handled immediately, this is only a fallback"
                    ).greaterThan(0.0F);
                option("perBlock")
                    .docs(
                        Style.wrap(
                            "Masks single blocks instead of whole 16x16x16 sections: every block that no line of sight from the",
                            "player's possible camera positions can reach is sent as stone, caves, rooms and ores right next to",
                            "the player included, unless they are open to the sky. Updates of blocks that can't be seen right now",
                            "are withheld until they can. Costs more worker CPU (a few milliseconds per update at view distance",
                            "10) and re-sends chunks more often when players dig into or walk through caves"
                        )
                    );
            }

            public boolean enabled = false;
            public double revealMargin = 8.0D;
            public int maxResendsPerTick = 8;
            public int updateIntervalTicks = 10;
            public boolean perBlock = false;
        }

        {
            option("undergroundViewDistance")
                .docs(
                    Style.wrap(
                        "Lowers the chunk view distance (the chunks sent to the client) of players that are underground,",
                        "where they can't see far anyway. Server side loading and ticking distances are not changed.",
                        "A player counts as underground when their eyes are at least \"surface-depth\" blocks below the",
                        "surface of the column they are in and receive no sky light"
                    )
                );
        }

        public UndergroundViewDistance undergroundViewDistance = new UndergroundViewDistance();
        public static class UndergroundViewDistance extends Part {

            {
                option("enabled").docs("Whether the underground view distance is enabled in this world");
                option("viewDistance").docs("The view distance, in chunks, used while a player is underground").between(2.0F, 32.0F);
                option("surfaceDepth").docs("How many blocks below the surface a player's eyes need to be to count as underground").greaterThanOrEqualTo(0.0F);
                option("enterDelayTicks").docs("How many ticks a player needs to stay underground before the view distance is lowered").greaterThanOrEqualTo(0.0F);
                option("exitDelayTicks").docs("How many ticks a player needs to stay above ground before the view distance is restored").greaterThanOrEqualTo(0.0F);
            }

            public boolean enabled = false;
            public int viewDistance = 4;
            public int surfaceDepth = 10;
            public int enterDelayTicks = 40;
            public int exitDelayTicks = 5;
        }

        {
            option("obfuscatePlayerHealth")
                .docs(
                    "Sends a fake health (the player's max health, or 0 once dead) and no absorption for other players,",
                    "so health indicator mods can't read them. Vanilla clients never display the health of other players,",
                    "the death animation keeps working. Scoreboard based health displays are not affected"
                );
        }

        public boolean obfuscatePlayerHealth = false;
    }

    public double waypointUpdateScale = 4000.0D;
    public boolean disableCriterionTrigger = false;
    public boolean cactusCheckSurvivalBeforeGrowth = false;
    public boolean enableSuffocationOptimization = false;

    public Sleeping sleeping = new Sleeping();

    @SuppressWarnings("FieldMayBeFinal")
    public static class Sleeping extends Part {

        // the following options are based of PurpurMC:
        // - sleepSkippingNight
        // - sleepingPlayersPercent
        // - sleepNotPossible
        // - rainStopsAfterSleep
        // - thunderStopsAfterSleep
        // these options were based off the 26.1 versions of these options
        // but have been modified from their original form

        {
            option("sleepSkippingNight")
                .docs(
                    "The actionbar message that appears when the night has been skipped.",
                    "Use \"default\" for Vanilla, leave empty to disable"
                );
            option("sleepingPlayersPercent")
                .docs(
                    "The actionbar message that appears when a player is asleep.",
                    "Use \"default\" for Vanilla, leave empty to disable. You can use \"<count>\" as",
                    "a placeholder for the current amount of players sleeping, and \"<total>\"",
                    "for the total amount of players needed to sleep"
                );
            option("sleepNotPossible")
                .docs(
                    "The actionbar message that appears when a player tries to sleep, but the \"players_sleeping_percentage\"",
                    "gamerule is set to a value greater than 100. Use \"default\" for Vanilla, leave empty to disable"
                );
        }

        private String sleepSkippingNight = "default";
        private String sleepingPlayersPercent = "default";
        private String sleepNotPossible = "default";

        public boolean sleepSkippingNightDisabled() {
            return sleepSkippingNight.isBlank();
        }

        public boolean sleepingPlayersPercentDisabled() {
            return sleepingPlayersPercent.isBlank();
        }

        public boolean sleepNotPossibleDisabled() {
            return sleepNotPossible.isBlank();
        }

        @Nullable
        public Component getSleepSkippingNight() {
            if (sleepSkippingNightDisabled()) {
                return null;
            }

            final Component message;
            if (sleepSkippingNight.equalsIgnoreCase("default")) {
                message = Component.translatable("sleep.skipping_night");
            }
            else {
                message = PaperAdventure.asVanilla(MiniMessage.miniMessage().deserialize(sleepSkippingNight));
            }

            return message;
        }

        @Nullable
        public Component getSleepingPlayersPercent(int amountSleeping, int sleepersNeeded) {
            if (sleepingPlayersPercentDisabled()) {
                return null;
            }

            final Component message;
            if (sleepingPlayersPercent.equalsIgnoreCase("default")) {
                message = Component.translatable("sleep.players_sleeping", amountSleeping, sleepersNeeded);
            }
            else {
                message = PaperAdventure.asVanilla(MiniMessage.miniMessage().deserialize(sleepingPlayersPercent,
                    Placeholder.parsed("count", Integer.toString(amountSleeping)),
                    Placeholder.parsed("total", Integer.toString(sleepersNeeded))));
            }

            return message;
        }

        @Nullable
        public Component getSleepNotPossible() {
            if (sleepNotPossibleDisabled()) {
               return null;
            }

            final Component message;
            if (sleepNotPossible.equalsIgnoreCase("default")) {
                message = Component.translatable("sleep.not_possible");
            }
            else {
                message = PaperAdventure.asVanilla(MiniMessage.miniMessage().deserialize(sleepNotPossible));
            }

            return message;
        }

        @Undocumented("Doesn't require docs.")
        public boolean sleepIgnoresNearbyMobs = false;
        @Undocumented("Doesn't require docs.")
        public boolean rainStopsAfterSleep = true;
        @Undocumented("Doesn't require docs.")
        public boolean thunderStopsAfterSleep = true;
    }

}
