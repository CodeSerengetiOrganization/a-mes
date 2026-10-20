package com.ames.mes_api.depanel;

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
 * Maps {@code depanel_split} — one PCBA bound to a panel at PCB Depaneler.
 * The database sets {@code recorded_at}. Java does not.
 */
@Entity
@Table(
		name = "depanel_split",
		uniqueConstraints = {
			@UniqueConstraint(name = "uq_depanel_split_panel_slot", columnNames = {"panel_number", "slot"}),
			@UniqueConstraint(name = "uq_depanel_split_panel_pcba", columnNames = {"panel_number", "pcba_serial"})
		})
public class DepanelSplitEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;

	@Column(name = "panel_number", length = 64, nullable = false)
	private String panelNumber;

	@Column(name = "slot", length = 2, nullable = false)
	private String slot;

	@Column(name = "pcba_serial", length = 64, nullable = false)
	private String pcbaSerial;

	@Column(name = "equipment_local_at", nullable = false)
	private LocalDateTime equipmentLocalAt;

	/** Filled by MySQL {@code DEFAULT CURRENT_TIMESTAMP(3)} — not written by the app. */
	@Generated(event = EventType.INSERT)
	@Column(name = "recorded_at", nullable = false, insertable = false, updatable = false)
	private LocalDateTime recordedAt;

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

	public String getSlot() {
		return slot;
	}

	public void setSlot(String slot) {
		this.slot = slot;
	}

	public String getPcbaSerial() {
		return pcbaSerial;
	}

	public void setPcbaSerial(String pcbaSerial) {
		this.pcbaSerial = pcbaSerial;
	}

	public LocalDateTime getEquipmentLocalAt() {
		return equipmentLocalAt;
	}

	public void setEquipmentLocalAt(LocalDateTime equipmentLocalAt) {
		this.equipmentLocalAt = equipmentLocalAt;
	}

	public LocalDateTime getRecordedAt() {
		return recordedAt;
	}

	public void setRecordedAt(LocalDateTime recordedAt) {
		this.recordedAt = recordedAt;
	}
}
