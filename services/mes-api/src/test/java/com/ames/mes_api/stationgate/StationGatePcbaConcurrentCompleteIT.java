package com.ames.mes_api.stationgate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ames.mes_api.depanel.DepanelSplitEntity;
import com.ames.mes_api.depanel.DepanelSplitRepository;
import com.ames.mes_api.panelregistration.PanelRegistrationEntity;
import com.ames.mes_api.panelregistration.PanelRegistrationRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Live-MySQL concurrency half of AS5-02 check 4.
 * The lock half is {@link StationGateServiceTest#as502_check4_pcbaComplete_locksSplitRowNotPanel()}.
 *
 * <p>Not {@code @Transactional}: each thread must commit its own {@code complete} so
 * {@code SELECT … FOR UPDATE} on the PCBA split row can serialize writers. Same form as
 * {@link StationGateConcurrentCompleteIT}.
 *
 * <p>The seeded Cold + Ambient work order is {@code WO-DEMO-002} ({@code pp_cold_ambient}).
 * {@code WO-DEMO-001} is Full EOL in the seed.
 */
@SpringBootTest
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+")
class StationGatePcbaConcurrentCompleteIT {

	private static final String WO_COLD_AMBIENT = "WO-DEMO-002";
	private static final String PANEL = "PANEL-APCU-20260729-0042";
	private static final String PCBA_1 = "PCBA-APCU-0000842101";
	private static final String PCBA_2 = "PCBA-APCU-0000842102";
	private static final LocalDateTime LOCAL_AT = LocalDateTime.of(2026, 8, 28, 17, 0);

	@Autowired
	private StationGateService stationGateService;

	@Autowired
	private PanelRegistrationRepository panelRegistrationRepository;

	@Autowired
	private DepanelSplitRepository depanelSplitRepository;

	@Autowired
	private OperationEventRepository operationEventRepository;

	@BeforeEach
	void seedSplitWithDepanelPass() {
		cleanup();
		PanelRegistrationEntity join = new PanelRegistrationEntity();
		join.setPanelNumber(PANEL);
		join.setWorkOrderId(WO_COLD_AMBIENT);
		panelRegistrationRepository.saveAndFlush(join);

		saveSplit("A1", PCBA_1);
		saveSplit("A2", PCBA_2);
		saveDepanelPass(PCBA_1);
		saveDepanelPass(PCBA_2);
	}

	@AfterEach
	void tearDown() {
		cleanup();
	}

	/**
	 * Two COMPLETE requests for the same PCBA at Cure: one allow, one ALREADY_COMPLETE, one row.
	 */
	@Test
	void complete_twoConcurrentRequestsForSamePcba_oneAllowOneAlreadyComplete() throws Exception {
		List<StationGateResponse> results = race(PCBA_1, PCBA_1);

		long allows = results.stream().filter(StationGateResponse::isAllowed).count();
		long already = results.stream()
				.filter(response -> !response.isAllowed() && "ALREADY_COMPLETE".equals(response.getReasonCode()))
				.count();
		assertEquals(1, allows, () -> "expected one allow, got " + results);
		assertEquals(1, already, () -> "expected one ALREADY_COMPLETE, got " + results);

		StationGateResponse winner = results.stream().filter(StationGateResponse::isAllowed).findFirst().orElseThrow();
		assertEquals("COMPLETE", winner.getOutcome());
		assertEquals("ASM", winner.getExpectedNext());

		List<OperationEventEntity> cureEvents = operationEventRepository.findBySerialNumberOrderByIdAsc(PCBA_1).stream()
				.filter(event -> "CURE".equals(event.getOpCode()) && "COMPLETE".equals(event.getOutcome()))
				.toList();
		assertEquals(1, cureEvents.size());
	}

	/**
	 * One COMPLETE for each PCBA on the same panel: both allowed, two rows. The lock is not the panel.
	 */
	@Test
	void complete_twoPcbasOnSamePanel_bothAllowed() throws Exception {
		List<StationGateResponse> results = race(PCBA_1, PCBA_2);

		assertEquals(2, results.stream().filter(StationGateResponse::isAllowed).count(), () -> "got " + results);
		for (StationGateResponse response : results) {
			assertTrue(response.isAllowed());
			assertEquals("COMPLETE", response.getOutcome());
			assertEquals("ASM", response.getExpectedNext());
		}
		assertEquals(1, cureCompletes(PCBA_1));
		assertEquals(1, cureCompletes(PCBA_2));
		assertFalse(panelRegistrationRepository.findByPanelNumber(PANEL).isEmpty());
	}

	private List<StationGateResponse> race(String firstSerial, String secondSerial) throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<StationGateResponse> first = pool.submit(() -> completeAfterBarrier(firstSerial, ready, start));
			Future<StationGateResponse> second = pool.submit(() -> completeAfterBarrier(secondSerial, ready, start));
			assertTrue(ready.await(10, TimeUnit.SECONDS), "workers did not reach the barrier");
			start.countDown();
			return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
		} finally {
			pool.shutdownNow();
		}
	}

	private StationGateResponse completeAfterBarrier(String serial, CountDownLatch ready, CountDownLatch start)
			throws Exception {
		ready.countDown();
		assertTrue(start.await(10, TimeUnit.SECONDS), "start signal timed out");
		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber(serial);
		request.setEquipmentId("AEL-01-CURE");
		request.setEquipmentLocalAt(LOCAL_AT);
		return stationGateService.complete(request);
	}

	private void saveSplit(String slot, String pcbaSerial) {
		DepanelSplitEntity row = new DepanelSplitEntity();
		row.setPanelNumber(PANEL);
		row.setSlot(slot);
		row.setPcbaSerial(pcbaSerial);
		row.setEquipmentLocalAt(LOCAL_AT);
		depanelSplitRepository.saveAndFlush(row);
	}

	private void saveDepanelPass(String pcbaSerial) {
		OperationEventEntity event = new OperationEventEntity();
		event.setSerialNumber(pcbaSerial);
		event.setOpCode("DEPANEL");
		event.setEquipmentId("AEL-01-DEPANEL");
		event.setOutcome("PASS");
		event.setEquipmentLocalAt(LOCAL_AT);
		operationEventRepository.saveAndFlush(event);
	}

	private int cureCompletes(String serial) {
		return (int) operationEventRepository.findBySerialNumberOrderByIdAsc(serial).stream()
				.filter(event -> "CURE".equals(event.getOpCode()) && "COMPLETE".equals(event.getOutcome()))
				.count();
	}

	private void cleanup() {
		for (String serial : List.of(PCBA_1, PCBA_2, PANEL)) {
			List<OperationEventEntity> events = operationEventRepository.findBySerialNumberOrderByIdAsc(serial);
			if (!events.isEmpty()) {
				operationEventRepository.deleteAll(events);
				operationEventRepository.flush();
			}
		}
		List<DepanelSplitEntity> rows = depanelSplitRepository.findByPanelNumberOrderBySlotAsc(PANEL);
		if (!rows.isEmpty()) {
			depanelSplitRepository.deleteAll(rows);
			depanelSplitRepository.flush();
		}
		panelRegistrationRepository.findByPanelNumber(PANEL).ifPresent(panel -> {
			panelRegistrationRepository.delete(panel);
			panelRegistrationRepository.flush();
		});
	}
}
