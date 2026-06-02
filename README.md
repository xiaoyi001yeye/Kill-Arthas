# Fordring

Fordring, full name Tirion Fordring, is an Arthas management platform.

Current version: `0.1.0`

Naming note: Tirion Fordring is the one who killed Arthas.

## Structure

```text
backend/   Spring Boot backend
frontend/  React + Vite frontend
docs/      Product, prototype, backend, and frontend design docs
```

## Docker Compose

Start all services:

```bash
docker compose up -d --build
```

Services:

```text
frontend: http://localhost:5173
backend:  http://localhost:8080
postgres: localhost:5432
redis:    localhost:6379
```

Stop all services:

```bash
docker compose down
```

## Local Backend Development

Requires Java 21.

```bash
cd backend
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn spring-boot:run
```

Backend URL:

```text
http://localhost:8080
```

## Local Frontend Development

```bash
cd frontend
npm install
npm run dev
```

Frontend URL:

```text
http://localhost:5173
```

## Build

```bash
cd backend && JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q -DskipTests package
cd frontend && npm run build
```

## Standalone Single-JAR

The standalone artifact only requires Java 21 at runtime. It embeds the frontend, uses an in-memory H2 database, and does not require PostgreSQL, Redis, or Nginx.

Build:

```bash
cd backend
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -q -Pstandalone clean package
```

Run:

```bash
java -jar target/fordring-standalone-0.1.0.jar
```

The process listens on `0.0.0.0` and automatically selects the first available port in `8080-8099`. It prints local access URLs when startup completes.

Standalone mode has no application login and does not enable HTTPS. Only run it inside a trusted development network, do not expose it to the public internet, and stop the process after diagnostics are complete.

## MVP Notes

- `0.1.0` is a controlled-environment MVP.
- The backend exposes the designed REST/WebSocket shape and persists data with PostgreSQL/Flyway.
- Arthas, SSH, and Docker connector behavior is implemented for controlled development environments.
- The frontend implements the main prototype flows: access management, console, command history, attach, rerun, and save command.
- MVP uses single-user mode and deployment-level protection.
