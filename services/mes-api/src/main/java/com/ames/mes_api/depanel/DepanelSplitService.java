package com.ames.mes_api.depanel;

import com.ames.mes_api.stationgate.StationGateRequest;
import com.ames.mes_api.stationgate.StationGateResponse;
import com.ames.mes_api.stationgate.StationGateService;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Depanel split write. The station gate stores the Depanel split COMPLETE.
 * This package does not call {@code OperationEventRepository} and does not write {@code panel_registration}.
 */
@Service
public class DepanelSplitService {

	static final String DEPANEL_OP = "DEPANEL";
	static final String HOLD = "HOLD";
	static final String HOLD_MESSAGE = "Bad cavity. MES stored nothing.";

	/** This part's cavity names. The table does not limit slot names. */
	private static final List<String> REQUIRED_SLOTS = List.of("A1", "A2", "B1", "B2");
	private static final Set<String> REQUIRED_SLOT_SET = Set.copyOf(REQUIRED_SLOTS);
	private static final Set<String> BAD_CAVITY = Set.of("XOUT", "EMPTY", "UNREADABLE");
	private static final int PCBA_MIN_LENGTH = 10;
	private static final int PCBA_MAX_LENGTH = 64;

	private static final Map<String, String> REFUSE_MESSAGES = Map.of(
			"ORPHAN", "Panel is not on a work order. MES stored nothing.",
			"WRONG_STATION", "Depanel is not the next step. MES stored nothing.",
			"ALREADY_COMPLETE", "This panel is already split. MES stored nothing.",
			"WRONG_ENDPOINT", "This station cannot split a panel. MES stored nothing.");

	private final StationGateService stationGateService;
	private final DepanelSplitRepository depanelSplitRepository;

	public DepanelSplitService(
			StationGateService stationGateService, DepanelSplitRepository depanelSplitRepository) {
		this.stationGateService = stationGateService;
		this.depanelSplitRepository = depanelSplitRepository;
	}

	/**
	 * Store the split, or return a refuse or hold with nothing stored.
	 *
	 * @param request depanel split request
	 * @return response body. The controller maps {@code stored} to 201 or 200
	 */
	@Transactional
	public DepanelSplitResponse split(DepanelSplitRequest request) {
		String panelNumber = requiredTrim(request.getPanelNumber(), "panelNumber");
		String equipmentId = requiredTrim(request.getEquipmentId(), "equipmentId");
		LocalDateTime equipmentLocalAt = request.getEquipmentLocalAt();
		if (equipmentLocalAt == null) {
			throw badRequest("equipmentLocalAt is required");
		}
		List<DepanelCavityRequest> cavities = request.getCavities();
		if (cavities == null || cavities.isEmpty()) {
			throw badRequest("cavities are required");
		}

		List<DepanelSplitInsertRow> rows = trimCavities(cavities);
		StationGateRequest gateRequest = gateRequest(panelNumber, equipmentId, equipmentLocalAt);

		StationGateResponse gate = stationGateService.evaluateGate(gateRequest);
		if (!gate.isAllowed()) {
			return refuse(panelNumber, gate.getReasonCode());
		}
		if (!DEPANEL_OP.equals(gate.getThisOp())) {
			return refuse(panelNumber, "WRONG_ENDPOINT");
		}
		if (badCavity(rows)) {
			return hold(panelNumber);
		}

		StationGateResponse completed = stationGateService.complete(gateRequest);
		if (!completed.isAllowed()) {
			return refuse(panelNumber, completed.getReasonCode());
		}

		List<DepanelSplitInsertRow> ordered = REQUIRED_SLOTS.stream()
				.map(slot -> rows.stream().filter(row -> slot.equals(row.slot())).findFirst().orElseThrow())
				.toList();
		depanelSplitRepository.insertSplit(panelNumber, ordered, equipmentLocalAt);
		return stored(panelNumber);
	}

	private static List<DepanelSplitInsertRow> trimCavities(List<DepanelCavityRequest> cavities) {
		return cavities.stream().map(cavity -> {
			if (cavity == null) {
				throw badRequest("slot and cavityResult are required");
			}
			String slot = requiredTrim(cavity.getSlot(), "slot");
			String cavityResult = requiredTrim(cavity.getCavityResult(), "cavityResult");
			return new DepanelSplitInsertRow(slot, cavityResult);
		}).toList();
	}

	private static boolean badCavity(List<DepanelSplitInsertRow> rows) {
		Set<String> slots = new HashSet<>();
		Set<String> serials = new HashSet<>();
		for (DepanelSplitInsertRow row : rows) {
			if (!slots.add(row.slot()) || !REQUIRED_SLOT_SET.contains(row.slot())) {
				return true;
			}
			String cavityResult = row.pcbaSerial();
			if (BAD_CAVITY.contains(cavityResult) || !validPcba(cavityResult) || !serials.add(cavityResult)) {
				return true;
			}
		}
		return slots.size() != REQUIRED_SLOTS.size();
	}

	/**
	 * This part's format rule: starts with {@code PCBA}, length 10 to 64.
	 * {@code XOUT}, {@code EMPTY}, and {@code UNREADABLE} are handled before this check.
	 */
	private static boolean validPcba(String serial) {
		return serial.startsWith("PCBA")
				&& serial.length() >= PCBA_MIN_LENGTH
				&& serial.length() <= PCBA_MAX_LENGTH;
	}

	private static StationGateRequest gateRequest(
			String panelNumber, String equipmentId, LocalDateTime equipmentLocalAt) {
		StationGateRequest gateRequest = new StationGateRequest();
		gateRequest.setSerialNumber(panelNumber);
		gateRequest.setEquipmentId(equipmentId);
		gateRequest.setEquipmentLocalAt(equipmentLocalAt);
		return gateRequest;
	}

	private static DepanelSplitResponse stored(String panelNumber) {
		DepanelSplitResponse response = new DepanelSplitResponse();
		response.setStored(true);
		response.setPanelNumber(panelNumber);
		response.setOutcome("COMPLETE");
		return response;
	}

	private static DepanelSplitResponse hold(String panelNumber) {
		DepanelSplitResponse response = new DepanelSplitResponse();
		response.setStored(false);
		response.setPanelNumber(panelNumber);
		response.setReasonCode(HOLD);
		response.setMessage(HOLD_MESSAGE);
		return response;
	}

	private static DepanelSplitResponse refuse(String panelNumber, String reasonCode) {
		DepanelSplitResponse response = new DepanelSplitResponse();
		response.setStored(false);
		response.setPanelNumber(panelNumber);
		response.setReasonCode(reasonCode);
		response.setMessage(REFUSE_MESSAGES.getOrDefault(reasonCode, "MES stored nothing."));
		return response;
	}

	private static String requiredTrim(String value, String field) {
		if (value == null || value.isBlank()) {
			throw badRequest(field + " is required");
		}
		return value.trim();
	}

	private static ResponseStatusException badRequest(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}
}
