package com.ames.mes_api.depanel;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Depanel split at PCB Depaneler. HTTP only. Plant rules stay in {@link DepanelSplitService}.
 */
@RestController
@RequestMapping("/api/depanel-splits")
public class DepanelSplitController {

	private final DepanelSplitService depanelSplitService;

	public DepanelSplitController(DepanelSplitService depanelSplitService) {
		this.depanelSplitService = depanelSplitService;
	}

	@PostMapping
	public ResponseEntity<DepanelSplitResponse> split(@Valid @RequestBody DepanelSplitRequest request) {
		DepanelSplitResponse body = depanelSplitService.split(request);
		if (body.isStored()) {
			return ResponseEntity.status(HttpStatus.CREATED).body(body);
		}
		return ResponseEntity.ok(body);
	}
}
