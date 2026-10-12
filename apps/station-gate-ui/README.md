# Station Gate UI (demo)

Thin demo screen for **AS3-03**: walk one serial along a process path and watch the station skip-ahead gate allow or deny each step. Not a floor deliverable.

MES (`services/mes-api`) is the source of truth. This app only calls:

- `POST /api/station-gate-checks`
- `POST /api/station-completes` (through stations)
- `POST /api/station-quality-results` (quality stations)

## Current state — in-memory MES stand-in

The screen currently runs on `src/api/fakeClient.ts`, an in-memory stand-in that follows the same gate order and response shape as mes-api. Nothing is sent over the network, and a page refresh resets every serial.

Demo serials (same joins as the mes-api V3 seed; the join already covers PCB Loader):

| Serial | Work order | Process path |
|----|----|----|
| `PANEL-DEMO-001` | `WO-DEMO-001` | `pp_full_eol` — Coating → UV → Depanel → Curing → Assembly → Cold EOL → Hot EOL → Ambient EOL → Packing |
| `PANEL-DEMO-002` | `WO-DEMO-002` | `pp_cold_ambient` — same, without Hot EOL |
| `PANEL-DEMO-003` | `WO-DEMO-003` | `pp_ambient_only` — Coating → UV → Depanel → Curing → Assembly → Ambient EOL → Packing |

Any other serial is denied as "not on any work order".

## Run locally

```bash
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
