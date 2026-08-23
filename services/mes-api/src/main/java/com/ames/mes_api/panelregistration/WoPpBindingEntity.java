package com.ames.mes_api.panelregistration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * Maps {@code wo_pp_binding} — work order owns a process path (AS2-01).
 */
@Entity
@Table(name = "wo_pp_binding")
public class WoPpBindingEntity {

	@Id
	@Column(name = "work_order_id", length = 64, nullable = false)
	private String workOrderId;

	@Column(name = "process_path_id", length = 64, nullable = false)
	private String processPathId;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt;

	public String getWorkOrderId() {
		return workOrderId;
	}

	public void setWorkOrderId(String workOrderId) {
		this.workOrderId = workOrderId;
	}

	public String getProcessPathId() {
		return processPathId;
	}

	public void setProcessPathId(String processPathId) {
		this.processPathId = processPathId;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}
}
