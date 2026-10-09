package com.ames.mes_api.stationgate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.groups.Default;
import java.time.LocalDateTime;

/**
 * POST /api/station-gate-checks, /api/station-completes, and /api/station-quality-results —
 * request body. {@code outcome} is quality-only.
 */
public class StationGateRequest {

	/** COMPLETE only — {@code equipmentLocalAt} required; extends {@link Default} so {@code @NotBlank} still runs. */
	public interface OnComplete extends Default {}

	/**
	 * Quality PASS/FAIL — {@code equipmentLocalAt} and {@code outcome} required; extends {@link Default}
	 * so {@code @NotBlank} on serial/equipment still runs.
	 */
	public interface OnQuality extends Default {}

	@NotBlank(message = "serialNumber is required")
	private String serialNumber;

	@NotBlank(message = "equipmentId is required")
	private String equipmentId;

	/**
	 * Station/machine local time — required on COMPLETE ({@link OnComplete}) and quality
	 * ({@link OnQuality}); optional on gate check.
	 */
	@NotNull(
			groups = {OnComplete.class, OnQuality.class},
			message = "equipmentLocalAt is required")
	private LocalDateTime equipmentLocalAt;

	/** Quality only — {@code PASS} or {@code FAIL}. Ignored on check / COMPLETE. */
	@NotBlank(groups = OnQuality.class, message = "outcome is required")
	@Pattern(regexp = "PASS|FAIL", groups = OnQuality.class, message = "outcome must be PASS or FAIL")
	private String outcome;

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

	public String getOutcome() {
		return outcome;
	}

	public void setOutcome(String outcome) {
		this.outcome = outcome;
	}
}
