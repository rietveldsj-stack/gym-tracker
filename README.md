# Gym Tracker

A personal gym tracker: exercises, workout sessions with warmup/work sets, history, personal records,
progress charts and a rest timer with lock-screen alerts. Spring Boot serves the API and an
offline-capable web app that is added to the iPhone home screen from Safari.

## Run locally

Needs Java 25 and Docker (for the throwaway Postgres).

    ./mvnw spring-boot:test-run -Dspring-boot.run.profiles=test

Open http://localhost:8080 and sign in with `tester` / `secret-pass`.

## Tests

    ./mvnw verify

Runs the API tests against Postgres in Docker and the browser tests in WebKit (Safari's engine).

## Deploy

See [docs/railway-setup.md](docs/railway-setup.md). All secrets are Railway variables; nothing secret is in this repo.
