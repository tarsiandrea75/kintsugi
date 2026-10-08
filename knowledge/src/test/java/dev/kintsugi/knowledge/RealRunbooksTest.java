package dev.kintsugi.knowledge;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The repository's own {@code docs/runbooks/}, indexed with the fake model: they pass the leakage gate, and they split
 * into a sensible number of chunks per service, none over 800 tokens.
 */
class RealRunbooksTest {

	private static FakeOllama ollama;

	@BeforeAll
	static void start() {
		ollama = FakeOllama.start();
		KnowledgeStoreContainer.empty();
	}

	@AfterAll
	static void stop() {
		ollama.close();
	}

	@Test
	void theRealRunbooksIndexIntoSensibleChunks() {
		OllamaApi api = OllamaApi.builder().baseUrl(ollama.baseUrl()).build();
		KnowledgeProperties.Model model = new KnowledgeProperties.Model("nomic-embed-text", 768, "search_document: ",
				"search_query: ");
		RunbookIndexer indexer = new RunbookIndexer(
				new KnowledgeProperties(KnowledgeStoreContainer.REPO.resolve("docs/runbooks"), model),
				new OllamaModelCatalog(api),
				OllamaEmbeddingModel.builder()
					.ollamaApi(api)
					.options(OllamaEmbeddingOptions.builder().model(model.name()).build())
					.build(),
				KnowledgeStoreContainer.indexerStore());

		IndexReport report = indexer.index();

		assertThat(report.embedded()).containsExactly("docs/runbooks/README.md", "docs/runbooks/gateway-mock.md",
				"docs/runbooks/inventory-service.md", "docs/runbooks/order-service.md",
				"docs/runbooks/payment-service.md");
		List<Map<String, String>> metadata = KnowledgeStoreContainer.chunkMetadata();
		Map<String, Long> perService = metadata.stream()
			.collect(Collectors.groupingBy(chunk -> chunk.getOrDefault("service", "README"), Collectors.counting()));
		// Every service runbook has 8 numbered sections plus symptom and lab-control subsections.
		assertThat(perService).containsOnlyKeys("README", "order-service", "payment-service", "inventory-service",
				"gateway-mock");
		assertThat(perService.get("README")).isBetween(1L, 3L);
		perService.forEach((service, count) -> {
			if (!service.equals("README")) {
				assertThat(count).as(service).isBetween(10L, 30L);
			}
		});
		assertThat(metadata).extracting(chunk -> chunk.get("section"))
			.doesNotContain("Authoring rule")
			.contains("Orders stuck in PAYMENT_PENDING", "Slow POST /reservations", "Slow POST /authorize");
		Encoding tokens = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
		assertThat(KnowledgeStoreContainer.admin().queryForList("SELECT content FROM runbook_chunks", String.class))
			.allSatisfy(content -> assertThat(tokens.countTokens(content)).isBetween(1, 800));
		System.out.println("Chunks per service: " + perService);
	}

}
