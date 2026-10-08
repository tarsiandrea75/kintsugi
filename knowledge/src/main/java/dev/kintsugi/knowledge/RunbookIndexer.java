package dev.kintsugi.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.stereotype.Component;

/**
 * One indexing run (knowledge-base spec). In order, and nothing is written before the last step:
 * <ol>
 * <li>read the allow-listed runbooks and run the leakage gate on all of them;</li>
 * <li>check the configured dimensions against the {@code vector} column, and read the model's digest from Ollama;</li>
 * <li>pick the files to embed: all of them when the model, digest, dimensions or prefixes differ from the recorded
 * ones, otherwise only those whose content hash differs from the one recorded in {@code indexed_files};</li>
 * <li>embed them, one call per file, checking every embedding's length;</li>
 * <li>write every change, the files' content hashes and the metadata row in one transaction.</li>
 * </ol>
 */
@Component
public class RunbookIndexer {

	private static final Logger log = LoggerFactory.getLogger(RunbookIndexer.class);

	private final KnowledgeProperties properties;

	private final OllamaModelCatalog catalog;

	private final EmbeddingModel embeddings;

	private final KnowledgeStore store;

	private final RunbookChunker chunker = new RunbookChunker();

	public RunbookIndexer(KnowledgeProperties properties, OllamaModelCatalog catalog, EmbeddingModel embeddings,
			KnowledgeStore store) {
		this.properties = properties;
		this.catalog = catalog;
		this.embeddings = embeddings;
		this.store = store;
	}

	public IndexReport index() {
		KnowledgeProperties.Model model = this.properties.model();
		List<Runbook> runbooks = RunbookFiles.read(this.properties.runbooksDir());
		LeakageGate.check(runbooks);

		int column = this.store.columnDimensions();
		if (column != model.dimensions()) {
			throw new IndexingException("Configured dimensions " + model.dimensions()
					+ " differ from the vector column's " + column + "; a different dimension needs a schema migration");
		}
		IndexModel wanted = new IndexModel(model.name(), this.catalog.digest(model.name()), model.dimensions(),
				model.documentPrefix(), model.queryPrefix());
		Optional<IndexModel> indexed = this.store.indexModel();
		boolean everything = indexed.map(current -> !current.equals(wanted)).orElse(true);

		Map<String, String> hashes = this.store.contentHashes();
		List<Runbook> changed = runbooks.stream()
			.filter(runbook -> everything || !runbook.contentHash().equals(hashes.get(runbook.source())))
			.toList();
		Set<String> removed = new TreeSet<>(hashes.keySet());
		runbooks.forEach(runbook -> removed.remove(runbook.source()));
		List<String> skipped = runbooks.stream()
			.filter(runbook -> !changed.contains(runbook))
			.map(Runbook::source)
			.toList();
		if (!everything && changed.isEmpty() && removed.isEmpty()) {
			IndexReport unchanged = new IndexReport(List.of(), skipped, List.of(), false, 0);
			log.info("Index unchanged, every runbook skipped, with {} ({}): {}", wanted.name(), wanted.digest(),
					unchanged);
			return unchanged;
		}

		List<KnowledgeStore.EmbeddedChunk> chunks = new ArrayList<>();
		for (Runbook runbook : changed) {
			chunks.addAll(embed(runbook, model));
		}
		Set<String> replaced = new TreeSet<>(removed);
		changed.forEach(runbook -> replaced.add(runbook.source()));
		Map<String, String> indexedFiles = new TreeMap<>();
		changed.forEach(runbook -> indexedFiles.put(runbook.source(), runbook.contentHash()));
		this.store.write(everything, replaced, chunks, indexedFiles, wanted);
		IndexReport report = new IndexReport(changed.stream().map(Runbook::source).toList(), skipped,
				List.copyOf(removed), everything, chunks.size());
		log.info("Indexed with {} ({}): {}", wanted.name(), wanted.digest(), report);
		return report;
	}

	private List<KnowledgeStore.EmbeddedChunk> embed(Runbook runbook, KnowledgeProperties.Model model) {
		List<RunbookChunker.Chunk> chunks = this.chunker.chunk(runbook);
		if (chunks.isEmpty()) {
			return List.of();
		}
		List<String> inputs = chunks.stream().map(chunk -> model.documentPrefix() + chunk.text()).toList();
		EmbeddingResponse response;
		try {
			response = this.embeddings
				.call(new EmbeddingRequest(inputs, OllamaEmbeddingOptions.builder().model(model.name()).build()));
		}
		catch (RuntimeException ex) {
			throw new IndexingException("Embedding " + runbook.source() + " with model " + model.name() + " failed: "
					+ ex.getMessage(), ex);
		}
		if (response.getResults().size() != chunks.size()) {
			throw new IndexingException("Model " + model.name() + " returned " + response.getResults().size()
					+ " embeddings for " + chunks.size() + " chunks of " + runbook.source());
		}
		List<KnowledgeStore.EmbeddedChunk> embedded = new ArrayList<>();
		for (int i = 0; i < chunks.size(); i++) {
			float[] vector = response.getResults().get(i).getOutput();
			if (vector.length != model.dimensions()) {
				throw new IndexingException("Model " + model.name() + " returned an embedding of " + vector.length
						+ " dimensions for " + runbook.source() + ", expected " + model.dimensions());
			}
			embedded.add(new KnowledgeStore.EmbeddedChunk(chunks.get(i).text(), chunks.get(i).metadata(), vector));
		}
		return embedded;
	}

}
