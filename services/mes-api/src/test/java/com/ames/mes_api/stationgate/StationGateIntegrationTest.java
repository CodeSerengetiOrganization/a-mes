package com.ames.mes_api.stationgate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ames.mes_api.panelregistration.PanelRegistrationEntity;
import com.ames.mes_api.panelregistration.PanelRegistrationRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Live-MySQL integration — Coat → Pack happy paths.
 *
 * <p>CI skips when {@code MYSQL_PASSWORD} is unset. Run locally: port-forward MySQL, set
 * {@code MYSQL_PASSWORD}, {@code local} profile (via {@code @ActiveProfiles}).
 *
 * <p>UV / EOL cannot COMPLETE here ({@code WRONG_ENDPOINT}); tests seed {@code PASS} rows
 * (AS-4 stub) so path next can advance past quality ops.
 */
@SpringBootTest
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+")
@Transactional
class StationGateIntegrationTest {

	/** Seeded WO → {@code pp_cold_ambient} (V3). */
	private static final String WO_COLD_AMBIENT = "WO-DEMO-002";

	/** Seeded WO → {@code pp_full_eol} (V3). */
	private static final String WO_FULL_EOL = "WO-DEMO-001";

	/** Dedicated serials so demo panels are not reused. Rolled back with the test txn. */
	private static final String SERIAL_COLD_AMBIENT = "PANEL-IT-COAT-PACK-001";
	private static final String SERIAL_FULL_EOL = "PANEL-IT-FULL-EOL-001";

	private static final LocalDateTime LOCAL_AT = LocalDateTime.of(2026, 8, 28, 17, 0);

	@Autowired
	private StationGateService stationGateService;

	@Autowired
	private PanelRegistrationRepository panelRegistrationRepository;

	@Autowired
	private OperationEventRepository operationEventRepository;

	@BeforeEach
	void seedJoinedPanels() {
		ensureJoined(SERIAL_COLD_AMBIENT, WO_COLD_AMBIENT);
		ensureJoined(SERIAL_FULL_EOL, WO_FULL_EOL);
	}

	@Test
	void complete_happyPath_coldAmbient_coatThroughPack_advancesExpectedNext() {
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
	void complete_happyPath_fullEol_coatThroughPack_advancesExpectedNext() {
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

	private void ensureJoined(String serial, String workOrderId) {
		if (panelRegistrationRepository.findByPanelNumber(serial).isEmpty()) {
			PanelRegistrationEntity join = new PanelRegistrationEntity();
			join.setPanelNumber(serial);
			join.setWorkOrderId(workOrderId);
			panelRegistrationRepository.saveAndFlush(join);
		}
	}

	private StationGateResponse check(String serial, String equipmentId) {
		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber(serial);
		request.setEquipmentId(equipmentId);
		return stationGateService.evaluateGate(request);
	}

	private StationGateResponse complete(String serial, String equipmentId) {
		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber(serial);
		request.setEquipmentId(equipmentId);
		request.setEquipmentLocalAt(LOCAL_AT);
		StationGateResponse response = stationGateService.complete(request);
		operationEventRepository.flush();
		return response;
	}

	private void assertCompleteAdvances(String serial, String equipmentId, String expectedNextAfter) {
		StationGateResponse response = complete(serial, equipmentId);
		assertTrue(response.isAllowed(), () -> "expected allow at " + equipmentId + " but got " + response);
		assertEquals("COMPLETE", response.getOutcome());
		assertEquals(expectedNextAfter, response.getExpectedNext());
	}

	/** Gate check after quality PASS — path next must advance before the next step. */
	private void assertGateShowsNext(String serial, String equipmentId, String expectedNext) {
		StationGateResponse response = check(serial, equipmentId);
		assertTrue(response.isAllowed(), () -> "expected gate allow at " + equipmentId + " but got " + response);
		assertEquals(expectedNext, response.getExpectedNext());
	}

	/** AS-4 stub — quality PASS advances path; not written via station-completes. */
	private void seedPass(String serial, String opCode, String equipmentId) {
		OperationEventEntity event = new OperationEventEntity();
		event.setSerialNumber(serial);
		event.setOpCode(opCode);
		event.setEquipmentId(equipmentId);
		event.setOutcome("PASS");
		event.setEquipmentLocalAt(LOCAL_AT);
		operationEventRepository.saveAndFlush(event);
	}
}
