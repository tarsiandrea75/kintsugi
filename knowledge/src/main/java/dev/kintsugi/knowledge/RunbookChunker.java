package dev.kintsugi.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

/**
 * Turns a runbook into chunks: one per Markdown section, then {@link TokenTextSplitter} cuts any section longer than
 * {@value #MAX_TOKENS} tokens. Every chunk carries the knowledge-base spec's metadata: {@code service} (absent when the
 * file is not a service runbook), {@code section}, {@code source} and {@code content_hash}.
 */
final class RunbookChunker {

	/** The spec's limit; counted with the splitter's own tokenizer (cl100k_base). */
	static final int MAX_TOKENS = 800;

	private final TokenTextSplitter splitter = TokenTextSplitter.builder().withChunkSize(MAX_TOKENS).build();

	/** A chunk's stored text, without the document prefix, and its metadata. */
	record Chunk(String text, Map<String, Object> metadata) {
	}

	List<Chunk> chunk(Runbook runbook) {
		List<Chunk> chunks = new ArrayList<>();
		for (MarkdownSections.Section section : runbook.sections()) {
			if (runbook.exempt().contains(section)) {
				continue;
			}
			String text = String.join("\n", runbook.lines().subList(section.firstLine(), section.endLine())).strip();
			if (section.level() > 0 && text.lines().count() < 2) {
				continue; // a heading with no text under it, such as a runbook's title
			}
			Map<String, Object> metadata = new LinkedHashMap<>();
			if (runbook.service() != null) {
				metadata.put("service", runbook.service());
			}
			metadata.put("section", section.level() > 0 ? section.title() : runbook.source());
			metadata.put("source", runbook.source());
			metadata.put("content_hash", runbook.contentHash());
			for (Document piece : this.splitter.split(new Document(text))) {
				chunks.add(new Chunk(piece.getText(), metadata));
			}
		}
		return chunks;
	}

}
