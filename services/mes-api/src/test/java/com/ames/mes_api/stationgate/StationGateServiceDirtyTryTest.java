package com.ames.mes_api.stationgate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Calls {@link StationGateService#evaluateGate} with real Spring Data repositories (live MySQL).
 *
 * <p>Requires port-forward / local MySQL and {@code MYSQL_PASSWORD} (same as {@code local} profile).
 * Skipped when that env var is unset so default {@code mvn test} stays green without a DB.
 */
@SpringBootTest
@ActiveProfiles("local")
@EnabledIfEnvironmentVariable(named = "MYSQL_PASSWORD", matches = ".+")
class StationGateServiceDirtyTryTest {

	@Autowired
	private StationGateService stationGateService;

	@Test
	void evaluateGate_whenSerialNotJoined_returnsOrphan() {
		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("PANEL-DEMO-014");
		request.setEquipmentId("AEL-01-COAT");

		StationGateResponse response = stationGateService.evaluateGate(request);

		assertFalse(response.isAllowed());
		assertEquals("ORPHAN", response.getReasonCode());
	}
}
