FROM rust:1.96-bookworm AS builder

WORKDIR /app/service

COPY service/Cargo.toml service/Cargo.lock ./
COPY service/migrations ./migrations
COPY service/src ./src

RUN cargo build --release

FROM debian:bookworm-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/service/target/release/service /usr/local/bin/dpt-service

ENV BIND_ADDR=0.0.0.0:3000
EXPOSE 3000

CMD ["dpt-service"]
