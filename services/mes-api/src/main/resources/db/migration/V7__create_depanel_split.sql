-- AS5-01: depanel split — one row per PCBA (panel, slot, PCBA serial).
-- This table is the only record of which PCBA serials came from which panel.
-- No work order, no process path, no PASS / FAIL. Those stay off this table.
-- Station clock is equipment_local_at (client). Server time is recorded_at (DB default).
-- Insert all four rows in one INSERT so recorded_at is the same commit time.
-- PCBA serial format stays a service check. This table does not encode the regex.
-- Slot names are not a fixed list in this table. This part's four names are a service rule.
-- Duplicate slot or duplicate PCBA serial on one panel is also a service hold.
-- The unique keys are the backstop for a race. A PCBA serial may exist on another panel.

CREATE TABLE depanel_split (
  id                   INT           NOT NULL AUTO_INCREMENT,
  panel_number         VARCHAR(64)   NOT NULL,
  slot                 VARCHAR(2)    NOT NULL,
  pcba_serial          VARCHAR(64)   NOT NULL,
  equipment_local_at   DATETIME(3)   NOT NULL,
  recorded_at          DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  CONSTRAINT fk_depanel_split_panel
    FOREIGN KEY (panel_number) REFERENCES panel_registration (panel_number),
  CONSTRAINT uq_depanel_split_panel_slot
    UNIQUE (panel_number, slot),
  CONSTRAINT uq_depanel_split_panel_pcba
    UNIQUE (panel_number, pcba_serial),
  INDEX idx_depanel_split_pcba_serial (pcba_serial)
);
