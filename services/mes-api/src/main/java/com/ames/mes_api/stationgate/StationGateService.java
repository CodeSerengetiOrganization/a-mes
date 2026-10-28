package com.ames.mes_api.stationgate;

import static java.util.Map.entry;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.ames.mes_api.depanel.DepanelSplitEntity;
import com.ames.mes_api.depanel.DepanelSplitRepository;
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
	private static final String MSG_WRONG_ENDPOINT_THROUGH_ON_QUALITY =
			"Through COMPLETE belongs on station-completes — not the quality API";
	private static final String MSG_WRONG_ENDPOINT_LOADER_ON_QUALITY =
			"LOADER is satisfied by Panel Registration — not the quality API";
	private static final String MSG_WRONG_ENDPOINT_CHAMBER_ON_QUALITY =
			"Chamber soak is not a quality PASS/FAIL step";
	private static final String MSG_WRONG_ENDPOINT_NOT_QUALITY =
			"This station cannot append PASS/FAIL via the quality API";
	private static final String REASON_PANEL_AFTER_DEPANEL = "PANEL_AFTER_DEPANEL";
	private static final String MSG_PANEL_AFTER_DEPANEL = "Scan the PCBA serial.";
	private static final String REASON_DEPANEL_FAIL_BLOCKED = "DEPANEL_FAIL_BLOCKED";
	private static final String MSG_DEPANEL_FAIL_BLOCKED =
			"This PCBA failed at Depanel. MES stored nothing.";
	private static final String PANEL_NUMBER_PREFIX = "PANEL-";
	private static final String PCBA_SERIAL_PREFIX = "PCBA-";
	private static final String DEPANEL_OP = "DEPANEL";

	private enum EndpointKind {
		CHECK,
		COMPLETE,
		QUALITY,
		/** In-process Depanel split COMPLETE. HTTP station-completes must not use this kind. */
		DEPANEL_SPLIT
	}

	/**
	 * Through ops that may append COMPLETE via station-completes (whitelist — fail-closed).
	 * Loaded from ames.station-gate.through-complete-ops.
	 */
	private final Set<String> throughCompleteOps;

	/**
	 * Quality ops — PASS / FAIL on quality API; never COMPLETE via station-completes.
	 * Loaded from ames.station-gate.quality-ops.
	 */
	private final Set<String> qualityOps;

	/**
	 * Ops where a panel number is not executable. Loaded from ames.station-gate.after-depanel-ops.
	 * Includes the flexible EOL sentinel {@code EOL}. Not built from {@code process_path}.
	 */
	private final Set<String> afterDepanelOps;

	private final PanelRegistrationRepository panelRegistrationRepository;
	private final ProcessPathRepository processPathRepository;
	private final WoPpBindingRepository woPpBindingRepository;
	private final OperationEventRepository operationEventRepository;
	private final DepanelSplitRepository depanelSplitRepository;
	private final ObjectMapper objectMapper;

	/**
	 * Create the station gate service.
	 *
	 * @param panelRegistrationRepository panel registration repository
	 * @param processPathRepository process path repository
	 * @param woPpBindingRepository work order to process path binding repository
	 * @param operationEventRepository operation event repository
	 * @param depanelSplitRepository depanel split repository
	 * @param objectMapper JSON object mapper
	 * @param throughCompleteOpsCsv comma-separated through-complete op whitelist
	 * @param qualityOpsCsv comma-separated quality op whitelist
	 * @param afterDepanelOpsCsv comma-separated ops that refuse a panel number
	 */
	public StationGateService(
			PanelRegistrationRepository panelRegistrationRepository,
			ProcessPathRepository processPathRepository,
			WoPpBindingRepository woPpBindingRepository,
			OperationEventRepository operationEventRepository,
			DepanelSplitRepository depanelSplitRepository,
			ObjectMapper objectMapper,
			@Value("${ames.station-gate.through-complete-ops}") String throughCompleteOpsCsv,
			@Value("${ames.station-gate.quality-ops}") String qualityOpsCsv,
			@Value("${ames.station-gate.after-depanel-ops}") String afterDepanelOpsCsv) {
		this.panelRegistrationRepository = panelRegistrationRepository;
		this.processPathRepository = processPathRepository;
		this.woPpBindingRepository = woPpBindingRepository;
		this.operationEventRepository = operationEventRepository;
		this.depanelSplitRepository = depanelSplitRepository;
		this.objectMapper = objectMapper;
		this.throughCompleteOps = parseOps("ames.station-gate.through-complete-ops", throughCompleteOpsCsv);
		this.qualityOps = parseOps("ames.station-gate.quality-ops", qualityOpsCsv);
		this.afterDepanelOps = parseOps("ames.station-gate.after-depanel-ops", afterDepanelOpsCsv);
		Set<String> overlap = new HashSet<>(throughCompleteOps);
		overlap.retainAll(qualityOps);
		if (!overlap.isEmpty()) {
			throw new IllegalStateException(
					"ops cannot be both through-complete and quality (fail-closed): " + overlap);
		}
	}

	/**
	 * Parse an op CSV whitelist; blank or empty fails closed.
	 *
	 * @param property property name, for the startup error message
	 * @param csv comma-separated ops
	 * @return unmodifiable set of ops
	 */
	private static Set<String> parseOps(String property, String csv) {
		if (csv == null || csv.isBlank()) {
			throw new IllegalStateException(property + " must not be blank (fail-closed)");
		}
		Set<String> ops = Arrays.stream(csv.split("\\s*,\\s*"))
				.map(String::trim)
				.filter(s -> !s.isBlank())
				.collect(Collectors.toUnmodifiableSet());
		if (ops.isEmpty()) {
			throw new IllegalStateException(property + " parsed empty (fail-closed)");
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
		StationGateResponse panelRefuse = panelAfterDepanelRefuse(serialNumber, equipmentId);
		if (panelRefuse != null) {
			return logAndReturn(panelRefuse);
		}
		return logAndReturn(evaluateGateCore(serialNumber, equipmentId, EndpointKind.CHECK).response());
	}

	/**
	 * Append through-station COMPLETE evidence when the gate allows.
	 *
	 * <p>A panel number locks the panel registration row (tech note 23). A PCBA serial locks that
	 * PCBA's split row and does not lock the panel. A panel number at an after-Depanel op is refused
	 * before either lock. The lock is released on commit/rollback. The locked row is not updated.
	 *
	 * @param request station complete request
	 * @return gate response with outcome COMPLETE on allow, or plant deny
	 */
	@Transactional
	public StationGateResponse complete(StationGateRequest request) {
		String serialNumber = request.getSerialNumber().trim();
		String equipmentId = request.getEquipmentId().trim();
		logger.info("complete start serialNumber={} equipmentId={}", serialNumber, equipmentId);
		return appendAllowedOutcome(request, serialNumber, equipmentId, "COMPLETE", EndpointKind.COMPLETE);
	}

	/**
	 * Append quality PASS/FAIL evidence when the gate allows.
	 *
	 * <p>Same lock and gate as {@link #complete} — inverted class fence (quality ops only).
	 * PASS advances path; FAIL is stored and expected next stays.
	 *
	 * @param request station quality request ({@code serialNumber}, {@code equipmentId}, {@code equipmentLocalAt})
	 * @param outcome {@code PASS} or {@code FAIL}
	 * @return gate response with outcome on allow, or plant deny
	 */
	@Transactional
	public StationGateResponse recordQuality(StationGateRequest request, String outcome) {
		String serialNumber = request.getSerialNumber().trim();
		String equipmentId = request.getEquipmentId().trim();
		logger.info(
				"recordQuality start serialNumber={} equipmentId={} outcome={}",
				serialNumber,
				equipmentId,
				outcome);
		if (outcome == null || !("PASS".equals(outcome) || "FAIL".equals(outcome))) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "outcome must be PASS or FAIL");
		}
		return appendAllowedOutcome(request, serialNumber, equipmentId, outcome, EndpointKind.QUALITY);
	}

	/**
	 * Store the Depanel split COMPLETE for a panel number.
	 *
	 * <p>HTTP {@code POST /api/station-completes} must not use this method. Depanel is a quality op,
	 * so that endpoint returns {@code WRONG_ENDPOINT}. This in-process call still stores
	 * {@code COMPLETE} under the panel number and locks the panel row.
	 *
	 * @param request gate request for the panel number at PCB Depaneler
	 * @return gate response with outcome COMPLETE on allow, or plant deny
	 */
	@Transactional
	public StationGateResponse completeDepanelSplit(StationGateRequest request) {
		String serialNumber = request.getSerialNumber().trim();
		String equipmentId = request.getEquipmentId().trim();
		logger.info("completeDepanelSplit start serialNumber={} equipmentId={}", serialNumber, equipmentId);
		return appendAllowedOutcome(request, serialNumber, equipmentId, "COMPLETE", EndpointKind.DEPANEL_SPLIT);
	}

	/**
	 * Lock, evaluate, append {@code operation_event} when allowed.
	 *
	 * @param request original request (for {@code equipmentLocalAt})
	 * @param serialNumber trimmed serial
	 * @param equipmentId trimmed equipment id
	 * @param outcome COMPLETE, PASS, or FAIL
	 * @param kind COMPLETE or QUALITY write path
	 * @return gate response
	 */
	private StationGateResponse appendAllowedOutcome(
			StationGateRequest request,
			String serialNumber,
			String equipmentId,
			String outcome,
			EndpointKind kind) {
		if (request.getEquipmentLocalAt() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "equipmentLocalAt is required");
		}

		StationGateResponse panelRefuse = panelAfterDepanelRefuse(serialNumber, equipmentId);
		if (panelRefuse != null) {
			return logAndReturn(panelRefuse);
		}

		// PCBA writes lock the split row. Panel-number writes lock the join row. Neither updates the row.
		if (isPcbaSerial(serialNumber)) {
			depanelSplitRepository.findByPcbaSerialForUpdate(serialNumber);
		} else {
			panelRegistrationRepository.findByPanelNumberForUpdate(serialNumber);
		}

		GateEvaluation evaluation = evaluateGateCore(serialNumber, equipmentId, kind);
		StationGateResponse gate = evaluation.response();
		if (!gate.isAllowed()) {
			return logAndReturn(gate);
		}

		OperationEventEntity event = new OperationEventEntity();
		event.setSerialNumber(serialNumber);
		event.setOpCode(evaluation.thisOp());
		event.setEquipmentId(equipmentId);
		event.setOutcome(outcome);
		event.setEquipmentLocalAt(request.getEquipmentLocalAt());
		operationEventRepository.save(event);

		gate.setOutcome(outcome);
		if ("FAIL".equals(outcome)) {
			// FAIL is evidence only — do not enter deriveNext done set.
			logger.info(
					"append end allowed={} outcome={} expectedNext={}",
					gate.isAllowed(),
					gate.getOutcome(),
					gate.getExpectedNext());
			return gate;
		}
		Set<String> completedAfter = new HashSet<>(evaluation.completedOps());
		completedAfter.add(evaluation.thisOp());
		gate.setExpectedNext(deriveNext(evaluation.pathOps(), completedAfter));
		logger.info(
				"append end allowed={} outcome={} expectedNext={}",
				gate.isAllowed(),
				gate.getOutcome(),
				gate.getExpectedNext());
		return gate;
	}

	/**
	 * Shared gate evaluation for check, COMPLETE, and quality PASS/FAIL.
	 *
	 * @param serialNumber scanned serial number
	 * @param equipmentId station equipment id
	 * @param kind CHECK (no class fence), COMPLETE (through whitelist), QUALITY (quality whitelist)
	 * @return gate evaluation including response, path ops, completed ops, and thisOp
	 */
	private GateEvaluation evaluateGateCore(String serialNumber, String equipmentId, EndpointKind kind) {
		if (isPcbaSerial(serialNumber)) {
			return evaluatePcba(serialNumber, equipmentId, kind);
		}

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
		requireKnownEquipment(equipmentId);

		// Step 3: load pathOps + completed ops (PASS/COMPLETE + LOADER via join) → expectedNext.
		ProcessPathContext processPathContext =
				loadProcessPathContext(serialNumber, panelRegEntity.get().getWorkOrderId());
		return decide(equipmentId, kind, processPathContext, false);
	}

	/**
	 * Gate a PCBA serial from its split row. The parent panel supplies the work order.
	 * The PCBA is not copied onto {@code panel_registration}.
	 *
	 * @param pcbaSerial scanned PCBA serial
	 * @param equipmentId station equipment id
	 * @param kind check, complete, or quality
	 * @return gate evaluation
	 */
	private GateEvaluation evaluatePcba(String pcbaSerial, String equipmentId, EndpointKind kind) {
		List<DepanelSplitEntity> rows = depanelSplitRepository.findByPcbaSerial(pcbaSerial);
		if (rows.isEmpty()) {
			return orphanEvaluation(equipmentId);
		}
		if (rows.size() != 1) {
			throw new ResponseStatusException(
					HttpStatus.CONFLICT, "PCBA serial is on more than one panel: " + pcbaSerial);
		}

		String panelNumber = rows.get(0).getPanelNumber();
		Optional<PanelRegistrationEntity> panel = panelRegistrationRepository.findByPanelNumber(panelNumber);
		if (panel.isEmpty() || panel.get().getWorkOrderId() == null) {
			return orphanEvaluation(equipmentId);
		}

		requireKnownEquipment(equipmentId);
		ProcessPathContext processPathContext =
				loadPcbaContext(pcbaSerial, panel.get().getWorkOrderId());
		return decide(equipmentId, kind, processPathContext, true);
	}

	/**
	 * Apply the class fence, the Depanel FAIL block, then allow / already complete / wrong station.
	 *
	 * @param equipmentId station equipment id
	 * @param kind endpoint kind
	 * @param processPathContext loaded path
	 * @param blockDepanelFail true when the scan is a PCBA serial
	 * @return gate evaluation
	 */
	private GateEvaluation decide(
			String equipmentId,
			EndpointKind kind,
			ProcessPathContext processPathContext,
			boolean blockDepanelFail) {
		String expectedNext = deriveNext(processPathContext.pathOps(), processPathContext.completedOps());
		String thisOp = resolveThisOp(equipmentId, expectedNext);
		if (kind == EndpointKind.COMPLETE && !isThroughCompleteOp(thisOp)) {
			return endpointDeny(wrongEndpointForComplete(thisOp, expectedNext), processPathContext, thisOp);
		}
		if (kind == EndpointKind.QUALITY && !isQualityOp(thisOp)) {
			return endpointDeny(wrongEndpointForQuality(thisOp, expectedNext), processPathContext, thisOp);
		}
		if (kind == EndpointKind.DEPANEL_SPLIT && !DEPANEL_OP.equals(thisOp)) {
			return endpointDeny(wrongEndpointForComplete(thisOp, expectedNext), processPathContext, thisOp);
		}
		if (blockDepanelFail
				&& DEPANEL_OP.equals(thisOp)
				&& kind != EndpointKind.COMPLETE
				&& hasDepanelFail(processPathContext)) {
			StationGateResponse blocked = deny(
					REASON_DEPANEL_FAIL_BLOCKED, expectedNext, MSG_DEPANEL_FAIL_BLOCKED);
			attachMesContext(
					blocked,
					processPathContext.workOrderId(),
					processPathContext.processPathId(),
					thisOp);
			return new GateEvaluation(
					blocked, processPathContext.pathOps(), processPathContext.completedOps(), thisOp);
		}
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
	 * Whether this op may append COMPLETE via station-completes (through whitelist).
	 *
	 * @param thisOp op implied by equipment id
	 * @return true when thisOp is on the through-complete whitelist
	 */
	private boolean isThroughCompleteOp(String thisOp) {
		return thisOp != null && throughCompleteOps.contains(thisOp);
	}

	/**
	 * Plant WRONG_ENDPOINT deny when COMPLETE was posted on a non-through station.
	 *
	 * @param thisOp op implied by equipment id
	 * @param expectedNext current path next op
	 * @return WRONG_ENDPOINT deny with class-specific message
	 */
	private StationGateResponse wrongEndpointForComplete(String thisOp, String expectedNext) {
		if (LOADER_OP.equals(thisOp)) {
			return deny("WRONG_ENDPOINT", expectedNext, MSG_WRONG_ENDPOINT_LOADER);
		}
		if (thisOp != null
				&& (qualityOps.contains(thisOp) || FLEXIBLE_EOL_SENTINEL.equals(thisOp))) {
			return deny("WRONG_ENDPOINT", expectedNext, MSG_WRONG_ENDPOINT_QUALITY);
		}
		if (thisOp != null && CHAMBER_OPS.contains(thisOp)) {
			return deny("WRONG_ENDPOINT", expectedNext, MSG_WRONG_ENDPOINT_CHAMBER);
		}
		return deny("WRONG_ENDPOINT", expectedNext, MSG_WRONG_ENDPOINT_NOT_THROUGH);
	}

	/**
	 * Whether this op may append PASS/FAIL on the quality writer (quality whitelist).
	 *
	 * @param thisOp op implied by equipment id
	 * @return true when thisOp is a quality op (or flexible EOL sentinel)
	 */
	private boolean isQualityOp(String thisOp) {
		return thisOp != null
				&& (qualityOps.contains(thisOp) || FLEXIBLE_EOL_SENTINEL.equals(thisOp));
	}

	/**
	 * Plant WRONG_ENDPOINT deny when PASS/FAIL was posted on a non-quality station.
	 *
	 * @param thisOp op implied by equipment id
	 * @param expectedNext current path next op
	 * @return WRONG_ENDPOINT deny with class-specific message
	 */
	private StationGateResponse wrongEndpointForQuality(String thisOp, String expectedNext) {
		if (LOADER_OP.equals(thisOp)) {
			return deny("WRONG_ENDPOINT", expectedNext, MSG_WRONG_ENDPOINT_LOADER_ON_QUALITY);
		}
		if (thisOp != null && throughCompleteOps.contains(thisOp)) {
			return deny("WRONG_ENDPOINT", expectedNext, MSG_WRONG_ENDPOINT_THROUGH_ON_QUALITY);
		}
		if (thisOp != null && CHAMBER_OPS.contains(thisOp)) {
			return deny("WRONG_ENDPOINT", expectedNext, MSG_WRONG_ENDPOINT_CHAMBER_ON_QUALITY);
		}
		return deny("WRONG_ENDPOINT", expectedNext, MSG_WRONG_ENDPOINT_NOT_QUALITY);
	}

	/**
	 * Attach MES context and wrap a class-fence deny as a gate evaluation.
	 *
	 * @param wrongEndpoint WRONG_ENDPOINT response
	 * @param processPathContext loaded path context
	 * @param thisOp op implied by equipment id
	 * @return gate evaluation that should return without appending
	 */
	private GateEvaluation endpointDeny(
			StationGateResponse wrongEndpoint, ProcessPathContext processPathContext, String thisOp) {
		attachMesContext(
				wrongEndpoint,
				processPathContext.workOrderId(),
				processPathContext.processPathId(),
				thisOp);
		return new GateEvaluation(
				wrongEndpoint,
				processPathContext.pathOps(),
				processPathContext.completedOps(),
				thisOp);
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
		return new ProcessPathContext(pathOps, completedOps, workOrderId, processPathId, serialNumber);
	}

	/**
	 * Load the process path for a PCBA. Ops before {@code DEPANEL} count as done because the split
	 * proves them. {@code DEPANEL} counts only after a PASS on this PCBA serial. The panel's Depanel
	 * split COMPLETE does not count. A path with no {@code DEPANEL} counts no op as done.
	 *
	 * @param pcbaSerial scanned PCBA serial
	 * @param workOrderId parent panel work order
	 * @return process path context
	 */
	private ProcessPathContext loadPcbaContext(String pcbaSerial, String workOrderId) {
		WoPpBindingEntity woPpBinding = woPpBindingRepository
				.findById(workOrderId)
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.BAD_REQUEST, "work order binding not found: " + workOrderId));
		String processPathId = woPpBinding.getProcessPathId();
		if (processPathId == null || processPathId.isBlank()) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST, "process path id missing for work order: " + workOrderId);
		}
		ProcessPathEntity processPath = processPathRepository
				.findById(processPathId)
				.orElseThrow(() -> new ResponseStatusException(
						HttpStatus.BAD_REQUEST, "process path not found: " + processPathId));
		List<String> pathOps = parseOrderedOps(processPath.getContent());
		Set<String> completedOps = completedOpsForPcba(pcbaSerial, pathOps);
		return new ProcessPathContext(pathOps, completedOps, workOrderId, processPathId, pcbaSerial);
	}

	/**
	 * Completed ops for one PCBA serial.
	 *
	 * @param pcbaSerial scanned PCBA serial
	 * @param pathOps ordered process path ops
	 * @return ops that count as done for this PCBA
	 */
	private Set<String> completedOpsForPcba(String pcbaSerial, List<String> pathOps) {
		int depanelIndex = pathOps.indexOf(DEPANEL_OP);
		if (depanelIndex < 0) {
			return Set.of();
		}
		Set<String> completed = new HashSet<>();
		for (int i = 0; i < depanelIndex; i++) {
			completed.add(pathOps.get(i));
		}
		List<OperationEventEntity> events = operationEventRepository
				.findBySerialNumberAndOutcomeInOrderByIdAsc(pcbaSerial, List.of("PASS", "COMPLETE"));
		for (OperationEventEntity event : events) {
			if (DEPANEL_OP.equals(event.getOpCode()) && !"PASS".equals(event.getOutcome())) {
				continue;
			}
			completed.add(event.getOpCode());
		}
		return completed;
	}

	/**
	 * True when this PCBA already has a Depanel FAIL.
	 *
	 * @param processPathContext context whose event serial is the PCBA
	 * @return true when a Depanel FAIL is stored
	 */
	private boolean hasDepanelFail(ProcessPathContext processPathContext) {
		return operationEventRepository
				.findBySerialNumberAndOutcomeInOrderByIdAsc(processPathContext.eventSerial(), List.of("FAIL"))
				.stream()
				.anyMatch(event -> DEPANEL_OP.equals(event.getOpCode()));
	}

	/**
	 * Refuse a panel number at an after-Depanel op before any table read.
	 *
	 * @param serialNumber trimmed serial
	 * @param equipmentId trimmed equipment id
	 * @return deny response, or null when this scan is not that case
	 */
	private StationGateResponse panelAfterDepanelRefuse(String serialNumber, String equipmentId) {
		if (!isPanelNumber(serialNumber)) {
			return null;
		}
		String thisOp = resolveThisOpForEcho(equipmentId);
		if (thisOp == null || !afterDepanelOps.contains(thisOp)) {
			return null;
		}
		return attachMesContext(
				deny(REASON_PANEL_AFTER_DEPANEL, null, MSG_PANEL_AFTER_DEPANEL), null, null, thisOp);
	}

	private static boolean isPanelNumber(String serialNumber) {
		return serialNumber.startsWith(PANEL_NUMBER_PREFIX);
	}

	private static boolean isPcbaSerial(String serialNumber) {
		return serialNumber.startsWith(PCBA_SERIAL_PREFIX);
	}

	private static void requireKnownEquipment(String equipmentId) {
		if (!EQUIPMENT_TO_OP.containsKey(equipmentId) && !FLEXIBLE_EOL_EQUIPMENT_IDS.contains(equipmentId)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown EQUIPMENT_ID: " + equipmentId);
		}
	}

	private GateEvaluation orphanEvaluation(String equipmentId) {
		String thisOpOrphan = resolveThisOpForEcho(equipmentId);
		StationGateResponse orphan =
				attachMesContext(deny("ORPHAN", null, MSG_ORPHAN), null, null, thisOpOrphan);
		return new GateEvaluation(orphan, List.of(), Set.of(), thisOpOrphan);
	}

	private record GateEvaluation(
			StationGateResponse response, List<String> pathOps, Set<String> completedOps, String thisOp) {}

	private record ProcessPathContext(
			List<String> pathOps,
			Set<String> completedOps,
			String workOrderId,
			String processPathId,
			String eventSerial) {}

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
