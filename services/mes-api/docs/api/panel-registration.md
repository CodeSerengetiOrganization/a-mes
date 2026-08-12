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
| `404` | Work order not found on soft check — never path-assigned (`wo_pp_binding`) |
| `409` | Panel already registered (soft check), **or** insert hit a DB integrity race (`UNIQUE(panel_number)` double-submit, or WO gone after soft check / other integrity conflict). No bare 500 for those races. |

Soft checks cover the normal path. On `DataIntegrityViolationException` after `save`, the service maps to **409** and does **not** re-query in the same transaction (failed INSERT can leave the JPA session unusable). Distinguishing FK-vs-UNIQUE on that rare path is out of stage 1.

## Persist

Insert into `panel_registration` (`panel_number`, `work_order_id`).  
`registered_at` is set by MySQL `DEFAULT CURRENT_TIMESTAMP` (not sent from Java).

Schema: `UNIQUE(panel_number)`; `FOREIGN KEY (work_order_id) REFERENCES wo_pp_binding (work_order_id)` (V4); table renamed from `wo_management` (V5).
