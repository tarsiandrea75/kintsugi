package dev.kintsugi.lab.payment.application;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.json.JsonMapper;

import dev.kintsugi.lab.payment.domain.Payment;
import dev.kintsugi.lab.payment.domain.PaymentEvent;
import dev.kintsugi.lab.payment.domain.PaymentStatus;
import dev.kintsugi.lab.payment.gateway.GatewayUnavailableException;
import dev.kintsugi.lab.payment.gateway.PaymentGateway;
import dev.kintsugi.lab.payment.persistence.PaymentEventRepository;
import dev.kintsugi.lab.payment.persistence.PaymentRepository;

/**
 * Pays an order: asks the gateway for the outcome, then stores the payment, and records every attempt.
 *
 * <p>Every attempt leaves one {@code payment_events} row. For a stored outcome ({@code AUTHORIZED}, {@code DECLINED},
 * {@code FAILED}) it is inserted in the same transaction as the {@code payments} row, so one never exists without the
 * other. For an unavailable gateway no payment is stored, and the {@code GATEWAY_UNAVAILABLE} event is inserted in a
 * transaction of its own; so is the {@code ERROR} event of an attempt that failed with an unexpected exception, and the
 * {@code COMMIT_UNKNOWN} event of an attempt whose payment transaction failed while committing, when the database may
 * or may not have stored it. The gateway call itself runs outside any transaction. When an event cannot be stored (the database is unavailable, or the
 * insert fails in any other way) it becomes one structured WARN line instead, and the attempt keeps its own outcome.
 * Nothing is dropped silently.
 */
@Service
public class PaymentService {

	private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

	private final PaymentGateway gateway;

	private final PaymentRepository repository;

	private final PaymentEventRepository events;

	private final TransactionTemplate transaction;

	private final Clock clock = Clock.systemUTC();

	public PaymentService(PaymentGateway gateway, PaymentRepository repository, PaymentEventRepository events,
			PlatformTransactionManager transactionManager) {
		this.gateway = gateway;
		this.repository = repository;
		this.events = events;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	/**
	 * Creates and stores the payment of {@code orderId} with the gateway's outcome, together with its event.
	 * @throws GatewayUnavailableException if the gateway gives no usable answer; the attempt is still recorded
	 * @throws RuntimeException any other failure, unchanged, after recording the attempt as {@code ERROR}, or as
 * {@code COMMIT_UNKNOWN} when the commit itself failed
	 */
	public Payment pay(UUID orderId) {
		long start = System.nanoTime();
		PaymentStatus outcome;
		try {
			outcome = gateway.authorize(orderId);
		}
		catch (GatewayUnavailableException ex) {
			PaymentEvent event = attempt(orderId, PaymentEvent.GATEWAY_UNAVAILABLE, unavailableDetail(ex), start);
			log.warn("Payment gateway unavailable: {} url={} exception={} cause={}", logFields(event),
					LogFmt.quote(ex.getUrl() == null ? null : ex.getUrl().toString()),
					NestedExceptionUtils.getMostSpecificCause(ex).getClass().getName(), LogFmt.quote(ex.getMessage()));
			record(event);
			throw ex;
		}
		catch (RuntimeException ex) {
			record(attempt(orderId, PaymentEvent.ERROR, errorDetail(ex), start));
			throw ex;
		}
		return store(orderId, outcome, start);
	}

	public Optional<Payment> find(UUID id) {
		return repository.findById(id);
	}

	/**
	 * Stores the payment and its event in one transaction. If either cannot be stored both roll back, so the request
	 * fails; the event is logged first, and the failure is recorded as {@code ERROR} unless the database itself is
	 * unavailable (another insert would only wait for a connection again). If the commit itself fails, the database may
	 * or may not have stored them: the attempt is recorded as {@code COMMIT_UNKNOWN}, never as {@code ERROR}.
	 */
	private Payment store(UUID orderId, PaymentStatus outcome, long start) {
		Payment payment = Payment.create(orderId, outcome);
		PaymentEvent event = attempt(orderId, outcome.name(), json(Map.of("paymentId", payment.getId().toString())),
				start);
		AtomicBoolean written = new AtomicBoolean();
		try {
			return transaction.execute(status -> {
				Payment saved = repository.save(payment);
				events.insert(event);
				// Spring Data JDBC and JdbcClient execute their SQL immediately (no write-behind persistence context), so
				// once both calls return the rows are in the open transaction: any later failure is the commit itself.
				written.set(true);
				return saved;
			});
		}
		catch (RuntimeException ex) {
			if (written.get()) {
				commitUnknown(event, payment, ex, start);
			}
			else {
				logNotStored(event, ex);
				if (!(ex instanceof CannotCreateTransactionException || ex instanceof DataAccessResourceFailureException)) {
					record(attempt(orderId, PaymentEvent.ERROR, errorDetail(ex), start));
				}
			}
			throw ex;
		}
	}

	/**
	 * Both rows were written but the commit failed, so whether they are stored is unknown: logs one WARN line saying so,
	 * then records {@code COMMIT_UNKNOWN} in a transaction of its own, best effort.
	 */
	private void commitUnknown(PaymentEvent stored, Payment payment, RuntimeException ex, long start) {
		Map<String, String> detail = new LinkedHashMap<>();
		detail.put("paymentId", payment.getId().toString());
		detail.put("exception", ex.getClass().getName());
		detail.put("message", ex.getMessage());
		PaymentEvent event = attempt(stored.orderId(), PaymentEvent.COMMIT_UNKNOWN, json(detail), start);
		log.warn("Payment commit outcome unknown, the payment may or may not be stored: {} payment={} gateway_outcome={}"
				+ " exception={} cause={}", logFields(event), payment.getId(), stored.outcome(), ex.getClass().getName(),
				LogFmt.quote(NestedExceptionUtils.getMostSpecificCause(ex).getMessage()));
		record(event);
	}

	/** Inserts {@code event} in a transaction of its own; when that fails for any reason, logs it instead. */
	private void record(PaymentEvent event) {
		try {
			transaction.executeWithoutResult(status -> events.insert(event));
		}
		catch (RuntimeException ex) {
			logNotStored(event, ex);
		}
	}

	private PaymentEvent attempt(UUID orderId, String outcome, String detail, long startNanos) {
		return new PaymentEvent(UUID.randomUUID(), orderId, clock.instant().truncatedTo(ChronoUnit.MICROS),
				PaymentEvent.PAYMENT_ATTEMPT, outcome, detail,
				Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
	}

	/** The most specific cause's class (for example {@code java.net.http.HttpTimeoutException}), URL and message. */
	private static String unavailableDetail(GatewayUnavailableException ex) {
		Map<String, String> detail = new LinkedHashMap<>();
		detail.put("exception", NestedExceptionUtils.getMostSpecificCause(ex).getClass().getName());
		detail.put("url", ex.getUrl() == null ? null : ex.getUrl().toString());
		detail.put("message", ex.getMessage());
		return json(detail);
	}

	/** The class and message of an unexpected failure. */
	private static String errorDetail(RuntimeException ex) {
		Map<String, String> detail = new LinkedHashMap<>();
		detail.put("exception", ex.getClass().getName());
		detail.put("message", ex.getMessage());
		return json(detail);
	}

	private static String json(Map<String, String> value) {
		return JsonMapper.shared().writeValueAsString(value);
	}

	private static String logFields(PaymentEvent event) {
		return "event=" + event.type() + " order=" + event.orderId() + " outcome=" + event.outcome() + " duration_ms="
				+ event.durationMs();
	}

	private static void logNotStored(PaymentEvent event, RuntimeException ex) {
		log.warn("Payment event not stored: {} detail={} record_failure={} cause={}", logFields(event),
				LogFmt.quote(event.detail()), ex.getClass().getName(),
				LogFmt.quote(NestedExceptionUtils.getMostSpecificCause(ex).getMessage()));
	}

}
