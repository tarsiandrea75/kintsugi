package dev.kintsugi.lab.order.inventory;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where inventory-service lives and how long order-service waits for it. The fault injector changes these
 * ({@code kintsugi.inventory.*}): a short read timeout against a slow inventory is fault F8.
 *
 * @param baseUrl inventory-service root URL
 * @param connectTimeout longest wait to open a connection
 * @param readTimeout longest wait for the response once the request is sent
 */
@ConfigurationProperties("kintsugi.inventory")
public record InventoryProperties(@DefaultValue("http://localhost:8083") URI baseUrl,
		@DefaultValue("1s") Duration connectTimeout, @DefaultValue("3s") Duration readTimeout) {
}
