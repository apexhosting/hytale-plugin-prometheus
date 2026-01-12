package com.apexminecrafthosting.hytale.plugins.prometheusexporter.metrics;

import com.hypixel.hytale.metrics.metric.HistoricMetric;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.*;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class HytaleMetricsCollector implements MultiCollector {

    /**
     * Precision for TPS calculations.
     */
    private static final double PRECISION = 1000;


    public HytaleMetricsCollector(@Nonnull PrometheusRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        registry.register(this);
    }

    /**
     * Called by Prometheus on each scrape. Collects all metrics at scrape time.
     */
    @Override
    public MetricSnapshots collect() {
        long startTime = System.nanoTime();

        Universe universe = Universe.get();
        if (universe == null) {
            return MetricSnapshots.of();
        }

        Map<String, World> worlds = universe.getWorlds();
        if (worlds == null || worlds.isEmpty()) {
            return MetricSnapshots.of();
        }

        List<MetricSnapshot> snapshots = new ArrayList<>();

        // Collect all metrics
        snapshots.add(collectPlayerMetrics(worlds));
        snapshots.add(collectActiveChunksMetrics(worlds));
        snapshots.addAll(collectChunkCounterMetrics(worlds));
        snapshots.add(collectEntityMetrics(worlds));
        snapshots.addAll(collectPerformanceMetrics(worlds));
        snapshots.add(collectServerMetrics());

        // Collector timing
        long elapsedNanos = System.nanoTime() - startTime;
        snapshots.add(GaugeSnapshot.builder()
            .name("hytale_metrics_collector_time_ms")
            .help("Time taken by the Hytale metrics collector in milliseconds")
            .dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
                .value(elapsedNanos / 1_000_000d)
                .build())
            .build());

        return new MetricSnapshots(snapshots);
    }

    private GaugeSnapshot collectServerMetrics() {
        GaugeSnapshot.Builder builder = GaugeSnapshot.builder()
            .name("hytale_server_max_view_radius")
            .help("Currently configured maximum view radius");

        builder.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
            .value(HytaleServer.get().getConfig().getMaxViewRadius())
            .build());

        return builder.build();
    }

    private GaugeSnapshot collectPlayerMetrics(@Nonnull Map<String, World> worlds) {
        GaugeSnapshot.Builder builder = GaugeSnapshot.builder()
            .name("hytale_players_online")
            .help("Number of players currently online per world");

        for (World world : worlds.values()) {
            if (world == null) continue;
            builder.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
                .labels(Labels.of("world", world.getName()))
                .value(world.getPlayerCount())
                .build());
        }

        return builder.build();
    }

    private GaugeSnapshot collectActiveChunksMetrics(@Nonnull Map<String, World> worlds) {
        GaugeSnapshot.Builder builder = GaugeSnapshot.builder()
            .name("hytale_chunks_active")
            .help("Number of currently loaded chunks per world");

        for (World world : worlds.values()) {
            if (world == null) continue;
            builder.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
                .labels(Labels.of("world", world.getName()))
                .value(world.getChunkStore().getLoadedChunksCount())
                .build());
        }

        return builder.build();
    }

    private List<CounterSnapshot> collectChunkCounterMetrics(@Nonnull Map<String, World> worlds) {
        CounterSnapshot.Builder loadedTotalBuilder = CounterSnapshot.builder()
            .name("hytale_chunks_loaded")
            .help("Total number of chunks loaded or generated");
        CounterSnapshot.Builder loadedDiskBuilder = CounterSnapshot.builder()
            .name("hytale_chunks_loaded_disk")
            .help("Total number of chunks loaded from disk");
        CounterSnapshot.Builder generatedBuilder = CounterSnapshot.builder()
            .name("hytale_chunks_generated")
            .help("Total number of newly generated chunks");

        for (World world : worlds.values()) {
            if (world == null) continue;

            var chunkStore = world.getChunkStore();
            String worldName = world.getName();

            int totalGenerated = chunkStore.getTotalGeneratedChunksCount();
            int totalLoadedFromDisk = chunkStore.getTotalLoadedChunksCount();
            int totalLoaded = totalLoadedFromDisk + totalGenerated;

            loadedTotalBuilder.dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
                .labels(Labels.of("world", worldName))
                .value(totalLoaded)
                .build());

            loadedDiskBuilder.dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
                .labels(Labels.of("world", worldName))
                .value(totalLoadedFromDisk)
                .build());

            generatedBuilder.dataPoint(CounterSnapshot.CounterDataPointSnapshot.builder()
                .labels(Labels.of("world", worldName))
                .value(totalGenerated)
                .build());
        }

        return List.of(loadedTotalBuilder.build(), loadedDiskBuilder.build(), generatedBuilder.build());
    }

    private GaugeSnapshot collectEntityMetrics(@Nonnull Map<String, World> worlds) {
        GaugeSnapshot.Builder builder = GaugeSnapshot.builder()
            .name("hytale_entities_active")
            .help("Number of currently loaded entities per world");

        for (World world : worlds.values()) {
            if (world == null) continue;
            var entityStore = world.getEntityStore();
            if (entityStore.getStore() == null) continue;

            builder.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
                .labels(Labels.of("world", world.getName()))
                .value(entityStore.getStore().getEntityCount())
                .build());
        }

        return builder.build();
    }

    private List<GaugeSnapshot> collectPerformanceMetrics(@Nonnull Map<String, World> worlds) {
        GaugeSnapshot.Builder avgBuilder = GaugeSnapshot.builder()
            .name("hytale_world_tps_avg")
            .help("Measured average TPS derived from recent ticks per world in the given period");
        GaugeSnapshot.Builder minBuilder = GaugeSnapshot.builder()
            .name("hytale_world_tps_min")
            .help("Measured minimum TPS derived from recent ticks per world in the given period");
        GaugeSnapshot.Builder maxBuilder = GaugeSnapshot.builder()
            .name("hytale_world_tps_max")
            .help("Measured maximum TPS derived from recent ticks per world in the given period");
        GaugeSnapshot.Builder targetBuilder = GaugeSnapshot.builder()
            .name("hytale_world_tps_target")
            .help("Configured TPS target per world");

        for (World world : worlds.values()) {
            if (world == null) continue;

            String worldName = world.getName();
            HistoricMetric metrics = world.getBufferedTickLengthMetricSet();
            long tickStepNanos = world.getTickStepNanos();
            long[] periodsNanos = metrics.getPeriodsNanos();

            for (int i = 0; i < periodsNanos.length; i++) {
                String periodSeconds = String.valueOf(periodsNanos[i] / 1_000_000_000);
                Labels labels = Labels.of("world", worldName, "period", periodSeconds);

                double avg = tpsFromDelta(metrics.getAverage(i), tickStepNanos);
                double min = tpsFromDelta(metrics.calculateMin(i), tickStepNanos);
                double max = tpsFromDelta(metrics.calculateMax(i), tickStepNanos);

                if (Double.isFinite(avg) && avg > 0) {
                    avgBuilder.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
                        .labels(labels).value(avg).build());
                }
                if (Double.isFinite(min) && min > 0) {
                    minBuilder.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
                        .labels(labels).value(min).build());
                }
                if (Double.isFinite(max) && max > 0) {
                    maxBuilder.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
                        .labels(labels).value(max).build());
                }
            }

            int targetTps = world.getTps();
            if (targetTps > 0) {
                targetBuilder.dataPoint(GaugeSnapshot.GaugeDataPointSnapshot.builder()
                    .labels(Labels.of("world", worldName))
                    .value(targetTps)
                    .build());
            }
        }

        return List.of(avgBuilder.build(), minBuilder.build(), maxBuilder.build(), targetBuilder.build());
    }


    /**
     * Convert tick delta to TPS.
     */
    public static double tpsFromDelta(final long delta, final long min) {
        long adjustedDelta = delta;
        if (adjustedDelta < min) adjustedDelta = min;
        return Math.round(((1.0 / adjustedDelta) * 1_000_000_000) * PRECISION) / PRECISION;
    }

    /**
     * Convert tick delta to TPS.
     */
    public static double tpsFromDelta(final double delta, final long min) {
        double adjustedDelta = delta;
        if (adjustedDelta < min) adjustedDelta = min;
        return Math.round(((1.0 / adjustedDelta) * 1_000_000_000) * PRECISION) / PRECISION;
    }
}
