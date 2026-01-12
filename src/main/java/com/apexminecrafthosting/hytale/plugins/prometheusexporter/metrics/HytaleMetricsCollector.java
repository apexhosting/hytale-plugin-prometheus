package com.apexminecrafthosting.hytale.plugins.prometheusexporter.metrics;

import com.hypixel.hytale.metrics.metric.HistoricMetric;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.model.registry.PrometheusRegistry;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class HytaleMetricsCollector {

    private final PrometheusRegistry registry;

    /**
     * Precision for TPS calculations.
     */
    private static final double PRECISION = 1000;

    // Player metrics (per-world)
    private final Gauge onlinePlayersGauge;

    // Chunk metrics (per-world)
    private final Gauge   loadedChunksGauge;
    private final Counter totalLoadedChunksCounter;
    private final Counter totalLoadedChunksDiskCounter;
    private final Counter totalGeneratedChunksCounter;
    private final Gauge   tpsAvgGauge;
    private final Gauge   tpsMinGauge;
    private final Gauge   tpsMaxGauge;
    private final Gauge   tpsTargetGauge;

    // Entity metrics (per-world)
    private final Gauge loadedEntitiesGauge;

    // Internal metrics
    private final Gauge collectorTimeMsGuage;

    // Track last known values for counters to detect resets
    private final Map<String, CounterState> chunkCounterStates = new ConcurrentHashMap<>();
    private final Map<String, TickSampleState> tickSamples = new ConcurrentHashMap<>();

    public HytaleMetricsCollector(@Nonnull PrometheusRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");

        // Player metrics with world label
        this.onlinePlayersGauge = Gauge.builder()
            .name("hytale_players_online")
            .help("Number of players currently online per world")
            .labelNames("world")
            .withoutExemplars()
            .register(registry);

        // Chunk metrics with world label
        this.loadedChunksGauge = Gauge.builder()
            .name("hytale_chunks_active")
            .help("Number of currently loaded chunks per world")
            .labelNames("world")
            .withoutExemplars()
            .register(registry);

        this.totalLoadedChunksCounter = Counter.builder()
            .name("hytale_chunks_loaded_total")
            .help("Total number of chunks loaded or generated")
            .labelNames("world")
            .withoutExemplars()
            .register(registry);

        this.totalLoadedChunksDiskCounter = Counter.builder()
            .name("hytale_chunks_loaded_disk_total")
            .help("Total number of chunks loaded from disk")
            .labelNames("world")
            .withoutExemplars()
            .register(registry);

        this.totalGeneratedChunksCounter = Counter.builder()
            .name("hytale_chunks_generated_total")
            .help("Total number of newly generated chunks")
            .labelNames("world")
            .withoutExemplars()
            .register(registry);

        // TPS metrics with world label
        this.tpsAvgGauge = Gauge.builder()
            .name("hytale_world_tps_avg")
            .help("Measured average TPS derived from recent ticks per world in the given period")
            .labelNames("world", "period")
            .withoutExemplars()
            .register(registry);

        this.tpsMinGauge = Gauge.builder()
            .name("hytale_world_tps_min")
            .help("Measured minimum TPS derived from recent ticks per world in the given period")
            .labelNames("world", "period")
            .withoutExemplars()
            .register(registry);

        this.tpsMaxGauge = Gauge.builder()
            .name("hytale_world_tps_max")
            .help("Measured maximum TPS derived from recent ticks per world in the given period")
            .labelNames("world", "period")
            .withoutExemplars()
            .register(registry);

        this.tpsTargetGauge = Gauge.builder()
            .name("hytale_world_tps_target")
            .help("Configured TPS target per world")
            .labelNames("world")
            .withoutExemplars()
            .register(registry);

        // Entity metrics with world label
        this.loadedEntitiesGauge = Gauge.builder()
            .name("hytale_entities_active")
            .help("Number of currently loaded entities per world")
            .labelNames("world")
            .withoutExemplars()
            .register(registry);

        // Internal metrics
        this.collectorTimeMsGuage = Gauge.builder()
            .name("hytale_metrics_collector_time_ms")
            .help("Time taken by the Hytale metrics collector in milliseconds")
            .withoutExemplars()
            .register(registry);
    }

    // Collect all metrics (players, chunks, entities).
    public void update() {
        long startTime = System.nanoTime();

        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }

        Map<String, World> worlds = universe.getWorlds();
        if (worlds == null || worlds.isEmpty()) {
            return;
        }

        updatePlayerMetrics(worlds);
        updateWorldMetrics(worlds);

        long elapsedNanos = System.nanoTime() - startTime;
        collectorTimeMsGuage.set(elapsedNanos / 1_000_000d);
    }

    private void updatePlayerMetrics(@Nonnull Map<String, World> worlds) {
        worlds.forEach((worldName, world) -> {
            if (world == null) {
                return;
            }

            onlinePlayersGauge.labelValues(world.getName()).set(world.getPlayerCount());
        });
    }

    private void updateWorldMetrics(@Nonnull Map<String, World> worlds) {
        for (Map.Entry<String, World> entry : worlds.entrySet()) {
            World world = entry.getValue();

            if (world == null) {
                continue;
            }

            updateChunkMetrics(world);
            updateEntityMetrics(world);
            updatePerformanceMetrics(world);
        }
    }

    private void updateChunkMetrics(@Nonnull World world) {
        var chunkStore = world.getChunkStore();

        // Currently loaded chunks (gauge)
        int loadedChunks = chunkStore.getLoadedChunksCount();
        loadedChunksGauge.labelValues(world.getName()).set(loadedChunks);

        int totalGenerated = chunkStore.getTotalGeneratedChunksCount();
        int totalLoadedFromDisk = chunkStore.getTotalLoadedChunksCount();
        int totalSeen = totalLoadedFromDisk + totalGenerated;

        // Total loaded chunks counter (disk loads only)
        updateCounter(world.getName(), "loaded_disk", totalLoadedFromDisk, totalLoadedChunksDiskCounter);

        // Total chunks seen counter (disk + generated)
        updateCounter(world.getName(), "loaded_total", totalSeen, totalLoadedChunksCounter);

        // Total generated chunks counter
        updateCounter(world.getName(), "generated", totalGenerated, totalGeneratedChunksCounter);
    }

    private void updateEntityMetrics(@Nonnull World world) {
        var entityStore = world.getEntityStore();
        if (entityStore.getStore() == null) {
            return;
        }

        // Currently loaded entities (gauge)
        int entityCount = entityStore.getStore().getEntityCount();
        loadedEntitiesGauge.labelValues(world.getName()).set(entityCount);
    }

    private void updatePerformanceMetrics(@Nonnull World world) {
        HistoricMetric metrics = world.getBufferedTickLengthMetricSet();
        var tickStepNanos = world.getTickStepNanos();

        long[] periodsNanos = metrics.getPeriodsNanos();

        for (int i = 0; i < periodsNanos.length; i++) {
            long periodSeconds =  periodsNanos[i] / 1_000_000_000;

            setTpsGauge(
                world.getName(),
                periodSeconds,
                tpsFromDelta(metrics.getAverage(i), tickStepNanos),
                tpsFromDelta(metrics.calculateMin(i), tickStepNanos),
                tpsFromDelta(metrics.calculateMax(i), tickStepNanos)
            );
        }

        setTpsTarget(world.getName(), world.getTps());
    }

    private void setTpsGauge(String worldName, long periodSeconds, double average, double min, double max) {
        if (Double.isFinite(average) && average > 0) {
            tpsAvgGauge.labelValues(worldName, String.valueOf(periodSeconds)).set(average);
        }

        if (Double.isFinite(min) && min > 0) {
            tpsMinGauge.labelValues(worldName, String.valueOf(periodSeconds)).set(min);
        }

        if (Double.isFinite(max) && max > 0) {
            tpsMaxGauge.labelValues(worldName, String.valueOf(periodSeconds)).set(max);
        }
    }

    private void setTpsTarget(String worldName, int targetTps) {
        if (targetTps > 0) {
            tpsTargetGauge.labelValues(worldName).set(targetTps);
        }
    }

    private void updateCounter(
        @Nonnull String worldName,
        @Nonnull String type,
        int currentValue,
        @Nonnull Counter counter
    ) {
        String key = worldName + ":" + type;
        CounterState state = chunkCounterStates.computeIfAbsent(key, k -> new CounterState());

        int delta = currentValue - state.lastValue;
        if (delta > 0) {
            counter.labelValues(worldName).inc(delta);
            state.lastValue = currentValue;
        } else if (delta < 0) {
            state.lastValue = currentValue;
        }
        // If delta == 0, no change.
    }

    private static final class CounterState {
        int lastValue;
    }

    private static final class TickSampleState {
        long lastProcessedTimestamp = Long.MIN_VALUE;
        long lastPollNano = System.nanoTime();
        double lastReportedTps;
    }

    /**
     * Convert tick delta to TPS.
     *
     * @param delta The tick delta in nanoseconds.
     * @param min   The minimum tick step in nanoseconds.
     * @return The TPS value.
     */
    public static double tpsFromDelta(final long delta, final long min) {
        long adjustedDelta = delta;
        if (adjustedDelta < min) adjustedDelta = min;
        return Math.round(((1.0 / adjustedDelta) * 1_000_000_000) * PRECISION) / PRECISION;
    }

    /**
     * Convert tick delta to TPS.
     *
     * @param delta The tick delta in nanoseconds.
     * @param min   The minimum tick step in nanoseconds.
     * @return The TPS value.
     */
    public static double tpsFromDelta(final double delta, final long min) {
        double adjustedDelta = delta;
        if (adjustedDelta < min) adjustedDelta = min;
        return Math.round(((1.0 / adjustedDelta) * 1_000_000_000) * PRECISION) / PRECISION;
    }
}
