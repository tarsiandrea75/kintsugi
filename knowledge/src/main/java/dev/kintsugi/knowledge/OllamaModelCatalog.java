package dev.kintsugi.knowledge;

import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.stereotype.Component;

/**
 * Reads a model's digest from Ollama ({@code GET /api/tags}). The digest is never configured: it is what identifies
 * the weights the index is built with, so a model re-pulled under the same name re-embeds everything.
 */
@Component
public class OllamaModelCatalog {

	private final OllamaApi ollama;

	public OllamaModelCatalog(OllamaApi ollama) {
		this.ollama = ollama;
	}

	/** The digest of {@code model}; a name without a tag means {@code :latest}, as in Ollama itself. */
	public String digest(String model) {
		String tagged = model.contains(":") ? model : model + ":latest";
		OllamaApi.ListModelResponse listed;
		try {
			listed = this.ollama.listModels();
		}
		catch (RuntimeException ex) {
			throw new IndexingException("Could not read the digest of model " + model + " from Ollama (GET /api/tags): "
					+ ex.getMessage(), ex);
		}
		return listed.models()
			.stream()
			.filter(listedModel -> tagged.equals(listedModel.name()))
			.map(OllamaApi.Model::digest)
			.findFirst()
			.orElseThrow(() -> new IndexingException(
					"Model " + model + " is not listed by Ollama (GET /api/tags); pull it with: ollama pull " + model));
	}

}
