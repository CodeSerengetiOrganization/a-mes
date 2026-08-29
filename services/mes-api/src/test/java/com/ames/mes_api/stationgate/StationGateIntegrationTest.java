package com.ames.mes_api.stationgate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ames.mes_api.panelregistration.PanelRegistrationEntity;
import com.ames.mes_api.panelregistration.PanelRegistrationRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
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
 * Live-MySQL integration — station gate HTTP ({@code MockMvc}) + cold-ambient deny matrix.
 *
 * <p>CI skips when {@code MYSQL_PASSWORD} is unset. Run locally: port-forward MySQL, set
 * {@code MYSQL_PASSWORD}, {@code local} profile (via {@code @ActiveProfiles}).
 *
 * <p>UV / EOL cannot COMPLETE here ({@code WRONG_ENDPOINT}). Happy-path / replay tests use
 * {@link #seedPass} — repo-only AS-4 stub, not quality HTTP — so path next can advance past
 * quality ops.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+")
@Transactional
class StationGateIntegrationTest {

	private static final String CHECK_PATH = "/api/station-gate-checks";
	private static final String COMPLETE_PATH = "/api/station-completes";

	/** Seeded WO → {@code pp_cold_ambient} (V3). */
	private static final String WO_COLD_AMBIENT = "WO-DEMO-002";

	private static final String PP_COLD_AMBIENT = "pp_cold_ambient";

	/** Seeded WO → {@code pp_full_eol} (V3). */
	private static final String WO_FULL_EOL = "WO-DEMO-001";

	/** Dedicated serials so demo panels are not reused. Rolled back with the test txn. */
	private static final String SERIAL_COLD_AMBIENT = "PANEL-IT-COAT-PACK-001";
	private static final String SERIAL_FULL_EOL = "PANEL-IT-FULL-EOL-001";

	private static final String SERIAL_ORPHAN = "PANEL-IT-ORPHAN-001";
	private static final String SERIAL_WRONG_STATION = "PANEL-IT-WRONG-STN-001";
	private static final String SERIAL_DOUBLE_COAT = "PANEL-IT-DBL-COAT-001";
	private static final String SERIAL_REPLAY_COAT = "PANEL-IT-REPLAY-COAT-001";
	private static final String SERIAL_LOADER = "PANEL-IT-LOADER-001";
	private static final String SERIAL_UV_WRONG_ENDPOINT = "PANEL-IT-UV-WE-001";
	private static final String SERIAL_CHAMBER = "PANEL-IT-CHAMBER-001";

	private static final String MSG_ORPHAN = "Serial not joined to a work order";
	private static final String MSG_WRONG_STATION_COAT = "Expected next: COAT";
	private static final String MSG_WRONG_ENDPOINT_LOADER =
			"LOADER is satisfied by Panel Registration — not station-completes";
	private static final String MSG_WRONG_ENDPOINT_QUALITY =
			"Quality PASS/FAIL belongs on the quality API — not station-completes";
	private static final String MSG_WRONG_ENDPOINT_CHAMBER =
			"Chamber soak is not a station-completes step — no COMPLETE here";

	private static final LocalDateTime LOCAL_AT = LocalDateTime.of(2026, 8, 28, 17, 0);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PanelRegistrationRepository panelRegistrationRepository;

	@Autowired
	private OperationEventRepository operationEventRepository;

	@BeforeEach
	void seedJoinedPanels() {
		// Happy-path serials only — deny tests join their own serials; orphan stays unjoined.
		ensureJoined(SERIAL_COLD_AMBIENT, WO_COLD_AMBIENT);
		ensureJoined(SERIAL_FULL_EOL, WO_FULL_EOL);
	}

	@Test
	void complete_happyPath_coldAmbient_coatThroughPack_advancesExpectedNext() throws Exception {
		// LOADER done via Panel Registration join — may work Coating now
		assertGateShowsNext(SERIAL_COLD_AMBIENT, "AEL-01-COAT", "COAT");

		assertCompleteAdvances(SERIAL_COLD_AMBIENT, "AEL-01-COAT", "UV");
		seedPass(SERIAL_COLD_AMBIENT, "UV", "AEL-01-UV");
		assertGateShowsNext(SERIAL_COLD_AMBIENT, "AEL-01-DEPANEL", "DEPANEL");

		assertCompleteAdvances(SERIAL_COLD_AMBIENT, "AEL-01-DEPANEL", "CURE");
		assertCompleteAdvances(SERIAL_COLD_AMBIENT, "AEL-01-CURE", "ASM");
		assertCompleteAdvances(SERIAL_COLD_AMBIENT, "AEL-01-ASM", "COLD_EOL");

		seedPass(SERIAL_COLD_AMBIENT, "COLD_EOL", "AEL-01-COLD-EOL");
		assertGateShowsNext(SERIAL_COLD_AMBIENT, "AEL-01-AMBIENT-EOL", "AMBIENT_EOL");
		seedPass(SERIAL_COLD_AMBIENT, "AMBIENT_EOL", "AEL-01-AMBIENT-EOL");
		assertGateShowsNext(SERIAL_COLD_AMBIENT, "AEL-01-PACK", "PACK");

		StationGateResponse pack = complete(SERIAL_COLD_AMBIENT, "AEL-01-PACK");
		assertTrue(pack.isAllowed());
		assertEquals("COMPLETE", pack.getOutcome());
		assertNull(pack.getExpectedNext());

		List<OperationEventEntity> events =
				operationEventRepository.findBySerialNumberOrderByIdAsc(SERIAL_COLD_AMBIENT);
		assertEquals(8, events.size());
		assertEquals(
				List.of("COAT", "UV", "DEPANEL", "CURE", "ASM", "COLD_EOL", "AMBIENT_EOL", "PACK"),
				events.stream().map(OperationEventEntity::getOpCode).toList());
		assertEquals(
				List.of("COMPLETE", "PASS", "COMPLETE", "COMPLETE", "COMPLETE", "PASS", "PASS", "COMPLETE"),
				events.stream().map(OperationEventEntity::getOutcome).toList());
	}

	@Test
	void complete_happyPath_fullEol_coatThroughPack_advancesExpectedNext() throws Exception {
		// pp_full_eol (WO-DEMO-001): Cold → Hot → Ambient → Pack
		assertGateShowsNext(SERIAL_FULL_EOL, "AEL-01-COAT", "COAT");

		assertCompleteAdvances(SERIAL_FULL_EOL, "AEL-01-COAT", "UV");
		seedPass(SERIAL_FULL_EOL, "UV", "AEL-01-UV");
		assertGateShowsNext(SERIAL_FULL_EOL, "AEL-01-DEPANEL", "DEPANEL");

		assertCompleteAdvances(SERIAL_FULL_EOL, "AEL-01-DEPANEL", "CURE");
		assertCompleteAdvances(SERIAL_FULL_EOL, "AEL-01-CURE", "ASM");
		assertCompleteAdvances(SERIAL_FULL_EOL, "AEL-01-ASM", "COLD_EOL");

		seedPass(SERIAL_FULL_EOL, "COLD_EOL", "AEL-01-COLD-EOL");
		assertGateShowsNext(SERIAL_FULL_EOL, "AEL-01-HOT-EOL", "HOT_EOL");
		seedPass(SERIAL_FULL_EOL, "HOT_EOL", "AEL-01-HOT-EOL");
		assertGateShowsNext(SERIAL_FULL_EOL, "AEL-01-AMBIENT-EOL", "AMBIENT_EOL");
		seedPass(SERIAL_FULL_EOL, "AMBIENT_EOL", "AEL-01-AMBIENT-EOL");
		assertGateShowsNext(SERIAL_FULL_EOL, "AEL-01-PACK", "PACK");

		StationGateResponse pack = complete(SERIAL_FULL_EOL, "AEL-01-PACK");
		assertTrue(pack.isAllowed());
		assertEquals("COMPLETE", pack.getOutcome());
		assertNull(pack.getExpectedNext());

		List<OperationEventEntity> events =
				operationEventRepository.findBySerialNumberOrderByIdAsc(SERIAL_FULL_EOL);
		assertEquals(9, events.size());
		assertEquals(
				List.of(
						"COAT",
						"UV",
						"DEPANEL",
						"CURE",
						"ASM",
						"COLD_EOL",
						"HOT_EOL",
						"AMBIENT_EOL",
						"PACK"),
				events.stream().map(OperationEventEntity::getOpCode).toList());
		assertEquals(
				List.of(
						"COMPLETE",
						"PASS",
						"COMPLETE",
						"COMPLETE",
						"COMPLETE",
						"PASS",
						"PASS",
						"PASS",
						"COMPLETE"),
				events.stream().map(OperationEventEntity::getOutcome).toList());
	}

	@Test
	void deny_orphan_checkAndComplete_noEvents() throws Exception {
		StationGateResponse checkResponse = check(SERIAL_ORPHAN, "AEL-01-COAT");
		assertDenied(checkResponse, "ORPHAN", null, MSG_ORPHAN);
		assertNull(checkResponse.getWorkOrderId());
		assertNull(checkResponse.getProcessPathId());
		assertEquals("COAT", checkResponse.getThisOp());

		StationGateResponse completeResponse = complete(SERIAL_ORPHAN, "AEL-01-COAT");
		assertDenied(completeResponse, "ORPHAN", null, MSG_ORPHAN);
		assertNull(completeResponse.getOutcome());

		assertEventCount(SERIAL_ORPHAN, 0);
	}

	@Test
	void deny_wrongStation_aheadAtAssembly_checkAndComplete_noEvents() throws Exception {
		ensureJoined(SERIAL_WRONG_STATION, WO_COLD_AMBIENT);

		StationGateResponse checkResponse = check(SERIAL_WRONG_STATION, "AEL-01-ASM");
		assertDenied(checkResponse, "WRONG_STATION", "COAT", MSG_WRONG_STATION_COAT);
		assertEquals(WO_COLD_AMBIENT, checkResponse.getWorkOrderId());
		assertEquals(PP_COLD_AMBIENT, checkResponse.getProcessPathId());
		assertEquals("ASM", checkResponse.getThisOp());

		StationGateResponse completeResponse = complete(SERIAL_WRONG_STATION, "AEL-01-ASM");
		assertDenied(completeResponse, "WRONG_STATION", "COAT", MSG_WRONG_STATION_COAT);
		assertNull(completeResponse.getOutcome());

		assertEventCount(SERIAL_WRONG_STATION, 0);
	}

	@Test
	void deny_alreadyComplete_doubleCheckAndCompleteAtCoat_oneEventOnly() throws Exception {
		ensureJoined(SERIAL_DOUBLE_COAT, WO_COLD_AMBIENT);

		assertGateShowsNext(SERIAL_DOUBLE_COAT, "AEL-01-COAT", "COAT");
		assertCompleteAdvances(SERIAL_DOUBLE_COAT, "AEL-01-COAT", "UV");
		assertEventCount(SERIAL_DOUBLE_COAT, 1);

		StationGateResponse secondCheck = check(SERIAL_DOUBLE_COAT, "AEL-01-COAT");
		assertDeniedWithoutMessage(secondCheck, "ALREADY_COMPLETE", "UV");
		assertEquals("COAT", secondCheck.getThisOp());

		StationGateResponse secondComplete = complete(SERIAL_DOUBLE_COAT, "AEL-01-COAT");
		assertDeniedWithoutMessage(secondComplete, "ALREADY_COMPLETE", "UV");
		assertNull(secondComplete.getOutcome());

		assertEventCount(SERIAL_DOUBLE_COAT, 1);
	}

	@Test
	void deny_alreadyComplete_replayCoatAfterPathAdvanced_twoEventsOnly() throws Exception {
		ensureJoined(SERIAL_REPLAY_COAT, WO_COLD_AMBIENT);

		assertCompleteAdvances(SERIAL_REPLAY_COAT, "AEL-01-COAT", "UV");
		seedPass(SERIAL_REPLAY_COAT, "UV", "AEL-01-UV");
		assertGateShowsNext(SERIAL_REPLAY_COAT, "AEL-01-DEPANEL", "DEPANEL");

		StationGateResponse replayCheck = check(SERIAL_REPLAY_COAT, "AEL-01-COAT");
		assertDeniedWithoutMessage(replayCheck, "ALREADY_COMPLETE", "DEPANEL");

		StationGateResponse replayComplete = complete(SERIAL_REPLAY_COAT, "AEL-01-COAT");
		assertDeniedWithoutMessage(replayComplete, "ALREADY_COMPLETE", "DEPANEL");
		assertNull(replayComplete.getOutcome());

		assertEventCount(SERIAL_REPLAY_COAT, 2);
	}

	@Test
	void deny_loader_checkAlreadyComplete_completeWrongEndpoint_noEvents() throws Exception {
		ensureJoined(SERIAL_LOADER, WO_COLD_AMBIENT);

		StationGateResponse checkResponse = check(SERIAL_LOADER, "AEL-01-LOADER");
		assertDeniedWithoutMessage(checkResponse, "ALREADY_COMPLETE", "COAT");
		assertEquals("LOADER", checkResponse.getThisOp());
		assertEquals(WO_COLD_AMBIENT, checkResponse.getWorkOrderId());
		assertEquals(PP_COLD_AMBIENT, checkResponse.getProcessPathId());

		StationGateResponse completeResponse = complete(SERIAL_LOADER, "AEL-01-LOADER");
		assertDenied(completeResponse, "WRONG_ENDPOINT", "COAT", MSG_WRONG_ENDPOINT_LOADER);
		assertEquals("LOADER", completeResponse.getThisOp());
		assertNull(completeResponse.getOutcome());

		assertEventCount(SERIAL_LOADER, 0);
	}

	@Test
	void deny_uvComplete_wrongEndpoint_checkAllowed_oneCoatEventOnly() throws Exception {
		ensureJoined(SERIAL_UV_WRONG_ENDPOINT, WO_COLD_AMBIENT);

		assertCompleteAdvances(SERIAL_UV_WRONG_ENDPOINT, "AEL-01-COAT", "UV");
		assertGateShowsNext(SERIAL_UV_WRONG_ENDPOINT, "AEL-01-UV", "UV");

		StationGateResponse uvComplete = complete(SERIAL_UV_WRONG_ENDPOINT, "AEL-01-UV");
		assertDenied(uvComplete, "WRONG_ENDPOINT", "UV", MSG_WRONG_ENDPOINT_QUALITY);
		assertEquals("UV", uvComplete.getThisOp());
		assertNull(uvComplete.getOutcome());

		assertEventCount(SERIAL_UV_WRONG_ENDPOINT, 1);
	}

	@Test
	void deny_chamber_checkWrongStation_completeWrongEndpoint_noEvents() throws Exception {
		ensureJoined(SERIAL_CHAMBER, WO_COLD_AMBIENT);

		StationGateResponse checkResponse = check(SERIAL_CHAMBER, "AEL-01-COLD-CHAMBER");
		assertDenied(checkResponse, "WRONG_STATION", "COAT", MSG_WRONG_STATION_COAT);
		assertEquals(WO_COLD_AMBIENT, checkResponse.getWorkOrderId());
		assertEquals(PP_COLD_AMBIENT, checkResponse.getProcessPathId());
		assertEquals("COLD_CHAMBER", checkResponse.getThisOp());

		StationGateResponse completeResponse = complete(SERIAL_CHAMBER, "AEL-01-COLD-CHAMBER");
		assertDenied(completeResponse, "WRONG_ENDPOINT", "COAT", MSG_WRONG_ENDPOINT_CHAMBER);
		assertEquals(WO_COLD_AMBIENT, completeResponse.getWorkOrderId());
		assertEquals(PP_COLD_AMBIENT, completeResponse.getProcessPathId());
		assertNull(completeResponse.getOutcome());

		assertEventCount(SERIAL_CHAMBER, 0);
	}

	@Test
	void check_unknownEquipmentId_returns400AndDoesNotAppend() throws Exception {
		mockMvc.perform(post(CHECK_PATH)
						.contentType(MediaType.APPLICATION_JSON)
						.content(checkBody(SERIAL_COLD_AMBIENT, "UNKNOWN-EQUIPMENT")))
				.andExpect(status().isBadRequest());

		assertEventCount(SERIAL_COLD_AMBIENT, 0);
	}

	@Test
	void complete_unknownEquipmentId_returns400AndDoesNotAppend() throws Exception {
		mockMvc.perform(post(COMPLETE_PATH)
						.contentType(MediaType.APPLICATION_JSON)
						.content(completeBody(SERIAL_COLD_AMBIENT, "UNKNOWN-EQUIPMENT")))
				.andExpect(status().isBadRequest());

		assertEventCount(SERIAL_COLD_AMBIENT, 0);
	}

	private void ensureJoined(String serial, String workOrderId) {
		if (panelRegistrationRepository.findByPanelNumber(serial).isEmpty()) {
			PanelRegistrationEntity join = new PanelRegistrationEntity();
			join.setPanelNumber(serial);
			join.setWorkOrderId(workOrderId);
			panelRegistrationRepository.saveAndFlush(join);
		}
	}

	private StationGateResponse check(String serial, String equipmentId) throws Exception {
		MvcResult result = mockMvc.perform(post(CHECK_PATH)
						.contentType(MediaType.APPLICATION_JSON)
						.content(checkBody(serial, equipmentId)))
				.andExpect(status().isOk())
				.andReturn();
		return readResponse(result);
	}

	private StationGateResponse complete(String serial, String equipmentId) throws Exception {
		MvcResult result = mockMvc.perform(post(COMPLETE_PATH)
						.contentType(MediaType.APPLICATION_JSON)
						.content(completeBody(serial, equipmentId)))
				.andExpect(status().isOk())
				.andReturn();
		operationEventRepository.flush();
		return readResponse(result);
	}

	private String checkBody(String serial, String equipmentId) throws Exception {
		Map<String, String> body = new LinkedHashMap<>();
		body.put("serialNumber", serial);
		body.put("equipmentId", equipmentId);
		return objectMapper.writeValueAsString(body);
	}

	private String completeBody(String serial, String equipmentId) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("serialNumber", serial);
		body.put("equipmentId", equipmentId);
		body.put("equipmentLocalAt", LOCAL_AT);
		return objectMapper.writeValueAsString(body);
	}

	private StationGateResponse readResponse(MvcResult result) throws Exception {
		return objectMapper.readValue(result.getResponse().getContentAsString(), StationGateResponse.class);
	}

	private void assertCompleteAdvances(String serial, String equipmentId, String expectedNextAfter)
			throws Exception {
		StationGateResponse response = complete(serial, equipmentId);
		assertTrue(response.isAllowed(), () -> "expected allow at " + equipmentId + " but got " + response);
		assertEquals("COMPLETE", response.getOutcome());
		assertEquals(expectedNextAfter, response.getExpectedNext());
	}

	/** Gate check after quality PASS — path next must advance before the next step. */
	private void assertGateShowsNext(String serial, String equipmentId, String expectedNext) throws Exception {
		StationGateResponse response = check(serial, equipmentId);
		assertTrue(response.isAllowed(), () -> "expected gate allow at " + equipmentId + " but got " + response);
		assertEquals(expectedNext, response.getExpectedNext());
	}

	/** AS-4 stub — inserts PASS via repo only; quality HTTP is out of scope for AS3-02. */
	private void seedPass(String serial, String opCode, String equipmentId) {
		OperationEventEntity event = new OperationEventEntity();
		event.setSerialNumber(serial);
		event.setOpCode(opCode);
		event.setEquipmentId(equipmentId);
		event.setOutcome("PASS");
		event.setEquipmentLocalAt(LOCAL_AT);
		operationEventRepository.saveAndFlush(event);
	}

	private void assertDenied(
			StationGateResponse response, String reasonCode, String expectedNext, String message) {
		assertFalse(response.isAllowed(), () -> "expected deny but got " + response);
		assertEquals(reasonCode, response.getReasonCode());
		assertEquals(expectedNext, response.getExpectedNext());
		assertEquals(message, response.getMessage());
	}

	/**
	 * Deny with no plant {@code message} — e.g. {@code ALREADY_COMPLETE} (service sets none today).
	 * Update tests if plant copy is added later.
	 */
	private void assertDeniedWithoutMessage(
			StationGateResponse response, String reasonCode, String expectedNext) {
		assertDenied(response, reasonCode, expectedNext, null);
	}

	private void assertEventCount(String serial, int expected) {
		assertEquals(
				expected,
				operationEventRepository.findBySerialNumberOrderByIdAsc(serial).size());
	}
}
