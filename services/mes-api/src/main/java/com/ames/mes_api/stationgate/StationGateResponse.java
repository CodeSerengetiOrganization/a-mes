package com.ames.mes_api.stationgate;

/**
 * Station gate check / COMPLETE response.
 *
 * <p>No request echoes ({@code serialNumber} / {@code equipmentId} — client already has them).
 * MES-derived context + gate decision only.
 */
public class StationGateResponse {

	private boolean allowed;
	private String workOrderId;
	private String processPathId;
	private String thisOp;
	private String expectedNext;
	private String reasonCode;
	private String message;
	/** Set on successful COMPLETE append; null on check and plant denies. */
	private String outcome;

	public boolean isAllowed() {
		return allowed;
	}

	public void setAllowed(boolean allowed) {
		this.allowed = allowed;
	}

	public String getWorkOrderId() {
		return workOrderId;
	}

	public void setWorkOrderId(String workOrderId) {
		this.workOrderId = workOrderId;
	}

	public String getProcessPathId() {
		return processPathId;
	}

	public void setProcessPathId(String processPathId) {
		this.processPathId = processPathId;
	}

	public String getThisOp() {
		return thisOp;
	}

	public void setThisOp(String thisOp) {
		this.thisOp = thisOp;
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

	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
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
				+ ", workOrderId=" + workOrderId
				+ ", processPathId=" + processPathId
				+ ", thisOp=" + thisOp
				+ ", expectedNext=" + expectedNext
				+ ", reasonCode=" + reasonCode
				+ ", message=" + message
				+ ", outcome=" + outcome
				+ '}';
	}
}
