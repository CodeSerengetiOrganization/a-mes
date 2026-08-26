package com.ames.mes_api.stationgate;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Station skip-ahead gate — scan check + through COMPLETE (AS-3 / AS3-02).
 * Maya draft — please review.
 */
@RestController
@RequestMapping("/api")
public class StationGateController {

	private final StationGateService stationGateService;

	public StationGateController(StationGateService stationGateService) {
		this.stationGateService = stationGateService;
	}

	/** Scan gate — allow / deny only (no append). */
	@PostMapping("/station-gate-checks")
	public ResponseEntity<StationGateResponse> check(@Valid @RequestBody StationGateRequest request) {
		StationGateResponse body = stationGateService.evaluateGate(request);
		return ResponseEntity.ok(body);
	}

	/** Through-station COMPLETE — append evidence when allowed. */
	@PostMapping("/station-completes")
	public ResponseEntity<StationGateResponse> complete(@Valid @RequestBody StationGateRequest request) {
		StationGateResponse body = stationGateService.complete(request);
		return ResponseEntity.ok(body);
	}
}
