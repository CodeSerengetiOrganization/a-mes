package com.ames.mes_api.stationgate;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Station skip-ahead gate — scan check + through COMPLETE. */
@RestController
@RequestMapping("/api")
public class StationGateController {

	private final StationGateService stationGateService;

	public StationGateController(StationGateService stationGateService) {
		this.stationGateService = stationGateService;
	}

	/** Scan gate — allow / deny only (no append). {@code equipmentLocalAt} not required. */
	@PostMapping("/station-gate-checks")
	public ResponseEntity<StationGateResponse> check(@Valid @RequestBody StationGateRequest request) {
		StationGateResponse body = stationGateService.evaluateGate(request);
		return ResponseEntity.ok(body);
	}

	/**
	 * Through-station COMPLETE — append evidence when allowed.
	 * {@link StationGateRequest.OnComplete} requires {@code equipmentLocalAt}.
	 */
	@PostMapping("/station-completes")
	public ResponseEntity<StationGateResponse> complete(
			@Validated(StationGateRequest.OnComplete.class) @RequestBody StationGateRequest request) {
		StationGateResponse body = stationGateService.complete(request);
		return ResponseEntity.ok(body);
	}
}
