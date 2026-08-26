-- AS3-tech: append-only operation evidence (through COMPLETE now; quality PASS/FAIL later)
-- Do not write COMPLETE/PASS onto panel_registration.
-- No UNIQUE(serial_number, op_code): EOL testers allow regulated retries (line management).
-- Double through COMPLETE is a service rule, not a DB unique.
-- Knowledge bank: a-mes-docs tech note 15 (gate vs unique) · 16 (no WO / no panel FK).

CREATE TABLE operation_event (
  id                   INT           NOT NULL AUTO_INCREMENT,
  serial_number        VARCHAR(64)   NOT NULL,
  op_code              VARCHAR(32)   NOT NULL,
  equipment_id         VARCHAR(64)   NOT NULL,
  outcome              VARCHAR(16)   NOT NULL,
  equipment_local_at   DATETIME(3)   NOT NULL,
  recorded_at          DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT chk_operation_event_outcome
    CHECK (outcome IN ('COMPLETE', 'PASS', 'FAIL')),
  INDEX idx_operation_event_serial (serial_number)
);
