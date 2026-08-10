package com.ames.mes_api.panelregistration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Thin controller tests (Maya draft — please review).
 *
 * <p>Only HTTP contract sanity: 201 happy path + 400 when {@code @Valid} fails.
 * Plant rules (404 / 409 / trim / save) stay in {@link PanelRegistrationServiceTest}.
 * Service is mocked — no MySQL.
 */
@WebMvcTest(controllers = PanelRegistrationController.class)
class PanelRegistrationControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PanelRegistrationService panelRegistrationService;

	/**
	 * Valid body → controller returns 201 and JSON fields from the service response.
	 */
	@Test
	void register_whenBodyValid_returns201WithContractFields() throws Exception {
		when(panelRegistrationService.register(any(PanelRegistrationRequest.class)))
				.thenReturn(new PanelRegistrationResponse(
						"PANEL-DEMO-004",
						"WO-DEMO-001",
						LocalDateTime.of(2026, 8, 9, 20, 15, 0)));

		mockMvc.perform(post("/api/panel-registrations")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "panelNumber": "PANEL-DEMO-004",
								  "workOrderId": "WO-DEMO-001"
								}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.panelNumber").value("PANEL-DEMO-004"))
				.andExpect(jsonPath("$.workOrderId").value("WO-DEMO-001"))
				.andExpect(jsonPath("$.registeredAt").exists());

		verify(panelRegistrationService).register(any(PanelRegistrationRequest.class));
	}

	/**
	 * Blank panelNumber → {@code @Valid} / {@code @NotBlank} fails before service → 400.
	 */
	@Test
	void register_whenPanelNumberBlank_returns400AndDoesNotCallService() throws Exception {
		mockMvc.perform(post("/api/panel-registrations")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "panelNumber": "  ",
								  "workOrderId": "WO-DEMO-001"
								}
								"""))
				.andExpect(status().isBadRequest());

		verify(panelRegistrationService, never()).register(any());
	}
}
