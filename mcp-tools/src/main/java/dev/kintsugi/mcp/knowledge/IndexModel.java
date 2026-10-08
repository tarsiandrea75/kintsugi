package dev.kintsugi.mcp.knowledge;

/**
 * The embedding model an index was built with, as the indexer recorded it in the single row of {@code index_metadata}
 * (knowledge-base spec).
 */
record IndexModel(String name, String digest, int dimensions, String documentPrefix, String queryPrefix) {
}
