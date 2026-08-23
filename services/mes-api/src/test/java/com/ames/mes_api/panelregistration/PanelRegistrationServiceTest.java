package com.ames.mes_api.panelregistration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for {@link PanelRegistrationService} (Maya draft — please review).
 *
 * <p>Why unit (not {@code @SpringBootTest})?
 * We only check service rules (WO must exist, panel not already joined, trim, save).
 * Repositories are mocked — no MySQL / Flyway. HTTP + DB proof belongs in a later integration test.
 *
 * <p>Mockito: {@code @Mock} fakes the repos; {@code @InjectMocks} builds the service with those fakes.
 */
@ExtendWith(MockitoExtension.class)
class PanelRegistrationServiceTest {

	/** Fake panel join table access. */
	@Mock
	private PanelRegistrationRepository panelRegistrationRepository;

	/** Fake WO↔path table access (exists check only in these tests). */
	@Mock
	private WoPpBindingRepository woPpBindingRepository;

	/** Real service under test, wired with the mocks above. */
	@InjectMocks
	private PanelRegistrationService panelRegistrationService;

	private PanelRegistrationRequest request;

	@BeforeEach
	void setUp() {
		// Default happy-path payload; individual tests override when needed.
		request = new PanelRegistrationRequest();
		request.setPanelNumber("PANEL-DEMO-004");
		request.setWorkOrderId("WO-DEMO-001");
	}

	/**
	 * Happy path: WO exists, panel is new → persist join and return response fields
	 * (panelNumber, workOrderId, registeredAt — same shape as the API 201 body).
	 */
	@Test
	void register_whenWorkOrderExistsAndPanelNew_savesAndReturnsResponse() {
		when(woPpBindingRepository.existsById("WO-DEMO-001")).thenReturn(true);
		when(panelRegistrationRepository.existsByPanelNumber("PANEL-DEMO-004")).thenReturn(false);
		AtomicReference<LocalDateTime> registeredAtSentToSave = new AtomicReference<>();
		// Simulate DB: IDENTITY id + DEFAULT CURRENT_TIMESTAMP for registered_at.
		when(panelRegistrationRepository.save(any(PanelRegistrationEntity.class))).thenAnswer(invocation -> {
			PanelRegistrationEntity e = invocation.getArgument(0);
			registeredAtSentToSave.set(e.getRegisteredAt());
			e.setId(42);
			e.setRegisteredAt(LocalDateTime.of(2026, 8, 10, 12, 0, 0));
			return e;
		});

		PanelRegistrationResponse response = panelRegistrationService.register(request);

		assertEquals("PANEL-DEMO-004", response.getPanelNumber());
		assertEquals("WO-DEMO-001", response.getWorkOrderId());
		assertNotNull(response.getRegisteredAt());

		ArgumentCaptor<PanelRegistrationEntity> captor = ArgumentCaptor.forClass(PanelRegistrationEntity.class);
		verify(panelRegistrationRepository).save(captor.capture());
		assertEquals("PANEL-DEMO-004", captor.getValue().getPanelNumber());
		assertEquals("WO-DEMO-001", captor.getValue().getWorkOrderId());
		// Java must not stamp registered_at before save (DB default owns it).
		assertEquals(null, registeredAtSentToSave.get());
	}

	/**
	 * Scanner / UI may send spaces; service should trim before lookup and save
	 * so " PANEL-X " does not bypass UNIQUE(panel_number) or miss the WO row.
	 */
	@Test
	void register_trimsPanelAndWorkOrderIds() {
		request.setPanelNumber("  PANEL-DEMO-004  ");
		request.setWorkOrderId("  WO-DEMO-001  ");
		when(woPpBindingRepository.existsById("WO-DEMO-001")).thenReturn(true);
		when(panelRegistrationRepository.existsByPanelNumber("PANEL-DEMO-004")).thenReturn(false);
		when(panelRegistrationRepository.save(any(PanelRegistrationEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

		PanelRegistrationResponse response = panelRegistrationService.register(request);

		assertEquals("PANEL-DEMO-004", response.getPanelNumber());
		assertEquals("WO-DEMO-001", response.getWorkOrderId());
		// Lookups must use trimmed values.
		verify(woPpBindingRepository).existsById("WO-DEMO-001");
		verify(panelRegistrationRepository).existsByPanelNumber("PANEL-DEMO-004");
	}

	/**
	 * Cannot join a panel to a WO that has no process-path binding (AS2-01 missing)
	 * → 404 and never call save.
	 */
	@Test
	void register_whenWorkOrderMissing_throwsNotFound() {
		when(woPpBindingRepository.existsById("WO-DEMO-001")).thenReturn(false);

		ResponseStatusException ex = assertThrows(
				ResponseStatusException.class,
				() -> panelRegistrationService.register(request));

		assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
		assertEquals("work order not found: WO-DEMO-001", ex.getReason());
		verify(panelRegistrationRepository, never()).save(any());
	}

	/**
	 * Plant rule: one panel → one WO. Soft check before insert → 409 Conflict.
	 * (DB UNIQUE from V4 is the hard guarantee; this test covers the service branch.)
	 */
	@Test
	void register_whenPanelAlreadyRegistered_throwsConflict() {
		when(woPpBindingRepository.existsById("WO-DEMO-001")).thenReturn(true);
		when(panelRegistrationRepository.existsByPanelNumber("PANEL-DEMO-004")).thenReturn(true);

		ResponseStatusException ex = assertThrows(
				ResponseStatusException.class,
				() -> panelRegistrationService.register(request));

		assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
		verify(panelRegistrationRepository, never()).save(any());
	}

	/**
	 * Concurrent race after soft checks: save hits UNIQUE or FK → 409 (not 500).
	 * No re-query after failed INSERT (same-TX session may be unusable).
	 */
	@Test
	void register_whenSaveHitsIntegrityConstraint_throwsConflict() {
		when(woPpBindingRepository.existsById("WO-DEMO-001")).thenReturn(true);
		when(panelRegistrationRepository.existsByPanelNumber("PANEL-DEMO-004")).thenReturn(false);
		when(panelRegistrationRepository.save(any(PanelRegistrationEntity.class)))
				.thenThrow(new DataIntegrityViolationException("integrity"));

		ResponseStatusException ex = assertThrows(
				ResponseStatusException.class,
				() -> panelRegistrationService.register(request));

		assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
		assertEquals("panel registration conflict: PANEL-DEMO-004", ex.getReason());
	}
}