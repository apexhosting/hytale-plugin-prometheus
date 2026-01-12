package com.apexminecrafthosting.hytale.plugins.prometheusexporter;

import com.apexminecrafthosting.hytale.plugins.prometheusexporter.config.PrometheusExporterConfig;
import com.apexminecrafthosting.hytale.plugins.prometheusexporter.metrics.HytaleMetricsCollector;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.logging.Level;

import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.util.Config;
import io.prometheus.metrics.instrumentation.jvm.JvmMetrics;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.exporter.servlet.jakarta.PrometheusMetricsServlet;
import net.nitrado.hytale.plugins.webserver.WebServerPlugin;
import net.nitrado.hytale.plugins.webserver.authorization.RequirePermissionsFilter;

public class PrometheusExporter extends JavaPlugin {

    private final Config<PrometheusExporterConfig> config = withConfig(PrometheusExporterConfig.CODEC);

    @Nullable
    private HytaleMetricsCollector hytaleMetricsCollector;

    private PrometheusRegistry prometheusRegistry;

    private WebServerPlugin webServerPlugin;

    public PrometheusExporter(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        getLogger().at(Level.INFO).log("Setting up Prometheus Exporter registry...");

        // Setup Prometheus Metrics Registry
        this.prometheusRegistry = new PrometheusRegistry();

        getLogger().at(Level.INFO).log("Starting up Prometheus Exporter server and metrics collection...");

        // Register built-in JVM metrics from Prometheus
        JvmMetrics.builder().register(this.prometheusRegistry);

        // Register Hytale metrics collector (implements MultiCollector for scrape-time collection)
        hytaleMetricsCollector = new HytaleMetricsCollector(this.prometheusRegistry);

        try {
            this.registerHandlers();
        } catch (Exception e) {
            getLogger().at(Level.SEVERE).withCause(e).log("Failed to start Prometheus Exporter HTTP server");
        }
    }

    private void registerHandlers() {
        var plugin = PluginManager.get().getPlugin(new PluginIdentifier("Nitrado", "WebServer"));

        if (!(plugin instanceof WebServerPlugin webServerPlugin)) {
            return;
        }

        this.webServerPlugin = webServerPlugin;

        try {
            this.webServerPlugin.addServlet(
                this,
                "/metrics",
                new PrometheusMetricsServlet(this.prometheusRegistry),
                new RequirePermissionsFilter(Permissions.READ)
            );
        } catch (Exception e) {
            getLogger().at(Level.SEVERE).log("Failed to register route: " + e.getMessage());
        }
    }


    @Override
    protected void shutdown() {
        getLogger().at(Level.INFO).log("Shutting down Prometheus Exporter...");

        if (webServerPlugin != null) {
            webServerPlugin.removeServlets(this);
        }
    }
}
