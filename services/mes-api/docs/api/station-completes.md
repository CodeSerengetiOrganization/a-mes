# API — Append through COMPLETE evidence

**Story:** AS3-02 — Append through COMPLETE evidence  
**Sibling (scan gate):** [`station-skip-ahead-gate.md`](station-skip-ahead-gate.md) — `POST /api/station-gate-checks`  
**Parent:** AS-3 — Stop skip-ahead at stations and Packing  
**AC SoT:** `a-mes-docs` → `requirements/epic-a/AS3-02-append-through-complete-evidence.md`  
**Vocab SoT:** `a-mes-docs` → `requirements/tech-notes/18-tech-note-complete-vs-pass-fail-operation-event-epic-a-b.md` — **COMPLETE ≠ PASS / FAIL**  
**Evidence:** `a-mes-docs` → `requirements/tech-notes/14-tech-note-as3-path-cursor-immutable-events-vs-state-machine.md`  
**Gate vs UNIQUE:** `a-mes-docs` → `requirements/tech-notes/15-tech-note-as3-gate-in-mes-api-not-db-unique.md`  
**Package:** `com.ames.mes_api.stationgate`  
**Status:** **AS3-02 contract freeze** (2026-08-30) — Kai approved · implemented in `com.ames.mes_api.stationgate` · no OpenAPI yet

Floor flow: **scan gate check** → operator works → **station COMPLETE** (tech note 19). Two verbs — do not merge.

This endpoint **appends** immutable through-station evidence on `operation_event` with `outcome=COMPLETE`. It does **not** write quality **PASS** / **FAIL** (UV · EOL → AS-4 / separate quality API).

---

## Methodology — evidence vs gate deny (frozen)

| Concern | Where it lives | What is stored |
|---------|----------------|----------------|
| Path / station control | `operation_event` + deriveNext | **Outcomes only:** `COMPLETE` · `PASS` · `FAIL` |
| Gate deny (orphan, wrong station, already done, wrong writer) | HTTP `200` + `allowed: false` + `reasonCode` | **Not** a row on `operation_event` |

**`operation_event.outcome` accepted values (table / CHECK):** **`COMPLETE` | `PASS` | `FAIL` only.**  
Do **not** invent `DENIED`, `WRONG_STATION`, `ORPHAN`, `WRONG_ENDPOINT`, or other deny labels as `outcome` values.

| Why | |
|-----|--|
| Path math | deriveNext: done = PASS ∪ COMPLETE; FAIL does not advance. Deny codes are not “op done.” |
| Audit language | COMPLETE / PASS / FAIL are plant evidence; gate rejects are control decisions. |
| CI later | Optional decision log / metrics from `reasonCode` — **separate** from evidence SoT; out of AS3-02. |

**This API on allow:** append `outcome=COMPLETE` only.  
**This API on deny:** response only — **no** `operation_event` insert.  
**Quality writer (AS-4):** append `PASS` / `FAIL` to the **same** table via a **different** endpoint — still never deny-as-outcome.

Vocab SoT: tech note **18**. Schema: V6 CHECK on `outcome`.

---

## Endpoint

| | |
|--|--|
| **Method** | `POST` |
| **Path** | `/api/station-completes` |
| **Success** | `200 OK` — body always returned for allow **or** plant deny |

---

## Request body

```json
{
  "serialNumber": "UNIT-DEMO-001",
  "equipmentId": "AEL-01-COAT",
  "equipmentLocalAt": "2026-08-26T08:15:00"
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `serialNumber` | string | yes | Scanned identity — panel (pre-depanel) or unit serial (post–AS-5 or seeded). Trim before lookup. |
| `equipmentId` | string | yes | Station client id (kiosk / config). Maps to a **through** op on the process path (e.g. `AEL-01-COAT` → `COAT`). Not inferred from client IP. |
| `equipmentLocalAt` | date-time | yes | When the station finished work — stored on `operation_event.equipment_local_at`. Client local clock; MES sets `recorded_at` on receipt. |

Client does **not** send “what I think next is.” MES owns next from path + evidence.

`workOrderId` is **not** on the request — resolve via join (`panel_registration` / unit→WO). Orphan = no join.

Missing / blank required fields → **`400`**.

---

## HTTP status rules (frozen — AS3-02)

| Case | Status |
|------|--------|
| Allow (evidence appended) or plant gate deny | **`200 OK`** + body (`allowed` true/false) |
| Missing / blank fields | **`400`** |
| **Unknown** `equipmentId` (not on AEL-01 equipment map / not mapped) | **`400`** |
| **Known** non-through `equipmentId` on this API (Loader · UV/EOL · **Cold/Hot Chamber**) | **`200`** + `allowed: false`, `reasonCode: WRONG_ENDPOINT` — **not** `400` |

Plant denies are successful gate **decisions**, not HTTP conflicts. Clients always read `allowed` / `reasonCode` / `expectedNext` on `200`.

**No `409` on this endpoint.** `operation_event` is append-only with **no** `UNIQUE (serial_number, op_code)` (tech note 15). Double COMPLETE is a **service** rule → `200` + `ALREADY_COMPLETE`. A duplicate row from a race is dirty evidence; the handler must not treat a second append as advancing next again.

---

## Write-path gate order (service)

The write path **owns** COMPLETE rules. It does **not** require calling or reusing the gate-check service implementation (parallel PR with AS3-tech is OK). Gate **rules** must match the sibling check contract.

1. Validate body (blank / missing `equipmentLocalAt` → `400`)  
2. Resolve identity → work order (no join → **`200`**, `allowed: false`, `reasonCode: ORPHAN` — **no** append)  
3. Classify `equipmentId`:  
   - **Unknown** (garbage / not provisioned) → **`400`**  
   - **Known Loader** (e.g. `AEL-01-LOADER`) → **`200`**, `allowed: false`, `reasonCode: WRONG_ENDPOINT`, `message` that LOADER is satisfied by **Panel Registration** — not this API — **no** append  
   - **Known UV or EOL** (quality stations) → **`200`**, `allowed: false`, `reasonCode: WRONG_ENDPOINT`, clear `message` — **no** append  
   - **Known Cold/Hot Chamber** (non-path soak assets) → **`200`**, `allowed: false`, `reasonCode: WRONG_ENDPOINT`, `message` that chamber soak is **not** a station-completes step — **no** append  
   - **Known through** → map to `op_code` (`COAT`, `DEPANEL`, `CURE`, `ASM`, `PACK`, …) — **not** `LOADER` / chamber / UV / EOL  
4. Load process path + **expected next** (derive from path + completed ops — PASS ∪ COMPLETE count as done; FAIL does not — tech note 17)  
5. If this op is **already done** for this serial — **any** `operation_event` row with `outcome` in {`COMPLETE`, `PASS`} for this serial + op → **`200`**, `allowed: false`, `reasonCode: ALREADY_COMPLETE`, `expectedNext` = current path next — **no** append; next does **not** advance again. (**Frozen — same set as deriveNext.** Through stations should only ever store `COMPLETE`; treating `PASS` as done still blocks a second append if bad/seeded data advanced the path.)  
6. If this op ≠ expected next → **`200`**, `allowed: false`, `reasonCode: WRONG_STATION`, `expectedNext` = path next — **no** append  
7. Else → **append** one `operation_event` row (`outcome=COMPLETE`, `op_code`, `equipment_id`, `equipment_local_at`) → **`200`**, `allowed: true`, `expectedNext` = **following** path step

**Through ops only:** Coat · Depanel · Cure · Assembly · Pack (when Pack is next).  
**LOADER (frozen — Option A):** known Loader `equipmentId` on this API → **`WRONG_ENDPOINT`** (message names Panel Registration). Join = LOADER done — **never** append Loader COMPLETE here.  
**UV / EOL:** quality **PASS** / **FAIL** on a separate endpoint (AS-4) — **not** here.  
**Cold / Hot Chamber (frozen — stage 1):** known chamber `equipmentId` on this API → **`WRONG_ENDPOINT`** (same code; message names chamber soak ≠ COMPLETE). Put chambers **on the map** as non-path — do **not** leave them off (silent `400`) or on the through list (silent COMPLETE). No dedicated chamber reason code.

After a through COMPLETE, `expectedNext` may be a **quality** op (e.g. **UV**). This API does **not** advance past UV/EOL — that requires a **PASS** writer (stub/seed OK until AS-4).

---

## Response body — success (`200`, `allowed: true`)

No request echoes — client already has `serialNumber` / `equipmentId`.

```json
{
  "allowed": true,
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
| `allowed` | boolean | `true` |
| `workOrderId` | string \| null | Set when join found; **`null` on `ORPHAN`** |
| `processPathId` | string \| null | From `wo_pp_binding` when known; **`null` on `ORPHAN`** |
| `thisOp` | string | Op implied by `equipmentId` |
| `expectedNext` | string \| null | **Following** path step after this COMPLETE (may be a quality op); **`null` on `ORPHAN`** |
| `reasonCode` | string \| null | `null` on success |
| `message` | string \| null | `null` on success |
| `outcome` | string | Always **`COMPLETE`** on success — never **`PASS`** for through stations |

---

## Response body — plant deny (`200`, `allowed: false`)

Same shape as success; omit `outcome` or set `null`.

On **`ORPHAN`**: `workOrderId`, `processPathId`, and `expectedNext` are **`null`** (no join → no WO / path / next). Clients must not assume those fields are always set on deny.

Example `WRONG_STATION` (join exists):

```json
{
  "allowed": false,
  "workOrderId": "WO-DEMO-001",
  "processPathId": "pp_cold_ambient",
  "thisOp": "COAT",
  "expectedNext": "ASM",
  "reasonCode": "WRONG_STATION",
  "message": "Expected next: ASM"
}
```

Example `ORPHAN` (no join — null WO / path / next):

```json
{
  "allowed": false,
  "workOrderId": null,
  "processPathId": null,
  "thisOp": "COAT",
  "expectedNext": null,
  "reasonCode": "ORPHAN",
  "message": "Serial not joined to a work order"
}
```

### `reasonCode` (COMPLETE)

| Code | When | Append? |
|------|------|---------|
| `ORPHAN` | Serial not joined to any work order | No |
| `WRONG_STATION` | Join OK but this op ≠ current path next (e.g. Pack while Ambient still next) | No |
| `ALREADY_COMPLETE` | This op **already done** — any `COMPLETE` **or** `PASS` for this serial + op (same set as deriveNext / tech note 17; `FAIL` does not count) | No |
| `WRONG_ENDPOINT` | **Known** equipment that must **not** COMPLETE here: **Loader** (Panel Registration) · **UV / EOL** (quality API) · **Cold/Hot Chamber** (soak ≠ COMPLETE step) | No |

Example `WRONG_ENDPOINT` (quality):

```json
{
  "allowed": false,
  "workOrderId": "WO-DEMO-001",
  "processPathId": "pp_cold_ambient",
  "thisOp": "COLD_EOL",
  "expectedNext": "COLD_EOL",
  "reasonCode": "WRONG_ENDPOINT",
  "message": "Quality PASS/FAIL belongs on the quality API — not station-completes"
}
```

Example `WRONG_ENDPOINT` (Loader — Option A):

```json
{
  "allowed": false,
  "workOrderId": "WO-DEMO-001",
  "processPathId": "pp_cold_ambient",
  "thisOp": "LOADER",
  "expectedNext": "COAT",
  "reasonCode": "WRONG_ENDPOINT",
  "message": "LOADER is satisfied by Panel Registration — not station-completes"
}
```

Example `WRONG_ENDPOINT` (Cold/Hot Chamber — non-path):

```json
{
  "allowed": false,
  "workOrderId": "WO-DEMO-001",
  "processPathId": "pp_cold_ambient",
  "thisOp": "COLD_CHAMBER",
  "expectedNext": "COLD_EOL",
  "reasonCode": "WRONG_ENDPOINT",
  "message": "Chamber soak is not a station-completes step — no COMPLETE here"
}
```

On `ALREADY_COMPLETE`, `expectedNext` = **current path next** (where the unit should go next) — same rule as check.  
**Already done (frozen):** `{ COMPLETE, PASS }` — aligned with deriveNext; never treat `FAIL` as done.

---

## Error responses

| Status | When |
|--------|------|
| `400` | Missing / blank `serialNumber`, `equipmentId`, or `equipmentLocalAt`; **unknown** `equipmentId` |
| `200` + `allowed: false` | Orphan / wrong station / already COMPLETE / wrong endpoint (known Loader · UV / EOL · Cold/Hot Chamber) |

Do **not** use `409` for plant denies or double-COMPLETE on this endpoint.

---

## Persist (append-only)

On **allow only** — insert into `operation_event`. On **any deny** — **no** insert (see Methodology above).

| Column | Value |
|--------|-------|
| `serial_number` | Trimmed scan |
| `op_code` | From `equipmentId` map (not raw equipment id) |
| `equipment_id` | Echo request |
| `outcome` | **`COMPLETE`** only on this API — never `PASS` / `FAIL` here; never deny codes |
| `equipment_local_at` | Request `equipmentLocalAt` |
| `recorded_at` | DB default on insert |

**Table rule (frozen):** `outcome ∈ { COMPLETE, PASS, FAIL }` — DB CHECK. Gate `reasonCode` values are **not** outcomes and must **not** be persisted on this table.

Schema: V6 · no UNIQUE on `(serial_number, op_code)` · see [`../schema.md`](../schema.md).

---

## Equipment class vs `equipmentId` (stage 1)

Server-side map classifies each known `equipmentId`. Stage-1 examples:

| Class | Example `equipmentId` | This API |
|-------|----------------------|----------|
| Through | `AEL-01-COAT`, `AEL-01-DEPANEL`, `AEL-01-CURE`, `AEL-01-ASM`, `AEL-01-PACK` | Allow when next → append `COMPLETE` |
| Loader (bootstrap) | `AEL-01-LOADER` | **`200` + `WRONG_ENDPOINT`** — Panel Registration only |
| Quality | `AEL-01-UV`, `AEL-01-COLD-EOL`, `AEL-01-HOT-EOL`, `AEL-01-AMBIENT-EOL` | **`200` + `WRONG_ENDPOINT`** — quality API |
| Chamber (non-path) | `AEL-01-COLD-CHAMBER`, `AEL-01-HOT-CHAMBER` | **`200` + `WRONG_ENDPOINT`** — soak ≠ COMPLETE |
| Unknown | `AEL-01-NOT-REAL` | **`400`** |

Exact id list: enablement ticket + test doubles until floor bind lands. Do **not** put `LOADER` or chambers on the through allow list. No silent third class — known non-through → `WRONG_ENDPOINT`; only garbage → `400`.

---

## Out of this contract

| Out | Where |
|-----|--------|
| Scan gate (no append) | [`station-skip-ahead-gate.md`](station-skip-ahead-gate.md) |
| Quality PASS / FAIL | AS-4 / separate quality API (same `operation_event` table; outcomes `PASS`/`FAIL` only) |
| Gate deny / CI decision warehouse | Not `operation_event` — optional later; do not store denies as `outcome` |
| Panel Registration / LOADER bootstrap | `/api/panel-registrations` (AS-2) |
| Depanel panel→unit split | AS-5 |
| OpenAPI / Swagger | Optional later |
| Full work-order / ERP product | Do not claim (**DoD-7**) |

---

## AC → contract map (AS3-02 PR ship bar)

| AC # | Contract proof |
|------|----------------|
| 1 | Coat allow → `outcome=COMPLETE` in DB; `expectedNext` advances (e.g. UV) |
| 2 | Second Coat COMPLETE → `200`, `ALREADY_COMPLETE` (already done = COMPLETE\|PASS); next unchanged |
| 3 | Coat while next is ASM → `200`, `WRONG_STATION`, no row |
| 4 | Pack when Pack is next → allow + `COMPLETE` |
| 5 | Orphan → `200`, deny, no row |
| 6 | Unknown `equipmentId` → `400` |
| 7 | Known UV/EOL **or** Loader **or** Cold/Hot Chamber id → `200`, `WRONG_ENDPOINT`, not `400` |
| 8 | No Loader COMPLETE after Panel Registration (Option A) — Loader `equipmentId` denied as above |
| 9–10 | Do not claim DoD-7 / AS-4 in this API |

---

## Review

| Role | Status |
|------|--------|
| Jonathan (draft) | **Draft (2026-08-26)** · response echoes dropped (request fields not re-sent) |
| Kai approve | **Frozen (2026-08-30)** — COMPLETE write path · `WRONG_ENDPOINT` · outcome ∈ {COMPLETE,PASS,FAIL} only · no deny rows · check non-through matrix locked on sibling doc |
| Robert (SoT / COMPLETE≠PASS) | **SoT OK** — evidence-vs-deny methodology · lean response (MES-derived fields only) |
