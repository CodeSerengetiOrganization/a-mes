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
 */
@WebMvcTest(controllers = StationGateController.class)
class StationGateControllerTest {

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

	@Test
	void complete_whenBodyValid_returns200WithOutcomeComplete() throws Exception {
		StationGateResponse response = new StationGateResponse();
		response.setAllowed(true);
		response.setExpectedNext("UV");
		response.setOutcome("COMPLETE");
		response.setWorkOrderId("WO-DEMO-001");
		response.setProcessPathId("pp_cold_ambient");
		response.setThisOp("COAT");
		when(stationGateService.complete(any(StationGateRequest.class))).thenReturn(response);

		mockMvc.perform(post("/api/station-completes")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "serialNumber": "PANEL-DEMO-001",
								  "equipmentId": "AEL-01-COAT",
								  "equipmentLocalAt": "2026-08-26T08:15:00"
								}
								"""))
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

	@Test
	void complete_whenSerialNumberBlank_returns400AndDoesNotCallService() throws Exception {
		mockMvc.perform(post("/api/station-completes")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "serialNumber": "  ",
								  "equipmentId": "AEL-01-COAT",
								  "equipmentLocalAt": "2026-08-26T08:15:00"
								}
								"""))
				.andExpect(status().isBadRequest());

		verify(stationGateService, never()).complete(any());
	}

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
}
