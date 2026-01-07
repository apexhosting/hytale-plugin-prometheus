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

    // Player metrics (per-world)
    private final Gauge onlinePlayersGauge;

    // Chunk metrics (per-world)
    private final Gauge   loadedChunksGauge;
    private final Counter totalLoadedChunksCounter;
    private final Counter totalLoadedChunksDiskCounter;
    private final Counter totalGeneratedChunksCounter;
    private final Gauge   tpsGauge;
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
        this.tpsGauge = Gauge.builder()
            .name("hytale_world_tps")
            .help("Measured TPS derived from recent ticks per world")
            .labelNames("world")
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
        long[] periods = metrics.getPeriodsNanos();
        if (periods.length == 0) {
            return;
        }

        int windowIndex = periods.length - 1;
        long[] timestamps = metrics.getTimestamps(windowIndex);
        long[] values = metrics.getValues(windowIndex);
        int sampleLength = Math.min(timestamps.length, values.length);
        if (sampleLength == 0) return;
        long tickStepNanos = world.getTickStepNanos();
        TickSampleState state = tickSamples.computeIfAbsent(world.getName(), k -> new TickSampleState());

        long now = System.nanoTime();
        long elapsedNanos = now - state.lastPollNano;
        if (elapsedNanos <= 0) elapsedNanos = tickStepNanos;
        state.lastPollNano = now;

        long newestTimestamp = state.lastProcessedTimestamp;
        int ticksProcessed = 0;
        for (int i = sampleLength - 1; i >= 0; i--) {
            long ts = timestamps[i];
            if (ts <= state.lastProcessedTimestamp) break;

            long delta = values[i];
            if (delta <= 0 || delta == Long.MAX_VALUE) continue;

            ticksProcessed++;
            if (ts > newestTimestamp) newestTimestamp = ts;
        }

        double tps;
        if (ticksProcessed > 0) {
            state.lastProcessedTimestamp = newestTimestamp;
            double elapsedSeconds = elapsedNanos / 1_000_000_000d;
            tps = ticksProcessed / Math.max(elapsedSeconds, tickStepNanos / 1_000_000_000d);
        } else if (state.lastProcessedTimestamp == Long.MIN_VALUE) {
            tps = world.getTps();
        } else {
            double elapsedSeconds = elapsedNanos / 1_000_000_000d;
            if (elapsedSeconds <= 0) elapsedSeconds = tickStepNanos / 1_000_000_000d;
            tps = 0.0d;
        }

        state.lastReportedTps = tps;
        setTpsGauge(world.getName(), tps);

        setTpsTarget(world.getName(), world.getTps());
    }

    private void setTpsGauge(String worldName, double value) {
        if (Double.isFinite(value) && value > 0) {
            tpsGauge.labelValues(worldName).set(value);
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
}
