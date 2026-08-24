package com.ames.mes_api.stationgate;

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
 * <p>Happy path: serial joined to a WO, LOADER already COMPLETE, scan at Coating → allowed.
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
	void evaluateGate_whenThisOpIsExpectedNext_returnsAllowed() {
		PanelRegistrationEntity join = new PanelRegistrationEntity();
		join.setPanelNumber("PANEL-DEMO-001");
		join.setWorkOrderId("WO-DEMO-001");
		when(panelRegistrationRepository.findByPanelNumber("PANEL-DEMO-001")).thenReturn(Optional.of(join));

		WoPpBindingEntity binding = new WoPpBindingEntity();
		binding.setWorkOrderId("WO-DEMO-001");
		binding.setProcessPathId("pp_cold_ambient");
		when(woPpBindingRepository.findById("WO-DEMO-001")).thenReturn(Optional.of(binding));

		ProcessPathEntity path = new ProcessPathEntity();
		path.setProcessPathId("pp_cold_ambient");
		path.setContent(PATH_CONTENT);
		when(processPathRepository.findById("pp_cold_ambient")).thenReturn(Optional.of(path));

		OperationEventEntity loaderComplete = new OperationEventEntity();
		loaderComplete.setOpCode("LOADER");
		loaderComplete.setOutcome("COMPLETE");
		when(operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						eq("PANEL-DEMO-001"), eq(List.of("PASS", "COMPLETE"))))
				.thenReturn(List.of(loaderComplete));

		StationGateRequest request = new StationGateRequest();
		request.setSerialNumber("PANEL-DEMO-001");
		request.setEquipmentId("AEL-01-COAT");

		StationGateResponse response = stationGateService.evaluateGate(request);

		assertTrue(response.isAllowed());
		assertNull(response.getReasonCode());
	}
}
