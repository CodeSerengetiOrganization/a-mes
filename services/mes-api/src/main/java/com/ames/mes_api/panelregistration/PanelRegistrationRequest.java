package com.ames.mes_api.panelregistration;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/panel-registrations — request body (Maya draft — please review).
 */
public class PanelRegistrationRequest {

	@NotBlank(message = "panelNumber is required")
	private String panelNumber;

	@NotBlank(message = "workOrderId is required")
	private String workOrderId;

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
}
