# API — Station skip-ahead gate (scan check)

**Story:** AS3-tech — Station skip-ahead gate check  
**Sibling (write path):** [`station-completes.md`](station-completes.md) — `POST /api/station-completes`  
**Parent:** AS-3 — Stop skip-ahead at stations and Packing  
**AC SoT:** `a-mes-docs` → `requirements/epic-a/AS3-tech-station-skip-ahead-gate.md`  
**Evidence direction:** `a-mes-docs` → `requirements/tech-notes/14-tech-note-as3-path-cursor-immutable-events-vs-state-machine.md`  
**Package:** `com.ames.mes_api.stationgate`  
**Status:** **Stage-1 contract freeze** (2026-08-16) — check slice · COMPLETE moved to [`station-completes.md`](station-completes.md) (AS3-02, 2026-08-26)

Two verbs (same gate **rules**): **check** (scan / allow?) then **COMPLETE** (append evidence). Do not merge into one vague “do everything” call.

This document covers **check only**. Through COMPLETE append → [`station-completes.md`](station-completes.md).

---

## Shared request fields (check)

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

For COMPLETE, the same core fields apply plus required `equipmentLocalAt` — see [`station-completes.md`](station-completes.md).

---

## HTTP status rules (check — frozen)

| Case | Status |
|------|--------|
| Allow or plant gate deny (orphan / wrong station / already COMPLETE) | **`200 OK`** + body (`allowed` true/false) |
| Missing / blank fields; unknown `equipmentId` (stage 1) | **`400`** |

Plant denies are successful gate **decisions**, not HTTP conflicts. Clients always read `allowed` / `reasonCode` / `expectedNext` on `200`.

Check does **not** append evidence and does **not** use `409`.

---

## Check (scan gate)

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
5. If this op is **already done** for this serial — any `outcome` in {`COMPLETE`, `PASS`} for this serial + op (same set as deriveNext / tech note 17; `FAIL` does not count) → `allowed: false`, `reasonCode: ALREADY_COMPLETE` (UI can block before work; do not leave this to COMPLETE-only). Align with [`station-completes.md`](station-completes.md).  
6. If this op ≠ next → **wrong station** (`allowed: false`, `expectedNext` = next, `reasonCode: WRONG_STATION`)  
7. Else → `allowed: true` (`expectedNext` = this op / next, `reasonCode: null`)

Check does **not** append COMPLETE and does **not** advance next. Check denies (**`ORPHAN`**, **`WRONG_STATION`**, **`ALREADY_COMPLETE`**, …) are response-only — **never** insert into `operation_event` (no deny-as-`outcome`; table allows only `COMPLETE` / `PASS` / `FAIL` — see [`station-completes.md`](station-completes.md) Methodology).

### Response body (`200`)

No request echoes — client already has `serialNumber` / `equipmentId`. MES-derived context + gate decision only.

```json
{
  "allowed": false,
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
| `ALREADY_COMPLETE` | This op **already done** — any `COMPLETE` **or** `PASS` for this serial + op (deriveNext set; Kai freeze 2026-08-26) — **required on check** |

### Error responses (check)

| Status | When |
|--------|------|
| `400` | Missing / blank `serialNumber` or `equipmentId`; unknown `equipmentId` (stage 1) |
| `200` + `allowed: false` | Orphan / wrong station / already COMPLETE — **not** silent allow; **not** `409` |

---

## Loader bootstrap (frozen — stage 1)

**Option A:** Successful **Panel Registration** (`POST /api/panel-registrations`) means **`LOADER` is satisfied**. Do **not** require a separate `POST /api/station-completes` for `LOADER`. Known Loader `equipmentId` on COMPLETE → **`WRONG_ENDPOINT`** (see [`station-completes.md`](station-completes.md)).

| After join | First gate **next** |
|------------|---------------------|
| Panel on WO with path | **`COAT`** (second op on AS-1 seeded paths) |

Operators must not COMPLETE Loader twice. Cursor / evidence init must honor this (append a LOADER COMPLETE on join, or seed next=`COAT` without a LOADER COMPLETE row — pick one persist style in the PR; behavior is Option A either way).

---

## Op codes vs `equipmentId` (stage 1)

Path JSON uses stable **op codes** (`LOADER`, `COAT`, `ASM`, `PACK`, …) from AS-1 seed.  
`equipmentId` values (e.g. `AEL-01-COAT`) come from the enablement list / test doubles until the bind ticket lands.

Contract assumes a **server-side map** `equipmentId → op_code`. Mapping table freeze is enablement / PR — not Story AC.

Non-through known ids (UV · EOL · Loader · Cold/Hot Chamber) on **check**: stage-1 behavior TBD in AS3-tech PR (prefer same `WRONG_ENDPOINT` fence as COMPLETE). **COMPLETE** handling for those classes is frozen in [`station-completes.md`](station-completes.md) → `200` + `WRONG_ENDPOINT`.

---

## Out of this contract

| Out | Where |
|-----|--------|
| Through COMPLETE append | [`station-completes.md`](station-completes.md) (AS3-02) |
| Panel Registration join | `/api/panel-registrations` (AS-2) |
| EOL / UV QUALITY PASS | AS-4 |
| OpenAPI / Swagger | Optional later |
| Soft warning / silent allow on wrong station | Forbidden (AS-3) |
| `409` for plant gate denies | Forbidden — use `200` + `allowed: false` |

---

## AC → contract map (AS3-tech PR ship bar)

| AS3-tech AC | Primary call |
|-------------|--------------|
| Wrong station + expected next | `POST .../station-gate-checks` → `200`, `allowed:false`, `expectedNext` |
| Allow at correct station | `POST .../station-gate-checks` → `200`, `allowed:true` |
| Already COMPLETE on check | → `200`, `ALREADY_COMPLETE` |
| Pack deny / allow when next | same gate; Pack `equipmentId` |
| Orphan | → `200`, `ORPHAN` |
| DoD-7 | Do not claim ERP WO product in API docs/UI copy |

COMPLETE append ACs → [`station-completes.md`](station-completes.md) (AS3-02).

---

## Review

| Role | Status |
|------|--------|
| Sam (draft) | **Done (2026-08-16)** |
| Jonathan revise | **Good enough (2026-08-16)** · **Split COMPLETE → station-completes.md (2026-08-26)** |
| Kai approve (freeze) | **Re-review OK (2026-08-16)** — check slice; COMPLETE re-review pending on sibling doc |
| Robert (SoT / naming) | **SoT OK (2026-08-16)** |

### Kai re-review notes (2026-08-16)

| # | Severity | Note |
|---|----------|------|
| 1 | Sharpen | On `ALREADY_COMPLETE`, `expectedNext` = **current path next** — **applied (Sam 2026-08-16)** |
| 2 | Implement → **frozen** | **Loader bootstrap (Option A):** Panel Registration join = `LOADER` satisfied; first gate **next = `COAT`**. No separate `station-completes` for `LOADER` |
| 3 | Implement | `equipmentId` → op map: stage 1 **through** ops on check; EOL/UV COMPLETE fence → [`station-completes.md`](station-completes.md) |
