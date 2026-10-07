package dev.kintsugi.lab.inventory.domain;

/** Available units of one SKU. */
public record Stock(String sku, int available) {

}
