# API — Depanel split

**Ticket:** AS5-01 — Write the depanel split  
**AC SoT:** `a-mes-docs` → `requirements/epic-a/AS5-01-write-depanel-split-backend-STE100-80%.md`  
**Parent:** AS5-00 — Track each board after depaneling  
**Depends on:** AS-2 (panel is on a work order) · existing station gate  
**Sibling (later):** AS5-02 — station gate reads the PCBA. This contract does not.  
**Gate contracts:** [`station-skip-ahead-gate.md`](station-skip-ahead-gate.md) · [`station-completes.md`](station-completes.md)  
**Package:** `com.ames.mes_api.depanel`  
**Status:** **Kai draft** (2026-10-19) — not frozen. Jonathan reviews. No OpenAPI yet.

At **PCB Depaneler** the client scans the panel and four cavity results. MES binds the panel to its PCBA serials and slots. MES stores one **Depanel split COMPLETE** for the panel number. MES does not store a Depanel result (`PASS` or `FAIL`). That is AS5-02.

This endpoint is the only client path for the split. The service calls `StationGateService` in-process. The client does not call `POST /api/station-completes` to split a panel.

---

## Endpoint

| | |
|--|--|
| **Method** | `POST` |
| **Path** | `/api/depanel-splits` |
| **Stored** | `201 Created` |
| **Hold or gate refuse** | `200 OK` — nothing stored |
| **Reject** | `400` — gate does not run — nothing stored |

`stored` on the body is the plant fact. This endpoint does not use the gate field `allowed`. A hold can happen after the gate allows. `allowed: true` would tell the client the wrong fact.

---

## Request body

```json
{
  "panelNumber": "PANEL-APCU-20260729-0042",
  "equipmentId": "AEL-01-DEPANEL",
  "equipmentLocalAt": "2026-10-16T19:30:00",
  "cavities": [
    { "slot": "A1", "cavityResult": "PCBA-APCU-0000842101" },
    { "slot": "A2", "cavityResult": "PCBA-APCU-0000842102" },
    { "slot": "B1", "cavityResult": "PCBA-APCU-0000842103" },
    { "slot": "B2", "cavityResult": "PCBA-APCU-0000842104" }
  ]
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `panelNumber` | string | yes | Panel scanned at PCB Depaneler. The service sends this value to the station gate as the serial. Trim before lookup. |
| `equipmentId` | string | yes | Station client id. PCB Depaneler is `AEL-01-DEPANEL`. The station gate decides. This endpoint does not keep a second allow-list. |
| `equipmentLocalAt` | date-time | yes | Depaneler clock for the split. MES stores it on the split rows and on `operation_event.equipment_local_at`. The database sets a separate server time on the split rows, and `recorded_at` on the COMPLETE. |
| `cavities` | array | yes | One object per cavity the client sends. |
| `cavities[].slot` | string | yes | Slot name the client chooses. MES stores this slot. MES does not invent the slot from the PCBA serial. |
| `cavities[].cavityResult` | string | yes | One cavity result: a PCBA serial, or `XOUT`, or `EMPTY`, or `UNREADABLE`. |

The array order does not assign the slot. The `slot` field does. Check 1 sends `A1 → A2 → B1 → B2`. A different array order with those slot names is still a valid request.

`workOrderId` is not on the request, and the response does not return it. The depaneler does not use it. MES still reads the work order from `panel_registration` to run the station gate. MES reads the process path from that work order. MES does not copy either value onto the split rows.

The client does not send `PASS`, `FAIL`, or `COMPLETE`. MES stores `COMPLETE` only when the split is stored.

### Cavity result wire values

| Wire value | Meaning | When the rest of the request is valid |
|------------|---------|----------------------------------------|
| PCBA serial | Serial the SMT vendor printed. Example: `PCBA-APCU-0000842101`. | MES can store the split only when all four cavity results are PCBA serials and Approach item 8 passes. |
| `XOUT` | Bad cavity. | Hold. MES stores nothing. |
| `EMPTY` | Bad cavity. | Hold. MES stores nothing. |
| `UNREADABLE` | Bad cavity. The glossary word is "unreadable". | Hold. MES stores nothing. |

MES does not know which physical PCBA the operator pointed at. The client names the slot and the cavity result.

The format check applies only to a PCBA serial. The pull request sets the exact rule (the ticket example: starts with `PCBA`, length in range). A format failure is a hold. It is not a `400`. The format check does not choose the slot.

`XOUT`, `EMPTY`, and `UNREADABLE` are not PCBA serials. They do not go through the format check.

---

## Write order (service)

1. Trim `panelNumber`, `equipmentId`, each `slot`, and each `cavityResult`.
2. **Reject (`400`)** when the request is not complete. The station gate does not run. MES stores nothing.
   - Blank `panelNumber`, `equipmentId`, or `equipmentLocalAt`.
   - `cavities` missing or empty (check 3: panel number and no PCBA serials).
   - A cavity with a blank `slot` or a blank `cavityResult`.
3. **Station gate, no write yet.** Call the existing station gate in-process with `panelNumber` and `equipmentId`. Do not call it over HTTP. Do not make a second gate.
   - Unknown `equipmentId` → `400`. Same rule as [`station-completes.md`](station-completes.md).
   - Gate refuses → `200`, `stored: false`, the gate `reasonCode`, and nothing stored. AS-3 owns why the gate refuses. This ticket does not re-test skip-ahead. MES does not store a refuse row (Epic C).
4. **Approach item 8, only after the gate allows.** If any check fails → `200`, `reasonCode: HOLD`, and nothing stored. Do not call the COMPLETE write. Do not insert a hold row.
   - The request has all four slots: `A1`, `A2`, `B1`, `B2`.
   - No slot name outside that list.
   - No slot appears twice.
   - No PCBA serial appears twice on this panel. This check is this panel only.
   - Each PCBA serial passes the format check.
   - Any cavity result is `XOUT`, `EMPTY`, or `UNREADABLE`.
5. **One transaction, only when item 8 passes.**
   - Lock the `panel_registration` row (`SELECT … FOR UPDATE`), the same lock `StationGateService.complete` already takes.
   - Inside that lock, `StationGateService` stores one `COMPLETE` at `DEPANEL` for the panel number.
   - The same transaction inserts the four split rows.
   - If one write fails, MES stores neither the rows nor the COMPLETE.
   - If the locked gate returns `ALREADY_COMPLETE`, MES stores no split row and no new COMPLETE.
6. The `depanel` package does not call `OperationEventRepository`.
7. The `depanel` package does not write `panel_registration`.
8. MES does not store `PASS` or `FAIL` on this endpoint.

A second split has no separate "already split" rule. The station gate returns `ALREADY_COMPLETE` because the Depanel split COMPLETE is already stored.

There is no read endpoint. Check 2 reads the stored rows in the test.

---

## Response body — stored (`201`)

The body returns the decision and the facts the client does not already hold. The client sent the slots and the PCBA serials. MES does not return them. This endpoint is only PCB Depaneler, so the body has no `thisOp` and no `processPathId`. Check 2 reads the split rows in the test.

No body on this endpoint returns `expectedNext`. The depaneler branches on `stored` and `reasonCode`. The station gate still computes the next step in-process. A client that needs that step calls `POST /api/station-gate-checks`.

After a stored split, the path step for the panel number is `CURE`. The panel is not the unit that walks forward. Cure scans the PCBA, and only after that PCBA has a Depanel PASS. AS5-02 owns both.

```json
{
  "stored": true,
  "panelNumber": "PANEL-APCU-20260729-0042",
  "reasonCode": null,
  "message": null,
  "outcome": "COMPLETE"
}
```

| Field | Type | Notes |
|-------|------|-------|
| `stored` | boolean | `true` |
| `panelNumber` | string | Panel that was split |
| `reasonCode` | null | Null when stored |
| `message` | null | Null when the split is stored. A person reads `message` only when `stored` is false. |
| `outcome` | string | `COMPLETE` only. This is the Depanel split COMPLETE. It is not the Depanel result. |

---

## Response body — gate refuse (`200`, nothing stored)

Same fields as the stored body. `outcome` is null. `reasonCode` is the station-gate code. `stored` is `false`. `message` is a plant sentence. The depanel service writes that sentence. It does not copy a null from the station gate.

The depaneler branches on `reasonCode`. A person at the machine reads `message`.

Example: second split (check 5).

```json
{
  "stored": false,
  "panelNumber": "PANEL-APCU-20260729-0042",
  "reasonCode": "ALREADY_COMPLETE",
  "message": "This panel is already split. MES stored nothing.",
  "outcome": null
}
```

| `reasonCode` | When | `message` |
|--------------|------|-----------|
| `ORPHAN` | Panel number is not on a work order | `Panel is not on a work order. MES stored nothing.` |
| `WRONG_STATION` | Depanel is not the next step | `Depanel is not the next step. MES stored nothing.` |
| `ALREADY_COMPLETE` | Depanel split COMPLETE is already stored for this panel | `This panel is already split. MES stored nothing.` |
| `WRONG_ENDPOINT` | Known equipment that must not split a panel here | `This station cannot split a panel. MES stored nothing.` |

MES stores nothing on every row above. The body does not return a work order or a next step.

This ticket's tests do not re-prove these gate codes. The depaneler reads `reasonCode` and shows `message`.

---

## Response body — hold (`200`, nothing stored)

The gate already allowed. Approach item 8 failed. MES stores no split row, no Depanel split COMPLETE, and no hold row.

```json
{
  "stored": false,
  "panelNumber": "PANEL-APCU-20260729-0042",
  "reasonCode": "HOLD",
  "message": "Bad cavity. MES stored nothing.",
  "outcome": null
}
```

`HOLD` is a response code for this endpoint. It is not an `operation_event.outcome`. The table still allows only `COMPLETE`, `PASS`, and `FAIL`.

The panel stays at PCB Depaneler because MES stored no COMPLETE. `HOLD` says that.

One bad cavity holds the whole panel. The response does not name a slot. MES does not know which PCBA was under the scanner.

---

## Error responses

| Status | When |
|--------|------|
| `400` | Incomplete request (see write order step 2), or unknown `equipmentId` |
| `200` + `stored: false` + gate `reasonCode` | Station gate refuses. Nothing stored. |
| `200` + `stored: false` + `reasonCode: HOLD` | Bad cavity after the gate allows. Nothing stored. |
| `201` + `stored: true` + `outcome: COMPLETE` | Four split rows and one Depanel split COMPLETE |

No `409` on this endpoint. A second split is `200` + `ALREADY_COMPLETE`.

---

## Persist

Column names are chosen in the pull request. This contract names the facts only.

### Split table

One row per PCBA. This table is the only record of which PCBA serials came from which panel.

| Fact | Value |
|------|-------|
| Parent panel | Trimmed `panelNumber` |
| Slot | The request `slot` |
| PCBA serial | The request cavity result, only when it is a PCBA serial |
| Station time | `equipmentLocalAt` — the depaneler clock |
| Server time | Database default at insert — when MES commits the split |

The four rows of one split share one server time. Java does not set it. The response does not return it. The pull request chooses the column name.

The split table does not store the work order, the process path, `PASS`, or `FAIL`.

### `operation_event`

One row, written only by `StationGateService` inside the split transaction.

| Column | Value |
|--------|-------|
| `serial_number` | Panel number |
| `op_code` | `DEPANEL` |
| `equipment_id` | Request `equipmentId` |
| `outcome` | `COMPLETE` |
| `equipment_local_at` | Request `equipmentLocalAt` |
| `recorded_at` | Database default |

That row is not a fifth split row.

`panel_registration` is unchanged. The `depanel` package reads it and does not write it.

---

## Out of this contract

| Out | Where |
|-----|--------|
| Operator screen at PCB Depaneler | Not this ticket |
| Depanel result `PASS` / `FAIL` per PCBA | AS5-02 |
| Move `DEPANEL` onto the quality-op list | AS5-02 |
| Cure scans the PCBA and refuses the panel number | AS5-02 |
| Read endpoint for a PCBA's panel and work order | Out of Epic A |
| Refuse-as-history | Epic C |
| Hold table, scrap, rework | Out of this ticket. Hold here means store nothing. |
| A second allow-list of stations | The station gate already requires `AEL-01-DEPANEL` when Depanel is the next step |
| Column names and the PCBA serial format regex | Pull request |
| Demo seed or demo script | Pull request chooses one, so the K3s walk can pass Depanel |
| OpenAPI / Swagger | Optional later |
| `POST /api/station-completes` at `DEPANEL` | Stays as it is in this ticket. AS5-02 moves `DEPANEL` to quality-ops. A COMPLETE posted there then returns `WRONG_ENDPOINT`. The in-process split write still stores the Depanel split COMPLETE. |

---

## AC → contract map (AS5-01)

| Check | Contract proof |
|-------|----------------|
| 1 | `201`, `outcome: COMPLETE`. The test reads four split rows. No `PASS` or `FAIL`. No minted serial. |
| 2 | Test reads the split rows. The work order and the process path stay on `panel_registration`. The response does not return the work order. No read endpoint. |
| 3 | Panel number and no cavities → `400` before the gate. Nothing stored. |
| 4 | One Approach item 8 failure → `200`, `HOLD`, nothing stored, no hold row |
| 5 | Second request → `200`, `ALREADY_COMPLETE`, no new row |
| 6 | Reviewer reads the pull request. No test. |

---

## Review

| Role | Status |
|------|--------|
| Kai (draft) | **Draft (2026-10-19)** · response slimmed the same day: no `splits`, no `processPathId`, no `thisOp`, no `workOrderId`, no `expectedNext`. `message` is set when `stored` is false. |
| Jonathan | Pending |
| Robert (SoT) | Pending — split table owns panel / slot / PCBA serial / station time / server time; `panel_registration` owns the work order |
