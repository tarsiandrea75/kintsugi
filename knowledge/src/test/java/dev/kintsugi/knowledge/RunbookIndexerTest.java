package dev.kintsugi.knowledge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The indexer against the real knowledge store and a fake Ollama: every scenario of the knowledge-base spec's indexer
 * requirements (openspec/specs/knowledge-base/spec.md).
 */
class RunbookIndexerTest {

	private static final KnowledgeProperties.Model DEFAULT_MODEL = new KnowledgeProperties.Model("nomic-embed-text",
			768, "search_document: ", "search_query: ");

	private static final String ORDER = """
			# Runbook: order-service

			## Key signals

			The default pool size is 10 connections.

			## Safe actions

			Restart the service with `docker compose restart order-service`.
			""";

	private static final String GATEWAY = """
			# Runbook: gateway-mock

			## Purpose

			Stands in for the external payment gateway.
			""";

	private static final String INVENTORY = """
			# Runbook: inventory-service

			## Purpose

			Holds and reserves stock per SKU.

			### Slow `POST /reservations`

			Read the reservation latency histogram first.
			""";

	private static final String README = """
			# Service runbooks

			One runbook per lab service.

			## Authoring rule

			Runbooks must never reference the fault catalog, its fault ids (F1 to F8), or the fault injector.

			## How to use them

			Start from the service where the symptom shows.
			""";

	private static FakeOllama ollama;

	@TempDir
	Path runbooks;

	@BeforeAll
	static void start() {
		ollama = FakeOllama.start();
		KnowledgeStoreContainer.get();
	}

	@AfterAll
	static void stop() {
		ollama.close();
	}

	@BeforeEach
	void reset() throws IOException {
		KnowledgeStoreContainer.empty();
		ollama.reset();
		write("order-service.md", ORDER);
		write("gateway-mock.md", GATEWAY);
		write("inventory-service.md", INVENTORY);
		write("README.md", README);
	}

	@Test
	void indexesOnlyTheTopLevelMarkdownFilesAndWritesNothingUnderTheRunbooks() throws IOException {
		write("notes.txt", "Notes about the fault catalog.");
		Files.createDirectories(this.runbooks.resolve("drafts"));
		write("drafts/extra.md", "# Draft\n\n## Section\n\nA draft section.\n");
		Map<Path, String> before = tree();

		IndexReport report = indexer(DEFAULT_MODEL).index();

		assertThat(report.embedded()).containsExactly("docs/runbooks/README.md", "docs/runbooks/gateway-mock.md",
				"docs/runbooks/inventory-service.md", "docs/runbooks/order-service.md");
		assertThat(KnowledgeStoreContainer.chunkMetadata()).extracting(metadata -> metadata.get("source"))
			.containsOnly("docs/runbooks/README.md", "docs/runbooks/gateway-mock.md",
					"docs/runbooks/inventory-service.md", "docs/runbooks/order-service.md");
		assertThat(tree()).isEqualTo(before);
	}

	@Test
	void aDirectoryWithNoRunbooksStopsTheRunInsteadOfEmptyingTheIndex() throws IOException {
		indexer(DEFAULT_MODEL).index();
		List<String> before = KnowledgeStoreContainer.snapshot();
		try (Stream<Path> files = Files.list(this.runbooks)) {
			for (Path file : files.toList()) {
				Files.delete(file);
			}
		}

		assertThatExceptionOfType(IndexingException.class).isThrownBy(() -> indexer(DEFAULT_MODEL).index())
			.withMessageContaining("No runbooks");

		assertThat(KnowledgeStoreContainer.snapshot()).isEqualTo(before);
	}

	@Test
	void eachChunkCarriesServiceSectionSourceAndTheFileHash() throws Exception {
		indexer(DEFAULT_MODEL).index();

		List<Map<String, String>> inventory = KnowledgeStoreContainer.chunkMetadata()
			.stream()
			.filter(metadata -> "docs/runbooks/inventory-service.md".equals(metadata.get("source")))
			.toList();
		assertThat(inventory).extracting(metadata -> metadata.get("section"))
			.containsExactlyInAnyOrder("Purpose", "Slow POST /reservations");
		assertThat(inventory).allSatisfy(metadata -> {
			assertThat(metadata).containsOnlyKeys("service", "section", "source", "content_hash");
			assertThat(metadata).containsEntry("service", "inventory-service")
				.containsEntry("content_hash", sha256(INVENTORY));
		});
	}

	@Test
	void theReadmeHasNoServiceAndItsAuthoringRuleIsNotIndexed() {
		indexer(DEFAULT_MODEL).index();

		List<Map<String, String>> readme = KnowledgeStoreContainer.chunkMetadata()
			.stream()
			.filter(metadata -> "docs/runbooks/README.md".equals(metadata.get("source")))
			.toList();
		assertThat(readme).extracting(metadata -> metadata.get("section"))
			.containsExactlyInAnyOrder("Service runbooks", "How to use them");
		assertThat(readme).allSatisfy(metadata -> assertThat(metadata).doesNotContainKey("service"));
		assertThat(KnowledgeStoreContainer.chunkMetadata()).extracting(metadata -> metadata.get("section"))
			.doesNotContain("Authoring rule");
		assertThat(KnowledgeStoreContainer.admin().queryForList("SELECT content FROM runbook_chunks", String.class))
			.noneMatch(content -> LeakageGate.LEAKAGE.matcher(content).find());
	}

	@Test
	void theDocumentPrefixIsSentButNotStored() {
		indexer(DEFAULT_MODEL).index();

		assertThat(ollama.embeddedInputs()).contains("search_document: ## Key signals\n\nThe default pool size is 10"
				+ " connections.");
		assertThat(ollama.embeddedInputs()).allMatch(input -> input.startsWith("search_document: "));
		assertThat(KnowledgeStoreContainer.admin().queryForList("SELECT content FROM runbook_chunks", String.class))
			.contains("## Key signals\n\nThe default pool size is 10 connections.")
			.noneMatch(content -> content.startsWith("search_document: "));
	}

	@Test
	void theIndexRecordsTheModelAndTheDigestReadFromOllama() {
		ollama.lists("nomic-embed-text:latest", "d1");

		indexer(DEFAULT_MODEL).index();

		assertThat(KnowledgeStoreContainer.admin().queryForMap("SELECT model_name, model_digest, dimensions,"
				+ " document_prefix, query_prefix FROM index_metadata"))
			.containsEntry("model_name", "nomic-embed-text")
			.containsEntry("model_digest", "d1")
			.containsEntry("dimensions", 768)
			.containsEntry("document_prefix", "search_document: ")
			.containsEntry("query_prefix", "search_query: ");
	}

	@ParameterizedTest
	@ValueSource(strings = { "This is fault F3", "Injected latency on the pool", "A FAULT here", "See F8.",
			"the injector hook" })
	void aLeakageMatchStopsTheRunNamingFileAndLineAndWritesNothing(String line) throws IOException {
		indexer(DEFAULT_MODEL).index();
		List<String> before = KnowledgeStoreContainer.snapshot();
		ollama.reset();
		write("order-service.md", ORDER + "\n" + line + "\n");

		assertThatExceptionOfType(IndexingException.class).isThrownBy(() -> indexer(DEFAULT_MODEL).index())
			.withMessageContaining("docs/runbooks/order-service.md:11");

		assertThat(ollama.tagCalls()).isZero();
		assertThat(ollama.embedCalls()).isEmpty();
		assertThat(KnowledgeStoreContainer.snapshot()).isEqualTo(before);
	}

	@Test
	void aWordThatOnlyContainsThePatternPasses() {
		assertThat(ORDER).contains("default");

		assertThat(indexer(DEFAULT_MODEL).index().embedded()).contains("docs/runbooks/order-service.md");
	}

	@Test
	void onlyTheReadmeAuthoringRuleIsExempt() throws IOException {
		write("order-service.md", ORDER + "\n## Authoring rule\n\nNever name a fault.\n");

		assertThatExceptionOfType(IndexingException.class).isThrownBy(() -> indexer(DEFAULT_MODEL).index())
			.withMessageContaining("docs/runbooks/order-service.md:13");
	}

	@Test
	void aSecondRunWithNoChangesEmbedsNothing() {
		indexer(DEFAULT_MODEL).index();
		List<String> before = KnowledgeStoreContainer.snapshot();
		ollama.reset();

		IndexReport report = indexer(DEFAULT_MODEL).index();

		assertThat(ollama.embedCalls()).isEmpty();
		assertThat(report.embedded()).isEmpty();
		assertThat(report.skipped()).hasSize(4);
		assertThat(KnowledgeStoreContainer.snapshot()).isEqualTo(before);
	}

	@Test
	void aChangedFileIsTheOnlyOneEmbeddedAndItsOldChunksAreReplaced() throws IOException {
		indexer(DEFAULT_MODEL).index();
		List<String> untouched = KnowledgeStoreContainer.snapshot()
			.stream()
			.filter(row -> !row.contains("gateway-mock"))
			.toList();
		ollama.reset();
		String changed = GATEWAY + "\n## Safe actions\n\nReset the gateway behavior.\n";
		write("gateway-mock.md", changed);

		IndexReport report = indexer(DEFAULT_MODEL).index();

		assertThat(report.embedded()).containsExactly("docs/runbooks/gateway-mock.md");
		assertThat(ollama.embedCalls()).hasSize(1);
		assertThat(ollama.embeddedInputs()).allMatch(input -> input.contains("gateway") || input.contains("Reset"));
		List<Map<String, String>> gateway = KnowledgeStoreContainer.chunkMetadata()
			.stream()
			.filter(metadata -> "docs/runbooks/gateway-mock.md".equals(metadata.get("source")))
			.toList();
		assertThat(gateway).hasSize(2).allSatisfy(metadata -> assertThat(metadata).containsEntry("content_hash",
				sha256(changed)));
		assertThat(KnowledgeStoreContainer.snapshot()).containsAll(untouched.subList(0, untouched.size() - 1));
	}

	@Test
	void aRemovedFileLosesItsChunks() throws IOException {
		indexer(DEFAULT_MODEL).index();
		Files.delete(this.runbooks.resolve("gateway-mock.md"));
		ollama.reset();

		IndexReport report = indexer(DEFAULT_MODEL).index();

		assertThat(report.removed()).containsExactly("docs/runbooks/gateway-mock.md");
		assertThat(ollama.embedCalls()).isEmpty();
		assertThat(KnowledgeStoreContainer.chunkMetadata()).extracting(metadata -> metadata.get("source"))
			.isNotEmpty()
			.doesNotContain("docs/runbooks/gateway-mock.md");
	}

	@Test
	void aDigestChangeReEmbedsEveryFile() {
		ollama.lists("nomic-embed-text:latest", "d1");
		indexer(DEFAULT_MODEL).index();
		ollama.reset();
		ollama.lists("nomic-embed-text:latest", "d2");

		IndexReport report = indexer(DEFAULT_MODEL).index();

		assertThat(report.fullReindex()).isTrue();
		assertThat(report.embedded()).hasSize(4);
		assertThat(ollama.embedCalls()).hasSize(4);
		assertThat(KnowledgeStoreContainer.admin().queryForObject("SELECT model_digest FROM index_metadata",
				String.class))
			.isEqualTo("d2");
	}

	@Test
	void aModelChangeReEmbedsEveryFileAndRecordsTheNewModel() {
		indexer(DEFAULT_MODEL).index();
		ollama.reset();
		ollama.lists("other-embed:v2", "d9");
		KnowledgeProperties.Model other = new KnowledgeProperties.Model("other-embed:v2", 768, "search_document: ",
				"search_query: ");

		IndexReport report = indexer(other).index();

		assertThat(report.embedded()).hasSize(4);
		assertThat(KnowledgeStoreContainer.admin().queryForMap("SELECT model_name, model_digest FROM index_metadata"))
			.containsEntry("model_name", "other-embed:v2")
			.containsEntry("model_digest", "d9");
	}

	@Test
	void aPrefixChangeReEmbedsEveryFile() {
		indexer(DEFAULT_MODEL).index();
		ollama.reset();
		KnowledgeProperties.Model prefixed = new KnowledgeProperties.Model("nomic-embed-text", 768, "doc: ",
				"search_query: ");

		IndexReport report = indexer(prefixed).index();

		assertThat(report.embedded()).hasSize(4);
		assertThat(ollama.embeddedInputs()).allMatch(input -> input.startsWith("doc: "));
	}

	@Test
	void aModelOllamaDoesNotListStopsTheRunNamingIt() {
		indexer(DEFAULT_MODEL).index();
		List<String> before = KnowledgeStoreContainer.snapshot();
		ollama.reset();
		ollama.lists("mxbai-embed-large:latest", "d3");

		assertThatExceptionOfType(IndexingException.class).isThrownBy(() -> indexer(DEFAULT_MODEL).index())
			.withMessageContaining("nomic-embed-text");

		assertThat(ollama.embedCalls()).isEmpty();
		assertThat(KnowledgeStoreContainer.snapshot()).isEqualTo(before);
	}

	@Test
	void aFailureInTheMiddleOfAFullReIndexLeavesThePreviousIndexIntact() {
		indexer(DEFAULT_MODEL).index();
		List<String> before = KnowledgeStoreContainer.snapshot();
		ollama.reset();
		ollama.lists("other-embed:latest", "d9");
		ollama.failOnEmbedCall(3);

		assertThatExceptionOfType(IndexingException.class)
			.isThrownBy(() -> indexer(new KnowledgeProperties.Model("other-embed", 768, "search_document: ",
					"search_query: "))
				.index())
			.withMessageContaining("other-embed");

		assertThat(ollama.embedCalls()).hasSize(3);
		assertThat(KnowledgeStoreContainer.snapshot()).isEqualTo(before);
	}

	@Test
	void ollamaDownStopsTheRunNamingTheModelAndKeepsTheOldChunks() throws IOException {
		indexer(DEFAULT_MODEL).index();
		List<String> before = KnowledgeStoreContainer.snapshot();
		write("gateway-mock.md", GATEWAY + "\nOne more line.\n");

		assertThatExceptionOfType(IndexingException.class)
			.isThrownBy(() -> indexer(DEFAULT_MODEL, "http://localhost:1").index())
			.withMessageContaining("nomic-embed-text");

		assertThat(KnowledgeStoreContainer.snapshot()).isEqualTo(before);
	}

	@Test
	void anEmbeddingOfTheWrongDimensionFailsTheRun() {
		ollama.embedsWithDimensions(384);

		assertThatExceptionOfType(IndexingException.class).isThrownBy(() -> indexer(DEFAULT_MODEL).index())
			.withMessageContaining("384")
			.withMessageContaining("768");

		assertThat(KnowledgeStoreContainer.snapshot()).isEmpty();
	}

	@Test
	void configuredDimensionsThatDifferFromTheColumnStopTheRunBeforeEmbedding() {
		KnowledgeProperties.Model wide = new KnowledgeProperties.Model("nomic-embed-text", 1024, "search_document: ",
				"search_query: ");

		assertThatExceptionOfType(IndexingException.class).isThrownBy(() -> indexer(wide).index())
			.withMessageContaining("1024")
			.withMessageContaining("768");

		assertThat(ollama.embedCalls()).isEmpty();
	}

	@Test
	void aLongSectionBecomesSeveralChunksOfAtMost800Tokens() throws IOException {
		String sentence = "The reservation latency rises when the pool waits for a connection to the database. ";
		write("payment-service.md", "# Runbook: payment-service\n\n## Key signals\n\n" + sentence.repeat(200));

		indexer(DEFAULT_MODEL).index();

		Encoding tokens = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
		List<String> contents = KnowledgeStoreContainer.admin()
			.queryForList("SELECT content FROM runbook_chunks WHERE metadata->>'source' = ?", String.class,
					"docs/runbooks/payment-service.md");
		assertThat(contents).hasSizeGreaterThan(3)
			.allSatisfy(content -> assertThat(tokens.countTokens(content)).isLessThanOrEqualTo(800));
		assertThat(KnowledgeStoreContainer.chunkMetadata()
			.stream()
			.filter(metadata -> "docs/runbooks/payment-service.md".equals(metadata.get("source")))
			.map(metadata -> metadata.get("service") + "|" + metadata.get("section"))
			.collect(Collectors.toSet())).containsExactly("payment-service|Key signals");
	}

	@Test
	void aStoreFailureWhileWritingRollsTheWholeRunBack() {
		indexer(DEFAULT_MODEL).index();
		List<String> before = KnowledgeStoreContainer.snapshot();
		float[] valid = new float[768];
		valid[0] = 1;
		float[] invalid = new float[768];
		invalid[0] = Float.NaN;
		Map<String, Object> metadata = Map.of("section", "x", "source", "docs/runbooks/x.md", "content_hash", "h");
		List<KnowledgeStore.EmbeddedChunk> chunks = List.of(new KnowledgeStore.EmbeddedChunk("a", metadata, valid),
				new KnowledgeStore.EmbeddedChunk("b", metadata, invalid));

		assertThatExceptionOfType(RuntimeException.class)
			.isThrownBy(() -> KnowledgeStoreContainer.indexerStore().write(true, Set.of(), chunks,
					new IndexModel("other", "d9", 768, "search_document: ", "search_query: ")));

		assertThat(KnowledgeStoreContainer.snapshot()).isEqualTo(before);
	}

	private RunbookIndexer indexer(KnowledgeProperties.Model model) {
		return indexer(model, ollama.baseUrl());
	}

	private RunbookIndexer indexer(KnowledgeProperties.Model model, String ollamaUrl) {
		OllamaApi api = OllamaApi.builder().baseUrl(ollamaUrl).build();
		EmbeddingModel embeddings = OllamaEmbeddingModel.builder()
			.ollamaApi(api)
			.options(OllamaEmbeddingOptions.builder().model(model.name()).build())
			.build();
		return new RunbookIndexer(new KnowledgeProperties(this.runbooks, model), new OllamaModelCatalog(api),
				embeddings, KnowledgeStoreContainer.indexerStore());
	}

	private void write(String name, String content) throws IOException {
		Files.writeString(this.runbooks.resolve(name), content, StandardCharsets.UTF_8);
	}

	private Map<Path, String> tree() throws IOException {
		try (Stream<Path> files = Files.walk(this.runbooks)) {
			return files.filter(Files::isRegularFile).collect(Collectors.toMap(path -> path, path -> {
				try {
					return Files.readString(path) + "@" + Files.getLastModifiedTime(path);
				}
				catch (IOException ex) {
					throw new IllegalStateException(ex);
				}
			}));
		}
	}

	static String sha256(String content) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
