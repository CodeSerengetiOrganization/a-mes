package com.ames.mes_api.stationgate;

/**
 * Station gate check / COMPLETE response.
 */
public class StationGateResponse {

	private boolean allowed;
	private String expectedNext;
	private String reasonCode;
	private String outcome;

	public boolean isAllowed() {
		return allowed;
	}

	public void setAllowed(boolean allowed) {
		this.allowed = allowed;
	}

	public String getExpectedNext() {
		return expectedNext;
	}

	public void setExpectedNext(String expectedNext) {
		this.expectedNext = expectedNext;
	}

	public String getReasonCode() {
		return reasonCode;
	}

	public void setReasonCode(String reasonCode) {
		this.reasonCode = reasonCode;
	}

	public String getOutcome() {
		return outcome;
	}

	public void setOutcome(String outcome) {
		this.outcome = outcome;
	}

	@Override
	public String toString() {
		return "StationGateResponse{allowed=" + allowed
				+ ", expectedNext=" + expectedNext
				+ ", reasonCode=" + reasonCode
				+ ", outcome=" + outcome
				+ '}';
	}
}
