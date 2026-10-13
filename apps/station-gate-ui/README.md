# Station Gate UI (demo)

Thin demo screen for **AS3-03**: walk one serial along a process path and watch the station skip-ahead gate allow or deny each step. Not a floor deliverable.

MES (`services/mes-api`) is the source of truth. This app only calls:

- `POST /api/station-gate-checks`
- `POST /api/station-completes` (through stations)
- `POST /api/station-quality-results` (quality stations)

Base URL comes from `VITE_MES_API_BASE_URL` (default `http://localhost:8080`).

## Run locally against mes-api in IntelliJ

1. **MySQL on `127.0.0.1:3306`.** For the K3s dev database:

   ```bash
   kubectl port-forward -n ames-dev svc/ames-mysql 3306:3306
   ```

2. **mes-api in IntelliJ** with environment variables `SPRING_PROFILES_ACTIVE=local` and `MYSQL_PASSWORD=<value from mysql-secret>`. It listens on `http://localhost:8080`, and the `local` profile allows CORS from `http://localhost:5173`.

3. **This app:**

   ```bash
   cp .env.example .env   # optional — defaults to http://localhost:8080
   npm install
   npm run dev            # http://localhost:5173
   ```

Dev server is pinned to **5173** because mes-api only allows that origin for CORS. Stop `panel-registration-ui` first if it is running.

## Run locally against mes-api on K3s (port-forward)

Use local port **18080** so it is never confused with mes-api in IntelliJ on 8080. The K3s CORS ConfigMap already allows `http://localhost:5173`.

```bash
kubectl -n ames-dev port-forward svc/ames-mes-api 18080:8080   # keep this terminal open
VITE_MES_API_BASE_URL=http://localhost:18080 npm run dev        # second terminal
```

Writes go to the K3s dev MySQL. PASS/FAIL needs a mes-api image that includes AS3-04 (`POST /api/station-quality-results`); an older image answers 404, which the screen shows as "Request failed".

## Demo serials

Seeded by mes-api migration V3; the join already covers PCB Loader:

| Serial | Work order | Process path |
|----|----|----|
| `PANEL-DEMO-001` | `WO-DEMO-001` | `pp_full_eol` — Coating → UV → Depanel → Curing → Assembly → Cold EOL → Hot EOL → Ambient EOL → Packing |
| `PANEL-DEMO-002` | `WO-DEMO-002` | `pp_cold_ambient` — same, without Hot EOL |
| `PANEL-DEMO-003` | `WO-DEMO-003` | `pp_ambient_only` — Coating → UV → Depanel → Curing → Assembly → Ambient EOL → Packing |

COMPLETE / PASS / FAIL are saved in MES (`operation_event`), so progress survives a page refresh and a serial walked to Packing stays done. For a fresh serial, register a new panel on one of these work orders with `panel-registration-ui`, then switch back to this app.

## Checks

```bash
npm run lint
npm test
npm run build
```

`src/test/fakeStationGateClient.ts` is an in-memory double used only by screen tests; the running app always calls mes-api.
