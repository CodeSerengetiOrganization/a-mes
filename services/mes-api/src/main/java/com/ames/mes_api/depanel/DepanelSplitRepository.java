package com.ames.mes_api.depanel;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepanelSplitRepository
		extends JpaRepository<DepanelSplitEntity, Integer>, DepanelSplitOneInsert {

	List<DepanelSplitEntity> findByPanelNumberOrderBySlotAsc(String panelNumber);
}
