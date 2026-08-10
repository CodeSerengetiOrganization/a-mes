# API — Panel Registration

**Story:** AS2-02 — Panel Registration: join panel to work order (PCB Loader)  
**Parent:** AS-2  
**Package:** `com.ames.mes_api.panelregistration`  
**Status:** Stage-1 contract freeze (no OpenAPI yet)

## Endpoint

| | |
|--|--|
| **Method** | `POST` |
| **Path** | `/api/panel-registrations` |
| **Success** | `201 Created` |

## Request body

```json
{
  "panelNumber": "PANEL-DEMO-004",
  "workOrderId": "WO-DEMO-001"
}
```

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `panelNumber` | string | yes | Panel identity scanned at PCB Loader |
| `workOrderId` | string | yes | Existing WO (must already have a process path) |

UI sends **both**. Backend does not invent an “active” work order.

## Response body (`201`)

```json
{
  "panelNumber": "PANEL-DEMO-004",
  "workOrderId": "WO-DEMO-001",
  "registeredAt": "2026-08-09T20:15:00"
}
```

| Field | Type | Notes |
|-------|------|-------|
| `panelNumber` | string | Echo of join |
| `workOrderId` | string | WO the panel is under |
| `registeredAt` | date-time | When the join was stored |

Process path is **not** in the body — inherit via WO → `wo_pp_binding`.

## Error responses

| Status | When |
|--------|------|
| `400` | Missing / blank `panelNumber` or `workOrderId` |
| `404` | Work order not found — either it was never path-assigned (`wo_pp_binding`), **or** it disappeared after the pre-check and the insert failed the FK (`work_order_id`). Service re-reads the WO to decide this after a DB integrity error. |
| `409` | Panel already registered — either the soft pre-check found it, **or** a concurrent request won the race and `UNIQUE(panel_number)` rejected the insert (typical double-submit). Service re-reads the panel to decide this after a DB integrity error. Also used for any other unexpected integrity conflict (still not a bare 500). |

Both UNIQUE and FK failures arrive as the same Spring `DataIntegrityViolationException`; the service does **not** parse MySQL constraint names — it re-checks panel / WO rows (rare path, cheap).

## Persist

Insert into `wo_management` (`panel_number`, `work_order_id`, `registered_at`).

Schema (V4): `UNIQUE(panel_number)`; `FOREIGN KEY (work_order_id) REFERENCES wo_pp_binding (work_order_id)`.
