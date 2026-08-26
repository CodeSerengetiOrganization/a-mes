package com.ames.mes_api.stationgate;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationEventRepository extends JpaRepository<OperationEventEntity, Integer> {

	List<OperationEventEntity> findBySerialNumberOrderByIdAsc(String serialNumber);

	/** Progress events only (PASS / COMPLETE) — for deriveNext completedOps set (tech note 17). */
	List<OperationEventEntity> findBySerialNumberAndOutcomeInOrderByIdAsc(
			String serialNumber, Collection<String> outcomes);
}
