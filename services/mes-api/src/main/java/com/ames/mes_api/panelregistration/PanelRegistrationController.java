package com.ames.mes_api.panelregistration;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Panel Registration at PCB Loader (Maya draft — please review).
 */
@RestController
@RequestMapping("/api/panel-registrations")
public class PanelRegistrationController {

	private final PanelRegistrationService panelRegistrationService;

	public PanelRegistrationController(PanelRegistrationService panelRegistrationService) {
		this.panelRegistrationService = panelRegistrationService;
	}

	@PostMapping
	public ResponseEntity<PanelRegistrationResponse> register(@Valid @RequestBody PanelRegistrationRequest request) {
		PanelRegistrationResponse body = panelRegistrationService.register(request);
		return ResponseEntity.status(HttpStatus.CREATED).body(body);
	}
}
