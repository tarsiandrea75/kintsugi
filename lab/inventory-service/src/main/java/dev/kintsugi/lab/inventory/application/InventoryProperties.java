package dev.kintsugi.lab.inventory.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Inventory settings ({@code kintsugi.inventory.*}).
 *
 * @param reservationDelay <strong>lab-only fault hook</strong>: when above zero, every reservation waits this long
 * on the database inside its transaction, holding a pooled connection. Fault F4 sets it, with a small pool, to
 * exhaust the pool; a real service would not have it.
 */
@ConfigurationProperties("kintsugi.inventory")
public record InventoryProperties(@DefaultValue("0s") Duration reservationDelay) {
}
