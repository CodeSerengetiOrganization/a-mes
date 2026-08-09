-- AS-2 seed sanity (not a Flyway migration) — thin proof only (Elon/Kai).
-- Expected map:
--   PANEL-DEMO-001 → WO-DEMO-001 → pp_full_eol
--   PANEL-DEMO-002 → WO-DEMO-002 → pp_cold_ambient
--   PANEL-DEMO-003 → WO-DEMO-003 → pp_ambient_only
--
-- Run whole file in DBeaver (Execute SQL Script) or:
--   mysql -h 127.0.0.1 -P 3306 -u ames -p ames < …/as2_wo_panel_path_check.sql

WITH checks AS (
  -- (1) POSITIVE: the three good triples exist
  SELECT
    1 AS seq,
    IF(COUNT(*) = 3, 'PASS', 'FAIL') AS assertion,
    COUNT(*) AS metric_count,
    'expect 3 panels each on correct WO+path' AS rule
  FROM wo_management m
  JOIN wo_pp_binding b ON b.work_order_id = m.work_order_id
  WHERE
       (m.panel_number = 'PANEL-DEMO-001' AND m.work_order_id = 'WO-DEMO-001' AND b.process_path_id = 'pp_full_eol')
    OR (m.panel_number = 'PANEL-DEMO-002' AND m.work_order_id = 'WO-DEMO-002' AND b.process_path_id = 'pp_cold_ambient')
    OR (m.panel_number = 'PANEL-DEMO-003' AND m.work_order_id = 'WO-DEMO-003' AND b.process_path_id = 'pp_ambient_only')

  UNION ALL

  -- (2) CATCH-ALL: nothing outside that map
  SELECT
    2,
    IF(COUNT(*) = 0, 'PASS', 'FAIL'),
    COUNT(*),
    'no panel outside expected WO+path map'
  FROM wo_management m
  JOIN wo_pp_binding b ON b.work_order_id = m.work_order_id
  WHERE NOT (
       (m.panel_number = 'PANEL-DEMO-001' AND m.work_order_id = 'WO-DEMO-001' AND b.process_path_id = 'pp_full_eol')
    OR (m.panel_number = 'PANEL-DEMO-002' AND m.work_order_id = 'WO-DEMO-002' AND b.process_path_id = 'pp_cold_ambient')
    OR (m.panel_number = 'PANEL-DEMO-003' AND m.work_order_id = 'WO-DEMO-003' AND b.process_path_id = 'pp_ambient_only')
  )
)
SELECT seq, assertion, metric_count, rule
FROM checks

UNION ALL

SELECT
  999,
  IF(SUM(assertion = 'FAIL') = 0, 'ALL PASS', 'SOME FAIL'),
  SUM(assertion = 'FAIL'),
  'rollup: FAIL count (0 means all passed)'
FROM checks

ORDER BY seq;
