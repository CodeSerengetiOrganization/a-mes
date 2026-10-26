package com.ames.mes_api.depanel;

import jakarta.validation.constraints.NotBlank;

/**
 * One cavity on {@code POST /api/depanel-splits}.
 */
public class DepanelCavityRequest {

	@NotBlank(message = "slot is required")
	private String slot;

	@NotBlank(message = "cavityResult is required")
	private String cavityResult;

	public String getSlot() {
		return slot;
	}

	public void setSlot(String slot) {
		this.slot = slot;
	}

	public String getCavityResult() {
		return cavityResult;
	}

	public void setCavityResult(String cavityResult) {
		this.cavityResult = cavityResult;
	}
}
