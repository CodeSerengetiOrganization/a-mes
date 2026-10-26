package com.ames.mes_api.depanel;

/**
 * One row for the four-row depanel split insert. The database sets {@code recorded_at}.
 */
public record DepanelSplitInsertRow(String slot, String pcbaSerial) {
}
