# Agent Guide

## Rebuild And Restart Services

When the user asks to rebuild services, restart services, says "重新构建服务并重启", or simply says "rebuild", treat it as a request to rebuild the Docker containers and restart the services. Use the Docker Compose workflow from the repository root:

```bash
docker compose up -d --build
```

This rebuilds the `backend` and `frontend` images and recreates the application containers. The `postgres` and `redis` services should remain running unless their configuration changes.

After the command finishes, verify service state:

```bash
docker compose ps
```

Expected services:

```text
fordring-backend    backend    Up    0.0.0.0:8080->8080/tcp
fordring-frontend   frontend   Up    0.0.0.0:5173->80/tcp
fordring-postgres   postgres   Up    healthy
fordring-redis      redis      Up    healthy
```

Check recent logs when confirmation is needed:

```bash
docker compose logs --tail=80 backend
docker compose logs --tail=50 frontend
```

Healthy startup signals:

- Backend logs show Spring Boot started on port `8080`.
- Flyway validates migrations and reports the schema is up to date or migrated successfully.
- Frontend logs show Nginx configuration completed and worker processes started.

User-facing URLs:

```text
Frontend: http://localhost:5173
Backend:  http://localhost:8080
```

If containers fail to start, inspect the failing service logs first, then rebuild only the affected service if appropriate:

```bash
docker compose up -d --build backend
docker compose up -d --build frontend
```
