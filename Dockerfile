# syntax=docker/dockerfile:1.7
#
FROM ghcr.io/graalvm/native-image-community:25-ol9 AS builder

RUN microdnf install -y git zip unzip \
    && microdnf clean all

WORKDIR /workspace

COPY project/ project/
COPY build.sbt .
COPY build.sbt.lock .
COPY modules/common/build.sbt.lock modules/common/
COPY modules/domain/build.sbt.lock modules/domain/
COPY modules/features/build.sbt.lock modules/features/
COPY .scalafix.conf .
COPY sbtx .

RUN chmod +x sbtx

RUN --mount=type=cache,target=/root/.sbt \
    --mount=type=cache,target=/root/.cache/coursier \
    --mount=type=cache,target=/root/.ivy2 \
    ./sbtx -Dsbt.color=false update

COPY modules/ modules/
COPY src/ src/
RUN --mount=type=cache,target=/root/.sbt \
    --mount=type=cache,target=/root/.cache/coursier \
    --mount=type=cache,target=/root/.ivy2 \
    ./sbtx -Dsbt.color=false "GraalVMNativeImage / packageBin"

# Final image
FROM gcr.io/distroless/base-debian12 AS runtime

# OCI Image Branding
LABEL org.opencontainers.image.title="BaseLedger Open Core"
LABEL org.opencontainers.image.description="High-integrity, high-performance usage and budget firewall for AI agents."
LABEL org.opencontainers.image.authors="Jabir Minjibir"
LABEL org.opencontainers.image.vendor="BaseLedger"
LABEL org.opencontainers.image.source="https://github.com/minjibir/baseledger/"
LABEL org.opencontainers.image.licenses="Apache-2.0"

WORKDIR /app

COPY --from=builder /workspace/target/graalvm-native-image/baseledger /app/baseledger

EXPOSE 8000
EXPOSE 9464

ENTRYPOINT ["/app/baseledger"]
