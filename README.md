# pantry-tracker-api

REST API for **Used Items Log**: a shared log of things that get used up (paper towels, coffee pods,
printer paper), with monthly totals so you know what to restock. Several people share one log by
joining it with an invite code.

The web app lives in [pantry-tracker-web](https://github.com/Rustem-Stilinski/pantry-tracker-web).

**Stack:** Spring Boot 4.1 (Java 21), Spring Security, Spring Data JPA, Flyway, PostgreSQL.
Live updates go out over Server-Sent Events.

```
src/main/java/com/usedlog/
├── auth/       sign up, sign in, "who am I"
├── security/   session + CSRF setup, user lookup
├── household/  shared logs and invite codes
├── entry/      entries, monthly totals, frequent items
├── events/     Server-Sent Events broadcaster
└── common/     error handling, text helpers
src/main/resources/db/migration/V1__init.sql
src/test/java/com/usedlog/UsedLogApiTests.java
```

## Run

**With Docker only:**

```bash
docker compose up --build        # PostgreSQL + API on http://localhost:8080
```

**From your IDE or terminal** (JDK 21 and Maven 3.9+):

```bash
docker compose up -d db          # just the database
mvn spring-boot:run              # API on http://localhost:8080
```

The API reads `DATABASE_URL`, `DATABASE_USERNAME` and `DATABASE_PASSWORD` and defaults to the
database from `docker-compose.yml`. Flyway creates the tables on first start.

**The whole app (API + web) in one command:** clone both repos side by side, then from this repo:

```
code/
├── pantry-tracker-api/
└── pantry-tracker-web/
```

```bash
docker compose --profile full up --build    # then open http://localhost:4200
```

## Tests

```bash
mvn test        # API tests on in-memory H2, no database needed
```

## API

All endpoints are under `/api` and return JSON. Errors use RFC 9457 problem details, so the message is
always in `detail`. Everything except register, login and health requires a signed-in session.

| Method | Path | What it does |
|---|---|---|
| POST | `/api/auth/register` | Create an account and sign in |
| POST | `/api/auth/login` | Sign in |
| POST | `/api/auth/logout` | Sign out |
| GET | `/api/auth/me` | The signed-in person (401 if none) |
| GET | `/api/households` | Logs you belong to |
| POST | `/api/households` | Start a log (you become the owner) |
| POST | `/api/households/join` | Join with an invite code, e.g. `{"inviteCode": "K7QM-3XRP"}` |
| GET | `/api/households/{id}` | A log with its invite code and members |
| GET | `/api/households/{id}/entries?month=2026-10&tz=America/New_York` | Entries in a month, newest first |
| POST | `/api/households/{id}/entries` | Log a use |
| DELETE | `/api/households/{id}/entries/{entryId}` | Remove an entry |
| GET | `/api/households/{id}/totals?month=2026-10&tz=America/New_York` | Per-item totals for the month |
| GET | `/api/households/{id}/items/frequent?limit=8` | Most-logged items (last 60 days) with last amount |
| GET | `/api/households/{id}/events` | Server-Sent Events stream (`entries-changed`) |
| GET | `/actuator/health` | Health check |

Example entry:

```json
POST /api/households/{id}/entries
{ "itemName": "Paper towels", "quantity": 2, "unit": "roll", "category": "Kitchen",
  "note": "Spill in the hallway", "usedAt": "2026-10-25T21:05:00Z" }
```

`usedAt` is optional (defaults to now) and can't be in the future.

## How it works

**Sign-in.** Sessions use an HttpOnly cookie. CSRF protection uses Spring Security's single-page-app
setup: the server sets an `XSRF-TOKEN` cookie and the web app echoes it in the `X-XSRF-TOKEN` header
on every POST and DELETE. Passwords are hashed with bcrypt. Because of the cookies, the web app and
the API must be served from the same origin (a proxy in front of both, see Deploying).

**Sharing.** Each log is a household. Whoever starts it gets an 8-character invite code; anyone who
enters the code joins. Every household endpoint checks membership and answers 404 to non-members.

**Months and time zones.** Clients send the viewer's time zone with each month request, so
"October" means October where the viewer is, not in UTC.

**Totals.** One SQL `GROUP BY item_key, unit` per month. `item_key` is the lower-cased item name, so
"Milk" and "milk" count as the same item.

**Live updates.** Each open browser tab keeps a Server-Sent Events connection. After an entry is
saved or removed, the API notifies every tab in that household and they re-fetch. Connections are
kept in memory, which is fine for one instance. If you run several, publish the events through
something shared, such as PostgreSQL `LISTEN/NOTIFY`, Redis, or SNS.

## Deploying to AWS

1. **Database:** RDS for PostgreSQL. Pass its JDBC URL and credentials as `DATABASE_URL`,
   `DATABASE_USERNAME` and `DATABASE_PASSWORD` (from Secrets Manager).
2. **API:** build the `Dockerfile`, push to ECR, run on ECS Fargate behind an Application Load
   Balancer. Set `COOKIE_SECURE=true`. Health check path: `/actuator/health`.
3. **Same origin:** the web app's CloudFront distribution forwards `/api/*` to the load balancer
   (see pantry-tracker-web). Keep the load balancer idle timeout above 30 seconds; the API sends a
   heartbeat on the live-updates stream every 25 seconds.
