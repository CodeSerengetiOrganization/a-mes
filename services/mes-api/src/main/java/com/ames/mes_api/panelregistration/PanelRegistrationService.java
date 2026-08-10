package com.ames.mes_api.panelregistration;

import java.time.LocalDateTime;
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
	private final WorkOrderRepository workOrderRepository;

	public PanelRegistrationService(
			PanelRegistrationRepository panelRegistrationRepository,
			WorkOrderRepository workOrderRepository) {
		this.panelRegistrationRepository = panelRegistrationRepository;
		this.workOrderRepository = workOrderRepository;
	}

	@Transactional
	public PanelRegistrationResponse register(PanelRegistrationRequest request) {
		String panelNumber = request.getPanelNumber().trim();
		String workOrderId = request.getWorkOrderId().trim();

		if (!workOrderRepository.existsById(workOrderId)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "work order not found: " + workOrderId);
		}
		if (panelRegistrationRepository.existsByPanelNumber(panelNumber)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "panel already registered: " + panelNumber);
		}

		PanelRegistrationEntity entity = new PanelRegistrationEntity();
		entity.setPanelNumber(panelNumber);
		entity.setWorkOrderId(workOrderId);
		entity.setRegisteredAt(LocalDateTime.now());

		try {
			PanelRegistrationEntity saved = panelRegistrationRepository.save(entity);
			return new PanelRegistrationResponse(
					saved.getPanelNumber(),
					saved.getWorkOrderId(),
					saved.getRegisteredAt());
		} catch (DataIntegrityViolationException ex) {
			// Soft checks concurrent raced with another request (or WO disappeared). UNIQUE and FK both
			// surface as DataIntegrityViolationException when the insert fails for two scenarios-same type, different plant meaning:
			// 1. UNIQUE(panel_number) — the concurrent request carries the same penel number;
			// 2. FK(work_order_id) — WO was removed after our existsById check.
			//Solution: Re-read SoT instead of parsing MySQL constraint names (cheap; this path is rare).
			if (panelRegistrationRepository.existsByPanelNumber(panelNumber)) {
				// UNIQUE(panel_number): panel already joined (typical double-submit).
				throw new ResponseStatusException(
						HttpStatus.CONFLICT,
						"panel already registered: " + panelNumber,
						ex);
			}
			if (!workOrderRepository.existsById(workOrderId)) {
				// FK(work_order_id): WO was removed after our existsById check.
				throw new ResponseStatusException(
						HttpStatus.NOT_FOUND,
						"work order not found: " + workOrderId,
						ex);
			}
			// Unexpected integrity failure — still avoid raw 500; treat as conflict.
			throw new ResponseStatusException(
					HttpStatus.CONFLICT,
					"panel registration conflict: " + panelNumber,
					ex);
		}
	}
}
