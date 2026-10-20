# slots

A time slot scheduler

Single-table design for performance and simpler handling of race conditions.
I was striving for keeping the scope at bay:
- users are plain positive ids
- a calendar is the slots a user owns
- participants are stored but don't see the meeting in their own calendar 

## Running

Everything runs in Docker. The service image is built inside Docker, so no JDK is needed. 

**Plain setup**: PostgreSQL, the service, Prometheus and Grafana, without the load test:

```bash
docker compose up -d --build
docker compose down -v            # deletes data
```

**Load test**: PostgreSQL with 110 connections and the load-test tuning, the service's pool raised to 100, plus k6. The script starts this composition itself:

```bash
./load-test/run-all.sh                                      # every endpoint, 3 min each, 500 users + 100 race interactions/s
DURATION=10s USERS=20 RACE_RATE=10 ./load-test/run-all.sh   # smoke run
docker compose -f compose.yaml -f compose.load-test.yaml up -d --build   # the stack alone, without running the test
```

**Tuned load test**: the same plus PostgreSQL `io_method=io_uring`. The PostgreSQL container runs with `seccomp=unconfined`, and the host kernel must allow io_uring:

```bash
COMPOSITION=tuned-load-test ./load-test/run-all.sh
docker compose -f compose.yaml -f compose.tuned-load-test.yaml up -d --build
```

| | URL |
|---|---|
| API | http://localhost:8080/api/slots |
| Health, metrics | http://localhost:8080/actuator/health, `/actuator/prometheus` |
| Grafana (admin/admin) | http://localhost:3000 |
| Prometheus | http://localhost:9090 |
| PostgreSQL | `localhost:5433/slots`, postgres/postgres |


> **Memory and disk.**
> - **Memory:** plan for about 4 GiB of free RAM:
>   - PostgreSQL is capped at 1 GiB
>   - the service has a fixed 1.5 GiB heap, about 2 GiB in total
>   - Grafana takes about 0.5 GiB
>   - Prometheus takes negligible
>
>   The load tests add k6, which also competes with the service for CPU.
> - **Disk:** the images take about 4 GB on first start, Grafana alone 1.9 GB. PostgreSQL keeps up to 4 GB of WAL in the plain setup. The load-test compositions keep 4 to 16 GB of WAL (`min_wal_size`/`max_wal_size`), so leave about 20 GB free for a full load test. `docker compose down -v` reclaims the data.

## API

All bodies are JSON. Times are ISO-8601 instants, and ranges are half-open `[begin, end)`. Query windows (`from`, `to`) may span at most 31 days.

| Call | Purpose | Success | Errors |
|---|---|---|---|
| `POST /api/slots` | Create a slot | 201 + `Location` | 400, 409 overlap |
| `GET /api/slots/{id}` | Get a slot | 200 | 404 |
| `PUT /api/slots/{id}` | Modify, or mark busy/free | 200 | 400, 404, 409 overlap / holds a meeting / concurrent change |
| `DELETE /api/slots/{id}` | Delete a slot | 204 | 404, 409 holds a meeting / concurrent change |
| `POST /api/slots/{id}/meeting` | Book a free slot as a meeting | 201 + `Location` | 400, 404, 409 busy / already a meeting / concurrent change |
| `PUT /api/slots/{id}/meeting` | Replace the meeting's details | 200 | 400, 404, 409 no meeting / concurrent change |
| `DELETE /api/slots/{id}/meeting` | Cancel the meeting, the slot becomes free | 204 | 404, 409 no meeting / concurrent change |
| `GET /api/slots?userId=&from=&to=[&status=FREE\|BUSY]` | List a user's slots in a window | 200 | 400 |
| `GET /api/slots/block-view?userId=&from=&to=` | Aggregated view: touching slots with the same status merge, gaps are left out | 200 | 400 |

**Create a slot.** `POST /api/slots`, `status` is optional and defaults to `FREE`:

```json
{
  "userId": 1001,
  "begin": "2026-11-02T09:00:00Z",
  "end": "2026-11-02T10:00:00Z",
  "status": "FREE"
}
```

**Modify a slot.** `PUT /api/slots/{id}` replaces all three fields:

```json
{
  "begin": "2026-11-02T10:00:00Z",
  "end": "2026-11-02T11:30:00Z",
  "status": "BUSY"
}
```

**Book or edit a meeting.** `POST` or `PUT /api/slots/{id}/meeting`, the title is required, the description and participants are optional, PUT replaces the whole meeting.:

```json
{
  "title": "Design review",
  "description": "Walk through the API",
  "participants": [1002, 1003]
}
```

**Slot response.** Returned by the create, get, modify, book and edit calls, and as an array by the list call. `meeting` is `null` for a slot without one:

```json
{
  "id": 1,
  "userId": 1001,
  "begin": "2026-11-02T09:00:00Z",
  "end": "2026-11-02T10:00:00Z",
  "status": "BUSY",
  "meeting": {
    "title": "Design review",
    "description": "Walk through the API",
    "participants": [1002, 1003]
  }
}
```

**Block view response:**

```json
[
  { "begin": "2026-11-02T09:00:00Z", "end": "2026-11-02T10:00:00Z", "status": "BUSY" },
  { "begin": "2026-11-02T10:00:00Z", "end": "2026-11-02T11:30:00Z", "status": "FREE" }
]
```

Example call:

```bash
curl -s -X POST http://localhost:8080/api/slots -H 'Content-Type: application/json' \
  -d '{"userId":1001,"begin":"2026-11-02T09:00:00Z","end":"2026-11-02T10:00:00Z"}'
```

## Errors

Errors are RFC 9457 problem details:

```json
{
  "detail": "Slot 1 holds a meeting: cancel the meeting first",
  "instance": "/api/slots/1",
  "status": 409,
  "title": "Conflict"
}
```

| Status | When | Example `detail` |
|---|---|---|
| 400 | Missing, malformed or invalid field or parameter, e.g. a non-positive `userId` | `Invalid request content.` (body), `Validation failure` (query), `Failed to convert 'status' with value: 'busy'` |
| 400 | `end` not after `begin` | `'end' must be after 'begin'` |
| 400 | Query window over 31 days | `The window must be at most 31 days` |
| 400 | Invalid meeting: blank or too long title (max 255), too long description (max 1000), non-positive or null participant | `'title' must not be blank`, `'participants' must be positive user ids`, `'participants' must not contain null`|
| 404 | Unknown slot | `Slot 42 not found` |
| 409 | Overlaps another slot of the same user | `Slot overlaps another slot of user 1001` |
| 409 | Booking a busy slot or one that already holds a meeting | `Slot 1 is busy`, `Slot 1 already holds a meeting` |
| 409 | Moving, freeing or deleting a slot that holds a meeting | `Slot 1 holds a meeting: cancel the meeting first` |
| 409 | Editing or cancelling a meeting that doesn't exist | `Slot 1 holds no meeting` |
| 409 | Lost a race to a concurrent change, retry the request | `Slot 1 was changed concurrently: retry` |
