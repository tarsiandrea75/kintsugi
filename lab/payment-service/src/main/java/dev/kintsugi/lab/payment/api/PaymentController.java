package dev.kintsugi.lab.payment.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import dev.kintsugi.lab.payment.application.PaymentService;
import dev.kintsugi.lab.payment.domain.Payment;
import dev.kintsugi.lab.payment.gateway.GatewayUnavailableException;

@RestController
@RequestMapping("/payments")
class PaymentController {

	private final PaymentService service;

	PaymentController(PaymentService service) {
		this.service = service;
	}

	@PostMapping
	ResponseEntity<PaymentResponse> create(@Valid @RequestBody CreatePaymentRequest request) {
		Payment payment = service.pay(request.orderId());
		URI location = ServletUriComponentsBuilder.fromCurrentRequest()
			.path("/{id}")
			.buildAndExpand(payment.getId())
			.toUri();
		return ResponseEntity.created(location).body(PaymentResponse.from(payment));
	}

	@GetMapping("/{id}")
	ResponseEntity<PaymentResponse> get(@PathVariable UUID id) {
		return ResponseEntity.of(service.find(id).map(PaymentResponse::from));
	}

	/**
	 * The gateway gave no usable answer, so the outcome is unknown and no payment was stored: 503, not a decline.
	 * {@link PaymentService} has already logged the attempt as one structured WARN line with the order id. Local to this
	 * controller so unrelated failures keep their own status.
	 */
	@ExceptionHandler(GatewayUnavailableException.class)
	ResponseEntity<ProblemDetail> gatewayUnavailable(GatewayUnavailableException ex) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Payment gateway unavailable"));
	}

}
