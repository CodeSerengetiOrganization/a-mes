package com.ames.mes_api.stationgate;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Station skip-ahead gate — scan check (AS-3).
 */
@RestController
@RequestMapping("/api/station-gate-checks")
public class StationGateController {

	private final StationGateService stationGateService;

	public StationGateController(StationGateService stationGateService) {
		this.stationGateService = stationGateService;
	}

	@PostMapping
	public ResponseEntity<StationGateResponse> check(@Valid @RequestBody StationGateRequest request) {
		StationGateResponse body = stationGateService.evaluateGate(request);
		return ResponseEntity.ok(body);
	}
}
