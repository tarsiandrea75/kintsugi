package dev.kintsugi.lab.payment.application;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import dev.kintsugi.lab.payment.domain.Payment;
import dev.kintsugi.lab.payment.domain.PaymentStatus;
import dev.kintsugi.lab.payment.gateway.PaymentGateway;
import dev.kintsugi.lab.payment.persistence.PaymentRepository;

/** Pays an order: asks the gateway for the outcome, then stores the payment. */
@Service
public class PaymentService {

	private final PaymentGateway gateway;

	private final PaymentRepository repository;

	public PaymentService(PaymentGateway gateway, PaymentRepository repository) {
		this.gateway = gateway;
		this.repository = repository;
	}

	/** Creates and stores the payment of {@code orderId} with the gateway's outcome. */
	public Payment pay(UUID orderId) {
		PaymentStatus outcome = gateway.authorize(orderId);
		return repository.save(Payment.create(orderId, outcome));
	}

	public Optional<Payment> find(UUID id) {
		return repository.findById(id);
	}

}
