package com.ames.mes_api.stationgate;

import com.ames.mes_api.panelregistration.PanelRegistrationEntity;
import com.ames.mes_api.panelregistration.PanelRegistrationRepository;
import com.ames.mes_api.panelregistration.WoPpBindingEntity;
import com.ames.mes_api.panelregistration.WoPpBindingRepository;
import com.ames.mes_api.processpath.ProcessPathEntity;
import com.ames.mes_api.processpath.ProcessPathRepository;
//import com.fasterxml.jackson.databind.JsonNode;
//import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Station skip-ahead gate (Maya draft — please review).
 * Step 1: serial → work order, else ORPHAN.
 * Step 2: equipmentId → op (unknown → 400).
 * Step 3: path ops + events → next / already COMPLETE / wrong station.
 * Step 4 (complete only): on allow, append COMPLETE then return advanced expectedNext.
 */
@Service
public class StationGateServiceMaya {

	private static final Map<String, String> EQUIPMENT_TO_OP = Map.ofEntries(
			Map.entry("AEL-01-LOADER", "LOADER"),
			Map.entry("AEL-01-COAT", "COAT"),
			Map.entry("AEL-01-UV", "UV"),
			Map.entry("AEL-01-DEPANEL", "DEPANEL"),
			Map.entry("AEL-01-CURE", "CURE"),
			Map.entry("AEL-01-ASM", "ASM"),
			Map.entry("AEL-01-COLD-EOL", "COLD_EOL"),
			Map.entry("AEL-01-HOT-EOL", "HOT_EOL"),
			Map.entry("AEL-01-AMBIENT-EOL", "AMBIENT_EOL"),
			Map.entry("AEL-01-PACK", "PACK"));
	// Flexible EOL PC AEL-01-EOL-01: Pattern 2 — see StationGateService.resolveThisOp

//	private final PanelRegistrationRepository panelRegistrationRepository;
//	private final WoPpBindingRepository woPpBindingRepository;
//	private final ProcessPathRepository processPathRepository;
//	private final OperationEventRepository operationEventRepository;
/*	private final ObjectMapper objectMapper;

	public StationGateServiceMaya(
			PanelRegistrationRepository panelRegistrationRepository,
			WoPpBindingRepository woPpBindingRepository,
			ProcessPathRepository processPathRepository,
			OperationEventRepository operationEventRepository,
			ObjectMapper objectMapper) {
		this.panelRegistrationRepository = panelRegistrationRepository;
		this.woPpBindingRepository = woPpBindingRepository;
		this.processPathRepository = processPathRepository;
		this.operationEventRepository = operationEventRepository;
		this.objectMapper = objectMapper;
	}

	@Transactional(readOnly = true)
	public StationGateResponse check(StationGateRequest request) {
		return evaluateGate(request, false);
	}

	@Transactional
	public StationGateResponse complete(StationGateRequest request) {
		return evaluateGate(request, true);
	}

	private StationGateResponse evaluateGate(StationGateRequest request, boolean appendComplete) {
		String serialNumber = request.getSerialNumber().trim();
		String equipmentId = request.getEquipmentId().trim();

		StationGateResponse response = new StationGateResponse();
		response.setOutcome(null);

		PanelRegistrationEntity join = panelRegistrationRepository.findByPanelNumber(serialNumber).orElse(null);
		if (join == null) {
			response.setAllowed(false);
			response.setExpectedNext(null);
			response.setReasonCode("ORPHAN");
			return response;
		}

		WoPpBindingEntity woPpBinding = woPpBindingRepository
				.findById(join.getWorkOrderId())
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "work order not found"));

		String thisOp = EQUIPMENT_TO_OP.get(equipmentId);
		if (thisOp == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown equipmentId: " + equipmentId);
		}

		List<String> pathOps = loadOrderedPathOps(woPpBinding.getProcessPathId());
		List<OperationEventEntity> events = operationEventRepository.findBySerialNumberOrderByIdAsc(serialNumber);

		String expectedNext = deriveNext(pathOps, events);
		response.setExpectedNext(expectedNext);

		if (alreadyComplete(thisOp, events)) {
			response.setAllowed(false);
			response.setReasonCode("ALREADY_COMPLETE");
			return response;
		}

		if (!thisOp.equals(expectedNext)) {
			response.setAllowed(false);
			response.setReasonCode("WRONG_STATION");
			return response;
		}

		response.setAllowed(true);
		response.setReasonCode(null);

		if (appendComplete) {
			if (request.getEquipmentLocalAt() == null) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "equipmentLocalAt is required");
			}
			OperationEventEntity event = new OperationEventEntity();
			event.setSerialNumber(serialNumber);
			event.setOpCode(thisOp);
			event.setEquipmentId(equipmentId);
			event.setOutcome("COMPLETE");
			event.setEquipmentLocalAt(request.getEquipmentLocalAt());
			operationEventRepository.save(event);

			List<OperationEventEntity> afterEvents = new ArrayList<>(events);
			afterEvents.add(event);
			response.setExpectedNext(deriveNext(pathOps, afterEvents));
			response.setOutcome("COMPLETE");
		}

		return response;
	}

	*//**
	 * Process path id → ops in path order (start → end).
	 *//*
	private List<String> loadOrderedPathOps(String processPathId) {
		ProcessPathEntity path = processPathRepository
				.findById(processPathId)
				.orElseThrow(
						() -> new ResponseStatusException(
								HttpStatus.BAD_REQUEST, "process path not found: " + processPathId));
		return parseOrderedOps(path.getContent());
	}

	private List<String> parseOrderedOps(String contentJson) {
		try {
			JsonNode opsNode = objectMapper.readTree(contentJson).get("ops");
			return objectMapper.convertValue(
					opsNode, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
		} catch (Exception ex) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "process path content is invalid", ex);
		}
	}

	private boolean alreadyComplete(String thisOp, List<OperationEventEntity> events) {
		if ("LOADER".equals(thisOp)) {
			return true;
		}
		for (OperationEventEntity event : events) {
			if (thisOp.equals(event.getOpCode()) && "COMPLETE".equals(event.getOutcome())) {
				return true;
			}
		}
		return false;
	}

	private String deriveNext(List<String> pathOps, List<OperationEventEntity> events) {
		Set<String> done = new HashSet<>();
		done.add("LOADER");
		for (OperationEventEntity event : events) {
			if ("COMPLETE".equals(event.getOutcome()) || "PASS".equals(event.getOutcome())) {
				done.add(event.getOpCode());
			}
		}
		for (String op : pathOps) {
			if (!done.contains(op)) {
				return op;
			}
		}
		return null;
	}*/
}
