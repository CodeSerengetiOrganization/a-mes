package com.ames.mes_api.depanel;

import com.ames.mes_api.stationgate.StationGateRequest;
import com.ames.mes_api.stationgate.StationGateResponse;
import com.ames.mes_api.stationgate.StationGateService;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write transaction for one depanel split.
 *
 * <p>The first database read in this transaction is the {@code panel_registration} row lock inside
 * {@link StationGateService#complete}. A concurrent second split waits on that lock. The station
 * gate then returns {@code ALREADY_COMPLETE}. This class does not map a unique-key error.
 */
@Service
public class DepanelSplitWriter {

	private final StationGateService stationGateService;
	private final DepanelSplitRepository depanelSplitRepository;

	public DepanelSplitWriter(
			StationGateService stationGateService, DepanelSplitRepository depanelSplitRepository) {
		this.stationGateService = stationGateService;
		this.depanelSplitRepository = depanelSplitRepository;
	}

	/**
	 * Store the Depanel split COMPLETE and the four split rows, or return the locked gate refuse.
	 *
	 * @param panelNumber trimmed panel number
	 * @param gateRequest gate request for {@code complete}
	 * @param orderedRows four rows in slot order {@code A1}, {@code A2}, {@code B1}, {@code B2}
	 * @param equipmentLocalAt depaneler clock
	 * @return stored body, or a refuse when the locked gate does not allow the write
	 */
	@Transactional
	public DepanelSplitResponse store(
			String panelNumber,
			StationGateRequest gateRequest,
			List<DepanelSplitInsertRow> orderedRows,
			LocalDateTime equipmentLocalAt) {
		StationGateResponse completed = stationGateService.complete(gateRequest);
		if (!completed.isAllowed()) {
			return DepanelSplitService.refuse(panelNumber, completed.getReasonCode());
		}
		depanelSplitRepository.insertSplit(panelNumber, orderedRows, equipmentLocalAt);
		return DepanelSplitService.stored(panelNumber);
	}
}
