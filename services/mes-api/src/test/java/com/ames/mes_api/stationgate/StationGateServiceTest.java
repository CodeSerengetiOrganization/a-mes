package com.ames.mes_api.stationgate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ames.mes_api.panelregistration.PanelRegistrationEntity;
import com.ames.mes_api.panelregistration.PanelRegistrationRepository;
import com.ames.mes_api.panelregistration.WoPpBindingEntity;
import com.ames.mes_api.panelregistration.WoPpBindingRepository;
import com.ames.mes_api.processpath.ProcessPathEntity;
import com.ames.mes_api.processpath.ProcessPathRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link StationGateService} — Mockito only, no MySQL.
 *
 * <p>Happy path: serial joined to a WO (LOADER via join rule), no events yet, scan at Coating → allowed.
 */
@ExtendWith(MockitoExtension.class)
public class StationGateServiceTest {

	private static final String PATH_CONTENT =
			"{\"name\":\"Cold + Ambient\",\"ops\":[\"LOADER\",\"COAT\",\"UV\",\"DEPANEL\",\"CURE\",\"ASM\",\"COLD_EOL\",\"AMBIENT_EOL\",\"PACK\"]}";

	@Mock
	private PanelRegistrationRepository panelRegistrationRepository;

	@Mock
	private ProcessPathRepository processPathRepository;

	@Mock
	private WoPpBindingRepository woPpBindingRepository;

	@Mock
	private OperationEventRepository operationEventRepository;

	private StationGateService stationGateService;

	@BeforeEach
	void setUp() {
		stationGateService = new StationGateService(
				panelRegistrationRepository,
				processPathRepository,
				woPpBindingRepository,
				operationEventRepository,
				new JsonMapper(),
				"COAT,DEPANEL,CURE,ASM,PACK",
				"UV,COLD_EOL,HOT_EOL,AMBIENT_EOL");
	}

	@Test
	void constructor_whenQualityOpsBlank_failsClosed() {
		IllegalStateException ex = assertThrows(IllegalStateException.class, () -> new StationGateService(
				panelRegistrationRepository,
				processPathRepository,
				woPpBindingRepository,
				operationEventRepository,
				new JsonMapper(),
				"COAT,DEPANEL,CURE,ASM,PACK",
				"  "));
		assertTrue(ex.getMessage().contains("ames.station-gate.quality-ops"));
	}

	@Test
	void constructor_whenOpIsBothThroughAndQuality_failsClosed() {
		IllegalStateException ex = assertThrows(IllegalStateException.class, () -> new StationGateService(
				panelRegistrationRepository,
				processPathRepository,
				woPpBindingRepository,
				operationEventRepository,
				new JsonMapper(),
				"COAT,DEPANEL,CURE,ASM,PACK",
				"UV,PACK"));
		assertTrue(ex.getMessage().contains("PACK"));
	}

	@Test
	void evaluateGate_whenJoinedAndNoEvents_coatIsExpectedNextAndAllowed() {
		stubJoinedPath("PANEL-DEMO-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of());

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("PANEL-DEMO-001");
		request.setEquipmentId("AEL-01-COAT");

		StationGateResponse response = stationGateService.evaluateGate(request);

		assertTrue(response.isAllowed());
		assertNull(response.getReasonCode());
		assertEquals("COAT", response.getExpectedNext());
		assertEquals("WO-DEMO-001", response.getWorkOrderId());
		assertEquals("pp_cold_ambient", response.getProcessPathId());
		assertEquals("COAT", response.getThisOp());
		assertNull(response.getMessage());
		assertNull(response.getOutcome());
	}

	@Test
	void evaluateGate_trimsSerialNumberAndEquipmentId() {
		stubJoinedPath("PANEL-DEMO-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of());

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("  PANEL-DEMO-001  ");
		request.setEquipmentId("  AEL-01-COAT  ");

		StationGateResponse response = stationGateService.evaluateGate(request);

		assertTrue(response.isAllowed());
		assertEquals("COAT", response.getExpectedNext());
	}

	@Test
	void evaluateGate_whenScanAtLoaderAfterJoin_returnsAlreadyComplete() {
		stubJoinedPath("PANEL-DEMO-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of());

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("PANEL-DEMO-001");
		request.setEquipmentId("AEL-01-LOADER");

		StationGateResponse response = stationGateService.evaluateGate(request);

		assertFalse(response.isAllowed());
		assertEquals("ALREADY_COMPLETE", response.getReasonCode());
		assertEquals("COAT", response.getExpectedNext());
	}

	@Test
	void evaluateGate_whenThisOpAlreadyComplete_returnsAlreadyCompleteWithExpectedNext() {
		stubJoinedPath("PANEL-DEMO-001", "WO-DEMO-001", "pp_cold_ambient");

		OperationEventEntity coatComplete = new OperationEventEntity();
		coatComplete.setOpCode("COAT");
		coatComplete.setOutcome("COMPLETE");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of(coatComplete));

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("PANEL-DEMO-001");
		request.setEquipmentId("AEL-01-COAT");

		StationGateResponse response = stationGateService.evaluateGate(request);

		assertFalse(response.isAllowed());
		assertEquals("ALREADY_COMPLETE", response.getReasonCode());
		assertEquals("UV", response.getExpectedNext());
	}

	@Test
	void evaluateGate_whenUvIsExpected_uvEquipmentAllowed() {
		stubJoinedPath("PANEL-DEMO-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of(event("COAT", "COMPLETE")));

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("PANEL-DEMO-001");
		request.setEquipmentId("AEL-01-UV");

		StationGateResponse response = stationGateService.evaluateGate(request);

		assertTrue(response.isAllowed());
		assertEquals("UV", response.getExpectedNext());
	}

	@Test
	void evaluateGate_whenColdEolIsExpected_coldEolEquipmentAllowed() {
		stubJoinedPath("PANEL-DEMO-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of(
						event("COAT", "COMPLETE"),
						event("UV", "COMPLETE"),
						event("DEPANEL", "COMPLETE"),
						event("CURE", "COMPLETE"),
						event("ASM", "COMPLETE")));

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("PANEL-DEMO-001");
		request.setEquipmentId("AEL-01-COLD-EOL");

		StationGateResponse response = stationGateService.evaluateGate(request);

		assertTrue(response.isAllowed());
		assertEquals("COLD_EOL", response.getExpectedNext());
	}

	@Test
	void evaluateGate_whenColdEolIsExpected_flexibleEolEquipmentAllowed() {
		stubJoinedPath("PANEL-DEMO-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of(
						event("COAT", "COMPLETE"),
						event("UV", "COMPLETE"),
						event("DEPANEL", "COMPLETE"),
						event("CURE", "COMPLETE"),
						event("ASM", "COMPLETE")));

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("PANEL-DEMO-001");
		request.setEquipmentId("AEL-01-EOL-01");

		StationGateResponse response = stationGateService.evaluateGate(request);

		assertTrue(response.isAllowed());
		assertEquals("COLD_EOL", response.getExpectedNext());
	}

	@Test
	void evaluateGate_whenCoatIsExpected_flexibleEolReturnsWrongStation() {
		stubJoinedPath("PANEL-DEMO-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of());

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("PANEL-DEMO-001");
		request.setEquipmentId("AEL-01-EOL-01");

		StationGateResponse response = stationGateService.evaluateGate(request);

		assertFalse(response.isAllowed());
		assertEquals("WRONG_STATION", response.getReasonCode());
		assertEquals("COAT", response.getExpectedNext());
		assertEquals("Expected next: COAT", response.getMessage());
		assertEquals("EOL", response.getThisOp());
		assertEquals("WO-DEMO-001", response.getWorkOrderId());
		assertEquals("pp_cold_ambient", response.getProcessPathId());
	}

	@Test
	void evaluateGate_whenNoPanelRegistration_returnsOrphan() {
		when(panelRegistrationRepository.findByPanelNumber("UNKNOWN-PANEL")).thenReturn(Optional.empty());

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("UNKNOWN-PANEL");
		request.setEquipmentId("AEL-01-COAT");

		StationGateResponse response = stationGateService.evaluateGate(request);

		assertFalse(response.isAllowed());
		assertEquals("ORPHAN", response.getReasonCode());
		assertNull(response.getExpectedNext());
		assertNull(response.getWorkOrderId());
		assertNull(response.getProcessPathId());
		assertEquals("COAT", response.getThisOp());
		assertEquals("Serial not joined to a work order", response.getMessage());
	}

	@Test
	void evaluateGate_whenJoinHasNoWorkOrder_returnsOrphan() {
		PanelRegistrationEntity join = new PanelRegistrationEntity();
		join.setPanelNumber("PANEL-NO-WO");
		join.setWorkOrderId(null);
		when(panelRegistrationRepository.findByPanelNumber("PANEL-NO-WO")).thenReturn(Optional.of(join));

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("PANEL-NO-WO");
		request.setEquipmentId("AEL-01-COAT");

		StationGateResponse response = stationGateService.evaluateGate(request);

		assertFalse(response.isAllowed());
		assertEquals("ORPHAN", response.getReasonCode());
		assertNull(response.getExpectedNext());
	}

	/**
	 * AS3-02 AC #1 — Next is Coating → COMPLETE at Coating → allowed; outcome=COMPLETE; expectedNext advances (e.g. UV).
	 */
	@Test
	void complete_whenCoatIsNext_appendsCompleteAndReturnsAllowed() {
		stubJoinedPath("PANEL-DEMO-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of());

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("PANEL-DEMO-001");
		request.setEquipmentId("AEL-01-COAT");
		request.setEquipmentLocalAt(LocalDateTime.of(2026, 8, 26, 8, 15));

		StationGateResponse response = stationGateService.complete(request);

		assertTrue(response.isAllowed());
		assertEquals("COMPLETE", response.getOutcome());
		assertEquals("UV", response.getExpectedNext());
		assertEquals("WO-DEMO-001", response.getWorkOrderId());
		assertEquals("pp_cold_ambient", response.getProcessPathId());
		assertEquals("COAT", response.getThisOp());
		assertNull(response.getReasonCode());
		assertNull(response.getMessage());

		ArgumentCaptor<OperationEventEntity> saved = ArgumentCaptor.forClass(OperationEventEntity.class);
		verify(operationEventRepository).save(saved.capture());
		assertEquals("PANEL-DEMO-001", saved.getValue().getSerialNumber());
		assertEquals("COAT", saved.getValue().getOpCode());
		assertEquals("AEL-01-COAT", saved.getValue().getEquipmentId());
		assertEquals("COMPLETE", saved.getValue().getOutcome());
		assertEquals(LocalDateTime.of(2026, 8, 26, 8, 15), saved.getValue().getEquipmentLocalAt());
	}

	/**
	 * AS3-02 AC #4 — After Pack is next (Ambient PASS stubbed) → Pack COMPLETE → allowed + outcome=COMPLETE.
	 */
	@Test
	void complete_whenPackIsNext_appendsCompleteAndReturnsAllowed() {
		stubJoinedPath("PANEL-DEMO-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of(
						event("COAT", "COMPLETE"),
						event("UV", "PASS"),
						event("DEPANEL", "COMPLETE"),
						event("CURE", "COMPLETE"),
						event("ASM", "COMPLETE"),
						event("COLD_EOL", "PASS"),
						event("AMBIENT_EOL", "PASS")));

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("PANEL-DEMO-001");
		request.setEquipmentId("AEL-01-PACK");
		request.setEquipmentLocalAt(LocalDateTime.of(2026, 8, 26, 8, 15));

		StationGateResponse response = stationGateService.complete(request);

		assertTrue(response.isAllowed());
		assertEquals("COMPLETE", response.getOutcome());
		assertNull(response.getExpectedNext());
		assertEquals("PACK", response.getThisOp());
		assertNull(response.getReasonCode());
		verify(operationEventRepository).save(any(OperationEventEntity.class));
	}

	/** Request validation — equipmentLocalAt required on COMPLETE (not an AS3-02 plant AC). */
	@Test
	void complete_whenEquipmentLocalAtMissing_throwsBadRequestAndDoesNotSave() {
		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("PANEL-DEMO-001");
		request.setEquipmentId("AEL-01-COAT");

		ResponseStatusException ex =
				assertThrows(ResponseStatusException.class, () -> stationGateService.complete(request));

		assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
		assertEquals("equipmentLocalAt is required", ex.getReason());
		verify(operationEventRepository, never()).save(any());
		verify(panelRegistrationRepository, never()).findByPanelNumberForUpdate(any());
	}

	/**
	 * AS3-02 AC #6 — Unknown equipmentId → 400 — not WRONG_ENDPOINT.
	 */
	@Test
	void complete_whenUnknownEquipmentId_throwsBadRequestAndDoesNotSave() {
		PanelRegistrationEntity join = new PanelRegistrationEntity();
		join.setPanelNumber("PANEL-DEMO-001");
		join.setWorkOrderId("WO-DEMO-001");
		when(panelRegistrationRepository.findByPanelNumberForUpdate("PANEL-DEMO-001"))
				.thenReturn(Optional.of(join));
		when(panelRegistrationRepository.findByPanelNumber("PANEL-DEMO-001")).thenReturn(Optional.of(join));

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("PANEL-DEMO-001");
		request.setEquipmentId("AEL-01-NOT-A-REAL-STATION");
		request.setEquipmentLocalAt(LocalDateTime.of(2026, 8, 26, 8, 15));

		ResponseStatusException ex =
				assertThrows(ResponseStatusException.class, () -> stationGateService.complete(request));

		assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
		assertEquals("Unknown EQUIPMENT_ID: AEL-01-NOT-A-REAL-STATION", ex.getReason());
		verify(operationEventRepository, never()).save(any());
	}

	/**
	 * AS3-02 AC #5 — Orphan serial → COMPLETE denied (allowed=false).
	 */
	@Test
	void complete_whenOrphan_denyAndDoesNotSave() {
		when(panelRegistrationRepository.findByPanelNumberForUpdate("UNKNOWN-PANEL"))
				.thenReturn(Optional.empty());
		when(panelRegistrationRepository.findByPanelNumber("UNKNOWN-PANEL")).thenReturn(Optional.empty());

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("UNKNOWN-PANEL");
		request.setEquipmentId("AEL-01-COAT");
		request.setEquipmentLocalAt(LocalDateTime.of(2026, 8, 26, 8, 15));

		StationGateResponse response = stationGateService.complete(request);

		assertFalse(response.isAllowed());
		assertEquals("ORPHAN", response.getReasonCode());
		assertNull(response.getOutcome());
		verify(operationEventRepository, never()).save(any());
	}

	/**
	 * AS3-02 AC #3 — Path next ≠ this op → WRONG_STATION + expectedNext; no operation_event append.
	 * (Here: next=COAT, COMPLETE at ASM — same rule as COMPLETE at Coating when next is Assembly.)
	 */
	@Test
	void complete_whenWrongStation_denyAndDoesNotSave() {
		stubJoinedPath("PANEL-DEMO-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of());

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("PANEL-DEMO-001");
		request.setEquipmentId("AEL-01-ASM");
		request.setEquipmentLocalAt(LocalDateTime.of(2026, 8, 26, 8, 15));

		StationGateResponse response = stationGateService.complete(request);

		assertFalse(response.isAllowed());
		assertEquals("WRONG_STATION", response.getReasonCode());
		assertEquals("COAT", response.getExpectedNext());
		verify(operationEventRepository, never()).save(any());
	}

	/**
	 * AS3-02 AC #8 — Known Loader equipmentId → WRONG_ENDPOINT (Option A; join satisfies LOADER).
	 */
	@Test
	void complete_whenLoader_returnsWrongEndpointAndDoesNotSave() {
		stubJoinedPath("PANEL-DEMO-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of());

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("PANEL-DEMO-001");
		request.setEquipmentId("AEL-01-LOADER");
		request.setEquipmentLocalAt(LocalDateTime.of(2026, 8, 26, 8, 15));

		StationGateResponse response = stationGateService.complete(request);

		assertFalse(response.isAllowed());
		assertEquals("WRONG_ENDPOINT", response.getReasonCode());
		assertEquals("LOADER is satisfied by Panel Registration — not station-completes", response.getMessage());
		assertEquals("COAT", response.getExpectedNext());
		assertNull(response.getOutcome());
		verify(operationEventRepository, never()).save(any());
	}

	/**
	 * AS3-02 AC #7 — Known UV equipmentId → WRONG_ENDPOINT (quality API); not 400.
	 */
	@Test
	void complete_whenUvIsNext_returnsWrongEndpointAndDoesNotSave() {
		stubJoinedPath("PANEL-DEMO-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of(event("COAT", "COMPLETE")));

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("PANEL-DEMO-001");
		request.setEquipmentId("AEL-01-UV");
		request.setEquipmentLocalAt(LocalDateTime.of(2026, 8, 26, 8, 15));

		StationGateResponse response = stationGateService.complete(request);

		assertFalse(response.isAllowed());
		assertEquals("WRONG_ENDPOINT", response.getReasonCode());
		assertEquals(
				"Quality PASS/FAIL belongs on the quality API — not station-completes", response.getMessage());
		assertEquals("UV", response.getExpectedNext());
		verify(operationEventRepository, never()).save(any());
	}

	/**
	 * AS3-02 AC #7 — Known EOL equipmentId → WRONG_ENDPOINT (quality API); not 400.
	 */
	@Test
	void complete_whenColdEolIsNext_returnsWrongEndpointAndDoesNotSave() {
		stubJoinedPath("PANEL-DEMO-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of(
						event("COAT", "COMPLETE"),
						event("UV", "COMPLETE"),
						event("DEPANEL", "COMPLETE"),
						event("CURE", "COMPLETE"),
						event("ASM", "COMPLETE")));

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("PANEL-DEMO-001");
		request.setEquipmentId("AEL-01-COLD-EOL");
		request.setEquipmentLocalAt(LocalDateTime.of(2026, 8, 26, 8, 15));

		StationGateResponse response = stationGateService.complete(request);

		assertFalse(response.isAllowed());
		assertEquals("WRONG_ENDPOINT", response.getReasonCode());
		assertEquals(
				"Quality PASS/FAIL belongs on the quality API — not station-completes", response.getMessage());
		assertEquals("COLD_EOL", response.getExpectedNext());
		verify(operationEventRepository, never()).save(any());
	}

	/**
	 * Contract — Known Cold/Hot Chamber equipmentId → WRONG_ENDPOINT (soak ≠ COMPLETE).
	 */
	@Test
	void complete_whenColdChamber_returnsWrongEndpointAndDoesNotSave() {
		stubJoinedPath("PANEL-DEMO-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of());

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("PANEL-DEMO-001");
		request.setEquipmentId("AEL-01-COLD-CHAMBER");
		request.setEquipmentLocalAt(LocalDateTime.of(2026, 8, 26, 8, 15));

		StationGateResponse response = stationGateService.complete(request);

		assertFalse(response.isAllowed());
		assertEquals("WRONG_ENDPOINT", response.getReasonCode());
		assertEquals(
				"Chamber soak is not a station-completes step — no COMPLETE here", response.getMessage());
		assertEquals("COAT", response.getExpectedNext());
		verify(operationEventRepository, never()).save(any());
	}

	/**
	 * AS3-02 AC #2 — Second COMPLETE at Coating → ALREADY_COMPLETE; next does not advance again.
	 */
	@Test
	void complete_whenAlreadyComplete_doesNotSave() {
		stubJoinedPath("PANEL-DEMO-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of(event("COAT", "COMPLETE")));

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("PANEL-DEMO-001");
		request.setEquipmentId("AEL-01-COAT");
		request.setEquipmentLocalAt(LocalDateTime.of(2026, 8, 26, 8, 15));

		StationGateResponse response = stationGateService.complete(request);

		assertFalse(response.isAllowed());
		assertEquals("ALREADY_COMPLETE", response.getReasonCode());
		assertEquals("UV", response.getExpectedNext());
		verify(operationEventRepository, never()).save(any());
	}

	@Test
	void recordQuality_whenUvIsNext_passAppendsAndAdvances() {
		stubJoinedPath("PANEL-DEMO-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of(event("COAT", "COMPLETE")));

		StationGateResponse response =
				stationGateService.recordQuality(qualityRequest("AEL-01-UV"), "PASS");

		assertTrue(response.isAllowed());
		assertEquals("PASS", response.getOutcome());
		assertEquals("DEPANEL", response.getExpectedNext());
		assertEquals("UV", response.getThisOp());
		assertNull(response.getReasonCode());

		ArgumentCaptor<OperationEventEntity> saved = ArgumentCaptor.forClass(OperationEventEntity.class);
		verify(operationEventRepository).save(saved.capture());
		assertEquals("UV", saved.getValue().getOpCode());
		assertEquals("PASS", saved.getValue().getOutcome());
		assertEquals("AEL-01-UV", saved.getValue().getEquipmentId());
	}

	@Test
	void recordQuality_whenUvIsNext_failAppendsAndDoesNotAdvance() {
		stubJoinedPath("PANEL-DEMO-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of(event("COAT", "COMPLETE")));

		StationGateResponse response =
				stationGateService.recordQuality(qualityRequest("AEL-01-UV"), "FAIL");

		assertTrue(response.isAllowed());
		assertEquals("FAIL", response.getOutcome());
		assertEquals("UV", response.getExpectedNext());
		verify(operationEventRepository).save(any(OperationEventEntity.class));
	}

	@Test
	void recordQuality_whenCoatIsNext_coatReturnsWrongEndpointAndDoesNotSave() {
		stubJoinedPath("PANEL-DEMO-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of());

		StationGateResponse response =
				stationGateService.recordQuality(qualityRequest("AEL-01-COAT"), "PASS");

		assertFalse(response.isAllowed());
		assertEquals("WRONG_ENDPOINT", response.getReasonCode());
		assertEquals(
				"Through COMPLETE belongs on station-completes — not the quality API", response.getMessage());
		assertNull(response.getOutcome());
		verify(operationEventRepository, never()).save(any());
	}

	@Test
	void recordQuality_whenUvIsNext_assemblyReturnsWrongEndpointAndDoesNotSave() {
		stubJoinedPath("PANEL-DEMO-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of(event("COAT", "COMPLETE")));

		StationGateResponse response =
				stationGateService.recordQuality(qualityRequest("AEL-01-ASM"), "PASS");

		assertFalse(response.isAllowed());
		assertEquals("WRONG_ENDPOINT", response.getReasonCode());
		assertEquals("UV", response.getExpectedNext());
		verify(operationEventRepository, never()).save(any());
	}

	@Test
	void recordQuality_whenAlreadyPassed_doesNotSave() {
		stubJoinedPath("PANEL-DEMO-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of(event("COAT", "COMPLETE"), event("UV", "PASS")));

		StationGateResponse response =
				stationGateService.recordQuality(qualityRequest("AEL-01-UV"), "PASS");

		assertFalse(response.isAllowed());
		assertEquals("ALREADY_COMPLETE", response.getReasonCode());
		assertEquals("DEPANEL", response.getExpectedNext());
		verify(operationEventRepository, never()).save(any());
	}

	@Test
	void recordQuality_whenOrphan_denyAndDoesNotSave() {
		when(panelRegistrationRepository.findByPanelNumberForUpdate("UNKNOWN-PANEL"))
				.thenReturn(Optional.empty());
		when(panelRegistrationRepository.findByPanelNumber("UNKNOWN-PANEL")).thenReturn(Optional.empty());

		StationGateRequest request = qualityRequest("AEL-01-UV");
		request.setSerialNumber("UNKNOWN-PANEL");

		StationGateResponse response = stationGateService.recordQuality(request, "PASS");

		assertFalse(response.isAllowed());
		assertEquals("ORPHAN", response.getReasonCode());
		verify(operationEventRepository, never()).save(any());
	}

	@Test
	void recordQuality_whenColdChamber_returnsWrongEndpointAndDoesNotSave() {
		stubJoinedPath("PANEL-DEMO-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of());

		StationGateResponse response =
				stationGateService.recordQuality(qualityRequest("AEL-01-COLD-CHAMBER"), "PASS");

		assertFalse(response.isAllowed());
		assertEquals("WRONG_ENDPOINT", response.getReasonCode());
		assertEquals("Chamber soak is not a quality PASS/FAIL step", response.getMessage());
		verify(operationEventRepository, never()).save(any());
	}

	@Test
	void recordQuality_whenUnknownEquipmentId_throwsBadRequestAndDoesNotSave() {
		PanelRegistrationEntity join = new PanelRegistrationEntity();
		join.setPanelNumber("PANEL-DEMO-001");
		join.setWorkOrderId("WO-DEMO-001");
		when(panelRegistrationRepository.findByPanelNumberForUpdate("PANEL-DEMO-001"))
				.thenReturn(Optional.of(join));
		when(panelRegistrationRepository.findByPanelNumber("PANEL-DEMO-001")).thenReturn(Optional.of(join));

		StationGateRequest request = qualityRequest("AEL-01-NOT-A-REAL-STATION");

		ResponseStatusException ex = assertThrows(
				ResponseStatusException.class, () -> stationGateService.recordQuality(request, "PASS"));

		assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
		assertEquals("Unknown EQUIPMENT_ID: AEL-01-NOT-A-REAL-STATION", ex.getReason());
		verify(operationEventRepository, never()).save(any());
	}

	@Test
	void recordQuality_whenOutcomeNotPassOrFail_throwsBadRequestAndDoesNotSave() {
		StationGateRequest request = qualityRequest("AEL-01-UV");

		ResponseStatusException ex = assertThrows(
				ResponseStatusException.class, () -> stationGateService.recordQuality(request, "COMPLETE"));

		assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
		assertEquals("outcome must be PASS or FAIL", ex.getReason());
		verify(operationEventRepository, never()).save(any());
		verify(panelRegistrationRepository, never()).findByPanelNumberForUpdate(any());
	}

	private static StationGateRequest qualityRequest(String equipmentId) {
		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("PANEL-DEMO-001");
		request.setEquipmentId(equipmentId);
		request.setEquipmentLocalAt(LocalDateTime.of(2026, 8, 26, 8, 15));
		return request;
	}

	private static OperationEventEntity event(String opCode, String outcome) {
		OperationEventEntity e = new OperationEventEntity();
		e.setOpCode(opCode);
		e.setOutcome(outcome);
		return e;
	}

	private void stubJoinedPath(String panelNumber, String workOrderId, String processPathId) {
		PanelRegistrationEntity join = new PanelRegistrationEntity();
		join.setPanelNumber(panelNumber);
		join.setWorkOrderId(workOrderId);
		when(panelRegistrationRepository.findByPanelNumber(panelNumber)).thenReturn(Optional.of(join));
		// COMPLETE path only — lenient so evaluateGate tests do not fail unused stubbing.
		lenient()
				.when(panelRegistrationRepository.findByPanelNumberForUpdate(panelNumber))
				.thenReturn(Optional.of(join));

		WoPpBindingEntity binding = new WoPpBindingEntity();
		binding.setWorkOrderId(workOrderId);
		binding.setProcessPathId(processPathId);
		when(woPpBindingRepository.findById(workOrderId)).thenReturn(Optional.of(binding));

		ProcessPathEntity path = new ProcessPathEntity();
		path.setProcessPathId(processPathId);
		path.setContent(PATH_CONTENT);
		when(processPathRepository.findById(processPathId)).thenReturn(Optional.of(path));
	}
}
