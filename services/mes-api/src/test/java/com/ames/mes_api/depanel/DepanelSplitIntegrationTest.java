package com.ames.mes_api.depanel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ames.mes_api.panelregistration.PanelRegistrationEntity;
import com.ames.mes_api.panelregistration.PanelRegistrationRepository;
import com.ames.mes_api.stationgate.OperationEventEntity;
import com.ames.mes_api.stationgate.OperationEventRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Live-MySQL proof for the depanel split. The station gate already owns the process path.
 *
 * <p>CI skips when {@code MYSQL_PASSWORD} is unset. Run locally: port-forward MySQL and set
 * {@code MYSQL_PASSWORD}. The {@code local} profile is set here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+")
@Transactional
class DepanelSplitIntegrationTest {

	private static final String SPLIT_PATH = "/api/depanel-splits";

	/** Seeded binding is {@code pp_cold_ambient}. This test does not assert the path. */
	private static final String WO_COLD_AMBIENT = "WO-DEMO-002";

	private static final String PANEL_STORED = "PANEL-IT-DEPANEL-001";
	private static final String PANEL_XOUT = "PANEL-IT-DEPANEL-XOUT-001";

	private static final LocalDateTime LOCAL_AT = LocalDateTime.of(2026, 10, 16, 19, 30);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PanelRegistrationRepository panelRegistrationRepository;

	@Autowired
	private OperationEventRepository operationEventRepository;

	@Autowired
	private DepanelSplitRepository depanelSplitRepository;

	@Test
	void storedSplit_thenSecondRequest_readsRowsAndStoresNothingNew() throws Exception {
		seedReadyForDepanel(PANEL_STORED);

		MvcResult stored = postSplit(PANEL_STORED, buildFourPcbaCavities());
		assertEquals(201, stored.getResponse().getStatus());
		DepanelSplitResponse storedBody = readResponse(stored);
		assertTrue(storedBody.isStored());
		assertEquals(PANEL_STORED, storedBody.getPanelNumber());
		assertEquals("COMPLETE", storedBody.getOutcome());
		assertNull(storedBody.getReasonCode());
		assertNull(storedBody.getMessage());

		flushWrites();
		List<DepanelSplitEntity> rows = depanelSplitRepository.findByPanelNumberOrderBySlotAsc(PANEL_STORED);
		assertEquals(List.of("A1", "A2", "B1", "B2"), rows.stream().map(DepanelSplitEntity::getSlot).toList());
		assertEquals(
				List.of(
						"PCBA-APCU-0000842101",
						"PCBA-APCU-0000842102",
						"PCBA-APCU-0000842103",
						"PCBA-APCU-0000842104"),
				rows.stream().map(DepanelSplitEntity::getPcbaSerial).toList());
		assertTrue(rows.stream().allMatch(row -> PANEL_STORED.equals(row.getPanelNumber())));
		LocalDateTime recordedAt = rows.get(0).getRecordedAt();
		assertNotNull(recordedAt);
		assertTrue(rows.stream().allMatch(row -> recordedAt.equals(row.getRecordedAt())));

		List<OperationEventEntity> depanelEvents = findDepanelEvents(PANEL_STORED);
		assertEquals(1, depanelEvents.size());
		assertEquals("COMPLETE", depanelEvents.get(0).getOutcome());
		assertEquals(WO_COLD_AMBIENT, panelRegistrationRepository.findByPanelNumber(PANEL_STORED).orElseThrow().getWorkOrderId());

		MvcResult second = postSplit(PANEL_STORED, buildFourPcbaCavities());
		assertEquals(200, second.getResponse().getStatus());
		DepanelSplitResponse secondBody = readResponse(second);
		assertFalse(secondBody.isStored());
		assertEquals("ALREADY_COMPLETE", secondBody.getReasonCode());
		assertEquals("This panel is already split. MES stored nothing.", secondBody.getMessage());
		assertNull(secondBody.getOutcome());

		flushWrites();
		assertEquals(4, depanelSplitRepository.findByPanelNumberOrderBySlotAsc(PANEL_STORED).size());
		assertEquals(1, findDepanelEvents(PANEL_STORED).size());
	}

	@Test
	void xout_holdsAndStoresNothing() throws Exception {
		seedReadyForDepanel(PANEL_XOUT);

		MvcResult result = postSplit(PANEL_XOUT, buildCavitiesWithXout());
		assertEquals(200, result.getResponse().getStatus());
		DepanelSplitResponse body = readResponse(result);
		assertFalse(body.isStored());
		assertEquals("HOLD", body.getReasonCode());
		assertEquals(DepanelSplitService.HOLD_MESSAGE, body.getMessage());
		assertNull(body.getOutcome());

		flushWrites();
		assertTrue(depanelSplitRepository.findByPanelNumberOrderBySlotAsc(PANEL_XOUT).isEmpty());
		assertTrue(findDepanelEvents(PANEL_XOUT).isEmpty());
		assertEquals(WO_COLD_AMBIENT, panelRegistrationRepository.findByPanelNumber(PANEL_XOUT).orElseThrow().getWorkOrderId());
	}

	/**
	 * Panel registration satisfies Loader. Coat COMPLETE and UV PASS make Depanel the next step.
	 * Same advance as {@code StationGateIntegrationTest}.
	 */
	private void seedReadyForDepanel(String panelNumber) {
		PanelRegistrationEntity join = new PanelRegistrationEntity();
		join.setPanelNumber(panelNumber);
		join.setWorkOrderId(WO_COLD_AMBIENT);
		panelRegistrationRepository.saveAndFlush(join);
		seedEvent(panelNumber, "COAT", "AEL-01-COAT", "COMPLETE");
		seedEvent(panelNumber, "UV", "AEL-01-UV", "PASS");
	}

	private void seedEvent(String panelNumber, String opCode, String equipmentId, String outcome) {
		OperationEventEntity event = new OperationEventEntity();
		event.setSerialNumber(panelNumber);
		event.setOpCode(opCode);
		event.setEquipmentId(equipmentId);
		event.setOutcome(outcome);
		event.setEquipmentLocalAt(LOCAL_AT);
		operationEventRepository.saveAndFlush(event);
	}

	private List<Map<String, String>> buildFourPcbaCavities() {
		return List.of(
				buildCavity("A1", "PCBA-APCU-0000842101"),
				buildCavity("A2", "PCBA-APCU-0000842102"),
				buildCavity("B1", "PCBA-APCU-0000842103"),
				buildCavity("B2", "PCBA-APCU-0000842104"));
	}

	private List<Map<String, String>> buildCavitiesWithXout() {
		return List.of(
				buildCavity("A1", "PCBA-APCU-0000842101"),
				buildCavity("A2", "PCBA-APCU-0000842102"),
				buildCavity("B1", "PCBA-APCU-0000842103"),
				buildCavity("B2", "XOUT"));
	}

	private static Map<String, String> buildCavity(String slot, String cavityResult) {
		Map<String, String> cavity = new LinkedHashMap<>();
		cavity.put("slot", slot);
		cavity.put("cavityResult", cavityResult);
		return cavity;
	}

	private MvcResult postSplit(String panelNumber, List<Map<String, String>> cavities) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("panelNumber", panelNumber);
		body.put("equipmentId", "AEL-01-DEPANEL");
		body.put("equipmentLocalAt", LOCAL_AT);
		body.put("cavities", cavities);
		return mockMvc.perform(post(SPLIT_PATH)
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(body)))
				.andReturn();
	}

	private DepanelSplitResponse readResponse(MvcResult result) throws Exception {
		return objectMapper.readValue(result.getResponse().getContentAsString(), DepanelSplitResponse.class);
	}

	private void flushWrites() {
		operationEventRepository.flush();
		depanelSplitRepository.flush();
	}

	private List<OperationEventEntity> findDepanelEvents(String panelNumber) {
		return operationEventRepository.findBySerialNumberOrderByIdAsc(panelNumber).stream()
				.filter(event -> "DEPANEL".equals(event.getOpCode()))
				.toList();
	}
}
