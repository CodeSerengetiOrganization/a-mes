package com.ames.mes_api.stationgate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.ames.mes_api.panelregistration.PanelRegistrationEntity;
import com.ames.mes_api.panelregistration.PanelRegistrationRepository;
import com.ames.mes_api.panelregistration.WoPpBindingEntity;
import com.ames.mes_api.panelregistration.WoPpBindingRepository;
import com.ames.mes_api.processpath.ProcessPathEntity;
import com.ames.mes_api.processpath.ProcessPathRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link StationGateService} — Mockito only, no MySQL.
 *
 * <p>Happy path: serial joined to a WO (LOADER via join rule), no events yet, scan at Coating → allowed.
 */
@ExtendWith(MockitoExtension.class)
class StationGateServiceTest {

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
				new JsonMapper());
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
