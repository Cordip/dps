# D4-D6 Flights Block

## Quick Start

Prepare the demo database and D4 pricing rules:

```bash
./scripts/setup_d4_docker.sh
```

`setup_d4_docker.sh` downloads the demo dump into `data/` if it is missing.
It starts only PostgreSQL, restores the `demo` database when needed, and creates
D4 pricing rules.

Start the D6 API:

```bash
docker compose up -d api
```

Start the API documentation client:

```bash
docker compose -f compose/openapi.yml up -d scalar
```

Scalar is available at `http://localhost:8083` and sends requests to the real
D6 Axum API at `http://localhost:3000/api/v1`.

If the database is already prepared, both main services can be started together:

```bash
docker compose up -d db api
```

Do not use `docker compose up -d` as the first command on a clean database:
it can start `api` before the `demo` database and D4 pricing rules exist.

## D6 Axum Service

Run locally against Docker PostgreSQL:

```bash
cd service
DATABASE_URL=postgres://postgres:postgres@localhost:5432/demo cargo run
```

Run as Docker service:

```bash
docker compose up -d db api
```

On a clean database, run `./scripts/setup_d4_docker.sh` before starting `api`.

The service listens on:

```text
http://localhost:3000
```

Health check:

```bash
curl http://localhost:3000/health
```

Run D6 smoke checks against the real Axum service:

```bash
./scripts/smoke_d6_axum.sh
```

The smoke script creates a temporary booking/check-in flow and removes the test
records before exit.

## Scripts

- `setup_d4_docker.sh`: download dump, start/restore DB if needed, run D4 create and validation.
- `restore_demo_docker.sh`: restore the demo dump into Docker PostgreSQL.
- `run_sql_docker.sh`: run a SQL file inside the Docker PostgreSQL container.
- `psql_demo_docker.sh`: open `psql` connected to the restored `demo` database.

## D4 Proof And Validation

```bash
./scripts/setup_d4_docker.sh --with-proof
```

Or run scripts manually:

```bash
./scripts/run_sql_docker.sh sql/00_smoke.sql
./scripts/run_sql_docker.sh sql/d4_pricing/01_explore_existing_prices.sql
./scripts/run_sql_docker.sh sql/d4_pricing/02_create_pricing_rules.sql
./scripts/run_sql_docker.sh sql/d4_pricing/03_validate_future_prices.sql
```

## Clean Restore

```bash
./scripts/setup_d4_docker.sh --force-restore
```

Or:

```bash
./scripts/restore_demo_docker.sh --force
```

## Connect

```bash
./scripts/psql_demo_docker.sh
```

## Stop

```bash
docker compose down
```

Delete restored database data:

```bash
docker compose down -v
```
