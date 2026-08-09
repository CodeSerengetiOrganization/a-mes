# Flyway migrations (mes-api)

Versioned SQL (Spring Boot default: `classpath:db/migration`):

| File | Purpose |
|------|---------|
| `V1__create_process_path.sql` | DDL — `process_path` table |
| `V2__seed_process_paths.sql` | Seed — `pp_full_eol`, `pp_cold_ambient`, `pp_ambient_only` |
| `V3__create_work_order_and_panels.sql` | DDL + demo seed — WO↔path + panels |

Do not edit applied `V*` files on a shared DB; add a new version instead.

**Existing `ames` DB (tables already applied before Boot):** use profile `dev`, which sets `spring.flyway.baseline-on-migrate=true` and `baseline-version=3` so Flyway creates `flyway_schema_history` without re-running V1–V3. Fresh empty DB: Flyway runs V1→V3 normally (baseline is unused).
