package dev.kintsugi.agent.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.agent.incident.IncidentViews.Error;

/**
 * The races of {@code POST /incidents} that HTTP tests cannot time: the running incident ending between the failed
 * insert and the lookup of its id (agent spec, "Another incident is running").
 */
class IncidentControllerTest {

	private static final String VALID = "{\"symptom\": \"Checkout orders stay pending for a long time\", \"sinceMinutes\": 15}";

	private final IncidentRepository incidents = mock(IncidentRepository.class);

	private final IncidentRunner runner = mock(IncidentRunner.class);

	private final IncidentController controller = new IncidentController(incidents, runner, JsonMapper.shared(),
			Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC));

	@Test
	void aConflictCarriesTheRunningId() {
		UUID running = UUID.randomUUID();
		doThrow(new DuplicateKeyException("one running")).when(incidents).insertRunning(any(), any(), any());
		when(incidents.findRunningId()).thenReturn(Optional.of(running));

		ResponseEntity<?> response = controller.open(VALID);

		assertThat(response.getStatusCode().value()).isEqualTo(409);
		assertThat(((Error) response.getBody()).id()).isEqualTo(running);
		verify(runner, never()).start(any());
	}

	@Test
	void whenTheRunningIncidentEndsBeforeItsIdIsReadTheInsertIsTriedOnceMore() {
		doThrow(new DuplicateKeyException("one running")).doNothing()
			.when(incidents)
			.insertRunning(any(), any(), any());
		when(incidents.findRunningId()).thenReturn(Optional.empty());

		ResponseEntity<?> response = controller.open(VALID);

		assertThat(response.getStatusCode().value()).isEqualTo(202);
		verify(incidents, times(2)).insertRunning(any(), any(), any());
		verify(runner).start(any());
	}

	@Test
	void aSecondConflictWithNoRunningIdIsAConflictWithoutId() {
		doThrow(new DuplicateKeyException("one running")).when(incidents).insertRunning(any(), any(), any());
		when(incidents.findRunningId()).thenReturn(Optional.empty());

		ResponseEntity<?> response = controller.open(VALID);

		assertThat(response.getStatusCode().value()).isEqualTo(409);
		Error error = (Error) response.getBody();
		assertThat(error.error()).isEqualTo(IncidentController.ALREADY_RUNNING);
		assertThat(error.id()).isNull();
		verify(incidents, times(2)).insertRunning(any(), any(), any());
		verify(runner, never()).start(any());
	}

	@Test
	void aRunnerFailureFailsTheIncidentItOpened() {
		doNothing().when(incidents).insertRunning(any(), any(), any());
		doThrow(new IllegalStateException("no executor")).when(runner).start(any());

		ResponseEntity<?> response = controller.open(VALID);

		assertThat(response.getStatusCode().value()).isEqualTo(500);
		UUID id = ((Error) response.getBody()).id();
		verify(incidents).fail(eq(id), eq(IncidentController.RUNNER_FAILED), eq(Instant.parse("2026-10-08T12:00:00Z")));
	}

	@Test
	void aStoreFailureWhileFailingTheIncidentStillAnswers500() {
		doNothing().when(incidents).insertRunning(any(), any(), any());
		doThrow(new IllegalStateException("no executor")).when(runner).start(any());
		when(incidents.fail(any(), any(), any())).thenThrow(new IllegalStateException("store down"));

		ResponseEntity<?> response = controller.open(VALID);

		// The row may stay RUNNING; the restart sweep (RestartRecovery) is the backstop that frees the slot.
		assertThat(response.getStatusCode().value()).isEqualTo(500);
		Error error = (Error) response.getBody();
		assertThat(error.error()).isEqualTo(IncidentController.RUNNER_FAILED);
		assertThat(error.id()).isNotNull();
	}

}
