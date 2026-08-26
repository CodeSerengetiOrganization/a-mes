package com.ames.mes_api.panelregistration;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PanelRegistrationRepository extends JpaRepository<PanelRegistrationEntity, Integer> {

	boolean existsByPanelNumber(String panelNumber);

	Optional<PanelRegistrationEntity> findByPanelNumber(String panelNumber);

	Optional<PanelRegistrationEntity> findByWorkOrderId(String workOrder);
}
