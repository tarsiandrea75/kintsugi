package dev.kintsugi.lab.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.kintsugi.lab.payment.domain.PaymentStatus;

class AlwaysAuthorizeGatewayTest {

	@Test
	void everyOrderIsAuthorized() {
		PaymentGateway gateway = new AlwaysAuthorizeGateway();

		assertThat(gateway.authorize(UUID.randomUUID())).isEqualTo(PaymentStatus.AUTHORIZED);
	}

}
