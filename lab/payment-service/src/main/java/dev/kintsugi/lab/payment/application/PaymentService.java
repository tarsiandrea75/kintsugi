package dev.kintsugi.lab.payment.application;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

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
 * transaction of its own. The gateway call itself runs outside any transaction. When the database is unavailable the
 * event cannot be stored, so it becomes one structured WARN line instead. Nothing is dropped silently.
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
	 */
	public Payment pay(UUID orderId) {
		long start = System.nanoTime();
		PaymentStatus outcome;
		try {
			outcome = gateway.authorize(orderId);
		}
		catch (GatewayUnavailableException ex) {
			record(attempt(orderId, PaymentEvent.GATEWAY_UNAVAILABLE, unavailableDetail(ex), start));
			throw ex;
		}
		Payment payment = Payment.create(orderId, outcome);
		PaymentEvent event = attempt(orderId, outcome.name(), json(Map.of("paymentId", payment.getId().toString())),
				start);
		try {
			return transaction.execute(status -> {
				Payment saved = repository.save(payment);
				events.insert(event);
				return saved;
			});
		}
		catch (CannotCreateTransactionException | DataAccessResourceFailureException ex) {
			logNotStored(event, ex);
			throw ex;
		}
	}

	public Optional<Payment> find(UUID id) {
		return repository.findById(id);
	}

	/** Inserts {@code event} in a transaction of its own; when the database is unavailable, logs it instead. */
	private void record(PaymentEvent event) {
		try {
			transaction.executeWithoutResult(status -> events.insert(event));
		}
		catch (CannotCreateTransactionException | DataAccessResourceFailureException ex) {
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

	private static String json(Map<String, String> value) {
		return JsonMapper.shared().writeValueAsString(value);
	}

	private static void logNotStored(PaymentEvent event, Exception ex) {
		log.warn("Payment event not stored: event={} order={} outcome={} duration_ms={} detail={} cause={}",
				event.type(), event.orderId(), event.outcome(), event.durationMs(), LogFmt.quote(event.detail()),
				LogFmt.quote(NestedExceptionUtils.getMostSpecificCause(ex).getMessage()));
	}

}
