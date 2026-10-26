package com.ames.mes_api.depanel;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Native insert so four rows share one database {@code recorded_at}.
 */
public class DepanelSplitOneInsertImpl implements DepanelSplitOneInsert {

	private final EntityManager entityManager;

	public DepanelSplitOneInsertImpl(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	@Override
	public void insertSplit(String panelNumber, List<DepanelSplitInsertRow> rows, LocalDateTime equipmentLocalAt) {
		if (rows == null || rows.isEmpty()) {
			throw new IllegalArgumentException("depanel split insert requires rows");
		}
		StringBuilder sql = new StringBuilder(
				"INSERT INTO depanel_split (panel_number, slot, pcba_serial, equipment_local_at) VALUES ");
		for (int i = 0; i < rows.size(); i++) {
			if (i > 0) {
				sql.append(", ");
			}
			int base = i * 4;
			sql.append("(?").append(base + 1)
					.append(", ?").append(base + 2)
					.append(", ?").append(base + 3)
					.append(", ?").append(base + 4)
					.append(')');
		}
		Query query = entityManager.createNativeQuery(sql.toString());
		int index = 1;
		for (DepanelSplitInsertRow row : rows) {
			query.setParameter(index++, panelNumber);
			query.setParameter(index++, row.slot());
			query.setParameter(index++, row.pcbaSerial());
			query.setParameter(index++, equipmentLocalAt);
		}
		query.executeUpdate();
	}
}
