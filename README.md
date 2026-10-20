# serverping

A small HTTP API that watches TCP services and tells you whether they are accepting
connections — and how long they take to answer.

Built with **Java 17** and **Spring Boot 4.0.6**, backed by **PostgreSQL 17**, with
**Flyway** owning the schema.

## Quick start

Two ways in. Docker Compose is the shorter one and needs nothing installed but Docker.

### With Docker Compose

```bash
git clone https://github.com/hariraja-07/serverping.git   # private: `gh repo clone`, or a PAT in the URL
cd serverping
cp .env.example .env        # defaults are fine for local use
docker compose up -d
curl localhost:8080/actuator/health
```

That starts PostgreSQL and the service, waits for the database to pass `pg_isready` before
starting the app, and applies migrations on boot. `application.properties` binds to
`127.0.0.1`, but the image deliberately sets `SERVER_ADDRESS=0.0.0.0` — inside a container
loopback would mean Docker's published port reaches nothing. The container boundary is the
isolation here, so **do not publish 8080 to the internet**: there is no authentication.

Prefer the published image over building from source?

```bash
docker pull ghcr.io/hariraja-07/serverping:latest
docker compose up -d
```

(`up -d` builds rather than pulls when `build:` is present and no image exists locally, so
pull first if you want the prebuilt one.)

Deploying to a machine that will never build the source at all — the compose file, the
registry login and why the port stays on loopback: [Deploying to a server](#deploying-to-a-server).

### Running from source

PostgreSQL has to be reachable first — the containerised database is the easy way:

```bash
docker compose up -d db
./mvnw spring-boot:run
```

Then, from another terminal:

```bash
curl -X POST localhost:8080/api/v1/targets \
  -H 'Content-Type: application/json' \
  -d '{"name":"GitHub","host":"api.github.com","port":443}'

curl -X POST localhost:8080/api/v1/targets/1/check
# {"checkId":1,"targetId":1,"status":"UP","latencyMs":144,"detail":null,"checkedAt":"..."}
```

Register a target once and a background scheduler keeps checking it every minute — the
`/check` endpoint is for when you want an answer *now* rather than in a minute.

---

## Endpoints

All under `/api/v1`.

| Method | Path | Does | Success |
|---|---|---|---|
| `POST` | `/targets` | Register a host to watch | `201` + `Location` |
| `GET` | `/targets` | List them all (`?status=DOWN` filters) | `200` |
| `GET` | `/targets/{id}` | One target and its current state | `200` / `404` |
| `PUT` | `/targets/{id}` | Replace its configuration | `200` / `404` |
| `DELETE` | `/targets/{id}` | Stop watching it | `204` / `404` |
| `POST` | `/targets/{id}/check` | Probe **now**, synchronously | `200` / `404` |
| `GET` | `/targets/{id}/checks` | Recent history, newest first | `200` / `404` |
| `GET` | `/summary` | `{total,up,down,unknown,avgLatencyMs}` | `200` |
| `GET` | `/actuator/health` | Is *serverping itself* healthy | `200` |

### Register

```bash
curl -X POST localhost:8080/api/v1/targets -H 'Content-Type: application/json' -d '
  {"name":"Production API","host":"api.example.com","port":443,
   "intervalSeconds":60,"enabled":true}'
```

Only `name`, `host` and `port` are required. Omitted fields default to
`protocol: TCP`, `intervalSeconds: 60`, `enabled: true`.

```json
{
  "id": 1, "name": "Production API", "host": "api.example.com", "port": 443,
  "protocol": "TCP", "intervalSeconds": 60, "enabled": true,
  "status": "UNKNOWN", "latencyMs": null, "lastCheckedAt": null,
  "consecutiveFails": 0, "createdAt": "2026-10-19T12:59:35.509Z"
}
```

`status` starts as `UNKNOWN` — nothing has been probed yet.

### Probe now

```bash
curl -X POST localhost:8080/api/v1/targets/1/check
```

```json
{"checkId":4,"targetId":1,"status":"UP","latencyMs":144,"detail":null,
 "checkedAt":"2026-10-19T13:12:54.571Z"}
```

Synchronous, and bounded by `app.check-timeout-ms` (2 s), so the worst case is a 2-second
response rather than a hung connection.

### History

```bash
curl 'localhost:8080/api/v1/targets/1/checks?limit=50'   # 1..500, default 100
```

Returns the same shape as a single check result, newest first. `limit` outside the range is
clamped rather than rejected — a bad page size should not fail an otherwise valid read.

### Summary

```bash
curl localhost:8080/api/v1/summary
{"total":3,"up":1,"down":0,"unknown":2,"avgLatencyMs":192}
```

`avgLatencyMs` is `null` until something has been checked, not `0` — "never measured" must not
read as "instant".

---

## Outcomes

A check does not produce a boolean. It produces one of five results:

| `status` | `detail` | Meaning |
|---|---|---|
| `UP` | *(null)* | Handshake completed — host up **and** service listening |
| `DOWN` | `REFUSED` | Host answered: nothing is listening on that port |
| `DOWN` | `TIMEOUT` | Nothing answered within the budget — host down or firewall dropping |
| `DOWN` | `DNS_FAILURE` | The name does not resolve |
| `DOWN` | `BLOCKED` | Resolved to a private/reserved address, so we refused to connect |

`REFUSED` versus `TIMEOUT` is the useful distinction: the first says *your service is off*,
the second says *we never reached your network at all*.

### Why TCP and not `ping`

ICMP (what `ping` uses) is a different protocol, and many production hosts drop it entirely —
AWS security groups, Cloudflare, hardened servers. `ping` will report a perfectly healthy
service as unreachable. The JVM also has **no ICMP API at all**: a real ping means shelling out
to the system binary, which needs `CAP_NET_RAW` and does not exist in most container images.

A TCP connect is unprivileged, JVM-native, and answers the question that actually matters —
*is the service accepting connections?* It does **not** tell you whether an HTTP endpoint
returns 500 or whether a certificate is about to expire; that is a future `protocol: HTTP`.

---

## Errors

Every failure is RFC 9457 `application/problem+json`:

```json
{"type":"about:blank","title":"Target Not Found","status":404,
 "detail":"No target with id 999","instance":"/api/v1/targets/999"}
```

The status code follows one rule:

- **`400`** — malformed or mis-typed. Bad JSON, a blank name, `port: 0`, `?status=BOGUS`.
  Validation failures carry a per-field map:
  `{"errors":{"name":"name must not be blank"}}`
- **`404`** — well-formed, but no such thing.
- **`422`** — understood and deliberately refused by our own rules. Currently only the
  SSRF guard:

```bash
curl -X POST localhost:8080/api/v1/targets -H 'Content-Type: application/json' \
  -d '{"name":"Meta","host":"169.254.169.254","port":80}'
# 422 {"title":"Invalid Target","detail":"Host '169.254.169.254' resolves to a private or reserved address: 169.254.169.254"}
```

---

## Security notes

**The SSRF guard refuses hosts that resolve to private or reserved addresses** — loopback,
RFC1918 (`10/8`, `172.16/12`, `192.168/16`), link-local (`169.254.169.254`, the cloud metadata
endpoint), CGNAT, documentation and benchmarking ranges, reserved IPv4, and IPv6 ULA/link-local.
Without it, anyone who could reach the API could make your server probe your internal network or
steal cloud credentials.

The address is **validated once, then pinned**: the hostname resolves to an `InetAddress`, that
address is checked, and the connection goes to *that* address rather than resolving again.
Re-resolving at connect time reopens a DNS-rebinding hole.

Two deliberate limitations:

1. **Validation happens at registration.** A name that resolves publicly *now* and privately
   *later* could slip through — closing that fully means pinning on every check (partially done)
   plus a re-check of every answer in a multi-record response.
2. **An unresolvable name is accepted.** A monitoring service must let you watch a host that is
   currently down; the checker records `DNS_FAILURE` instead.

**The API binds to `127.0.0.1` by default** (`server.address`), so it is not network-reachable
until you deliberately open it. There is **no authentication** yet: set `server.address=0.0.0.0`
only alongside a reverse proxy, a firewall, or a tunnel.

The Docker image is the exception — it sets `ENV SERVER_ADDRESS=0.0.0.0`, because a container
bound to loopback makes Docker's published port unreachable with no error anywhere, just
`connection refused` from the host. Inside a container the container boundary is the
isolation, which is fine on a single machine and is **not** a reason to publish 8080 to the
internet.

---

## Configuration

`src/main/resources/application.properties`:

| Property | Default | What it does |
|---|---|---|
| `server.port` | `8080` | HTTP port |
| `server.address` | `127.0.0.1` | Bind address — loopback until you expose it |
| `app.check-timeout-ms` | `2000` | Hard cap on one TCP connect |
| `app.check-interval` | `60000` | Background sweep interval |
| `app.check-initial-delay` | `5000` | Delay before the first sweep |
| `app.scheduler.enabled` | `true` | Turn background probing off entirely |
| `app.retention.enabled` | `true` | Turn background pruning off entirely |
| `app.retention.days` | `30` | Delete check history older than this. `0` or less keeps everything |
| `app.retention.interval-ms` | `3600000` | How often the prune runs (hourly) |
| `app.retention.initial-delay-ms` | `60000` | Grace period before the first prune, so migrations land first |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/serverping` | Where targets and history live |
| `spring.datasource.username` / `.password` | `serverping` / `serverping` | Database credentials |
| `spring.jpa.hibernate.ddl-auto` | `validate` | Hibernate only *checks* the schema — Flyway owns it |
| `spring.flyway.enabled` | `true` (default) | Runs `src/main/resources/db/migration` on boot |

Override anything on the command line:

```bash
java -jar target/serverping-0.1.1.jar \
  --server.port=9090 --app.check-interval=15000
```

`fixedDelay` counts from the **end** of a sweep, so with `N` targets each allowed
`app.check-timeout-ms`, a real cycle is `interval + sweep duration`.

---

## Build and test

```bash
./mvnw clean test     # 48 tests — in-memory H2, nothing external needed
./mvnw package        # target/serverping-0.1.1.jar (executable, self-contained)
docker compose up -d db                                  # PostgreSQL for the app
java -jar target/serverping-0.1.1.jar
```

Only `clean test` and `package` work on their own. Running the application needs a reachable
PostgreSQL, because `application.properties` points at `localhost:5432` — `docker compose up
-d db` is the quickest way to have one.

Tests run against in-memory H2 (`src/test/resources/application.properties`) with the scheduler
disabled, so a background sweep cannot race an assertion. The SSRF policy is substituted in
probe unit tests — production refuses loopback, which is exactly what a local test listener
binds to.

---

## Docker

```bash
docker compose up -d --build   # build the image and start everything
docker compose ps              # both services should report healthy
docker compose logs -f serverping
```

`docker-compose.yml` defines two services:

| Service | Image | Purpose |
|---|---|---|
| `db` | `postgres:17` | The datastore, on a named volume |
| `serverping` | built from `Dockerfile` | The API, started only once `db` reports healthy |

Credentials come from `.env` (copy `.env.example`), and `docker compose` reads it
automatically. `.env` is gitignored — `.env.example` is what gets committed, so keep only
placeholders there. The same file also publishes the host ports:

| Variable | Default | Meaning |
|---|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `serverping` ×3 | Database name and credentials |
| `SERVER_PORT` | `8080` | Host port the API is published on |
| `POSTGRES_PORT` | `5432` | Host port for the database, **bound to `127.0.0.1` only** |

The app is passed `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD`, which Spring's relaxed
binding maps onto `spring.datasource.*`. The image therefore never has to know which database
it is pointed at.

### Your data is in a volume, not in the container

`db` stores everything in the named volume `serverping_pgdata`. This is the distinction that
matters most:

```bash
docker compose down      # stops and deletes the containers — your DATA SURVIVES
docker compose up -d     # fresh containers, same data, schema already migrated
docker compose down -v   # DESTRUCTIVE: -v removes the volume. This is the delete command.
```

`docker compose down` destroys the containers' writable layers, so anything stored inside a
container is lost with it. That is exactly why the database uses a volume — it is the part
that is supposed to survive. `down -v` is the command that deliberately does not.

The project name is pinned with `name: serverping` at the top of the file. Without it Compose
falls back to the directory name, so cloning the repo to a different folder would silently
start an *empty* database while your real data sat untouched in an orphaned volume.

### The image itself

- `eclipse-temurin:17-jre-jammy` — **glibc, not Alpine**. serverping resolves hostnames for a
  living, and musl's resolver handles search domains and `ndots` differently from glibc. For
  a network monitor, ~80 MB is not worth occasionally not finding a host.
- Runs as a non-root user (`serverping`, uid 1001). Nothing here needs a capability — TCP
  connects to high ports are unprivileged.
- No data directory in the image at all. Persistence moved to PostgreSQL, so the
  root-owned-volume question that usually comes with a database container does not arise.
- `-XX:MaxRAMPercentage=75` instead of a fixed `-Xmx`, so the JVM sizes its heap against the
  cgroup rather than the whole machine and getting OOM-killed at random.
- A `HEALTHCHECK` that hits `/actuator/health` with `curl`, which gives a real verdict rather
  than merely noticing that a socket is open.

### Published image

Every push to `main` builds and publishes to GitHub Container Registry:

```bash
docker pull ghcr.io/hariraja-07/serverping:latest
```

Tags are event-derived: a push to `main` publishes `main` and `sha-<short>`; a release tag
`v0.1.1` publishes `0.1.1`, `0.1`, `v0.1.1` and `latest`. Note that `latest` follows the
*release*, not the branch — with `latest=auto` a branch build deliberately does not move it,
so pointing consumers at `latest` always gets the last tagged release rather than an
arbitrary commit. `.github/workflows/docker.yml` gates the publish behind the test job, so a
commit that breaks the test suite can never reach the registry.

---

## Deploying to a server

`docker pull` gets the app image and nothing else. Postgres is a separate image with its own
release cadence — bundling it would mean republishing serverping every time Postgres patches
a CVE — so an image alone has never been enough to start a stack. The compose file is the
other half, and a machine that will never compile anything should not have to clone a
repository full of source code just to obtain it.

Every release carries the two files a deployed box needs. On a fresh server with Docker
installed:

```bash
# one token does both jobs: `repo` reads this private release, `read:packages` pulls the private image
export GH_TOKEN=<PAT with repo and read:packages>

gh release download -R hariraja-07/serverping --clobber \
  -p docker-compose.prod.yml -p env.example

cp env.example .env && nano .env    # a real POSTGRES_PASSWORD, before anything is reachable
echo "$GH_TOKEN" | docker login ghcr.io -u hariraja-07 --password-stdin

docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml ps      # both services healthy
curl localhost:8080/actuator/health
```

No clone, no Maven, no Dockerfile — a token, two files and a pull.

The credentials template is `env.example` on the release rather than `.env.example`, because
GitHub will not keep a leading dot on a release asset: the upload is silently stored as
`default.env.example`, and a download asking for `.env.example` matches nothing *and still
exits 0*. The bytes are identical to the file in the repository; only the name differs. One
consequence worth knowing: `--clobber` above replaces the two files this release shipped with
and nothing else, so re-running the download cannot overwrite your `.env`.

Without the GitHub CLI the same two files come from the release directly:

```bash
curl -fsSL -H "Authorization: Bearer $GH_TOKEN" -L \
  https://github.com/hariraja-07/serverping/releases/latest/download/docker-compose.prod.yml \
  -o docker-compose.prod.yml     # and the same path with env.example for the template
```

`docker-compose.prod.yml` is not the dev file with `build:` deleted:

| | `docker-compose.yml` (dev) | `docker-compose.prod.yml` (server) |
|---|---|---|
| app image | `build: .` | `image:` from GHCR — there is no Dockerfile on the box |
| API port | `0.0.0.0:8080` | `127.0.0.1:8080` |
| Postgres port | `127.0.0.1:5432` | published to no host at all |

Both port differences exist for one reason: **the service has no authentication.** Reach it
over SSH instead of opening it up:

```bash
ssh -L 8080:127.0.0.1:8080 user@server   # then curl localhost:8080 from your laptop
sudo ufw allow OpenSSH && sudo ufw enable # and deliberately never `ufw allow 8080`
```

Putting a reverse proxy in front is what makes it reachable *on purpose* — which needs
authentication first, and that is why it is still listed under "Not built yet".

Both files pin `name: serverping`, so they share one `serverping_pgdata` volume and both want
port 8080: alternative modes of a single stack, never two stacks. Stop one before starting the
other, and the data follows you either way.

### Updating and operating

```bash
cd /opt/serverping
docker compose -f docker-compose.prod.yml logs -f serverping   # tail

# `latest` moves only when a release is cut, so pull is how a new one is picked up
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d

docker compose -f docker-compose.prod.yml down                 # stop — the data survives
docker compose -f docker-compose.prod.yml down -v              # deletes every row of history
docker compose -f docker-compose.prod.yml exec db \
  pg_dump -U serverping serverping > backup-$(date +%F).sql
```

---

## Database and migrations

Flyway owns the schema; Hibernate only checks that what Flyway built still matches the
entities (`ddl-auto=validate`). A mismatch therefore fails at startup instead of surfacing
later as a broken query against a column that moved.

Migrations live in `src/main/resources/db/migration` and run automatically on boot:

```
V1__init.sql              target + target_check
V2__history_indexes.sql   indexes for history reads and for retention deletes
```

`V1__init.sql` was not hand-written. Hibernate was pointed at an empty PostgreSQL with
`ddl-auto=update`, `pg_dump --schema-only` captured the DDL it produced, and those statements
were transcribed. Guessing whether an `Instant` maps to `timestamp(6)` or
`timestamp(6) with time zone` is exactly how you end up debugging a type mismatch at startup
instead of shipping.

To change the schema, add a new file — never edit an applied one, because Flyway checks
checksums and will refuse to start:

```bash
touch src/main/resources/db/migration/V3__add_something.sql
```

Version, then two underscores, then a description. Flyway applies it on the next boot.

Note there is **no foreign key** from `target_check.target_id` to `target.id`: history is meant
to outlive the target it belongs to, so deleting a target must not delete its check rows.

### Retention

History would otherwise grow forever: every sweep appends one row per enabled target, which at
the default 60s interval is **1,440 rows per target per day** — around half a million a year.

`RetentionSweeper` deletes rows older than `app.retention.days` (default 30) in the background,
hourly. Each delete is a range scan against `idx_target_check_checked_at`, so it stays small and
the table never drifts far from its steady-state size. A daily job would do the same total work
in one spike and let the table grow an extra day in between. An hourly log line saying "deleted
0 rows" would tell nobody anything, so it stays silent when there is nothing to do:

```
INFO RetentionSweeper : Pruned 1 check row(s) older than 30 day(s)
   (before 2026-09-19T17:39:52Z)
```

Set `app.retention.days=0`, or `app.retention.enabled=false`, to keep everything forever — that
is an explicit supported setting, not a misconfiguration that quietly looks like the feature is
broken. Because there is no foreign key, retention is also the **only** thing that removes rows:
an orphaned history belonging to a deleted target is still pruned.

The second index in `V2` exists for this. Without it each prune would be a full scan of the
very table it is trying to keep small.

### Backing up and restoring

```bash
# backup
docker compose exec db pg_dump -U serverping serverping > backup.sql

# restore
docker compose exec -T db psql -U serverping serverping < backup.sql
```

To move the data somewhere else entirely, `docker compose down -v` and point a fresh volume
at a restored dump. The schema comes from the dump *and* from Flyway — Flyway sees the
`flyway_schema_history` table in the dump and leaves it alone.

### Tests do not touch PostgreSQL

The test suite runs on in-memory H2 with Flyway disabled
(`src/test/resources/application.properties`). H2 is `<scope>test</scope>` in `pom.xml`,
nothing external has to be running, and the CI test job stays dependency-free — which is the
reason it completes in well under a minute.

---

## Layout

```
Dockerfile                       multi-stage: maven build -> JRE runtime
docker-compose.yml               db + serverping, named volume `pgdata`
docker-compose.prod.yml          same stack, image pulled from GHCR, loopback only
.env.example                     credentials template (.env is gitignored)
.github/workflows/docker.yml     test -> build -> publish to GHCR (+ compose files on a release)

src/main/resources/
  application.properties         Postgres, Flyway on, ddl-auto=validate
  db/migration/V1__init.sql      target + target_check
  db/migration/V2__history_indexes.sql   indexes for history reads and retention

src/main/java/com/serverping/
  ServerpingApplication.java     entry point, @EnableScheduling
  ApiExceptionHandler.java       every failure -> problem+json
  target/
    Target.java                  entity: config you set + state we observe
    TargetRepository.java        Spring Data
    TargetRequest/Response.java  wire records
    TargetService.java           CRUD, checkNow, history, summary
    TargetController.java        endpoints 1-5
    TargetValidator.java         SSRF guard
    CheckService.java            TCP probe: resolve -> validate -> pin -> connect
    CheckResult.java             UP / REFUSED / TIMEOUT / DNS_FAILURE / BLOCKED
    Check.java                   one row per probe (survives restart)
    CheckRepository.java
    CheckController.java         /check and /checks
    SummaryController.java       /summary
    Scheduler.java               background sweep
    RetentionSweeper.java        prunes history older than app.retention.days
    Status.java, Protocol.java   UP|DOWN|UNKNOWN, TCP
```

---

## Not built yet

- **Authentication** — the API is unauthenticated; keep it on loopback or behind a proxy.
- **`protocol: HTTP`** — status-code and TLS-certificate checks.

## License

Unlicensed / personal project.
