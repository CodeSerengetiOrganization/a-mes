package com.ames.mes_api.panelregistration;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Panel Registration join — panel under a work order (Maya draft — please review).
 */
@Service
public class PanelRegistrationService {

	private final PanelRegistrationRepository panelRegistrationRepository;
	private final WoPpBindingRepository woPpBindingRepository;

	public PanelRegistrationService(
			PanelRegistrationRepository panelRegistrationRepository,
			WoPpBindingRepository woPpBindingRepository) {
		this.panelRegistrationRepository = panelRegistrationRepository;
		this.woPpBindingRepository = woPpBindingRepository;
	}

	@Transactional
	public PanelRegistrationResponse register(PanelRegistrationRequest request) {
		String panelNumber = request.getPanelNumber().trim();
		String workOrderId = request.getWorkOrderId().trim();

		if (!woPpBindingRepository.existsById(workOrderId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "work order not found: " + workOrderId);
		}
		if (panelRegistrationRepository.existsByPanelNumber(panelNumber)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "panel already registered: " + panelNumber);
		}

		PanelRegistrationEntity entity = new PanelRegistrationEntity();
		entity.setPanelNumber(panelNumber);
		entity.setWorkOrderId(workOrderId);
		// registered_at: DB DEFAULT CURRENT_TIMESTAMP (not set in Java)

		try {
			PanelRegistrationEntity saved = panelRegistrationRepository.save(entity);
			return new PanelRegistrationResponse(
					saved.getPanelNumber(),
					saved.getWorkOrderId(),
					saved.getRegisteredAt());
		} catch (DataIntegrityViolationException ex) {
			// Soft checks raced (UNIQUE panel or FK WO gone). Do not re-query in this TX:
			// after a failed INSERT the JPA session is often unusable → 500. Rare path → 409.
			throw new ResponseStatusException(
					HttpStatus.CONFLICT,
					"panel registration conflict: " + panelNumber,
					ex);
		}
	}
}
