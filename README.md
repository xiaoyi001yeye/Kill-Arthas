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

## MVP Notes

- `0.1.0` is a controlled-environment MVP.
- The backend exposes the designed REST/WebSocket shape and persists data with PostgreSQL/Flyway.
- Arthas, SSH, and Docker connector behavior is simulated in this version.
- The frontend implements the main prototype flows: access management, console, command history, attach, rerun, and save command.
- MVP uses single-user mode and deployment-level protection.
