package com.ames.mes_api.stationgate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * Maps {@code operation_event} — station COMPLETE / quality result for a serial (Maya draft — please review).
 */
@Entity
@Table(name = "operation_event")
public class OperationEventEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Integer id;

	@Column(name = "serial_number", length = 64, nullable = false)
	private String serialNumber;

	@Column(name = "op_code", length = 32, nullable = false)
	private String opCode;

	@Column(name = "equipment_id", length = 64, nullable = false)
	private String equipmentId;

	@Column(name = "outcome", length = 16, nullable = false)
	private String outcome;

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

	public String getSerialNumber() {
		return serialNumber;
	}

	public void setSerialNumber(String serialNumber) {
		this.serialNumber = serialNumber;
	}

	public String getOpCode() {
		return opCode;
	}

	public void setOpCode(String opCode) {
		this.opCode = opCode;
	}

	public String getEquipmentId() {
		return equipmentId;
	}

	public void setEquipmentId(String equipmentId) {
		this.equipmentId = equipmentId;
	}

	public String getOutcome() {
		return outcome;
	}

	public void setOutcome(String outcome) {
		this.outcome = outcome;
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
