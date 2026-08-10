-- AS2-02: plant rule in schema (Maya draft — please review)
-- One panel → one WO; panel row must reference an existing WO↔path binding.

ALTER TABLE wo_management
  ADD CONSTRAINT uq_wo_management_panel_number UNIQUE (panel_number);

ALTER TABLE wo_management
  ADD CONSTRAINT fk_wo_management_work_order
    FOREIGN KEY (work_order_id) REFERENCES wo_pp_binding (work_order_id);
