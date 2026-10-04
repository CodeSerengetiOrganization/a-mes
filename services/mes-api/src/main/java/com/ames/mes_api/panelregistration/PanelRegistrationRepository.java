package com.ames.mes_api.panelregistration;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PanelRegistrationRepository extends JpaRepository<PanelRegistrationEntity, Integer> {

	boolean existsByPanelNumber(String panelNumber);

	Optional<PanelRegistrationEntity> findByPanelNumber(String panelNumber);

	/**
	 * Lock the join row with {@code SELECT … FOR UPDATE} for concurrent COMPLETE (tech note 23).
	 * Stage-1: valid while scanned identity is a panel number — until Depanel / AS-5.
	 *
	 * @param panelNumber panel number (scan identity pre–AS-5)
	 * @return join entity when present
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM PanelRegistrationEntity p WHERE p.panelNumber = :panelNumber")
	Optional<PanelRegistrationEntity> findByPanelNumberForUpdate(@Param("panelNumber") String panelNumber);

	Optional<PanelRegistrationEntity> findByWorkOrderId(String workOrder);
}
