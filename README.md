# pantry-tracker-api

[![CI](https://github.com/Rustem-Stilinski/pantry-tracker-api/actions/workflows/ci.yml/badge.svg)](https://github.com/Rustem-Stilinski/pantry-tracker-api/actions/workflows/ci.yml)

REST API for **Pantry Tracker**: a shared log of things that get used up (paper towels, coffee pods,
printer paper) and of what they cost. Snap a receipt and the API reads the items and prices with
Claude, then tracks price history, compares stores and totals spending. Scan a product's barcode to
see what it cost last time and where it's cheapest. Several people share one log by joining it with an
invite code.

The web app lives in [pantry-tracker-web](https://github.com/Rustem-Stilinski/pantry-tracker-web).

**Stack:** Spring Boot 4.1 (Java 21), Spring Security, Spring Data JPA, Flyway, PostgreSQL.
Live updates go out over Server-Sent Events.

```
src/main/java/com/usedlog/
├── auth/       sign up, sign in, "who am I"
├── security/   session + CSRF setup, user lookup
├── household/  shared logs and invite codes
├── entry/      entries, monthly totals, frequent items
├── price/      receipts, reading photos with Claude, prices, stores, spending
├── barcode/    scanned barcodes: which item each one is, name suggestions from Open Food Facts
├── events/     Server-Sent Events broadcaster
└── common/     error handling, text helpers
src/main/resources/db/migration/   V1 (log), V2 (prices), V3 (store location), V4 (barcodes), V5 (sessions)
src/main/resources/receipt-schema.json   the shape Claude must return for a receipt
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
mvn test              # API tests on in-memory H2, no database needed
./ci/smoke-test.sh    # end-to-end check against a running API (e.g. after docker compose up)
```

CI (`.github/workflows/ci.yml`) runs on every push and pull request: the Maven build and tests, the
smoke test against a real PostgreSQL, and a Docker image build.

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
| POST | `/api/households/{id}/receipts/read` | 1–4 receipt photos (multipart `photos`) → a draft to check; nothing saved |
| POST | `/api/households/{id}/receipts` | Save a checked receipt; returns price changes it revealed |
| GET | `/api/households/{id}/receipts?month=2026-10&tz=…` | Receipts in a month |
| GET / DELETE | `/api/households/{id}/receipts/{receiptId}` | One receipt with its lines |
| PATCH | `/api/households/{id}/receipts/{receiptId}` | Correct its date and time, e.g. `{"purchasedAt": "2026-11-01T17:38:00Z"}` |
| GET | `/api/households/{id}/prices` | Every item's latest, lowest and average price, plus changes of 10%+ in the last 60 days |
| GET | `/api/households/{id}/prices/history?item=Eggs, 12 ct&unit=pcs` | One item's purchases over time and by store |
| GET | `/api/households/{id}/prices/compare` | Items bought at 2+ stores, cheapest store first |
| GET | `/api/households/{id}/spending?year=2026&tz=…` | Spending per month, by store and category |
| GET | `/api/households/{id}/stores` | Store names, for autocomplete |
| GET | `/api/households/{id}/barcodes/{code}?format=upc_a` | A scanned barcode: its item and prices, or a suggested name if it's new |
| PUT | `/api/households/{id}/barcodes/{code}` | Name (or rename) a barcode, e.g. `{"itemName": "Milk 2%, 1 gal"}` |
| GET | `/api/households/{id}/barcodes?item=Milk 2%, 1 gal` | The UPCs linked to an item; without `item`, every UPC in the log |
| DELETE | `/api/households/{id}/barcodes/{code}` | Unlink a UPC from its item |
| GET | `/actuator/health` | Health check |

Example entry:

```json
POST /api/households/{id}/entries
{ "itemName": "Paper towels", "quantity": 2, "unit": "roll", "category": "Kitchen",
  "note": "Spill in the hallway", "usedAt": "2026-10-25T21:05:00Z" }
```

`usedAt` is optional (defaults to now) and can't be in the future.

## Reading receipts with Claude

`POST /receipts/read` sends the photos to the Claude Messages API (`claude-haiku-5-5` by default) with
[structured outputs](https://platform.claude.com/docs/en/build-with-claude/structured-outputs), so the
reply always matches `receipt-schema.json`: store, date, each line's readable name, quantity, unit,
unit price, line total and category, plus subtotal, tax, total and a note on anything unclear. The
household's existing item and store names go into the prompt so the same product keeps the same name.
A receipt costs well under a cent.

| Variable | |
|---|---|
| `ANTHROPIC_API_KEY` | Turns reading on. Create one at [platform.claude.com/settings/keys](https://platform.claude.com/settings/keys); a key scoped to one workspace is simplest. Without it, the read endpoint answers 503 and the app offers manual entry. |
| `ANTHROPIC_WORKSPACE_ID` | Only for a key that isn't scoped to a workspace. |
| `ANTHROPIC_MODEL` | Optional; default `claude-haiku-5-5`. |

## Scanning barcodes

The web app reads a product's barcode with the phone camera and asks `GET /barcodes/{code}`:

- **A barcode the household has named** comes back with the item's name and, when the item is on any
  receipt, its latest, lowest and average price (`prices`, the same shape as an item on `/prices`).
- **A new barcode** comes back with `known: false` and, when Open Food Facts has it, a `suggestion`
  ("Fairlife 2% Reduced Fat Milk, 52 fl oz"). The person picks a name, ideally the one the item has on
  receipts so the scan finds its prices, and `PUT /barcodes/{code}` saves it for everyone in the log.

**Codes printed on receipts.** Some stores print each line's item code; Safeway prints the UPC without
its first and last digit (`2113050504` for UPC `0 21130 50504 3`). A receipt line keeps that code
(`itemCode`, digits only). Scanning a barcode that no one has named checks the receipt lines for any way
of printing it (in full, without the check digit, or a UPC without its end digits); a match links the
barcode to that line's item on the spot, so the scan shows its prices with no naming step. Until then
the code shows on the item (`GET /barcodes?item=…`, `source: "receipt"`).

**How prices link to a UPC.** Receipts print product names, not UPCs, so every price is filed under
the item's name (`item_key`: lower case, single spaces). A barcode stores the item name too, and its
prices are the receipt lines with the same key. Linking a UPC from the item's own price page (the web
app's **Scan its UPC**) uses that exact name, so they always match.

Codes are stored in one form: a 12-digit UPC-A gets a leading zero (EAN-13), an 8-digit UPC-E is
expanded, and a code whose check digit is wrong is refused with 400, so a misread never becomes an item.
`format` (the scanner's name for the symbology) only matters for 8-digit codes, which can be EAN-8 or UPC-E.

| Variable | |
|---|---|
| `PRODUCT_LOOKUP_URL` | Optional; default `https://world.openfoodfacts.org` (free, no key). Only the barcode number is sent; answers slower than 4 seconds are skipped. Set it empty to turn suggestions off. |

## How it works

**Sign-in.** Sessions use an HttpOnly cookie and are stored in PostgreSQL (Spring Session JDBC, tables from
`V5__sessions.sql`), so a deploy or the host waking from sleep doesn't sign anyone out. CI checks this by
restarting the API mid-test (`ci/session-restart.sh`). CSRF protection uses Spring Security's single-page-app
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

## Deploying to Render (current)

Live at https://pantry-tracker-api-mys0.onrender.com, with a Render PostgreSQL database. The web app
forwards `/api` here, so people use the app's address, not this one.

- **Environment:** `DATABASE_URL` (JDBC URL to the database's internal host), `DATABASE_USERNAME`,
  `DATABASE_PASSWORD`, `COOKIE_SECURE=true`, and `ANTHROPIC_API_KEY` for reading receipts. Render sets `PORT`.
- **Deploys:** CI's last job calls the service's deploy hook after the build, tests, smoke test and
  Docker build all pass on `main`. The hook URL is the `RENDER_DEPLOY_HOOK_URL` repository secret
  (Render service > Settings > Deploy Hook). Without the secret the job just skips with a warning.
- **Free tier:** the service sleeps after about 15 minutes idle and takes a couple of minutes to
  wake; the free database expires 30 days after creation unless upgraded.

## Deploying to AWS

1. **Database:** RDS for PostgreSQL. Pass its JDBC URL and credentials as `DATABASE_URL`,
   `DATABASE_USERNAME` and `DATABASE_PASSWORD` (from Secrets Manager).
2. **API:** build the `Dockerfile`, push to ECR, run on ECS Fargate behind an Application Load
   Balancer. Set `COOKIE_SECURE=true`. Health check path: `/actuator/health`.
3. **Same origin:** the web app's CloudFront distribution forwards `/api/*` to the load balancer
   (see pantry-tracker-web). Keep the load balancer idle timeout above 30 seconds; the API sends a
   heartbeat on the live-updates stream every 25 seconds.
