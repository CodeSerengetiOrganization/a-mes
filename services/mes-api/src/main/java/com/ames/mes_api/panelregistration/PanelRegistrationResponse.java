package com.ames.mes_api.panelregistration;

import java.time.LocalDateTime;

/**
 * POST /api/panel-registrations — 201 body (Maya draft — please review).
 */
public class PanelRegistrationResponse {

	private String panelNumber;
	private String workOrderId;
	private LocalDateTime registeredAt;

	public PanelRegistrationResponse() {
	}

	public PanelRegistrationResponse(String panelNumber, String workOrderId, LocalDateTime registeredAt) {
		this.panelNumber = panelNumber;
		this.workOrderId = workOrderId;
		this.registeredAt = registeredAt;
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
