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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ames.mes_api.depanel.DepanelSplitEntity;
import com.ames.mes_api.depanel.DepanelSplitRepository;
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

	private static final String PATH_WITHOUT_DEPANEL =
			"{\"name\":\"No Depanel\",\"ops\":[\"LOADER\",\"COAT\",\"CURE\",\"PACK\"]}";

	private static final String THROUGH_OPS = "COAT,CURE,ASM,PACK";
	private static final String QUALITY_OPS = "UV,DEPANEL,COLD_EOL,HOT_EOL,AMBIENT_EOL";
	private static final String AFTER_DEPANEL_OPS =
			"CURE,ASM,COLD_EOL,HOT_EOL,AMBIENT_EOL,EOL,PACK";

	private static final String PANEL = "PANEL-APCU-20260729-0042";
	private static final String PCBA_1 = "PCBA-APCU-0000842101";
	private static final String PCBA_2 = "PCBA-APCU-0000842102";
	private static final String WO = "WO-DEMO-001";
	private static final String PATH_ID = "pp_cold_ambient";
	private static final LocalDateTime AT = LocalDateTime.of(2026, 8, 26, 8, 15);

	@Mock
	private PanelRegistrationRepository panelRegistrationRepository;

	@Mock
	private ProcessPathRepository processPathRepository;

	@Mock
	private WoPpBindingRepository woPpBindingRepository;

	@Mock
	private OperationEventRepository operationEventRepository;

	@Mock
	private DepanelSplitRepository depanelSplitRepository;

	private StationGateService stationGateService;

	@BeforeEach
	void setUp() {
		stationGateService = newGate(THROUGH_OPS, QUALITY_OPS, AFTER_DEPANEL_OPS);
	}

	@Test
	void constructor_whenQualityOpsBlank_failsClosed() {
		IllegalStateException ex = assertThrows(
				IllegalStateException.class, () -> newGate(THROUGH_OPS, "  ", AFTER_DEPANEL_OPS));
		assertTrue(ex.getMessage().contains("ames.station-gate.quality-ops"));
	}

	@Test
	void constructor_whenAfterDepanelOpsBlank_failsClosed() {
		IllegalStateException ex = assertThrows(
				IllegalStateException.class, () -> newGate(THROUGH_OPS, QUALITY_OPS, "  "));
		assertTrue(ex.getMessage().contains("ames.station-gate.after-depanel-ops"));
	}

	@Test
	void constructor_whenOpIsBothThroughAndQuality_failsClosed() {
		IllegalStateException ex = assertThrows(
				IllegalStateException.class, () -> newGate("COAT,CURE,ASM,PACK", "UV,PACK", AFTER_DEPANEL_OPS));
		assertTrue(ex.getMessage().contains("PACK"));
	}

	private StationGateService newGate(String throughOps, String qualityOps, String afterDepanelOps) {
		return new StationGateService(
				panelRegistrationRepository,
				processPathRepository,
				woPpBindingRepository,
				operationEventRepository,
				depanelSplitRepository,
				new JsonMapper(),
				throughOps,
				qualityOps,
				afterDepanelOps);
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
		stubJoinedPath("FP-SEEDED-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("FP-SEEDED-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of(
						event("COAT", "COMPLETE"),
						event("UV", "COMPLETE"),
						event("DEPANEL", "COMPLETE"),
						event("CURE", "COMPLETE"),
						event("ASM", "COMPLETE")));

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("FP-SEEDED-001");
		request.setEquipmentId("AEL-01-COLD-EOL");

		StationGateResponse response = stationGateService.evaluateGate(request);

		assertTrue(response.isAllowed());
		assertEquals("COLD_EOL", response.getExpectedNext());
	}

	@Test
	void evaluateGate_whenColdEolIsExpected_flexibleEolEquipmentAllowed() {
		stubJoinedPath("FP-SEEDED-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("FP-SEEDED-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of(
						event("COAT", "COMPLETE"),
						event("UV", "COMPLETE"),
						event("DEPANEL", "COMPLETE"),
						event("CURE", "COMPLETE"),
						event("ASM", "COMPLETE")));

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("FP-SEEDED-001");
		request.setEquipmentId("AEL-01-EOL-01");

		StationGateResponse response = stationGateService.evaluateGate(request);

		assertTrue(response.isAllowed());
		assertEquals("COLD_EOL", response.getExpectedNext());
	}

	@Test
	void evaluateGate_whenCoatIsExpected_flexibleEolReturnsWrongStation() {
		stubJoinedPath("FP-SEEDED-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("FP-SEEDED-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of());

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("FP-SEEDED-001");
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
		stubJoinedPath("FP-SEEDED-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("FP-SEEDED-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of(
						event("COAT", "COMPLETE"),
						event("UV", "PASS"),
						event("DEPANEL", "COMPLETE"),
						event("CURE", "COMPLETE"),
						event("ASM", "COMPLETE"),
						event("COLD_EOL", "PASS"),
						event("AMBIENT_EOL", "PASS")));

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("FP-SEEDED-001");
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
		stubJoinedPath("FP-SEEDED-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("FP-SEEDED-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of());

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("FP-SEEDED-001");
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
		stubJoinedPath("FP-SEEDED-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("FP-SEEDED-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of(
						event("COAT", "COMPLETE"),
						event("UV", "COMPLETE"),
						event("DEPANEL", "COMPLETE"),
						event("CURE", "COMPLETE"),
						event("ASM", "COMPLETE")));

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("FP-SEEDED-001");
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
		stubJoinedPath("FP-SEEDED-001", "WO-DEMO-001", "pp_cold_ambient");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("FP-SEEDED-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of(event("COAT", "COMPLETE")));

		StationGateRequest request = qualityRequest("AEL-01-ASM");
		request.setSerialNumber("FP-SEEDED-001");
		StationGateResponse response = stationGateService.recordQuality(request, "PASS");

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

	/** AS5-02 check 1 — PCBA at Cure after Depanel PASS. */
	@Test
	void as502_check1_pcbaAtCure_allowedWithWorkOrderPathAndCure() {
		stubPcba(PCBA_1);
		stubProgress(PCBA_1, List.of(event("DEPANEL", "PASS")));

		StationGateResponse response = stationGateService.evaluateGate(scan(PCBA_1, "AEL-01-CURE"));

		assertTrue(response.isAllowed());
		assertEquals(WO, response.getWorkOrderId());
		assertEquals(PATH_ID, response.getProcessPathId());
		assertEquals("CURE", response.getExpectedNext());
		assertEquals("CURE", response.getThisOp());
	}

	/** AS5-02 check 2 — panel number at every after-Depanel op, before any repository call. */
	@Test
	void as502_check2_panelAfterDepanel_refusesBeforeAnyRepository() {
		String[] equipmentIds = {
			"AEL-01-CURE",
			"AEL-01-ASM",
			"AEL-01-COLD-EOL",
			"AEL-01-HOT-EOL",
			"AEL-01-AMBIENT-EOL",
			"AEL-01-PACK",
			"AEL-01-EOL-01"
		};
		for (String equipmentId : equipmentIds) {
			assertPanelAfterDepanel(stationGateService.evaluateGate(scan(PANEL, equipmentId)));
			StationGateResponse complete = stationGateService.complete(scan(PANEL, equipmentId));
			assertPanelAfterDepanel(complete);
			assertNull(complete.getOutcome());
			assertPanelAfterDepanel(stationGateService.recordQuality(scan(PANEL, equipmentId), "PASS"));
		}
		verifyNoInteractions(
				panelRegistrationRepository,
				processPathRepository,
				woPpBindingRepository,
				operationEventRepository,
				depanelSplitRepository);
	}

	/** AS5-02 check 3 — one PCBA can move to Assembly. The other stays at Cure. */
	@Test
	void as502_check3_onePcbaMoves_siblingStaysAtCure() {
		stubPcba(PCBA_1);
		stubPcba(PCBA_2);
		stubProgress(PCBA_1, List.of(event("DEPANEL", "PASS")));
		stubProgress(PCBA_2, List.of(event("DEPANEL", "PASS")));

		StationGateResponse moved = stationGateService.complete(scan(PCBA_1, "AEL-01-CURE"));
		assertTrue(moved.isAllowed());
		assertEquals("COMPLETE", moved.getOutcome());
		assertEquals("ASM", moved.getExpectedNext());

		StationGateResponse sibling = stationGateService.evaluateGate(scan(PCBA_2, "AEL-01-CURE"));
		assertTrue(sibling.isAllowed());
		assertEquals("CURE", sibling.getExpectedNext());
	}

	/**
	 * AS5-02 check 4, lock half — one PCBA COMPLETE locks that PCBA's split row, not the panel.
	 * The concurrent COMPLETE runs are in {@link StationGatePcbaConcurrentCompleteIT}.
	 */
	@Test
	void as502_check4_pcbaComplete_locksSplitRowNotPanel() {
		stubPcba(PCBA_1);
		stubProgress(PCBA_1, List.of(event("DEPANEL", "PASS")));

		stationGateService.complete(scan(PCBA_1, "AEL-01-CURE"));

		verify(depanelSplitRepository).findByPcbaSerialForUpdate(PCBA_1);
		verify(panelRegistrationRepository, never()).findByPanelNumberForUpdate(any());
	}

	/** AS5-02 check 5 — PCBA serial not on the split. */
	@Test
	void as502_check5_pcbaNotOnSplit_orphanAndStoresNothing() {
		when(depanelSplitRepository.findByPcbaSerial("PCBA-APCU-0000999999")).thenReturn(List.of());
		lenient()
				.when(depanelSplitRepository.findByPcbaSerialForUpdate("PCBA-APCU-0000999999"))
				.thenReturn(List.of());

		StationGateResponse check = stationGateService.evaluateGate(scan("PCBA-APCU-0000999999", "AEL-01-CURE"));
		assertFalse(check.isAllowed());
		assertEquals("ORPHAN", check.getReasonCode());
		assertNull(check.getWorkOrderId());

		StationGateResponse complete = stationGateService.complete(scan("PCBA-APCU-0000999999", "AEL-01-CURE"));
		assertFalse(complete.isAllowed());
		assertEquals("ORPHAN", complete.getReasonCode());
		assertNull(complete.getOutcome());
		verify(operationEventRepository, never()).save(any());
	}

	/** AS5-02 check 6 — Depanel PASS and FAIL are stored on each PCBA serial. */
	@Test
	void as502_check6_depanelPassAndFail_storeOnEachPcba() {
		stubPcba(PCBA_1);
		stubPcba(PCBA_2);
		stubProgress(PCBA_1, List.of());
		stubProgress(PCBA_2, List.of());

		StationGateResponse blockedComplete =
				stationGateService.complete(scan(PCBA_1, "AEL-01-DEPANEL"));
		assertFalse(blockedComplete.isAllowed());
		assertEquals("WRONG_ENDPOINT", blockedComplete.getReasonCode());

		StationGateResponse pass = stationGateService.recordQuality(scan(PCBA_1, "AEL-01-DEPANEL"), "PASS");
		assertTrue(pass.isAllowed());
		assertEquals("PASS", pass.getOutcome());
		assertEquals("CURE", pass.getExpectedNext());

		StationGateResponse fail = stationGateService.recordQuality(scan(PCBA_2, "AEL-01-DEPANEL"), "FAIL");
		assertTrue(fail.isAllowed());
		assertEquals("FAIL", fail.getOutcome());
		assertEquals("DEPANEL", fail.getExpectedNext());

		ArgumentCaptor<OperationEventEntity> saved = ArgumentCaptor.forClass(OperationEventEntity.class);
		verify(operationEventRepository, times(2)).save(saved.capture());
		assertEquals(PCBA_1, saved.getAllValues().get(0).getSerialNumber());
		assertEquals("DEPANEL", saved.getAllValues().get(0).getOpCode());
		assertEquals("PASS", saved.getAllValues().get(0).getOutcome());
		assertEquals(PCBA_2, saved.getAllValues().get(1).getSerialNumber());
		assertEquals("FAIL", saved.getAllValues().get(1).getOutcome());
	}

	/** AS5-02 check 7 — panel number at Depanel after the split. */
	@Test
	void as502_check7_panelAtDepanelAfterSplit_alreadyCompleteOrWrongEndpoint() {
		stubJoinedPath(PANEL, WO, PATH_ID);
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq(PANEL), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of(
						event("COAT", "COMPLETE"), event("UV", "PASS"), event("DEPANEL", "COMPLETE")));

		StationGateResponse check = stationGateService.evaluateGate(scan(PANEL, "AEL-01-DEPANEL"));
		assertFalse(check.isAllowed());
		assertEquals("ALREADY_COMPLETE", check.getReasonCode());

		StationGateResponse pass = stationGateService.recordQuality(scan(PANEL, "AEL-01-DEPANEL"), "PASS");
		assertEquals("ALREADY_COMPLETE", pass.getReasonCode());
		StationGateResponse fail = stationGateService.recordQuality(scan(PANEL, "AEL-01-DEPANEL"), "FAIL");
		assertEquals("ALREADY_COMPLETE", fail.getReasonCode());

		StationGateResponse complete = stationGateService.complete(scan(PANEL, "AEL-01-DEPANEL"));
		assertFalse(complete.isAllowed());
		assertEquals("WRONG_ENDPOINT", complete.getReasonCode());
		assertEquals(
				"Quality PASS/FAIL belongs on the quality API — not station-completes",
				complete.getMessage());
		verify(operationEventRepository, never()).save(any());
	}

	/** AS5-02 check 8 — Cure before Depanel PASS. */
	@Test
	void as502_check8_cureBeforeDepanelPass_refusesAndStoresNothing() {
		stubPcba(PCBA_1);
		stubProgress(PCBA_1, List.of());

		StationGateResponse check = stationGateService.evaluateGate(scan(PCBA_1, "AEL-01-CURE"));
		assertFalse(check.isAllowed());
		assertEquals("WRONG_STATION", check.getReasonCode());
		assertEquals("DEPANEL", check.getExpectedNext());

		StationGateResponse complete = stationGateService.complete(scan(PCBA_1, "AEL-01-CURE"));
		assertFalse(complete.isAllowed());
		assertEquals("DEPANEL", complete.getExpectedNext());
		assertNull(complete.getOutcome());
		verify(operationEventRepository, never()).save(any());
	}

	/** AS5-02 check 9 — a Depanel FAIL blocks later requests for that PCBA at Depanel. */
	@Test
	void as502_check9_depanelFail_blocksLaterRequests() {
		stubPcba(PCBA_2);
		stubProgress(PCBA_2, List.of());
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq(PCBA_2), eq(List.of("FAIL"))))
				.thenReturn(List.of(event("DEPANEL", "FAIL")));

		StationGateResponse check = stationGateService.evaluateGate(scan(PCBA_2, "AEL-01-DEPANEL"));
		StationGateResponse pass = stationGateService.recordQuality(scan(PCBA_2, "AEL-01-DEPANEL"), "PASS");
		StationGateResponse fail = stationGateService.recordQuality(scan(PCBA_2, "AEL-01-DEPANEL"), "FAIL");

		for (StationGateResponse response : List.of(check, pass, fail)) {
			assertFalse(response.isAllowed());
			assertEquals("DEPANEL_FAIL_BLOCKED", response.getReasonCode());
			assertEquals("DEPANEL", response.getExpectedNext());
		}
		verify(operationEventRepository, never()).save(any());
	}

	/** In-process split write still stores COMPLETE after Depanel moves to quality-ops. */
	@Test
	void completeDepanelSplit_whenDepanelIsNext_storesCompleteOnPanel() {
		stubJoinedPath(PANEL, WO, PATH_ID);
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq(PANEL), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of(event("COAT", "COMPLETE"), event("UV", "PASS")));

		StationGateResponse response =
				stationGateService.completeDepanelSplit(scan(PANEL, "AEL-01-DEPANEL"));

		assertTrue(response.isAllowed());
		assertEquals("COMPLETE", response.getOutcome());
		assertEquals("CURE", response.getExpectedNext());
		ArgumentCaptor<OperationEventEntity> saved = ArgumentCaptor.forClass(OperationEventEntity.class);
		verify(operationEventRepository).save(saved.capture());
		assertEquals(PANEL, saved.getValue().getSerialNumber());
		assertEquals("DEPANEL", saved.getValue().getOpCode());
		assertEquals("COMPLETE", saved.getValue().getOutcome());
	}

	/** A path with no DEPANEL gives the PCBA no completed op. */
	@Test
	void pcbaOnPathWithoutDepanel_countsNoOpDone() {
		stubPcba(PCBA_1);
		ProcessPathEntity path = new ProcessPathEntity();
		path.setProcessPathId(PATH_ID);
		path.setContent(PATH_WITHOUT_DEPANEL);
		when(processPathRepository.findById(PATH_ID)).thenReturn(Optional.of(path));

		StationGateResponse response = stationGateService.evaluateGate(scan(PCBA_1, "AEL-01-CURE"));

		assertFalse(response.isAllowed());
		assertEquals("WRONG_STATION", response.getReasonCode());
		assertEquals("LOADER", response.getExpectedNext());
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

	private void stubPcba(String pcbaSerial) {
		DepanelSplitEntity row = new DepanelSplitEntity();
		row.setPanelNumber(PANEL);
		row.setPcbaSerial(pcbaSerial);
		row.setSlot("A1");
		when(depanelSplitRepository.findByPcbaSerial(pcbaSerial)).thenReturn(List.of(row));
		lenient()
				.when(depanelSplitRepository.findByPcbaSerialForUpdate(pcbaSerial))
				.thenReturn(List.of(row));
		stubJoinedPath(PANEL, WO, PATH_ID);
	}

	private void stubProgress(String serialNumber, List<OperationEventEntity> events) {
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq(serialNumber), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(events);
		lenient()
				.when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq(serialNumber), eq(List.of("FAIL"))))
				.thenReturn(List.of());
	}

	private static StationGateRequest scan(String serialNumber, String equipmentId) {
		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber(serialNumber);
		request.setEquipmentId(equipmentId);
		request.setEquipmentLocalAt(AT);
		return request;
	}

	private static void assertPanelAfterDepanel(StationGateResponse response) {
		assertFalse(response.isAllowed());
		assertEquals("PANEL_AFTER_DEPANEL", response.getReasonCode());
		assertEquals("Scan the PCBA serial.", response.getMessage());
		assertNull(response.getExpectedNext());
		assertNull(response.getWorkOrderId());
		assertNull(response.getProcessPathId());
	}
}
