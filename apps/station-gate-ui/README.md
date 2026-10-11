# Station Gate UI (demo)

Thin demo screen for **AS3-03**: walk one serial along a process path and watch the station skip-ahead gate allow or deny each step. Not a floor deliverable.

MES (`services/mes-api`) is the source of truth. This app only calls:

- `POST /api/station-gate-checks`
- `POST /api/station-completes` (through stations)
- `POST /api/station-quality-results` (quality stations)

## Run locally

```bash
cp .env.example .env   # optional — defaults to http://localhost:8080
npm install
npm run dev            # http://localhost:5173
```

Dev server is pinned to **5173** because mes-api only allows that origin for CORS. Stop `panel-registration-ui` first if it is running.

## Checks

```bash
npm run lint
npm test
npm run build
```
