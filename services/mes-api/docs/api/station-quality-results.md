# API — Append quality PASS/FAIL evidence

**Story:** AS3-04 — Append quality PASS/FAIL evidence (thin writer)  
**Siblings:** [`station-skip-ahead-gate.md`](station-skip-ahead-gate.md) · [`station-completes.md`](station-completes.md)  
**AC SoT:** `a-mes-docs` → `requirements/epic-a/AS3-04-append-quality-pass-fail-evidence.md`  
**Vocab SoT:** `a-mes-docs` → `requirements/tech-notes/18-tech-note-complete-vs-pass-fail-operation-event-epic-a-b.md`  
**Package:** `com.ames.mes_api.stationgate`  
**Status:** Draft contract — path frozen `POST /api/station-quality-results` · AS-4 extends this writer (no second quality endpoint)

Through COMPLETE stays on `/api/station-completes`. Do **not** send PASS/FAIL there.

---

## Endpoint

| | |
|--|--|
| **Method** | `POST` |
| **Path** | `/api/station-quality-results` |
| **Success** | `200 OK` — body for allow **or** plant deny |

---

## Request body

```json
{
  "serialNumber": "PANEL-DEMO-001",
  "equipmentId": "AEL-01-UV",
  "equipmentLocalAt": "2026-08-26T08:15:00",
  "outcome": "PASS"
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `serialNumber` | string | yes | Scanned identity. No `workOrderId` on the wire. |
| `equipmentId` | string | yes | Quality station id (UV · Cold/Hot/Ambient EOL). |
| `equipmentLocalAt` | date-time | yes | Same meaning as COMPLETE. |
| `outcome` | string | yes | **`PASS`** or **`FAIL`** only. |

Missing / blank required fields, or `outcome` not PASS/FAIL → **`400`**.  
Unknown `equipmentId` → **`400`**.  
Plant denies (orphan, wrong station, already-done, through/loader/chamber on this API) → **`200`** + `allowed: false` + `reasonCode`. **No `409`.**

Class fence runs **before** skip-ahead: through / Loader / Chamber → `WRONG_ENDPOINT`.

PASS advances path. FAIL appends and does **not** advance.

---

## Response

Same shape as station-completes (`StationGateResponse`). No request echoes. `outcome` set on allow (`PASS` / `FAIL`).
