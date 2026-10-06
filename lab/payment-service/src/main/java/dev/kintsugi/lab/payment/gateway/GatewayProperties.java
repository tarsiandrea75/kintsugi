package dev.kintsugi.lab.payment.gateway;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where gateway-mock lives and how long payment-service waits for it ({@code kintsugi.gateway.*}). The read timeout
 * is longer than order-service's 3s wait for payment-service, so with a slow gateway order-service gives up first
 * and the order stays {@code PAYMENT_PENDING} (fault F1).
 *
 * @param baseUrl gateway-mock root URL
 * @param connectTimeout longest wait to open a connection
 * @param readTimeout longest wait for the response once the request is sent
 */
@ConfigurationProperties("kintsugi.gateway")
public record GatewayProperties(@DefaultValue("http://localhost:8082") URI baseUrl,
		@DefaultValue("1s") Duration connectTimeout, @DefaultValue("15s") Duration readTimeout) {
}
