package dev.kintsugi.lab.payment.gateway;

import java.util.UUID;

import org.springframework.stereotype.Component;

import dev.kintsugi.lab.payment.domain.PaymentStatus;

/**
 * Fake gateway that authorizes every payment. It stands in until the week-1 Thursday task replaces it with the
 * HTTP client to {@code gateway-mock}, where fault F1 is injected; until then no fault can reach this path.
 */
@Component
public class AlwaysAuthorizeGateway implements PaymentGateway {

	@Override
	public PaymentStatus authorize(UUID orderId) {
		return PaymentStatus.AUTHORIZED;
	}

}
