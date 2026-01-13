# Hytale Prometheus Exporter Plugin

This plugin exposes Hytale server metrics in Prometheus format via an HTTP endpoint. It depends on
[Nitrado:WebServer](https://github.com/nitrado/hytale-plugin-webserver).

## Purpose of this Plugin

When running a Hytale server, operators often need to monitor server performance and health metrics.
This plugin provides a Prometheus-compatible endpoint that exports structured metrics about the server,
worlds, players, entities, and JVM performance.

## Main Features

- **Player Metrics:** Number of players online per world.
- **Chunk Metrics:** Active chunks, loaded chunks, and generated chunks per world.
- **Entity Metrics:** Number of active entities per world.
- **Performance Metrics:** TPS (ticks per second) metrics including average, min, max, and target values per world.
- **Server Configuration:** Exports configured maximum view radius.
- **JVM Metrics:** Built-in Prometheus JVM metrics (memory, GC, threads, etc.).
- **Permission-Based Access:** Endpoint access is controlled via Hytale's permission system.

## Installation

1. Install the [Nitrado:WebServer](https://github.com/nitrado/hytale-plugin-webserver) plugin.
2. Copy the JAR of this plugin into your Hytale server's `mods/` folder.

## Usage

### Endpoint

The plugin registers a single endpoint at `GET /ApexHosting/PrometheusExporter/metrics`.

### Example Response

```
# HELP hytale_players_online Number of players currently online per world
# TYPE hytale_players_online gauge
hytale_players_online{world="default"} 5

# HELP hytale_chunks_active Number of currently loaded chunks per world
# TYPE hytale_chunks_active gauge
hytale_chunks_active{world="default"} 256

# HELP hytale_chunks_loaded Total number of chunks loaded or generated
# TYPE hytale_chunks_loaded counter
hytale_chunks_loaded{world="default"} 1024

# HELP hytale_chunks_loaded_disk Total number of chunks loaded from disk
# TYPE hytale_chunks_loaded_disk counter
hytale_chunks_loaded_disk{world="default"} 512

# HELP hytale_chunks_generated Total number of newly generated chunks
# TYPE hytale_chunks_generated counter
hytale_chunks_generated{world="default"} 512

# HELP hytale_entities_active Number of currently loaded entities per world
# TYPE hytale_entities_active gauge
hytale_entities_active{world="default"} 150

# HELP hytale_world_tps_avg Measured average TPS derived from recent ticks per world in the given period
# TYPE hytale_world_tps_avg gauge
hytale_world_tps_avg{world="default",period="60"} 20.0

# HELP hytale_world_tps_min Measured minimum TPS derived from recent ticks per world in the given period
# TYPE hytale_world_tps_min gauge
hytale_world_tps_min{world="default",period="60"} 19.5

# HELP hytale_world_tps_max Measured maximum TPS derived from recent ticks per world in the given period
# TYPE hytale_world_tps_max gauge
hytale_world_tps_max{world="default",period="60"} 20.0

# HELP hytale_world_tps_target Configured TPS target per world
# TYPE hytale_world_tps_target gauge
hytale_world_tps_target{world="default"} 20

# HELP hytale_server_max_view_radius Currently configured maximum view radius
# TYPE hytale_server_max_view_radius gauge
hytale_server_max_view_radius 16

# HELP hytale_metrics_collector_time_ms Time taken by the Hytale metrics collector in milliseconds
# TYPE hytale_metrics_collector_time_ms gauge
hytale_metrics_collector_time_ms 0.5
```

### Permissions

Access to the metrics endpoint requires the following permission:

| Permission                                        | Description             |
|---------------------------------------------------|-------------------------|
| `apexhosting.prometheusexporter.web.read.metrics` | Access metrics endpoint |


### Prometheus Configuration

Add the following scrape configuration to your `prometheus.yml`:

```yaml
scrape_configs:
  - job_name: 'hytale'
    static_configs:
      - targets: ['your-server:7003']
    metrics_path: '/ApexHosting/PrometheusExporter/metrics'
    basic_auth:
      username: 'serviceaccount.prometheus'
      password: 'YourServiceAccountPassword'
```

#### Creating a Service Account for Prometheus

Create a file at `mods/Nitrado_WebServer/provisioning/prometheus.serviceaccount.json`:

```json
{
  "Enabled": true,
  "Name": "serviceaccount.prometheus",
  "PasswordHash": "$2b$10$YourBcryptHashHere",
  "Groups": [],
  "Permissions": ["apexhosting.prometheusexporter.web.read.metrics"]
}
```

The `PasswordHash` must be a bcrypt hash of your chosen password. You can generate one using:

```bash
# Using htpasswd (Apache utilities)
htpasswd -nbBC 10 "" YourPassword | tr -d ':\n'

# Using mkpasswd (whois package)
mkpasswd -m bcrypt YourPassword
```

Note the `serviceaccount.` prefix in both the username (for Prometheus) and the `Name` field (in the provisioning file).

## Contributing

Community contributions are welcome and encouraged.
