# Conceptual schema — mes-api (A-MES)

**Type:** Human-readable picture for onboarding — **not** applied by Flyway  
**SoT for DDL:** `src/main/resources/db/migration/V*.sql` (run by Spring Boot)  
**As of:** V6 (2026-08-18)  
**Audience:** new engineers · Maya / Sam · reviewers

If this note disagrees with a migration, **trust the migration** and update this file.

---

## Why this note exists

Flyway `V1`…`Vn` is the path the database took. New readers should not have to replay every `ALTER` to learn the plant model. This page is the **current conceptual picture** only — not a second `baseline.sql` SoT.

---

## Plant rules (stage 1)

| Rule | Where it lives |
|------|----------------|
| Three approved process paths (`pp_*`) | `process_path` (seeded AS-1) |
| One work order → exactly one process path | `wo_pp_binding` PK = `work_order_id` |
| Panel joins a work order at **Panel Registration** | `panel_registration` |
| One panel → one work order | `UNIQUE(panel_number)` (V4) |
| Panel’s WO must already have a path binding | FK → `wo_pp_binding` (V4) |
| Units inherit path via WO — not a column on the panel row | Join WO → `process_path_id` |
| Through COMPLETE / quality PASS/FAIL evidence | `operation_event` (append-only; not on `panel_registration`) |
| EOL retry allowed | No UNIQUE on (`serial_number`, `op_code`) — line management regulates retries |

---

## Picture

```text
process_path                 wo_pp_binding                 panel_registration
┌──────────────────┐        ┌─────────────────────┐      ┌──────────────────────────┐
│ process_path_id  │◄─── ?  │ work_order_id (PK)  │◄────│ work_order_id (FK)       │
│ content (JSON)   │        │ process_path_id     │      │ panel_number (UNIQUE)    │
│ description      │        │ created_at          │      │ registered_at            │
│ created_at       │        └─────────────────────┘      │ id (surrogate PK)        │
└──────────────────┘                                     └──────────────────────────┘
     AS-1 master              AS2-01 WO ↔ path                 AS2-02 Panel Registration

operation_event  (serial → join via panel_registration / later unit join — no WO copy)
┌──────────────────────────┐
│ id (surrogate PK)        │
│ serial_number            │
│ op_code                  │
│ equipment_id             │
│ outcome (COMPLETE/PASS/FAIL) │
│ equipment_local_at       │
│ recorded_at (DB default) │
└──────────────────────────┘
     AS3-tech performance evidence
```

`?` = logical pointer today (`process_path_id` string). Optional hard FK to `process_path` can land in a later migration if we want DB-enforced path ids.

---

## Tables (current)

### `process_path` — approved process path master (AS-1)

| Column | Meaning |
|--------|---------|
| `process_path_id` | Stable id: `pp_full_eol`, `pp_cold_ambient`, `pp_ambient_only` |
| `content` | JSON — human name + ordered ops |
| `description` | Short note (incl. legacy R1/R2/R3 alias) |
| `created_at` | Row create time |

### `wo_pp_binding` — work order owns a process path (AS2-01)

| Column | Meaning |
|--------|---------|
| `work_order_id` | Business WO id (PK), e.g. `WO-DEMO-001` |
| `process_path_id` | Which `pp_*` this WO follows |
| `created_at` | Assignment time |

No ERP quantity/status here (DoD-7).

### `panel_registration` — panel under a work order (AS2-02)

| Column | Meaning |
|--------|---------|
| `id` | Surrogate PK |
| `panel_number` | Panel identity (UNIQUE — one WO only) |
| `work_order_id` | FK → `wo_pp_binding` |
| `registered_at` | Panel Registration time (`DEFAULT CURRENT_TIMESTAMP` — DB owns stamp) |

Renamed from `wo_management` in V5 so table ↔ entity ↔ API share plant language (see `.cursor/rules/plant-language-naming.mdc`).

### `operation_event` — append-only operation evidence (AS3-tech)

| Column | Meaning |
|--------|---------|
| `id` | Surrogate PK |
| `serial_number` | Scanned identity (panel today; unit serial after AS-5) |
| `op_code` | Path op (`COAT`, `ASM`, `PACK`, `AMBIENT_EOL`, …) — not `equipment_id` |
| `equipment_id` | Which station/tester client called (e.g. `AEL-01-COAT`) |
| `outcome` | `COMPLETE` (through) · `PASS` / `FAIL` (quality). Through API writes COMPLETE only |
| `equipment_local_at` | Client/machine local timestamp — app sets this |
| `recorded_at` | MES receipt (`DEFAULT CURRENT_TIMESTAMP(3)` — DB owns stamp) |

No `work_order_id` — WO membership stays on `panel_registration` (later unit join).  
No FK on `serial_number` → `panel_registration` — unit / finished-product serials will not be panels (AS-5).  
No UNIQUE(`serial_number`, `op_code`) — EOL testers may retry; through double-COMPLETE is a service rule.  
Knowledge bank: `a-mes-docs` → tech notes **15** (gate vs unique) · **16** (no WO / no panel FK).

---

## Demo seeds (dev)

| Panel | Work order | Process path |
|-------|------------|--------------|
| `PANEL-DEMO-001` | `WO-DEMO-001` | `pp_full_eol` |
| `PANEL-DEMO-002` | `WO-DEMO-002` | `pp_cold_ambient` |
| `PANEL-DEMO-003` | `WO-DEMO-003` | `pp_ambient_only` |

Sanity SQL: `src/main/resources/db/check/as2_wo_panel_path_check.sql`

---

## How schema changes

1. Add `V(n+1)__….sql` — never edit applied `V*` on a shared DB.  
2. Restart app (Flyway migrates).  
3. Update **this** note so the picture stays current.

Related API: [`api/panel-registration.md`](api/panel-registration.md) · [`api/station-skip-ahead-gate.md`](api/station-skip-ahead-gate.md)  
Migration index: [`../src/main/resources/db/migration/README.md`](../src/main/resources/db/migration/README.md)
