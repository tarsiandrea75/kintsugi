package dev.kintsugi.lab.fault.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import dev.kintsugi.lab.fault.control.FakeLabControl;
import dev.kintsugi.lab.fault.domain.InjectionEventType;
import dev.kintsugi.lab.fault.persistence.InjectionEventRepository;
import dev.kintsugi.lab.fault.persistence.InjectionRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * After a failed activation, recording the outcome can itself fail with any {@link RuntimeException}, not only a
 * database one (for example a serialization error of the event detail). The caller must still get
 * {@link ActivationFailedException}, carrying the activation failure, never the recording error.
 */
class CompensationRecordingFailureTest {

	private final InjectionRepository injections = mock(InjectionRepository.class);

	private final InjectionEventRepository events = mock(InjectionEventRepository.class);

	private final TransactionTemplate transaction = mock(TransactionTemplate.class);

	private final FakeLabControl lab = new FakeLabControl();

	@BeforeEach
	@SuppressWarnings("unchecked")
	void runTransactionsInline() {
		doAnswer(call -> {
			call.getArgument(0, Consumer.class).accept(mock(TransactionStatus.class));
			return null;
		}).when(transaction).executeWithoutResult(any());
		lab.failActivate(true);
	}

	@Test
	void aNonDatabaseErrorWhileRecordingStillAnswersActivationFailed() {
		doThrow(new IllegalStateException("cannot record")).when(events)
			.append(any(), eq(InjectionEventType.ACTIVATION_FAILED), any(), any());
		FaultInjectionService service = new FaultInjectionService(injections, events, lab, transaction,
				JsonMapper.builder().build());

		assertThatExceptionOfType(ActivationFailedException.class).isThrownBy(() -> service.activate("F3"))
			.withCauseInstanceOf(dev.kintsugi.lab.fault.control.LabControlException.class);
		assertThat(lab.calls()).containsExactly("activate F3", "restore F3");
	}

	@Test
	void aSerializationErrorOfTheEventDetailStillAnswersActivationFailed() {
		JsonMapper json = mock(JsonMapper.class);
		// The parameters serialize; the failure detail of the compensation does not.
		when(json.writeValueAsString(anyMap())).thenReturn("{}")
			.thenThrow(new IllegalArgumentException("detail not serializable"));
		FaultInjectionService service = new FaultInjectionService(injections, events, lab, transaction, json);

		assertThatExceptionOfType(ActivationFailedException.class).isThrownBy(() -> service.activate("F4"))
			.withCauseInstanceOf(dev.kintsugi.lab.fault.control.LabControlException.class);
	}

}
