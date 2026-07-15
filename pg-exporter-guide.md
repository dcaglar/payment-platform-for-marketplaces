
Based on my search of the docker-library/postgres repository, there is no built-in environment variable or special configuration for Prometheus metrics integration.

The official PostgreSQL Docker image doesn't include native Prometheus metrics export. The Dockerfiles show PostgreSQL is compiled with various features (GSSAPI, ICU, LDAP, PERL, PYTHON, etc.), but there's no mention of Prometheus exporter or metrics configuration.

To enable Prometheus metrics for PostgreSQL, you have several options:
Use postgres_exporter (most common)

A separate sidecar exporter that connects to PostgreSQL and exposes metrics on port 9187
GitHub: https://github.com/prometheus-community/postgres_exporter
Deploy as a companion container
Use PostgreSQL's built-in statistics

PostgreSQL has native pg_stat_* views you can query
The exporter above uses these internally
Custom monitoring setup

Query PostgreSQL directly in your monitoring stack
Use PostgreSQL's log_statement and log_duration for query metrics
Use a pre-configured image

Some distributions include postgres_exporter bundled
Or create your own Dockerfile based on postgres:latest with the exporter added
The official Docker image intentionally keeps a minimal footprint, so additional tools like exporters aren't included by default.

can you explaoin that  how does tihs exporter works , i mean iahve dcoker image, i also have telemetry trace and alsm o tercis being sent to promteheus, for exporter, are  there any setting for metrics on exporter side or on totele collector sude

Using results of get file and 1 other tool call
How postgres_exporter Works
The postgres_exporter acts as a bridge between your PostgreSQL database and Prometheus. Here's the architecture:

Code
┌─────────────────────┐
│   PostgreSQL DB     │
│  (running metrics)  │
└──────────┬──────────┘
│ connects to port 5432
│ queries pg_stat_* views
▼
┌─────────────────────────────────────┐
│   postgres_exporter                 │
│   (separate container/process)      │
│   Listens on port 9187              │
└──────────┬──────────────────────────┘
│ exposes /metrics endpoint
│ in Prometheus format
▼
┌─────────────────────┐
│   Prometheus        │
│   (scrapes metrics) │
└─────────────────────┘
Settings Location: Two Sides
You're right to ask about settings! There are configuration points on both sides:

EXPORTER SIDE (postgres_exporter container)
These control which metrics are collected from PostgreSQL:

Via Environment Variables:

bash
DATA_SOURCE_URI="localhost:5432/postgres?sslmode=disable"
DATA_SOURCE_USER=postgres
DATA_SOURCE_PASS=password
PG_EXPORTER_COLLECTION_TIMEOUT=1m        # How long to wait for queries
PG_EXPORTER_METRIC_PREFIX=pg             # Prefix for metrics
PG_EXPORTER_WEB_TELEMETRY_PATH=/metrics  # Endpoint path
Via CLI Flags (enable/disable specific collectors):

bash
--collector.stat_statements            # Enable statement tracking
--collector.stat_database               # Enable database stats
--collector.replication                 # Enable replication metrics
--collector.locks                       # Enable lock metrics
--collector.process_idle                # Enable idle process tracking
--no-collector.stat_statements          # Disable something
Configuration file (postgres_exporter.yml):

YAML
auth_modules:
foo:
type: userpass
userpass:
username: postgres_exporter
password: secret
options:
sslmode: disable
PROMETHEUS SIDE (prometheus.yml)
These control how often and from where Prometheus scrapes:

YAML
scrape_configs:
- job_name: 'postgres'
  scrape_interval: 15s        # How often to scrape (default 15s)
  scrape_timeout: 10s         # Timeout for each scrape
  static_configs:
    - targets: ['127.0.0.1:9187']  # Where to find exporter

  # Optional: add labels to all metrics
  metric_relabel_configs:
    - source_labels: [__name__]
      regex: 'pg_stat_.*'
      action: keep  # Only keep certain metrics
      Docker Compose Example (Complete Setup)
      Here's how your setup would look:

YAML
version: '3.8'

services:
postgres:
image: postgres:latest
ports:
- "5432:5432"
environment:
POSTGRES_PASSWORD: password
POSTGRES_DB: mydb
volumes:
- postgres_data:/var/lib/postgresql/data

postgres_exporter:
image: prometheuscommunity/postgres-exporter:latest
ports:
- "9187:9187"
environment:
DATA_SOURCE_NAME: "postgresql://postgres:password@postgres:5432/postgres?sslmode=disable"
# EXPORTER SETTINGS - which metrics to collect
PG_EXPORTER_COLLECTION_TIMEOUT: "1m"
PG_EXPORTER_WEB_TELEMETRY_PATH: "/metrics"
depends_on:
- postgres

prometheus:
image: prom/prometheus:latest
ports:
- "9090:9090"
volumes:
- ./prometheus.yml:/etc/prometheus/prometheus.yml
command:
- '--config.file=/etc/prometheus/prometheus.yml'
- '--scrape-interval=15s'  # PROMETHEUS SETTINGS - scrape frequency

volumes:
postgres_data:
Key Metrics Collectors (Exporter Side)
Collector	Enabled by Default	What it tracks
stat_database	✅ Yes	Connections, commits, rollbacks
stat_statements	❌ No	Individual query performance
replication	✅ Yes	Replication lag, status
locks	✅ Yes	Blocked queries, locks
stat_activity	✅ Yes	Active connections
long_running_transactions	❌ No	Transactions running > 5min
postmaster	❌ No	Uptime, version
To enable disabled collectors, add flags:

bash
--collector.stat_statements
--collector.long_running_transactions
--collector.postmaster
What Gets Exposed
When you curl http://localhost:9187/metrics, you get Prometheus text format:

Code
# HELP pg_up Whether the last scrape of Postgres was successful
# TYPE pg_up gauge
pg_up 1

# HELP pg_exporter_scrapes_total Total number of times Postgres was scraped
# TYPE pg_exporter_scrapes_total counter
pg_exporter_scrapes_total 42

# HELP pg_stat_database_xact_commit Number of transactions committed
# TYPE pg_stat_database_xact_commit counter
pg_stat_database_xact_commit{datname="postgres"} 1234
pg_stat_database_xact_commit{datname="mydb"} 5678
Prometheus then scrapes this endpoint on its schedule and stores the time-series data.

Summary
Component	Settings	Purpose
Exporter	Env vars, CLI flags, config file	Control what metrics PostgreSQL exposes
Prometheus	prometheus.yml	Control how often and from where to scrape
Telemetry/Traces	OpenTelemetry exporters (separate)	For distributed tracing (different from Prometheus metrics)