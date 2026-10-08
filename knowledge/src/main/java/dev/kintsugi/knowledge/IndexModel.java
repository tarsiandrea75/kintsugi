package dev.kintsugi.knowledge;

/** The model an index is built with: the single row of {@code index_metadata}. Any difference means a full re-index. */
public record IndexModel(String name, String digest, int dimensions, String documentPrefix, String queryPrefix) {
}
