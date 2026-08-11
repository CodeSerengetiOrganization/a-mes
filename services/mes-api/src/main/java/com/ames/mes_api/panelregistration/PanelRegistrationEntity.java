package com.ames.mes_api.panelregistration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Maps {@code panel_registration} — panel joined to a work order at Panel Registration.
 * DB: UNIQUE(panel_number) + FK(work_order_id) → wo_pp_binding (V4); table renamed from {@code wo_management} (V5).
 */
@Entity
@Table(
		name = "panel_registration",
		uniqueConstraints = @UniqueConstraint(name = "uq_panel_registration_panel_number", columnNames = "panel_number"))
public class PanelRegistrationEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;

	@Column(name = "panel_number", length = 64, nullable = false, unique = true)
	private String panelNumber;

	@Column(name = "work_order_id", length = 64, nullable = false)
	private String workOrderId;

	/** Filled by MySQL {@code DEFAULT CURRENT_TIMESTAMP} — not written by the app. */
	@Generated(event = EventType.INSERT)
	@Column(name = "registered_at", nullable = false, insertable = false, updatable = false)
	private LocalDateTime registeredAt;

	public Integer getId() {
		return id;
	}

	public void setId(Integer id) {
		this.id = id;
	}

	public String getPanelNumber() {
		return panelNumber;
	}

	public void setPanelNumber(String panelNumber) {
		this.panelNumber = panelNumber;
	}

	public String getWorkOrderId() {
		return workOrderId;
	}

	public void setWorkOrderId(String workOrderId) {
		this.workOrderId = workOrderId;
	}

	public LocalDateTime getRegisteredAt() {
		return registeredAt;
	}

	public void setRegisteredAt(LocalDateTime registeredAt) {
		this.registeredAt = registeredAt;
	}
}
