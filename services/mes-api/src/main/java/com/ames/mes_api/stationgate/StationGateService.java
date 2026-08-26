package com.ames.mes_api.stationgate;

import static java.util.Map.entry;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.ames.mes_api.panelregistration.PanelRegistrationEntity;
import com.ames.mes_api.panelregistration.PanelRegistrationRepository;
import com.ames.mes_api.panelregistration.WoPpBindingEntity;
import com.ames.mes_api.panelregistration.WoPpBindingRepository;
import com.ames.mes_api.processpath.ProcessPathEntity;
import com.ames.mes_api.processpath.ProcessPathRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class StationGateService {
	public static final Logger logger = LoggerFactory.getLogger(StationGateService.class);
	/** Fixed station/tester → path op. Tech note 03 + plant AEL-01 IDs. */
	private static final Map<String, String> EQUIPMENT_TO_OP = Map.ofEntries(
			entry("AEL-01-LOADER", "LOADER"),
			entry("AEL-01-COAT", "COAT"),
			entry("AEL-01-UV", "UV"),
			entry("AEL-01-DEPANEL", "DEPANEL"),
			entry("AEL-01-CURE", "CURE"),
			entry("AEL-01-ASM", "ASM"),
			entry("AEL-01-COLD-EOL", "COLD_EOL"),
			entry("AEL-01-HOT-EOL", "HOT_EOL"),
			entry("AEL-01-AMBIENT-EOL", "AMBIENT_EOL"),
			entry("AEL-01-PACK", "PACK"));

	/**
	 * Pattern 2 flexible EOL PC — one equipment_id; serial picks which EOL op (AS-4 spirit on
	 * check). See tech note 01 / ticket-equipment-id-station-binding.
	 */
	private static final Set<String> FLEXIBLE_EOL_EQUIPMENT_IDS = Set.of("AEL-01-EOL-01");

	private static final Set<String> EOL_OPS = Set.of("COLD_EOL", "HOT_EOL", "AMBIENT_EOL");

	/** Sentinel thisOp when flexible EOL calls but path next is not an EOL op. */
	private static final String FLEXIBLE_EOL_SENTINEL = "EOL";

	private final PanelRegistrationRepository panelRegistrationRepository;
	private final ProcessPathRepository processPathRepository;
	private final WoPpBindingRepository woPpBindingRepository;
	private final OperationEventRepository operationEventRepository;
	private final ObjectMapper objectMapper;

	public StationGateService(
			PanelRegistrationRepository panelRegistrationRepository,
			ProcessPathRepository processPathRepository,
			WoPpBindingRepository woPpBindingRepository,
			OperationEventRepository operationEventRepository,
			ObjectMapper objectMapper) {
		this.panelRegistrationRepository = panelRegistrationRepository;
		this.processPathRepository = processPathRepository;
		this.woPpBindingRepository = woPpBindingRepository;
		this.operationEventRepository = operationEventRepository;
		this.objectMapper = objectMapper;
	}

	/*
	 * Gate check: may this serial be worked at this equipment now?
	 * Step 1: ORPHAN — no Panel Registration join (or join without WO) → deny.
	 * Step 2: known equipmentId? (EQUIPMENT_TO_OP or flexible EOL PC) — unknown → 400.
	 * Step 3: load pathOps + completed ops (PASS/COMPLETE + LOADER via join) → expectedNext;
	 *         resolve thisOp (map or Pattern 2 flexible EOL); then
	 *         ALREADY_COMPLETE / allow / WRONG_STATION.
	 */
	@Transactional(readOnly = true)
	public StationGateResponse evaluateGate(StationGateRequest request) {
		String serialNumber = request.getSerialNumber().trim();
		String equipmentId = request.getEquipmentId().trim();
		logger.info("evaluateGate start serialNumber={} equipmentId={}", serialNumber, equipmentId);

		StationGateResponse response;
		// Step 1: ORPHAN — no Panel Registration join (or join without WO)
		Optional<PanelRegistrationEntity> panelRegEntity =
				panelRegistrationRepository.findByPanelNumber(serialNumber);
		if (panelRegEntity.isEmpty() || panelRegEntity.get().getWorkOrderId() == null) {
			response = deny("ORPHAN", null);
			return logAndReturn(response);
		}

		// Step2: known equipment? (fixed map or flexible EOL PC) — unknown → 400
		if (!EQUIPMENT_TO_OP.containsKey(equipmentId)
				&& !FLEXIBLE_EOL_EQUIPMENT_IDS.contains(equipmentId)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown EQUIPMENT_ID: " + equipmentId);
		}

		// Step3 : current Operation vs expectedNext Operation.
		// current Operation: map via equipmentId (or Pattern 2 flexible EOL → current EOL next)
		// expectedNext Operation:serialNumber->workOrderId->processPathId->processPath, iterate the process path and check the eventSet to locate the first missing one;
		// Step3.1: get the process path id from wo_pp_binding
		String workOrderId = panelRegEntity.get().getWorkOrderId();
		WoPpBindingEntity woPpBinding = woPpBindingRepository
				.findById(workOrderId)
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.BAD_REQUEST, "work order binding not found: " + workOrderId));
		String processPathId = woPpBinding.getProcessPathId();
		if (processPathId == null || processPathId.isBlank()) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST, "process path id missing for work order: " + workOrderId);
		}
		// Step3.2: get the process content and put into List
		ProcessPathEntity processPath = processPathRepository
				.findById(processPathId)
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.BAD_REQUEST, "process path not found: " + processPathId));
		// pick up the "ops" values and convert the string into ArrayList
		List<String> pathOps = parseOrderedOps(processPath.getContent());

		// Step3.3: get the operation list and convert operationEventList to a HashSet
		List<OperationEventEntity> operationEventList =
				operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(
						serialNumber, List.of("PASS", "COMPLETE"));
		Set<String> operationEventSet = operationEventList.stream()
				.map(OperationEventEntity::getOpCode)
				.collect(Collectors.toSet());
		// Tech note 17: Panel Registration join means LOADER is done (no COMPLETE row required)
		operationEventSet.add("LOADER");

		String expectedNext = deriveNext(pathOps, operationEventSet);
		String thisOp = resolveThisOp(equipmentId, expectedNext);
		// Step3.4: check the thisOp is Already COMPLETE or expectedNext
		// Already COMPLETE at this station → deny with current path next (AS-3 AC)
		if (operationEventSet.contains(thisOp)) {
			response = deny("ALREADY_COMPLETE", expectedNext);
		} else if (thisOp.equals(expectedNext)) {
			response = allow(expectedNext);
		} else {
			response = deny("WRONG_STATION", expectedNext);
		}
		return logAndReturn(response);
	}

	/**
	 * Through-station COMPLETE (AS3-02) — Maya draft, please review.
	 * Reuses gate check; on allow, appends a COMPLETE row. Gate checking again is necessary as no one knows what could happen after gate checking before writing COMPLETE.
	 */
	@Transactional
	public StationGateResponse complete(StationGateRequest request) {
		String serialNumber = request.getSerialNumber().trim();
		String equipmentId = request.getEquipmentId().trim();
		logger.info("complete start serialNumber={} equipmentId={}", serialNumber, equipmentId);

		StationGateResponse gate = evaluateGate(request);
		if (!gate.isAllowed()) {
			return logAndReturn(gate);
		}

		String thisOp = resolveThisOp(equipmentId, gate.getExpectedNext());

		OperationEventEntity event = new OperationEventEntity();
		event.setSerialNumber(serialNumber);
		event.setOpCode(thisOp);
		event.setEquipmentId(equipmentId);
		event.setOutcome("COMPLETE");
		event.setEquipmentLocalAt(request.getEquipmentLocalAt());
		operationEventRepository.save(event);

		gate.setOutcome("COMPLETE");
		logger.info(
				"complete end allowed={} outcome={} expectedNext={}",
				gate.isAllowed(),
				gate.getOutcome(),
				gate.getExpectedNext());
		return gate;
	}

	/**
	 * Fixed benches: equipmentId → one path op. Flexible EOL PC: adopt expectedNext when it is an
	 * EOL op; otherwise a non-path sentinel so comparison yields WRONG_STATION.
	 */
	private String resolveThisOp(String equipmentId, String expectedNext) {
		if (FLEXIBLE_EOL_EQUIPMENT_IDS.contains(equipmentId)) {
			if (expectedNext != null && EOL_OPS.contains(expectedNext)) {
				return expectedNext;
			}
			return FLEXIBLE_EOL_SENTINEL;
		}
		return EQUIPMENT_TO_OP.get(equipmentId);
	}

	private StationGateResponse logAndReturn(StationGateResponse response) {
		logger.info(
				"evaluateGate end allowed={} reasonCode={} expectedNext={}",
				response.isAllowed(),
				response.getReasonCode(),
				response.getExpectedNext());
		return response;
	}

	private String deriveNext(List<String> pathOps, Set<String> operationEventSet) {
		// iterate through the pathOps and check if the op is in the operationEventSet
		for (String op : pathOps) {
			if (!operationEventSet.contains(op)) {
				return op;
			}
		}
		return null;
	}

	/** Parse process_path.content JSON → ordered ops list. */
	private List<String> parseOrderedOps(String contentJson) {
		try {
			JsonNode opsNode = objectMapper.readTree(contentJson).get("ops");
			return objectMapper.convertValue(
					opsNode, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
		} catch (Exception ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "process path content is invalid", ex);
		}
	}

	private StationGateResponse deny(String reasonCode, String expectedNext) {
		StationGateResponse response = new StationGateResponse();
		response.setAllowed(false);
		response.setReasonCode(reasonCode);
		response.setExpectedNext(expectedNext);
		return response;
	}

	private StationGateResponse allow(String expectedNext) {
		StationGateResponse response = new StationGateResponse();
		response.setAllowed(true);
		response.setExpectedNext(expectedNext);
		return response;
	}
}
