package com.ames.mes_api.depanel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ames.mes_api.stationgate.StationGateResponse;
import com.ames.mes_api.stationgate.StationGateService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class DepanelSplitServiceTest {

	private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 16, 19, 30);

	@Mock
	private StationGateService stationGateService;

	@Mock
	private DepanelSplitRepository depanelSplitRepository;

	private DepanelSplitService service;

	@BeforeEach
	void setUp() {
		service = new DepanelSplitService(
				stationGateService, new DepanelSplitWriter(stationGateService, depanelSplitRepository));
	}

	/**
	 * AS5-01 check 1, service half — the gate allows, and {@code insertSplit} receives slots
	 * A1, A2, B1, B2 with the four PCBA serials.
	 * The stored rows and the Depanel split COMPLETE are in
	 * {@link DepanelSplitIntegrationTest#storedSplit_thenSecondRequest_readsRowsAndStoresNothingNew()}.
	 */
	@Test
	void storesSplitWhenGateAllowsFourPcbaSerials() {
		when(stationGateService.evaluateGate(any())).thenReturn(allowDepanel());
		when(stationGateService.completeDepanelSplit(any())).thenReturn(completed());

		DepanelSplitResponse response = service.split(goodRequest());

		assertTrue(response.isStored());
		assertEquals("COMPLETE", response.getOutcome());
		assertEquals("PANEL-APCU-20260729-0042", response.getPanelNumber());
		assertNull(response.getReasonCode());
		assertNull(response.getMessage());

		ArgumentCaptor<List<DepanelSplitInsertRow>> rows = listCaptor();
		verify(depanelSplitRepository).insertSplit(
				ArgumentMatchers.eq("PANEL-APCU-20260729-0042"),
				rows.capture(),
				ArgumentMatchers.eq(AT));
		assertEquals(List.of("A1", "A2", "B1", "B2"), rows.getValue().stream().map(DepanelSplitInsertRow::slot).toList());
		assertEquals(List.of(
				"PCBA-APCU-0000842101",
				"PCBA-APCU-0000842102",
				"PCBA-APCU-0000842103",
				"PCBA-APCU-0000842104"),
				rows.getValue().stream().map(DepanelSplitInsertRow::pcbaSerial).toList());
	}

	/**
	 * AS5-01 check 4, {@code XOUT} case — one bad cavity returns HOLD and calls no write.
	 * MySQL stored nothing is in {@link DepanelSplitIntegrationTest#xout_holdsAndStoresNothing()}.
	 */
	@Test
	void holdStoresNothingWhenCavityIsXout() {
		when(stationGateService.evaluateGate(any())).thenReturn(allowDepanel());

		DepanelSplitRequest request = goodRequest();
		request.getCavities().get(1).setCavityResult("XOUT");
		DepanelSplitResponse response = service.split(request);

		assertFalse(response.isStored());
		assertEquals("HOLD", response.getReasonCode());
		assertEquals(DepanelSplitService.HOLD_MESSAGE, response.getMessage());
		verify(stationGateService, never()).completeDepanelSplit(any());
		verify(depanelSplitRepository, never()).insertSplit(any(), any(), any());
	}

	/**
	 * AS5-01 check 4, missing-slot case — one missing slot returns HOLD and calls no write.
	 */
	@Test
	void holdStoresNothingWhenOneSlotIsMissing() {
		when(stationGateService.evaluateGate(any())).thenReturn(allowDepanel());

		DepanelSplitRequest request = goodRequest();
		request.setCavities(List.of(
				cavity("A1", "PCBA-APCU-0000842101"),
				cavity("A2", "PCBA-APCU-0000842102"),
				cavity("B1", "PCBA-APCU-0000842103")));
		DepanelSplitResponse response = service.split(request);

		assertFalse(response.isStored());
		assertEquals("HOLD", response.getReasonCode());
		assertEquals(DepanelSplitService.HOLD_MESSAGE, response.getMessage());
		verify(stationGateService, never()).completeDepanelSplit(any());
		verify(depanelSplitRepository, never()).insertSplit(any(), any(), any());
	}

	/**
	 * Not an AS5-01 check. Approach item 5 — a station-gate refuse stores nothing.
	 * AS-3 owns the refuse reason. Former ticket check 3 was removed.
	 */
	@Test
	void gateRefuseStoresNothing() {
		StationGateResponse denied = new StationGateResponse();
		denied.setAllowed(false);
		denied.setReasonCode("WRONG_STATION");
		denied.setThisOp("DEPANEL");
		when(stationGateService.evaluateGate(any())).thenReturn(denied);

		DepanelSplitResponse response = service.split(goodRequest());

		assertFalse(response.isStored());
		assertEquals("WRONG_STATION", response.getReasonCode());
		assertEquals("Depanel is not the next step. MES stored nothing.", response.getMessage());
		verify(stationGateService, never()).completeDepanelSplit(any());
		verify(depanelSplitRepository, never()).insertSplit(any(), any(), any());
	}

	/**
	 * AS5-01 check 5, service half — when the gate returns {@code ALREADY_COMPLETE},
	 * {@code insertSplit} is not called.
	 * The second request after a stored split is in
	 * {@link DepanelSplitIntegrationTest#storedSplit_thenSecondRequest_readsRowsAndStoresNothingNew()}.
	 */
	@Test
	void secondSplitStoresNothing() {
		when(stationGateService.evaluateGate(any())).thenReturn(allowDepanel());
		StationGateResponse already = new StationGateResponse();
		already.setAllowed(false);
		already.setReasonCode("ALREADY_COMPLETE");
		when(stationGateService.completeDepanelSplit(any())).thenReturn(already);

		DepanelSplitResponse response = service.split(goodRequest());

		assertFalse(response.isStored());
		assertEquals("ALREADY_COMPLETE", response.getReasonCode());
		assertEquals("This panel is already split. MES stored nothing.", response.getMessage());
		verify(depanelSplitRepository, never()).insertSplit(any(), any(), any());
	}

	/**
	 * Not an AS5-01 check. A blank panel number is rejected before the station gate.
	 */
	@Test
	void blankPanelNumberRejectsBeforeGate() {
		DepanelSplitRequest request = goodRequest();
		request.setPanelNumber("  ");

		ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.split(request));

		assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
		assertEquals("panelNumber is required", ex.getReason());
		assertRejectedBeforeGate();
	}

	/**
	 * Not an AS5-01 check. A missing {@code equipmentLocalAt} is rejected before the station gate.
	 */
	@Test
	void missingEquipmentLocalAtRejectsBeforeGate() {
		DepanelSplitRequest request = goodRequest();
		request.setEquipmentLocalAt(null);

		ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.split(request));

		assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
		assertEquals("equipmentLocalAt is required", ex.getReason());
		assertRejectedBeforeGate();
	}

	/**
	 * AS5-01 check 3, service half — a panel number and no cavities is rejected before the station gate.
	 * The HTTP 400 is in
	 * {@link DepanelSplitControllerTest#split_whenCavitiesEmpty_returns400AndDoesNotCallService()}.
	 */
	@Test
	void emptyCavitiesRejectsBeforeGate() {
		DepanelSplitRequest request = goodRequest();
		request.setCavities(List.of());

		ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.split(request));

		assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
		assertEquals("cavities are required", ex.getReason());
		assertRejectedBeforeGate();
	}

	private void assertRejectedBeforeGate() {
		verify(stationGateService, never()).evaluateGate(any());
		verify(stationGateService, never()).completeDepanelSplit(any());
		verify(depanelSplitRepository, never()).insertSplit(any(), any(), any());
	}

	private static StationGateResponse allowDepanel() {
		StationGateResponse response = new StationGateResponse();
		response.setAllowed(true);
		response.setThisOp("DEPANEL");
		return response;
	}

	private static StationGateResponse completed() {
		StationGateResponse response = allowDepanel();
		response.setOutcome("COMPLETE");
		return response;
	}

	private static DepanelSplitRequest goodRequest() {
		DepanelSplitRequest request = new DepanelSplitRequest();
		request.setPanelNumber(" PANEL-APCU-20260729-0042 ");
		request.setEquipmentId("AEL-01-DEPANEL");
		request.setEquipmentLocalAt(AT);
		request.setCavities(List.of(
				cavity("B2", "PCBA-APCU-0000842104"),
				cavity("A1", "PCBA-APCU-0000842101"),
				cavity("A2", "PCBA-APCU-0000842102"),
				cavity("B1", "PCBA-APCU-0000842103")));
		return request;
	}

	private static DepanelCavityRequest cavity(String slot, String cavityResult) {
		DepanelCavityRequest cavity = new DepanelCavityRequest();
		cavity.setSlot(slot);
		cavity.setCavityResult(cavityResult);
		return cavity;
	}

	@SuppressWarnings("unchecked")
	private static ArgumentCaptor<List<DepanelSplitInsertRow>> listCaptor() {
		return ArgumentCaptor.forClass(List.class);
	}
}
