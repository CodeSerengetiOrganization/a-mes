# API — Station skip-ahead gate (scan check + COMPLETE)

**Story:** AS3-tech — Station skip-ahead gate  
**Parent:** AS-3 — Stop skip-ahead at stations and Packing  
**AC SoT:** `a-mes-docs` → `requirements/epic-a/AS3-stop-skip-ahead-at-stations-and-packing.md`  
**Evidence direction:** `a-mes-docs` → `requirements/tech-notes/14-tech-note-as3-path-cursor-immutable-events-vs-state-machine.md`  
**Package (proposed):** `com.ames.mes_api.stationgate`  
**Status:** **Stage-1 contract freeze** (2026-08-16) — Kai picks applied by Sam · no OpenAPI yet

Two verbs (same gate rules): **check** (scan / allow?) then **COMPLETE** (advance + append evidence). Do not merge into one vague “do everything” call.

---

## Shared request fields

Both endpoints take the same core body:

```json
{
  "serialNumber": "UNIT-DEMO-001",
  "equipmentId": "AEL-01-COAT"
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `serialNumber` | string | yes | Scanned identity — **panel** (pre-depanel / Loader path) or **unit serial** (post–AS-5 or seeded). Trim before lookup. |
| `equipmentId` | string | yes | Which station client is calling (kiosk / config). Maps to a through **op** on the process path (e.g. `AEL-01-COAT` → `COAT`). Not inferred from client IP. |

Client does **not** send “what I think next is.” MES owns next from path + progress.

`workOrderId` is **not** required on the request — resolve via join (`panel_registration` / unit→WO). Orphan = no join.

---

## HTTP status rules (frozen — Kai)

| Case | Status |
|------|--------|
| Allow or plant gate deny (orphan / wrong station / already COMPLETE) | **`200 OK`** + body (`allowed` true/false) |
| Missing / blank fields; unknown `equipmentId` (stage 1) | **`400`** |
| COMPLETE append hit **DB integrity race** (e.g. unique constraint after soft allow) | **`409`** — Panel Registration pattern; **not** for normal gate denies |

Plant denies are successful gate **decisions**, not HTTP conflicts. Clients always read `allowed` / `reasonCode` / `expectedNext` on `200`.

---

## 1) Check (scan gate)

| | |
|--|--|
| **Method** | `POST` |
| **Path** | `/api/station-gate-checks` |
| **Success** | `200 OK` — body always returned for allow **or** deny |

### Gate order (service)

1. Validate body (blank → `400`)  
2. Resolve identity → work order (no join → **orphan**, `allowed: false`, `reasonCode: ORPHAN`)  
3. Resolve `equipmentId` → this station’s **op** (unknown id → `400` stage 1)  
4. Load process path + **current next**  
5. If this op **already COMPLETE’d** for this serial → `allowed: false`, `reasonCode: ALREADY_COMPLETE` (UI can block before work; do not leave this to COMPLETE-only)  
6. If this op ≠ next → **wrong station** (`allowed: false`, `expectedNext` = next, `reasonCode: WRONG_STATION`)  
7. Else → `allowed: true` (`expectedNext` = this op / next, `reasonCode: null`)

Check does **not** append COMPLETE and does **not** advance next.

### Response body (`200`)

```json
{
  "allowed": false,
  "serialNumber": "UNIT-DEMO-001",
  "equipmentId": "AEL-01-ASM",
  "workOrderId": "WO-DEMO-001",
  "processPathId": "pp_cold_ambient",
  "thisOp": "ASM",
  "expectedNext": "COAT",
  "reasonCode": "WRONG_STATION",
  "message": "Expected next: COAT"
}
```

| Field | Type | Notes |
|-------|------|-------|
| `allowed` | boolean | `true` = may work / may COMPLETE here; `false` = hard-block |
| `serialNumber` | string | Echo |
| `equipmentId` | string | Echo |
| `workOrderId` | string \| null | Set when join found; `null` on orphan |
| `processPathId` | string \| null | From `wo_pp_binding` when known |
| `thisOp` | string \| null | Op implied by `equipmentId` |
| `expectedNext` | string \| null | Path **next** op code (e.g. `COAT`, `PACK`). Required on wrong-station deny (AC). On `ALREADY_COMPLETE`, always return **current path next** (where the unit should go next) — frozen, not left to the PR. |
| `reasonCode` | string \| null | See table below; `null` when `allowed: true` |
| `message` | string \| null | Short plant-facing text |

### `reasonCode` (check)

| Code | When |
|------|------|
| `ORPHAN` | Serial not joined to any work order |
| `WRONG_STATION` | Join OK but this op ≠ current next |
| `ALREADY_COMPLETE` | This op already COMPLETE’d for this serial — **required on check** (Kai freeze) |

### Error responses (check)

| Status | When |
|--------|------|
| `400` | Missing / blank `serialNumber` or `equipmentId`; unknown `equipmentId` (stage 1) |
| `200` + `allowed: false` | Orphan / wrong station / already COMPLETE — **not** silent allow; **not** `409` |

---

## 2) COMPLETE (through-station success)

| | |
|--|--|
| **Method** | `POST` |
| **Path** | `/api/station-completes` |
| **Success** | `200 OK` |

Same request body as check. Re-runs the **same gate**; only on allow:

1. Append **immutable** through COMPLETE evidence (tech note 14) — **not** quality `PASS`  
2. Advance **next** (cursor cache and/or derive-from-events)  
3. Reject second COMPLETE at same op (`ALREADY_COMPLETE`) — next must **not** advance again  

QUALITY PASS / FAIL for EOL is **AS-4** — out of this contract.

### Response body (success — `200`)

```json
{
  "allowed": true,
  "serialNumber": "UNIT-DEMO-001",
  "equipmentId": "AEL-01-COAT",
  "workOrderId": "WO-DEMO-001",
  "processPathId": "pp_cold_ambient",
  "thisOp": "COAT",
  "expectedNext": "UV",
  "reasonCode": null,
  "message": null,
  "outcome": "COMPLETE"
}
```

| Field | Type | Notes |
|-------|------|-------|
| *(same as check)* | | After success, `expectedNext` is the **following** through/quality step on the path |
| `outcome` | string | Always `COMPLETE` on success — never `PASS` for through stations |

### Response body (plant deny — `200`, same shape as check)

Gate denies use **`200` + `allowed: false`** (same as check) — orphan / wrong station / already COMPLETE. Do **not** use `409` for these.

| `reasonCode` | When |
|--------------|------|
| `ORPHAN` | No WO join |
| `WRONG_STATION` | This op ≠ next (e.g. Pack while Ambient still next) |
| `ALREADY_COMPLETE` | This op already COMPLETE’d for this serial |

On successful deny body, omit `outcome` or set it null — only success carries `outcome: COMPLETE`.

### Error responses (COMPLETE)

| Status | When |
|--------|------|
| `400` | Missing / blank fields; unknown `equipmentId` |
| `200` + `allowed: false` | Plant gate deny (orphan / wrong station / already COMPLETE) |
| `409` | Soft gate passed (or race) but **DB integrity** failed on append — e.g. unique COMPLETE constraint / double-submit race. Map like Panel Registration; do **not** re-query in the same TX after failed INSERT. Prefer body with `reasonCode: ALREADY_COMPLETE` when that was the race. |

---

## Persist (constraints — schema in PR)

| Concern | Direction |
|---------|-----------|
| Orphan / join | Read `panel_registration` (and later unit join / seed) — **do not** write COMPLETE here |
| Evidence SoT | Append-only through COMPLETE row (identity, op, `equipment_id`, time, outcome=`COMPLETE`) |
| Next | Derive from path + events **or** thin cursor updated only from COMPLETE |
| COMPLETE ≠ PASS | Never store through success as quality `PASS` |

Exact table/column names: **AS3-tech PR** + update [`../schema.md`](../schema.md).

---

## Loader bootstrap (frozen — stage 1)

**Option A:** Successful **Panel Registration** (`POST /api/panel-registrations`) means **`LOADER` is satisfied**. Do **not** require a separate `POST /api/station-completes` for `LOADER`.

| After join | First gate **next** |
|------------|---------------------|
| Panel on WO with path | **`COAT`** (second op on AS-1 seeded paths) |

Operators must not COMPLETE Loader twice. Cursor / evidence init in the AS3-tech PR must honor this (append a LOADER COMPLETE on join, or seed next=`COAT` without a LOADER COMPLETE row — pick one persist style in the PR; behavior is Option A either way).

---

## Op codes vs `equipmentId` (stage 1)

Path JSON uses stable **op codes** (`LOADER`, `COAT`, `ASM`, `PACK`, …) from AS-1 seed.  
`equipmentId` values (e.g. `AEL-01-COAT`) come from the enablement list / test doubles until the bind ticket lands.

Contract assumes a **server-side map** `equipmentId → op_code`. Mapping table freeze is enablement / PR — not Story AC.

---

## Out of this contract

| Out | Where |
|-----|--------|
| Panel Registration join | `/api/panel-registrations` (AS-2) |
| EOL next-op / QUALITY PASS | AS-4 |
| OpenAPI / Swagger | Optional later |
| Soft warning / silent allow on wrong station | Forbidden (AS-3) |
| `409` for normal plant gate denies | Forbidden — use `200` + `allowed: false` |

---

## AC → contract map (for PR ship bar)

| AS-3 / AS3-tech AC | Primary call |
|--------------------|--------------|
| 1 Wrong station + expected next | `POST .../station-gate-checks` → `200`, `allowed:false`, `expectedNext` |
| 2 Allow + COMPLETE advances | check allow → `POST .../station-completes` → `200`, new `expectedNext` |
| 3 COMPLETE ≠ PASS | `outcome: COMPLETE` only; persist rule |
| 4 Double COMPLETE rejected | check and/or COMPLETE → `200`, `ALREADY_COMPLETE` |
| 5–6 Pack deny / allow when next | same gate; Pack `equipmentId` |
| 7 Orphan | either call → `200`, `ORPHAN` |
| 8 DoD-7 | Do not claim ERP WO product in API docs/UI copy |

---

## Review

| Role | Status |
|------|--------|
| Sam (draft) | **Done (2026-08-16)** |
| Jonathan revise | **Good enough (2026-08-16)** |
| Kai approve (freeze) | **Re-review OK (2026-08-16)** — picks applied; residual notes below (non-blocking / sharpen in PR or tiny md fix) |
| Robert (SoT / naming) | **SoT OK (2026-08-16)** |
| Sam (Kai picks into md) | **Done (2026-08-16)** — status → stage-1 contract freeze |

### Kai re-review notes (2026-08-16)

| # | Severity | Note |
|---|----------|------|
| 1 | Sharpen | On `ALREADY_COMPLETE`, `expectedNext` = **current path next** — **applied (Sam 2026-08-16)** |
| 2 | Implement → **frozen** | **Loader bootstrap (Option A):** Panel Registration join = `LOADER` satisfied; first gate **next = `COAT`**. No separate `station-completes` for `LOADER` (Jonathan 2026-08-16 · Kai lean) |
| 3 | Implement | `equipmentId` → op map: stage 1 **through** ops only; EOL ids belong to AS-4 — reject or out-of-scope clearly in code |
