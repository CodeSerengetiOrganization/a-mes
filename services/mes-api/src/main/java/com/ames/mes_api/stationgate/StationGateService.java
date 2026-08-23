package com.ames.mes_api.stationgate;

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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class StationGateService {
	private static final Map<String, String> EQUIPMENT_TO_OP = Map.of(
			"AEL-01-LOADER", "LOADER",
			"AEL-01-COAT", "COAT",
			"AEL-01-DEPANEL", "DEPANEL",
			"AEL-01-CURE", "CURE",
			"AEL-01-ASM", "ASM",
			"AEL-01-PACK", "PACK");

	private final PanelRegistrationRepository panelRegistrationRepository;
	private final ProcessPathRepository processPathRepository;
	private final WoPpBindingRepository woPpBindingRepository;
	private final OperationEventRepository operationEventRepository;
	private final ObjectMapper objectMapper;

	public StationGateService(
            PanelRegistrationRepository panelRegistrationRepository,
            ProcessPathRepository processPathRepository,
            WoPpBindingRepository woPpBindingRepository, OperationEventRepository operationEventRepository,
            ObjectMapper objectMapper) {
		this.panelRegistrationRepository = panelRegistrationRepository;
		this.processPathRepository = processPathRepository;
		this.woPpBindingRepository = woPpBindingRepository;
        this.operationEventRepository = operationEventRepository;
        this.objectMapper = objectMapper;
	}

	/*
	 * check the serial number if it should be worked on current machine station
	 * Steps:
	 * Step 1: check ORPHAN;
	 * Step 2: check if the process of current equipmentId is in the process path;
	 */
	@Transactional
	public StationGateResponse evaluateGate(StationGateRequest request) {
		// Step 1: ORPHAN — no Panel Registration join (or join without WO)
		Optional<PanelRegistrationEntity> panelRegEntity =
				panelRegistrationRepository.findByPanelNumber(request.getSerialNumber());
		if (panelRegEntity.isEmpty() || panelRegEntity.get().getWorkOrderId() == null) {
			return deny("ORPHAN");
		}

		//step2 : get the thisOp from the hard code map EQUIPMENT_TO_OP
		String thisOp = EQUIPMENT_TO_OP.get(request.getEquipmentId());
		if(thisOp == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown EQUIPMENT_ID: "+request.getEquipmentId());
		}

		// Step3 : current Operation vs expectedNext Operation.
		// current Operation: get it from a Map via equipmentId (step2)
		// expectedNext Operation:serialNumber->workOrderId->processPathId->processPath, iterate the process path and check the evetSet to locate the first missing one;
		// Step3.1: get the process path id from wo_pp_binding via
		String workOrderId = panelRegEntity.get().getWorkOrderId();
		System.out.println("workOrderId: " + workOrderId);
		Optional<WoPpBindingEntity> woPpBindingEntity = woPpBindingRepository.findById(workOrderId);
		String processPathId = woPpBindingEntity.map(WoPpBindingEntity::getProcessPathId).orElse(null);
		System.out.println("processPathId: " + processPathId);
		//step3.2 : get the process content and put into List
		Optional<ProcessPathEntity> processPathOpt = processPathRepository.findById(processPathId);
		String processPathContent = processPathOpt.get().getContent();
		System.out.println("processPathContent: " + processPathContent);
		//pick up the "ops" values and convert the string into ArrayList
		List<String> pathOps = parseOrderedOps(processPathContent);
		System.out.println("pathOps: " + pathOps);

		//step3.3 :get the operation list and convert operationEventList to a HashSet
		List<OperationEventEntity> operationEventList = operationEventRepository.findBySerialNumberAndOutcomeInOrderByIdAsc(request.getSerialNumber(), List.of("PASS", "COMPLETE"));
		Set<String> operationEventSet = operationEventList.stream()
				.map(OperationEventEntity::getOpCode)
				.collect(Collectors.toSet());

		String expectedNext = deriveNext(pathOps,operationEventSet);
		//step3.4: check the thisOp and expectedNext
		if(thisOp.equals(expectedNext)) {
			//todo: need to check how we use the reason code.
			return allow(null);
		}
		return deny(expectedNext);

	}

	private String deriveNext(List<String> pathOps, Set<String> operationEventSet) {
		//iterate through the pathOps and check if the op is in the operationEventSet
		for(String op : pathOps) {
			if(!operationEventSet.contains(op)) {
				return op;
			}
		}
		return null;
	}

	/*
	 * Looks up Panel Registration for the serial. If there is no join row, or the join
	 * has no work order, the serial is an orphan and the station gate must deny (AS-3).
	 */
	private boolean isOrphan(String serialNumber) {
		Optional<PanelRegistrationEntity> panelRegistration =
				panelRegistrationRepository.findByPanelNumber(serialNumber);
		return panelRegistration.isEmpty() || panelRegistration.get().getWorkOrderId() == null;
	}

	/** True when this op appears on the process path (membership only — not expected-next). */
	private boolean isOpOnProcessPath(String workOrder, List<String> pathOps) {
		return false;
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

	private StationGateResponse deny(String reasonCode) {
		StationGateResponse response = new StationGateResponse();
		response.setAllowed(false);
		response.setReasonCode(reasonCode);
		return response;
	}

	private StationGateResponse allow(String expectedNext) {
		StationGateResponse response = new StationGateResponse();
		response.setAllowed(true);
		response.setExpectedNext(expectedNext);
		return response;
	}
}
