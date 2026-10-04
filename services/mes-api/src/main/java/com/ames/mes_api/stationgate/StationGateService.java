package com.ames.mes_api.stationgate;

import static java.util.Map.entry;

import java.util.Arrays;
import java.util.HashSet;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class StationGateService {
	public static final Logger logger = LoggerFactory.getLogger(StationGateService.class);
	private static final String LOADER_EQUIPMENT_ID = "AEL-01-LOADER";
	private static final String LOADER_OP = "LOADER";

	/**
	 * Fixed station / tester equipment id to path op map (tech note 03 + plant AEL-01 ids).
	 */
	private static final Map<String, String> EQUIPMENT_TO_OP = Map.ofEntries(
			entry(LOADER_EQUIPMENT_ID, LOADER_OP),
			entry("AEL-01-COAT", "COAT"),
			entry("AEL-01-UV", "UV"),
			entry("AEL-01-DEPANEL", "DEPANEL"),
			entry("AEL-01-CURE", "CURE"),
			entry("AEL-01-ASM", "ASM"),
			entry("AEL-01-COLD-EOL", "COLD_EOL"),
			entry("AEL-01-HOT-EOL", "HOT_EOL"),
			entry("AEL-01-AMBIENT-EOL", "AMBIENT_EOL"),
			entry("AEL-01-PACK", "PACK"),
			entry("AEL-01-COLD-CHAMBER", "COLD_CHAMBER"),
			entry("AEL-01-HOT-CHAMBER", "HOT_CHAMBER"));

	/**
	 * Pattern 2 flexible EOL PC equipment ids — one equipment id; serial picks which EOL op.
	 */
	private static final Set<String> FLEXIBLE_EOL_EQUIPMENT_IDS = Set.of("AEL-01-EOL-01");

	private static final Set<String> EOL_OPS = Set.of("COLD_EOL", "HOT_EOL", "AMBIENT_EOL");

	/**
	 * Quality ops — PASS / FAIL on quality API; never COMPLETE via station-completes.
	 */
	private static final Set<String> QUALITY_OPS = Set.of("UV", "COLD_EOL", "HOT_EOL", "AMBIENT_EOL");

	/**
	 * Non-path soak ops — WRONG_ENDPOINT on COMPLETE (not silent 400).
	 */
	private static final Set<String> CHAMBER_OPS = Set.of("COLD_CHAMBER", "HOT_CHAMBER");

	/**
	 * Sentinel thisOp when flexible EOL calls but path next is not an EOL op.
	 */
	private static final String FLEXIBLE_EOL_SENTINEL = "EOL";

	private static final String MSG_ORPHAN = "Serial not joined to a work order";
	private static final String MSG_WRONG_ENDPOINT_LOADER =
			"LOADER is satisfied by Panel Registration — not station-completes";
	private static final String MSG_WRONG_ENDPOINT_QUALITY =
			"Quality PASS/FAIL belongs on the quality API — not station-completes";
	private static final String MSG_WRONG_ENDPOINT_CHAMBER =
			"Chamber soak is not a station-completes step — no COMPLETE here";
	private static final String MSG_WRONG_ENDPOINT_NOT_THROUGH =
			"This station cannot append COMPLETE via station-completes";

	/**
	 * Through ops that may append COMPLETE via station-completes (whitelist — fail-closed).
	 * Loaded from ames.station-gate.through-complete-ops.
	 */
	private final Set<String> throughCompleteOps;

	private final PanelRegistrationRepository panelRegistrationRepository;
	private final ProcessPathRepository processPathRepository;
	private final WoPpBindingRepository woPpBindingRepository;
	private final OperationEventRepository operationEventRepository;
	private final ObjectMapper objectMapper;

	/**
	 * Create the station gate service.
	 *
	 * @param panelRegistrationRepository panel registration repository
	 * @param processPathRepository process path repository
	 * @param woPpBindingRepository work order to process path binding repository
	 * @param operationEventRepository operation event repository
	 * @param objectMapper JSON object mapper
	 * @param throughCompleteOpsCsv comma-separated through-complete op whitelist
	 */
	public StationGateService(
			PanelRegistrationRepository panelRegistrationRepository,
			ProcessPathRepository processPathRepository,
			WoPpBindingRepository woPpBindingRepository,
			OperationEventRepository operationEventRepository,
			ObjectMapper objectMapper,
			@Value("${ames.station-gate.through-complete-ops}") String throughCompleteOpsCsv) {
		this.panelRegistrationRepository = panelRegistrationRepository;
		this.processPathRepository = processPathRepository;
		this.woPpBindingRepository = woPpBindingRepository;
		this.operationEventRepository = operationEventRepository;
		this.objectMapper = objectMapper;
		this.throughCompleteOps = parseThroughCompleteOps(throughCompleteOpsCsv);
	}

	/**
	 * Parse the through-complete op CSV whitelist; blank or empty fails closed.
	 *
	 * @param csv comma-separated through-complete ops
	 * @return unmodifiable set of through-complete ops
	 */
	private static Set<String> parseThroughCompleteOps(String csv) {
		if (csv == null || csv.isBlank()) {
			throw new IllegalStateException(
					"ames.station-gate.through-complete-ops must not be blank (fail-closed)");
		}
		Set<String> ops = Arrays.stream(csv.split("\\s*,\\s*"))
				.filter(s -> !s.isBlank())
				.collect(Collectors.toUnmodifiableSet());
		if (ops.isEmpty()) {
			throw new IllegalStateException(
					"ames.station-gate.through-complete-ops parsed empty (fail-closed)");
		}
		return ops;
	}

	/**
	 * Evaluate the station gate check — may this serial be worked at this equipment now.
	 *
	 * @param request station gate request
	 * @return gate response (allow or plant deny)
	 */
	@Transactional(readOnly = true)
	public StationGateResponse evaluateGate(StationGateRequest request) {
		String serialNumber = request.getSerialNumber().trim();
		String equipmentId = request.getEquipmentId().trim();
		logger.info("evaluateGate start serialNumber={} equipmentId={}", serialNumber, equipmentId);
		return logAndReturn(evaluateGateCore(serialNumber, equipmentId, false).response());
	}

	/**
	 * Append through-station COMPLETE evidence when the gate allows.
	 *
	 * <p>Takes {@code SELECT … FOR UPDATE} on the panel registration join row first so concurrent
	 * COMPLETE requests for the same panel serialize (tech note 23). Stage-1 only: valid while the
	 * scanned identity is a panel number — revisit at Depanel / AS-5 for unit serials. The lock is
	 * released on commit/rollback; the join row is not updated by the lock itself. This is the approach to solve concurrent COMPLETE requests for the same panel.
	 *
	 * @param request station complete request
	 * @return gate response with outcome COMPLETE on allow, or plant deny
	 */
	@Transactional
	public StationGateResponse complete(StationGateRequest request) {
		String serialNumber = request.getSerialNumber().trim();
		String equipmentId = request.getEquipmentId().trim();
		logger.info("complete start serialNumber={} equipmentId={}", serialNumber, equipmentId);

		if (request.getEquipmentLocalAt() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "equipmentLocalAt is required");
		}

		// Serialize concurrent COMPLETE per panel — lock join row (no panel UPDATE).
		panelRegistrationRepository.findByPanelNumberForUpdate(serialNumber);

		GateEvaluation evaluation = evaluateGateCore(serialNumber, equipmentId, true);
		StationGateResponse gate = evaluation.response();
		if (!gate.isAllowed()) {
			return logAndReturn(gate);
		}

		OperationEventEntity event = new OperationEventEntity();
		event.setSerialNumber(serialNumber);
		event.setOpCode(evaluation.thisOp());
		event.setEquipmentId(equipmentId);
		event.setOutcome("COMPLETE");
		event.setEquipmentLocalAt(request.getEquipmentLocalAt());
		operationEventRepository.save(event);

		Set<String> completedAfter = new HashSet<>(evaluation.completedOps());
		completedAfter.add(evaluation.thisOp());

		gate.setOutcome("COMPLETE");
		gate.setExpectedNext(deriveNext(evaluation.pathOps(), completedAfter));
		logger.info(
				"complete end allowed={} outcome={} expectedNext={}",
				gate.isAllowed(),
				gate.getOutcome(),
				gate.getExpectedNext());
		return gate;
	}

	/**
	 * Shared gate evaluation for check and complete.
	 *
	 * @param serialNumber scanned serial number
	 * @param equipmentId station equipment id
	 * @param forComplete true for COMPLETE write path (applies through-complete fence)
	 * @return gate evaluation including response, path ops, completed ops, and thisOp
	 */
	private GateEvaluation evaluateGateCore(String serialNumber, String equipmentId, boolean forComplete) {
		// Step 1: ORPHAN — no Panel Registration join (or join without WO) → deny.
		Optional<PanelRegistrationEntity> panelRegEntity =
				panelRegistrationRepository.findByPanelNumber(serialNumber);
		if (panelRegEntity.isEmpty() || panelRegEntity.get().getWorkOrderId() == null) {
			String thisOpOrphan = resolveThisOpForEcho(equipmentId);
			StationGateResponse orphan = attachMesContext(
					deny("ORPHAN", null, MSG_ORPHAN),
					null,
					null,
					thisOpOrphan);
			return new GateEvaluation(orphan, List.of(), Set.of(), thisOpOrphan);
		}

		// Step 2: known equipmentId? (EQUIPMENT_TO_OP or flexible EOL PC) — unknown → 400.
		if (!EQUIPMENT_TO_OP.containsKey(equipmentId)
				&& !FLEXIBLE_EOL_EQUIPMENT_IDS.contains(equipmentId)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown EQUIPMENT_ID: " + equipmentId);
		}

		// Step 3: load pathOps + completed ops (PASS/COMPLETE + LOADER via join) → expectedNext;
		//         resolve thisOp (map or Pattern 2 flexible EOL); then
		//         ALREADY_COMPLETE / allow / WRONG_STATION.
		ProcessPathContext processPathContext =
				loadProcessPathContext(serialNumber, panelRegEntity.get().getWorkOrderId());
		String expectedNext = deriveNext(processPathContext.pathOps(), processPathContext.completedOps());
		String thisOp = resolveThisOp(equipmentId, expectedNext);
		if (forComplete) {
			StationGateResponse notThroughCompleteDeny =
					denyIfNotThroughCompleteStation(thisOp, expectedNext);
			if (notThroughCompleteDeny != null) {
				attachMesContext(
						notThroughCompleteDeny,
						processPathContext.workOrderId(),
						processPathContext.processPathId(),
						thisOp);
				return new GateEvaluation(
						notThroughCompleteDeny,
						processPathContext.pathOps(),
						processPathContext.completedOps(),
						thisOp);
			}
		}
		// Step3.4: check the thisOp is Already COMPLETE or expectedNext
		// Already COMPLETE at this station → deny with current path next
		StationGateResponse response;
		if (processPathContext.completedOps().contains(thisOp)) {
			response = deny("ALREADY_COMPLETE", expectedNext);
		} else if (thisOp.equals(expectedNext)) {
			response = allow(expectedNext);
		} else {
			response = deny(
					"WRONG_STATION",
					expectedNext,
					expectedNext != null ? "Expected next: " + expectedNext : null);
		}
		attachMesContext(
				response,
				processPathContext.workOrderId(),
				processPathContext.processPathId(),
				thisOp);
		return new GateEvaluation(
				response, processPathContext.pathOps(), processPathContext.completedOps(), thisOp);
	}

	/**
	 * Deny COMPLETE when thisOp is not on the through-complete whitelist.
	 *
	 * @param thisOp op implied by equipment id
	 * @param expectedNext current path next op
	 * @return WRONG_ENDPOINT deny response, or null when thisOp may append COMPLETE
	 */
	private StationGateResponse denyIfNotThroughCompleteStation(String thisOp, String expectedNext) {
		if (thisOp != null && throughCompleteOps.contains(thisOp)) {
			return null;
		}
		if (LOADER_OP.equals(thisOp)) {
			return deny("WRONG_ENDPOINT", expectedNext, MSG_WRONG_ENDPOINT_LOADER);
		}
		if (thisOp != null
				&& (QUALITY_OPS.contains(thisOp) || FLEXIBLE_EOL_SENTINEL.equals(thisOp))) {
			return deny("WRONG_ENDPOINT", expectedNext, MSG_WRONG_ENDPOINT_QUALITY);
		}
		if (thisOp != null && CHAMBER_OPS.contains(thisOp)) {
			return deny("WRONG_ENDPOINT", expectedNext, MSG_WRONG_ENDPOINT_CHAMBER);
		}
		return deny("WRONG_ENDPOINT", expectedNext, MSG_WRONG_ENDPOINT_NOT_THROUGH);
	}

	/**
	 * Load the process path context from the work order id.
	 *
	 * @param serialNumber the serial number
	 * @param workOrderId the work order id
	 * @return the process path context
	 */
	private ProcessPathContext loadProcessPathContext(String serialNumber, String workOrderId) {
		// Step3.1: get the process path id from wo_pp_binding
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
		Set<String> completedOps = operationEventRepository
				.findBySerialNumberAndOutcomeInOrderByIdAsc(serialNumber, List.of("PASS", "COMPLETE"))
				.stream()
				.map(OperationEventEntity::getOpCode)
				.collect(Collectors.toSet());
		//Panel Registration join means LOADER is done (no COMPLETE row required)
		completedOps.add(LOADER_OP);
		return new ProcessPathContext(pathOps, completedOps, workOrderId, processPathId);
	}

	private record GateEvaluation(
			StationGateResponse response, List<String> pathOps, Set<String> completedOps, String thisOp) {}

	private record ProcessPathContext(
			List<String> pathOps, Set<String> completedOps, String workOrderId, String processPathId) {}

	/**
	 * Resolve thisOp from equipment id (fixed map or Pattern 2 flexible EOL).
	 * If the machine is a fixed station: equipmentId → one path op. If the machine is a flexible EOL PC: expectedNext → EOL sentine
	 * If the machine is a flexible EOL PC: adopt expectedNext when it is an EOL op;otherwise a non-path sentinel so comparison yields WRONG_STATION.
	 * @param equipmentId station equipment id
	 * @param expectedNext current path next op
	 * @return path op for this station, or EOL sentinel for flexible EOL off-path
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

	/**
	 * Resolve thisOp for ORPHAN echo before path load (no expectedNext yet).
	 *
	 * @param equipmentId station equipment id
	 * @return path op or EOL sentinel
	 */
	private String resolveThisOpForEcho(String equipmentId) {
		if (FLEXIBLE_EOL_EQUIPMENT_IDS.contains(equipmentId)) {
			return FLEXIBLE_EOL_SENTINEL;
		}
		return EQUIPMENT_TO_OP.get(equipmentId);
	}

	/**
	 * Attach MES-derived context on every 200 body (no request echoes).
	 *
	 * @param response gate response to enrich
	 * @param workOrderId resolved work order id
	 * @param processPathId resolved process path id
	 * @param thisOp op implied by equipment id
	 * @return the same response with MES context set
	 */
	private StationGateResponse attachMesContext(
			StationGateResponse response,
			String workOrderId,
			String processPathId,
			String thisOp) {
		response.setWorkOrderId(workOrderId);
		response.setProcessPathId(processPathId);
		response.setThisOp(thisOp);
		return response;
	}

	/**
	 * Log the gate response and return it.
	 *
	 * @param response gate response
	 * @return the same response
	 */
	private StationGateResponse logAndReturn(StationGateResponse response) {
		logger.info(
				"evaluateGate end allowed={} reasonCode={} expectedNext={}",
				response.isAllowed(),
				response.getReasonCode(),
				response.getExpectedNext());
		return response;
	}

	/**
	 * Derive the next operation from path ops and completed ops.
	 *
	 * @param pathOps ordered process path ops
	 * @param operationEventSet completed ops for this serial
	 * @return first incomplete path op, or null when the path is finished
	 */
	private String deriveNext(List<String> pathOps, Set<String> operationEventSet) {
		for (String op : pathOps) {
			if (!operationEventSet.contains(op)) {
				return op;
			}
		}
		return null;
	}

	/**
	 * Parse process_path.content JSON into an ordered ops list.
	 *
	 * @param contentJson process path content JSON
	 * @return ordered ops list
	 */
	private List<String> parseOrderedOps(String contentJson) {
		try {
			JsonNode opsNode = objectMapper.readTree(contentJson).get("ops");
			return objectMapper.convertValue(
					opsNode, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
		} catch (Exception ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "process path content is invalid", ex);
		}
	}

	/**
	 * Create a deny response without a message.
	 *
	 * @param reasonCode plant deny reason code
	 * @param expectedNext current path next op
	 * @return deny response
	 */
	private StationGateResponse deny(String reasonCode, String expectedNext) {
		return deny(reasonCode, expectedNext, null);
	}

	/**
	 * Create a deny response.
	 *
	 * @param reasonCode plant deny reason code
	 * @param expectedNext current path next op
	 * @param message plant-facing message
	 * @return deny response
	 */
	private StationGateResponse deny(String reasonCode, String expectedNext, String message) {
		StationGateResponse response = new StationGateResponse();
		response.setAllowed(false);
		response.setReasonCode(reasonCode);
		response.setExpectedNext(expectedNext);
		response.setMessage(message);
		return response;
	}

	/**
	 * Create an allow response.
	 *
	 * @param expectedNext current path next op
	 * @return allow response
	 */
	private StationGateResponse allow(String expectedNext) {
		StationGateResponse response = new StationGateResponse();
		response.setAllowed(true);
		response.setExpectedNext(expectedNext);
		return response;
	}
}
