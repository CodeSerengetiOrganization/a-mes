package com.ames.mes_api.stationgate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Thin controller tests — HTTP contract only; plant rules stay in {@link StationGateServiceTest}.
 *
 * <p>COMPLETE HTTP status coverage: (1) allow → 200, (2) EOL WRONG_ENDPOINT → 200 not 409,
 * (3) missing equipmentLocalAt → 400.
 */
@WebMvcTest(controllers = StationGateController.class)
public class StationGateControllerTest {

	private static final String COMPLETE_COAT_BODY = """
			{
			  "serialNumber": "PANEL-DEMO-001",
			  "equipmentId": "AEL-01-COAT",
			  "equipmentLocalAt": "2026-08-26T08:15:00"
			}
			""";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private StationGateService stationGateService;

	@Test
	void check_whenBodyValid_returns200WithAllowed() throws Exception {
		StationGateResponse response = new StationGateResponse();
		response.setAllowed(true);
		response.setExpectedNext("COAT");
		response.setWorkOrderId("WO-DEMO-001");
		response.setProcessPathId("pp_cold_ambient");
		response.setThisOp("COAT");
		when(stationGateService.evaluateGate(any(StationGateRequest.class))).thenReturn(response);

		mockMvc.perform(post("/api/station-gate-checks")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "serialNumber": "PANEL-DEMO-001",
								  "equipmentId": "AEL-01-COAT"
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.allowed").value(true))
				.andExpect(jsonPath("$.expectedNext").value("COAT"))
				.andExpect(jsonPath("$.workOrderId").value("WO-DEMO-001"))
				.andExpect(jsonPath("$.processPathId").value("pp_cold_ambient"))
				.andExpect(jsonPath("$.thisOp").value("COAT"))
				.andExpect(jsonPath("$.serialNumber").doesNotExist())
				.andExpect(jsonPath("$.equipmentId").doesNotExist());

		verify(stationGateService).evaluateGate(any(StationGateRequest.class));
	}

	@Test
	void check_whenSerialNumberBlank_returns400AndDoesNotCallService() throws Exception {
		mockMvc.perform(post("/api/station-gate-checks")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "serialNumber": "  ",
								  "equipmentId": "AEL-01-COAT"
								}
								"""))
				.andExpect(status().isBadRequest());

		verify(stationGateService, never()).evaluateGate(any());
	}

	/**
	 * COMPLETE HTTP #1 — allow → 200 + routes to {@code complete} (not evaluateGate).
	 */
	@Test
	void complete_whenAllowed_returns200AndCallsComplete() throws Exception {
		when(stationGateService.complete(any(StationGateRequest.class)))
				.thenReturn(allowedComplete("COAT", "UV"));

		mockMvc.perform(post("/api/station-completes")
						.contentType(MediaType.APPLICATION_JSON)
						.content(COMPLETE_COAT_BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.allowed").value(true))
				.andExpect(jsonPath("$.outcome").value("COMPLETE"))
				.andExpect(jsonPath("$.expectedNext").value("UV"))
				.andExpect(jsonPath("$.workOrderId").value("WO-DEMO-001"))
				.andExpect(jsonPath("$.processPathId").value("pp_cold_ambient"))
				.andExpect(jsonPath("$.thisOp").value("COAT"))
				.andExpect(jsonPath("$.serialNumber").doesNotExist())
				.andExpect(jsonPath("$.equipmentId").doesNotExist());

		verify(stationGateService).complete(any(StationGateRequest.class));
		verify(stationGateService, never()).evaluateGate(any());
	}

	/**
	 * COMPLETE HTTP #2 — EOL plant deny (WRONG_ENDPOINT) → 200 (not 409).
	 */
	@Test
	void complete_whenEolWrongEndpoint_returns200NotConflict() throws Exception {
		when(stationGateService.complete(any(StationGateRequest.class)))
				.thenReturn(denied(
						"WRONG_ENDPOINT",
						"COLD_EOL",
						"COLD_EOL",
						"Quality PASS/FAIL belongs on the quality API — not station-completes"));

		mockMvc.perform(post("/api/station-completes")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "serialNumber": "PANEL-DEMO-001",
								  "equipmentId": "AEL-01-COLD-EOL",
								  "equipmentLocalAt": "2026-08-26T08:15:00"
								}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.allowed").value(false))
				.andExpect(jsonPath("$.reasonCode").value("WRONG_ENDPOINT"))
				.andExpect(jsonPath("$.thisOp").value("COLD_EOL"))
				.andExpect(jsonPath("$.expectedNext").value("COLD_EOL"))
				.andExpect(jsonPath("$.message")
						.value("Quality PASS/FAIL belongs on the quality API — not station-completes"))
				.andExpect(jsonPath("$.outcome").doesNotExist());

		verify(stationGateService).complete(any(StationGateRequest.class));
	}

	/**
	 * COMPLETE HTTP #3 — validation → 400; equipmentLocalAt required on COMPLETE.
	 */
	@Test
	void complete_whenEquipmentLocalAtMissing_returns400AndDoesNotCallService() throws Exception {
		mockMvc.perform(post("/api/station-completes")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "serialNumber": "PANEL-DEMO-001",
								  "equipmentId": "AEL-01-COAT"
								}
								"""))
				.andExpect(status().isBadRequest());

		verify(stationGateService, never()).complete(any());
	}

	private static StationGateResponse allowedComplete(String thisOp, String expectedNext) {
		StationGateResponse response = new StationGateResponse();
		response.setAllowed(true);
		response.setOutcome("COMPLETE");
		response.setExpectedNext(expectedNext);
		response.setWorkOrderId("WO-DEMO-001");
		response.setProcessPathId("pp_cold_ambient");
		response.setThisOp(thisOp);
		return response;
	}

	private static StationGateResponse denied(
			String reasonCode, String expectedNext, String thisOp, String message) {
		StationGateResponse response = new StationGateResponse();
		response.setAllowed(false);
		response.setReasonCode(reasonCode);
		response.setExpectedNext(expectedNext);
		response.setWorkOrderId("WO-DEMO-001");
		response.setProcessPathId("pp_cold_ambient");
		response.setThisOp(thisOp);
		response.setMessage(message);
		return response;
	}
}
