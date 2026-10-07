package dev.kintsugi.lab.inventory.api;

import dev.kintsugi.lab.inventory.domain.Stock;

/** Body of {@code GET /stock/{sku}}. */
public record StockResponse(String sku, int available) {

	static StockResponse from(Stock stock) {
		return new StockResponse(stock.sku(), stock.available());
	}

}
