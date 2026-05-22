# Build stage
FROM rust:1.90-slim-bookworm AS builder

# Install build dependencies
RUN apt-get update && apt-get install -y pkg-config libsqlite3-dev libssl-dev && rm -rf /var/lib/apt/lists/*

WORKDIR /usr/src/app

COPY ./Cargo.toml ./Cargo.lock ./

# Create an empty src directory to trick Cargo into thinking it's a valid Rust project
RUN mkdir -p server/src \
        && mkdir -p migration/src \
        && mkdir -p common/src \
        && echo "fn main() {}" > server/src/main.rs \
        && echo "fn main() {}" > migration/src/main.rs \
        && touch migration/src/lib.rs \
        && touch common/src/lib.rs

COPY ./server/Cargo.toml ./server/
COPY ./migration/Cargo.toml ./migration/
COPY ./common/Cargo.toml ./common/

# Build the dependencies without the actual source code to cache dependencies separately
RUN cargo build --release

COPY ./server ./server
COPY ./migration ./migration
COPY ./common ./common

# Build your application
RUN touch -a -m  server/src/main.rs \
        && touch -a -m migration/src/main.rs \
        && touch -a -m migration/src/lib.rs \
        && touch -a -m common/src/lib.rs \
        && cargo build --release

# Runtime stage
FROM debian:bookworm-slim

# Install runtime dependencies
RUN apt-get update && apt-get install -y libsqlite3-0 libssl3 ca-certificates && rm -rf /var/lib/apt/lists/*

# Create a non-root user for security
RUN useradd -m app
USER app
WORKDIR /home/app

# Copy the binary from the build stage
COPY --from=builder /usr/src/app/target/release/server /usr/local/bin/server
COPY --from=builder /usr/src/app/target/release/migration /usr/local/bin/migration

# Copy and set up the entrypoint script
COPY ./docker/entrypoint.sh /usr/local/bin/entrypoint.sh

USER root
RUN chmod +x /usr/local/bin/entrypoint.sh \
        && mkdir -p /home/app/data \
        && chown -R app:app /home/app/data

USER app

# Default environment variables
ENV OTP_PUSH_SERVER_LISTEN_ADDR=0.0.0.0:3000
ENV OTP_PUSH_SERVER_DATABASE_URL=sqlite:///home/app/data/otp-push.db
ENV RUST_LOG=info

# Expose the default port
EXPOSE 3000

# Set the entrypoint script
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
