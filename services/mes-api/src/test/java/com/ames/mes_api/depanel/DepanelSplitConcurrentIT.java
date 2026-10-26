package com.ames.mes_api.depanel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ames.mes_api.panelregistration.PanelRegistrationEntity;
import com.ames.mes_api.panelregistration.PanelRegistrationRepository;
import com.ames.mes_api.stationgate.OperationEventEntity;
import com.ames.mes_api.stationgate.OperationEventRepository;
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
 * Live-MySQL concurrency test — two depanel splits race the same panel.
 *
 * <p>Not in {@link DepanelSplitIntegrationTest}: that class uses {@code @Transactional}, so both
 * requests share one transaction and the row lock cannot serialize them. Each thread here commits
 * its own {@code split} call.
 *
 * <p>CI skips when {@code MYSQL_PASSWORD} is unset. Run locally: port-forward MySQL, set
 * {@code MYSQL_PASSWORD}, {@code local} profile.
 */
@SpringBootTest
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+")
class DepanelSplitConcurrentIT {

	/** Seeded WO → {@code pp_cold_ambient} (V3). This test does not assert the path. */
	private static final String WO_COLD_AMBIENT = "WO-DEMO-002";

	private static final String PANEL = "PANEL-IT-DEPANEL-CONCURRENT-001";

	private static final LocalDateTime LOCAL_AT = LocalDateTime.of(2026, 10, 16, 19, 30);

	@Autowired
	private DepanelSplitService depanelSplitService;

	@Autowired
	private PanelRegistrationRepository panelRegistrationRepository;

	@Autowired
	private OperationEventRepository operationEventRepository;

	@Autowired
	private DepanelSplitRepository depanelSplitRepository;

	@BeforeEach
	void seedReadyForDepanel() {
		cleanupPanel();
		PanelRegistrationEntity join = new PanelRegistrationEntity();
		join.setPanelNumber(PANEL);
		join.setWorkOrderId(WO_COLD_AMBIENT);
		panelRegistrationRepository.saveAndFlush(join);
		seedEvent("COAT", "AEL-01-COAT", "COMPLETE");
		seedEvent("UV", "AEL-01-UV", "PASS");
	}

	@AfterEach
	void tearDown() {
		cleanupPanel();
	}

	/**
	 * Two concurrent splits → one stored split and one {@code ALREADY_COMPLETE}. One Depanel
	 * COMPLETE and four split rows remain.
	 */
	@Test
	void split_twoConcurrentRequests_oneStoredOneAlreadyComplete() throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<DepanelSplitResponse> first = pool.submit(() -> splitAfterBarrier(ready, start));
			Future<DepanelSplitResponse> second = pool.submit(() -> splitAfterBarrier(ready, start));

			assertTrue(ready.await(10, TimeUnit.SECONDS), "workers did not reach the barrier");
			start.countDown();

			DepanelSplitResponse a = first.get(30, TimeUnit.SECONDS);
			DepanelSplitResponse b = second.get(30, TimeUnit.SECONDS);

			long stored = List.of(a, b).stream().filter(DepanelSplitResponse::isStored).count();
			long alreadyComplete = List.of(a, b).stream()
					.filter(response -> !response.isStored() && "ALREADY_COMPLETE".equals(response.getReasonCode()))
					.count();

			assertEquals(1, stored, () -> "expected one stored split, got a=" + a + " b=" + b);
			assertEquals(1, alreadyComplete, () -> "expected one ALREADY_COMPLETE, got a=" + a + " b=" + b);

			DepanelSplitResponse winner = a.isStored() ? a : b;
			assertEquals(PANEL, winner.getPanelNumber());
			assertEquals("COMPLETE", winner.getOutcome());
			assertNull(winner.getReasonCode());
			assertNull(winner.getMessage());

			DepanelSplitResponse loser = a.isStored() ? b : a;
			assertFalse(loser.isStored());
			assertEquals("ALREADY_COMPLETE", loser.getReasonCode());
			assertEquals("This panel is already split. MES stored nothing.", loser.getMessage());
			assertNull(loser.getOutcome());

			List<DepanelSplitEntity> rows = depanelSplitRepository.findByPanelNumberOrderBySlotAsc(PANEL);
			assertEquals(List.of("A1", "A2", "B1", "B2"), rows.stream().map(DepanelSplitEntity::getSlot).toList());
			assertEquals(4, rows.size());

			List<OperationEventEntity> depanelEvents = operationEventRepository.findBySerialNumberOrderByIdAsc(PANEL)
					.stream()
					.filter(event -> "DEPANEL".equals(event.getOpCode()))
					.toList();
			assertEquals(1, depanelEvents.size());
			assertEquals("COMPLETE", depanelEvents.get(0).getOutcome());
			assertEquals(WO_COLD_AMBIENT, panelRegistrationRepository.findByPanelNumber(PANEL).orElseThrow().getWorkOrderId());
		} finally {
			pool.shutdownNow();
		}
	}

	private DepanelSplitResponse splitAfterBarrier(CountDownLatch ready, CountDownLatch start) throws Exception {
		ready.countDown();
		assertTrue(start.await(10, TimeUnit.SECONDS), "start signal timed out");
		return depanelSplitService.split(splitRequest());
	}

	private static DepanelSplitRequest splitRequest() {
		DepanelSplitRequest request = new DepanelSplitRequest();
		request.setPanelNumber(PANEL);
		request.setEquipmentId("AEL-01-DEPANEL");
		request.setEquipmentLocalAt(LOCAL_AT);
		request.setCavities(List.of(
				cavity("A1", "PCBA-IT-CONC-2101"),
				cavity("A2", "PCBA-IT-CONC-2102"),
				cavity("B1", "PCBA-IT-CONC-2103"),
				cavity("B2", "PCBA-IT-CONC-2104")));
		return request;
	}

	private static DepanelCavityRequest cavity(String slot, String cavityResult) {
		DepanelCavityRequest cavity = new DepanelCavityRequest();
		cavity.setSlot(slot);
		cavity.setCavityResult(cavityResult);
		return cavity;
	}

	private void seedEvent(String opCode, String equipmentId, String outcome) {
		OperationEventEntity event = new OperationEventEntity();
		event.setSerialNumber(PANEL);
		event.setOpCode(opCode);
		event.setEquipmentId(equipmentId);
		event.setOutcome(outcome);
		event.setEquipmentLocalAt(LOCAL_AT);
		operationEventRepository.saveAndFlush(event);
	}

	/** Committing cleanup — no outer test transaction on this class. */
	private void cleanupPanel() {
		List<DepanelSplitEntity> splits = depanelSplitRepository.findByPanelNumberOrderBySlotAsc(PANEL);
		if (!splits.isEmpty()) {
			depanelSplitRepository.deleteAll(splits);
			depanelSplitRepository.flush();
		}
		List<OperationEventEntity> events = operationEventRepository.findBySerialNumberOrderByIdAsc(PANEL);
		if (!events.isEmpty()) {
			operationEventRepository.deleteAll(events);
			operationEventRepository.flush();
		}
		panelRegistrationRepository.findByPanelNumber(PANEL).ifPresent(panel -> {
			panelRegistrationRepository.delete(panel);
			panelRegistrationRepository.flush();
		});
	}
}
