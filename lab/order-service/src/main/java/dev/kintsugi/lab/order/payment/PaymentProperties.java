package dev.kintsugi.lab.order.payment;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where payment-service lives and how long order-service waits for it. The fault injector changes these
 * ({@code kintsugi.payment.*}), so slow or failing payments show up as orders stuck in {@code PAYMENT_PENDING}.
 *
 * @param baseUrl payment-service root URL
 * @param connectTimeout longest wait to open a connection
 * @param readTimeout longest wait for the response once the request is sent
 */
@ConfigurationProperties("kintsugi.payment")
public record PaymentProperties(@DefaultValue("http://localhost:8081") URI baseUrl,
		@DefaultValue("1s") Duration connectTimeout, @DefaultValue("3s") Duration readTimeout) {
}
