# syntax=docker/dockerfile:1.7
#
# Oracle GraalVM (GFTC license) - required for the G1 GC (G1 is NOT available in Community Edition).
# Non-musl image: G1 is glibc-based, so the binary is mostly-static (glibc) rather than fully-static musl.
FROM container-registry.oracle.com/graalvm/native-image:25-ol9 AS builder

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
# G1 produces a glibc-linked (mostly-static) binary, so the runtime image MUST provide glibc.
FROM gcr.io/distroless/base-debian12 AS runtime

# Copy CA Certificates from the builder to allow HTTPS requests (keeps the builder's trust store)
COPY --from=builder /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem /etc/pki/tls/certs/ca-bundle.crt
COPY --from=builder /etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem /etc/ssl/certs/ca-certificates.crt

# OCI Image Branding
LABEL org.opencontainers.image.title="BaseLedger"
LABEL org.opencontainers.image.description="High-integrity, high-performance usage and budget firewall for AI agents."
LABEL org.opencontainers.image.authors="support@baseledger.io"
LABEL org.opencontainers.image.vendor="BaseLedger"
LABEL org.opencontainers.image.source="https://github.com/baseledger-io/baseledger/"
LABEL org.opencontainers.image.licenses="Apache-2.0"

WORKDIR /app

COPY --from=builder /workspace/target/graalvm-native-image/baseledger /app/baseledger

EXPOSE 8000

ENTRYPOINT ["/app/baseledger"]
