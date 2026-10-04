package com.ames.mes_api.stationgate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Live-MySQL concurrency IT — two COMPLETE calls race the same panel (tech note 23).
 *
 * <p><strong>Not</strong> in {@link StationGateIntegrationTest}: that suite uses class-level
 * {@code @Transactional} (one test TX / rollback). This class must let each thread commit its own
 * {@code complete(...)} transaction so {@code SELECT … FOR UPDATE} can serialize writers.
 *
 * <p>CI skips when {@code MYSQL_PASSWORD} is unset. Run locally: port-forward MySQL, set
 * {@code MYSQL_PASSWORD}, {@code local} profile.
 */
@SpringBootTest
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+")
class StationGateConcurrentCompleteIT {

	/** Seeded WO → {@code pp_cold_ambient} (V3). */
	private static final String WO_COLD_AMBIENT = "WO-DEMO-002";

	private static final String SERIAL = "PANEL-IT-CONCURRENT-COAT-001";

	private static final LocalDateTime LOCAL_AT = LocalDateTime.of(2026, 8, 28, 17, 0);

	@Autowired
	private StationGateService stationGateService;

	@Autowired
	private PanelRegistrationRepository panelRegistrationRepository;

	@Autowired
	private OperationEventRepository operationEventRepository;

	@BeforeEach
	void seedJoinedPanel() {
		cleanupSerial();
		PanelRegistrationEntity join = new PanelRegistrationEntity();
		join.setPanelNumber(SERIAL);
		join.setWorkOrderId(WO_COLD_AMBIENT);
		panelRegistrationRepository.saveAndFlush(join);
	}

	@AfterEach
	void tearDown() {
		cleanupSerial();
	}

	/**
	 * Two concurrent COMPLETE at Coating → exactly one allow + one COMPLETE row; loser
	 * ALREADY_COMPLETE (panel_registration FOR UPDATE — tech note 23).
	 */
	@Test
	void complete_twoConcurrentCoatRequests_oneAllowOneAlreadyComplete_oneEvent() throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<StationGateResponse> first = pool.submit(() -> completeAfterBarrier(ready, start));
			Future<StationGateResponse> second = pool.submit(() -> completeAfterBarrier(ready, start));

			assertTrue(ready.await(10, TimeUnit.SECONDS), "workers did not reach the barrier");
			start.countDown();

			StationGateResponse a = first.get(30, TimeUnit.SECONDS);
			StationGateResponse b = second.get(30, TimeUnit.SECONDS);

			long allows = List.of(a, b).stream().filter(StationGateResponse::isAllowed).count();
			long alreadyComplete = List.of(a, b).stream()
					.filter(r -> !r.isAllowed() && "ALREADY_COMPLETE".equals(r.getReasonCode()))
					.count();

			assertEquals(1, allows, () -> "expected one allow, got a=" + a + " b=" + b);
			assertEquals(1, alreadyComplete, () -> "expected one ALREADY_COMPLETE, got a=" + a + " b=" + b);

			StationGateResponse winner = a.isAllowed() ? a : b;
			assertEquals("COMPLETE", winner.getOutcome());
			assertEquals("UV", winner.getExpectedNext());

			StationGateResponse loser = a.isAllowed() ? b : a;
			assertFalse(loser.isAllowed());
			assertEquals("ALREADY_COMPLETE", loser.getReasonCode());
			assertEquals("UV", loser.getExpectedNext());

			List<OperationEventEntity> events =
					operationEventRepository.findBySerialNumberOrderByIdAsc(SERIAL);
			assertEquals(1, events.size());
			assertEquals("COAT", events.get(0).getOpCode());
			assertEquals("COMPLETE", events.get(0).getOutcome());
		} finally {
			pool.shutdownNow();
		}
	}

	private StationGateResponse completeAfterBarrier(CountDownLatch ready, CountDownLatch start)
			throws Exception {
		ready.countDown();
		assertTrue(start.await(10, TimeUnit.SECONDS), "start signal timed out");
		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber(SERIAL);
		request.setEquipmentId("AEL-01-COAT");
		request.setEquipmentLocalAt(LOCAL_AT);
		return stationGateService.complete(request);
	}

	/** Committing cleanup — no outer test TX on this class. */
	private void cleanupSerial() {
		List<OperationEventEntity> events =
				operationEventRepository.findBySerialNumberOrderByIdAsc(SERIAL);
		if (!events.isEmpty()) {
			operationEventRepository.deleteAll(events);
			operationEventRepository.flush();
		}
		panelRegistrationRepository
				.findByPanelNumber(SERIAL)
				.ifPresent(panel -> {
					panelRegistrationRepository.delete(panel);
					panelRegistrationRepository.flush();
				});
	}
}
