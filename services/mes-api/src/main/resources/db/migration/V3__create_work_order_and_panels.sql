-- AS-2: work order ↔ process path + panels under each WO
-- (Maya draft — please review)

CREATE TABLE wo_pp_binding (
  work_order_id   VARCHAR(64)  NOT NULL,
  process_path_id VARCHAR(64)  NOT NULL,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (work_order_id)
);

CREATE TABLE wo_management (
  id              INT          NOT NULL AUTO_INCREMENT,
  panel_number    VARCHAR(64)  NOT NULL,
  work_order_id   VARCHAR(64)  NOT NULL,
  registered_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id)
);

-- Demo seeds for AC — one WO per approved process path
INSERT INTO wo_pp_binding (work_order_id, process_path_id) VALUES
('WO-DEMO-001',    'pp_full_eol'),
('WO-DEMO-002',     'pp_cold_ambient'),
('WO-DEMO-003', 'pp_ambient_only');

INSERT INTO wo_management (work_order_id, panel_number) VALUES
('WO-DEMO-001', 'PANEL-DEMO-001'),
('WO-DEMO-002', 'PANEL-DEMO-002'),
('WO-DEMO-003', 'PANEL-DEMO-003');
