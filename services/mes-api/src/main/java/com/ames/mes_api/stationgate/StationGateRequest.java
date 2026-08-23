package com.ames.mes_api.stationgate;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

/**
 * POST /api/station-gate-checks and /api/station-completes — request body (Maya draft — please review).
 */
public class StationGateRequest {

	@NotBlank(message = "serialNumber is required")
	private String serialNumber;

	@NotBlank(message = "equipmentId is required")
	private String equipmentId;

	/** Station/machine local time — required when appending COMPLETE. */
	private LocalDateTime equipmentLocalAt;

	public String getSerialNumber() {
		return serialNumber;
	}

	public void setSerialNumber(String serialNumber) {
		this.serialNumber = serialNumber;
	}

	public String getEquipmentId() {
		return equipmentId;
	}

	public void setEquipmentId(String equipmentId) {
		this.equipmentId = equipmentId;
	}

	public LocalDateTime getEquipmentLocalAt() {
		return equipmentLocalAt;
	}

	public void setEquipmentLocalAt(LocalDateTime equipmentLocalAt) {
		this.equipmentLocalAt = equipmentLocalAt;
	}
}
