# serverping

A small HTTP API that watches TCP services and tells you whether they are accepting
connections — and how long they take to answer.

Built with **Java 17** and **Spring Boot 4.0.6**.

```bash
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
| `spring.datasource.url` | `jdbc:h2:file:./data/serverping` | Where targets and history live |

Override anything on the command line:

```bash
java -jar target/serverping-0.0.1-SNAPSHOT.jar \
  --server.port=9090 --app.check-interval=15000
```

`fixedDelay` counts from the **end** of a sweep, so with `N` targets each allowed
`app.check-timeout-ms`, a real cycle is `interval + sweep duration`.

---

## Build and test

```bash
./mvnw clean test     # 43 tests
./mvnw package        # target/serverping-0.0.1-SNAPSHOT.jar (executable, self-contained)
java -jar target/serverping-0.0.1-SNAPSHOT.jar
```

Tests run against in-memory H2 (`src/test/resources/application.properties`) with the scheduler
disabled, so a background sweep cannot race an assertion. The SSRF policy is substituted in
probe unit tests — production refuses loopback, which is exactly what a local test listener
binds to.

---

## Layout

```
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
    Status.java, Protocol.java   UP|DOWN|UNKNOWN, TCP
```

---

## Not built yet

- **Authentication** — the API is unauthenticated; keep it on loopback or behind a proxy.
- **`protocol: HTTP`** — status-code and TLS-certificate checks.
- **Retention** — history grows forever; nothing prunes old `target_check` rows.
- **Container image** — no `Dockerfile` yet.

## License

Unlicensed / personal project.
