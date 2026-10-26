package com.ames.mes_api.depanel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * POST /api/depanel-splits — request body.
 */
public class DepanelSplitRequest {

	@NotBlank(message = "panelNumber is required")
	private String panelNumber;

	@NotBlank(message = "equipmentId is required")
	private String equipmentId;

	@NotNull(message = "equipmentLocalAt is required")
	private LocalDateTime equipmentLocalAt;

	@NotEmpty(message = "cavities are required")
	@Valid
	private List<DepanelCavityRequest> cavities;

	public String getPanelNumber() {
		return panelNumber;
	}

	public void setPanelNumber(String panelNumber) {
		this.panelNumber = panelNumber;
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

	public List<DepanelCavityRequest> getCavities() {
		return cavities;
	}

	public void setCavities(List<DepanelCavityRequest> cavities) {
		this.cavities = cavities;
	}
}
