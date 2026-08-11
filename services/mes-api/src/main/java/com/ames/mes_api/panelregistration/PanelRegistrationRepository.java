package com.ames.mes_api.panelregistration;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PanelRegistrationRepository extends JpaRepository<PanelRegistrationEntity, Integer> {

	boolean existsByPanelNumber(String panelNumber);
}
