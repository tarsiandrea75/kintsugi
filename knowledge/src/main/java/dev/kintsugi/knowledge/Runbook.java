package dev.kintsugi.knowledge;

import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * One allowed runbook file, read once.
 *
 * @param source the repository-relative path, such as {@code docs/runbooks/order-service.md}
 * @param service the service it documents, or {@code null} for README.md and any other file
 * @param contentHash SHA-256 of the file's bytes, in hex
 * @param lines the file's lines
 * @param sections its Markdown sections, in order
 * @param exempt the sections the leakage gate skips and the indexer never indexes: the README's "Authoring rule"
 */
record Runbook(String source, @Nullable String service, String contentHash, List<String> lines,
		List<MarkdownSections.Section> sections, Set<MarkdownSections.Section> exempt) {

	boolean isExemptLine(int line) {
		return this.exempt.stream().anyMatch(section -> line >= section.firstLine() && line < section.endLine());
	}

}
