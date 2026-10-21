package com.ames.mes_api.depanel;

import static org.hamcrest.Matchers.nullValue;
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
 * Thin controller tests — HTTP status only. Plant rules stay in {@link DepanelSplitServiceTest}.
 *
 * <p>Stored → 201. Hold or gate refuse → 200. Incomplete body → 400 before the service.
 */
@WebMvcTest(controllers = DepanelSplitController.class)
class DepanelSplitControllerTest {

	private static final String SPLIT_BODY = """
			{
			  "panelNumber": "PANEL-APCU-20260729-0042",
			  "equipmentId": "AEL-01-DEPANEL",
			  "equipmentLocalAt": "2026-10-16T19:30:00",
			  "cavities": [
			    { "slot": "A1", "cavityResult": "PCBA-APCU-0000842101" },
			    { "slot": "A2", "cavityResult": "PCBA-APCU-0000842102" },
			    { "slot": "B1", "cavityResult": "PCBA-APCU-0000842103" },
			    { "slot": "B2", "cavityResult": "PCBA-APCU-0000842104" }
			  ]
			}
			""";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private DepanelSplitService depanelSplitService;

	@Test
	void split_whenStored_returns201() throws Exception {
		when(depanelSplitService.split(any(DepanelSplitRequest.class))).thenReturn(stored());

		mockMvc.perform(post("/api/depanel-splits")
						.contentType(MediaType.APPLICATION_JSON)
						.content(SPLIT_BODY))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.stored").value(true))
				.andExpect(jsonPath("$.panelNumber").value("PANEL-APCU-20260729-0042"))
				.andExpect(jsonPath("$.outcome").value("COMPLETE"))
				.andExpect(jsonPath("$.reasonCode").value(nullValue()))
				.andExpect(jsonPath("$.message").value(nullValue()))
				.andExpect(jsonPath("$.workOrderId").doesNotExist())
				.andExpect(jsonPath("$.expectedNext").doesNotExist());

		verify(depanelSplitService).split(any(DepanelSplitRequest.class));
	}

	@Test
	void split_whenNotStored_returns200() throws Exception {
		when(depanelSplitService.split(any(DepanelSplitRequest.class))).thenReturn(hold());

		mockMvc.perform(post("/api/depanel-splits")
						.contentType(MediaType.APPLICATION_JSON)
						.content(SPLIT_BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.stored").value(false))
				.andExpect(jsonPath("$.panelNumber").value("PANEL-APCU-20260729-0042"))
				.andExpect(jsonPath("$.reasonCode").value("HOLD"))
				.andExpect(jsonPath("$.message").value("Bad cavity. MES stored nothing."))
				.andExpect(jsonPath("$.outcome").value(nullValue()));

		verify(depanelSplitService).split(any(DepanelSplitRequest.class));
	}

	@Test
	void split_whenCavitiesEmpty_returns400AndDoesNotCallService() throws Exception {
		mockMvc.perform(post("/api/depanel-splits")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "panelNumber": "PANEL-APCU-20260729-0042",
								  "equipmentId": "AEL-01-DEPANEL",
								  "equipmentLocalAt": "2026-10-16T19:30:00",
								  "cavities": []
								}
								"""))
				.andExpect(status().isBadRequest());

		verify(depanelSplitService, never()).split(any());
	}

	private static DepanelSplitResponse stored() {
		DepanelSplitResponse response = new DepanelSplitResponse();
		response.setStored(true);
		response.setPanelNumber("PANEL-APCU-20260729-0042");
		response.setOutcome("COMPLETE");
		return response;
	}

	private static DepanelSplitResponse hold() {
		DepanelSplitResponse response = new DepanelSplitResponse();
		response.setStored(false);
		response.setPanelNumber("PANEL-APCU-20260729-0042");
		response.setReasonCode("HOLD");
		response.setMessage("Bad cavity. MES stored nothing.");
		return response;
	}
}
