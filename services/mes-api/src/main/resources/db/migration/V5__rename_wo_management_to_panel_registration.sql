-- AS2-02: rename WO management → panel_registration (plant language)
-- Leaves V3/V4 history intact; renames table + constraint names to match API/entity.

RENAME TABLE wo_management TO panel_registration;

ALTER TABLE panel_registration
  DROP FOREIGN KEY fk_wo_management_work_order;

ALTER TABLE panel_registration
  DROP INDEX uq_wo_management_panel_number;

ALTER TABLE panel_registration
  ADD CONSTRAINT uq_panel_registration_panel_number UNIQUE (panel_number);

ALTER TABLE panel_registration
  ADD CONSTRAINT fk_panel_registration_work_order
    FOREIGN KEY (work_order_id) REFERENCES wo_pp_binding (work_order_id);
