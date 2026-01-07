package com.apexminecrafthosting.hytale.plugins.prometheusexporter.config;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public class PrometheusExporterConfig {

    public static final BuilderCodec<PrometheusExporterConfig> CODEC = BuilderCodec.builder(PrometheusExporterConfig.class, PrometheusExporterConfig::new)
        .append(
            new KeyedCodec<>("BindHost", Codec.STRING),
            (prometheusExporterConfig, value) -> prometheusExporterConfig.bindHost = value,
            prometheusExporterConfig -> prometheusExporterConfig.bindHost
        ).add()
        .append(
            new KeyedCodec<>("BindPort", Codec.INTEGER),
            (prometheusExporterConfig, value) -> prometheusExporterConfig.bindPort = value,
            prometheusExporterConfig -> prometheusExporterConfig.bindPort
        ).add()
        .append(
            new KeyedCodec<>("UpdateIntervalSeconds", Codec.INTEGER),
            (prometheusExporterConfig, value) -> prometheusExporterConfig.updateIntervalSeconds = value,
            prometheusExporterConfig -> prometheusExporterConfig.updateIntervalSeconds
        ).add()
        .build();

    private String bindHost = "localhost";
    private int bindPort = 9400;
    private int updateIntervalSeconds = 1;

    public String getBindHost() {
        return bindHost;
    }

    public int getBindPort() {
        return bindPort;
    }

    public int getUpdateIntervalSeconds() {
        return updateIntervalSeconds;
    }
}
