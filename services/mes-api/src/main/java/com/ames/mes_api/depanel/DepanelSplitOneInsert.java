package com.ames.mes_api.depanel;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One INSERT for the four split rows so they share one {@code recorded_at}.
 */
public interface DepanelSplitOneInsert {

	/**
	 * Insert the split rows in one statement. {@code recorded_at} stays the database default.
	 *
	 * @param panelNumber parent panel
	 * @param rows slot and PCBA serial pairs
	 * @param equipmentLocalAt depaneler clock, stored on every row
	 */
	void insertSplit(String panelNumber, List<DepanelSplitInsertRow> rows, LocalDateTime equipmentLocalAt);
}
