package com.ames.mes_api.depanel;

import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DepanelSplitRepository
		extends JpaRepository<DepanelSplitEntity, Integer>, DepanelSplitOneInsert {

	List<DepanelSplitEntity> findByPanelNumberOrderBySlotAsc(String panelNumber);

	List<DepanelSplitEntity> findByPcbaSerial(String pcbaSerial);

	/**
	 * Lock the split row for this PCBA serial. A PCBA write must not lock the panel.
	 *
	 * @param pcbaSerial scanned PCBA serial
	 * @return matching split rows, locked
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT d FROM DepanelSplitEntity d WHERE d.pcbaSerial = :pcbaSerial")
	List<DepanelSplitEntity> findByPcbaSerialForUpdate(@Param("pcbaSerial") String pcbaSerial);
}
