# Flyway migrations (mes-api)

Versioned SQL (Spring Boot default: `classpath:db/migration`):

| File | Purpose |
|------|---------|
| `V1__create_process_path.sql` | DDL — `process_path` table |
| `V2__seed_process_paths.sql` | Seed — `pp_full_eol`, `pp_cold_ambient`, `pp_ambient_only` |

Do not edit applied `V*` files on a shared DB; add a new version instead.
