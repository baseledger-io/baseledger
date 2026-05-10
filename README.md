# BaseLedger Engine (Open Core)

A high-integrity, high-performance usage and budget firewall for AI agents. Built with Scala 3, Pekko, and PostgreSQL.

## Why BaseLedger?

Autonomous LLM agents and concurrent API calls create a unique financial risk: **API Budget Bankruptcy**.

Traditional "balance" columns in a user table are vulnerable to race conditions and double-charging during network retries. BaseLedger solves this using three mathematically proven mechanics:

1.  **The Reservation Pattern**: Lock funds (or usage units) _before_ starting an LLM call. Only deduct them once the job succeeds.
2.  **The Idempotency Shield**: Every command is tagged with a key. Network retries are automatically ignored, ensuring you never charge a user twice for the same prompt.
3.  **The Dead-Man's Switch**: Reservations automatically expire (and refund) if an AI job crashes or hangs, preventing "frozen" units.

## Prerequisites

Depending on how you intend to run BaseLedger, you will need:
- **For the Quick Start:** [Docker](https://docs.docker.com/get-docker/) (with Docker Compose) OR [Podman](https://podman.io/) (with Podman Compose).
- **For Local Development:** JDK 17+ (GraalVM recommended) and a running PostgreSQL instance. 


## Quick Start

The fastest way to get started is spinning up the pre-configured PostgreSQL instance and the BaseLedger API via containers.

**Using Docker:**
```bash
docker-compose up -d
```

**Using Podman (Daemonless alternative to Docker):**
```bash
podman-compose up -d
```

### Core Lifecycle Example

**1. Add Credits (or Usage Units) to a Wallet**

```bash
curl -X POST http://localhost:8000/wallet/user-123/add \
  -H "Content-Type: application/json" \
  -d '{"idempotencyKey": "topup-1", "amount": 1000}'
```

**2. Reserve Tokens for an LLM Prompt**

```bash
curl -X POST http://localhost:8000/wallet/user-123/reserve \
  -H "Content-Type: application/json" \
  -d '{
    "idempotencyKey": "prompt-1", 
    "holdId": "hold-abc", 
    "amount": 400, 
    "ttlSeconds": 30,
    "metadata": {"model": "gpt-4-turbo"}
  }'
```

**3. Settle the Charge (LLM Success)**

```bash
curl -X POST http://localhost:8000/wallet/user-123/spend \
  -H "Content-Type: application/json" \
  -d '{
    "idempotencyKey": "spend-1", 
    "holdId": "hold-abc",
    "metadata": {"feature": "image_generation"}
  }'
```

## How it Works (The Integrity Core)

BaseLedger is designed for absolute correctness. It uses **Event Sourcing** and **Monotonic Timestamps** to ensure a perfect, immutable audit trail of every credit and debit.

- **Event Sourced**: Every balance change is derived from a sequence of events.
- **R2DBC Journal**: High-performance, non-blocking persistence.
- **Projected Read Side**: A separate SQL table for instant balance queries.


## Configuration

| Variable            | Description       | Default     |
| ------------------- | ----------------- | ----------- |
| `POSTGRES_HOST`     | Database host     | `localhost` |
| `POSTGRES_PORT`     | Database port     | `3210`      |
| `POSTGRES_DB`       | Database name     | `baseledger`|
| `POSTGRES_USER`     | Database user     | `baseledger`|
| `POSTGRES_PASSWORD` | Database password | `password`  |
| `HTTP_PORT`         | API Port          | `8000`      |


## Local Development (From Source)

Ensure you have **JDK 17+** installed and a local PostgreSQL database running on port `3210`. 

**1. Apply Database Migrations:**
BaseLedger requires the database schema to be initialized before booting. Use the Flyway CLI to run the migrations located in the resources directory:

```bash
flyway -url=jdbc:postgresql://localhost:3210/baseledger -user=baseledger -password=password -locations=filesystem:src/main/resources/db/migrations migrate
```

*(Alternatively, you can just run `docker-compose up -d db migrations` to spin up the DB and run the migrations, then run `./sbtx run` locally).*

**2. Start the Application:**
With the schema in place, you can now run the application:

```bash
./sbtx run
```

### Running Tests

Integration tests use Testcontainers to spin up a real PostgreSQL instance.

```bash
./sbtx test
```

### Building the Native Image

To build the highly optimized GraalVM standalone binary:

```bash
./sbtx "GraalVMNativeImage / packageBin"
```

---

_Built for the next generation of autonomous AI applications._

---
## Enterprise & Cloud 
The Open-Source engine uses PostgreSQL for zero-friction local deployment. If your AI agents go viral and you need to scale past Postgres limitations, we offer a fully-managed **Serverless Cloud tier** (powered by DynamoDB). 

For massive scale, we offer a BYOC (Bring Your Own Cloud) Enterprise License powered by **ScyllaDB** for 10,000+ TPS and 99.999% SLA. 

[Learn more at baseledger.io](https://baseledger.io)