# OpenCode Instructions for BaseLedger

This file contains high-signal, repo-specific context to help AI agents work effectively in this repository.

## Architecture & Module Boundaries
- **Stack:** Scala 3, Apache Pekko, PostgreSQL (R2DBC for persistence/reads), Tapir.
- **Paradigm:** Strict Event Sourcing and CQRS. State is mutated by events, and reads happen via projected SQL tables.
- **Monorepo Structure (sbt):**
  - `modules/common/`: Shared utilities and configuration.
  - `modules/domain/`: Core business logic, Pekko Typed Actors, and Event Sourcing models.
  - `modules/features/`: API Routes (Tapir), Read-side Projections (Pekko Projection), and R2DBC integrations.
  - `modules/tests/`: Fast unit tests for the domain layer.
  - `src/`: Application bootstrapper (`Main.scala`), Flyway migrations (`src/main/resources/db/migrations`), and root-level integration tests.

## Build, Test, & Tooling Quirks
- **SBT Wrapper:** ALWAYS run `./sbtx` instead of standard `sbt`.
- **Database Requirement:** Local execution needs Postgres on port 5432. 
  - *Setup:* Run `docker-compose up -d db migrations` to spin up PostgreSQL and run Flyway migrations before starting the app.
- **Run Application Locally:** `./sbtx run`
- **Testing:** 
  - Run all: `./sbtx test`
  - Integration tests use `testcontainers-scala-postgresql`. You **must** have a Docker/Podman daemon running locally, or integration tests will fail with connection errors.
  - Run single test: `./sbtx "testOnly *WalletIntegrationSpec"` (ensure quotes for wildcard matching).
- **Format & Lint:** 
  - Formatting: `./sbtx scalafmtAll`
  - Linting: `./sbtx "scalafixAll"`
- **Native Image Build:** Builds highly optimized GraalVM standalone binaries via `./sbtx "GraalVMNativeImage / packageBin"`.

## Codegen & Conventions
- **Protobuf Codegen:** Pekko persistence events are serialized via Protobuf (`modules/domain/src/main/protobuf/*.proto`). `ScalaPB` automatically generates the corresponding Scala case classes during compile. If you change a `.proto` file, run `./sbtx compile` to update the Scala structures. Do not manually edit the generated output.
- **Error Handling:** Avoid throwing exceptions for domain errors. Use Tapir's structured error mapping (`ApiError` / `DomainError` in `modules/features`).
- **Idempotency:** Core wallet operations (`reserve`, `spend`, `release`) strictly mandate an `idempotencyKey` to prevent double-charging on network retries. Honor this pattern in any new wallet transactions or network integrations.
