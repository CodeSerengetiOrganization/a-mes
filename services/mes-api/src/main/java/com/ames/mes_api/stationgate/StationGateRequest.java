package com.ames.mes_api.stationgate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.groups.Default;
import java.time.LocalDateTime;

/** POST /api/station-gate-checks and /api/station-completes — request body. */
public class StationGateRequest {

	/** COMPLETE only — {@code equipmentLocalAt} required; extends {@link Default} so {@code @NotBlank} still runs. */
	public interface OnComplete extends Default {}

	@NotBlank(message = "serialNumber is required")
	private String serialNumber;

	@NotBlank(message = "equipmentId is required")
	private String equipmentId;

	/** Station/machine local time — required on COMPLETE ({@link OnComplete}); optional on gate check. */
	@NotNull(groups = OnComplete.class, message = "equipmentLocalAt is required")
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
