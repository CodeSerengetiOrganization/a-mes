# Flyway migrations (mes-api)

Versioned SQL (Spring Boot default: `classpath:db/migration`):

| File | Purpose |
|------|---------|
| `V1__create_process_path.sql` | DDL — `process_path` table |
| `V2__seed_process_paths.sql` | Seed — `pp_full_eol`, `pp_cold_ambient`, `pp_ambient_only` |
| `V3__create_work_order_and_panels.sql` | DDL + demo seed — WO↔path + panels |
| `V4__panel_unique_and_wo_fk.sql` | UNIQUE(`panel_number`); FK `work_order_id` → `wo_pp_binding` |
| `V5__rename_wo_management_to_panel_registration.sql` | Rename `wo_management` → `panel_registration` (+ constraint names) |

Do not edit applied `V*` files on a shared DB; add a new version instead.

**Human picture (not applied by Flyway):** [`docs/schema.md`](../../../../../docs/schema.md) — conceptual tables + plant rules. Trust `V*` if they disagree.

**Existing `ames` DB (tables already applied before Boot):** use profile `dev`, which sets `spring.flyway.baseline-on-migrate=true` and `baseline-version=3` so Flyway creates `flyway_schema_history` without re-running V1–V3. **V4+ still run** after baseline. Fresh empty DB: Flyway runs V1→Vn normally.
