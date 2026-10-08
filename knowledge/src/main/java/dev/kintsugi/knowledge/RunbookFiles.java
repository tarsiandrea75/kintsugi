package dev.kintsugi.knowledge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The allow-list: the regular {@code *.md} files directly in the runbooks directory, nothing else. Subdirectories,
 * other extensions and symbolic links are ignored, so nothing outside the mount can be reached through it. Files are
 * only read, never written. A directory with no runbooks stops the run, since it would remove every indexed file.
 */
final class RunbookFiles {

	/** Where the runbooks live in the repository; {@code source} is this plus the file name. */
	static final String SOURCE_DIR = "docs/runbooks/";

	static final Set<String> SERVICES = Set.of("order-service", "payment-service", "inventory-service",
			"gateway-mock");

	private static final String README = "README.md";

	private static final String AUTHORING_RULE = "Authoring rule";

	private RunbookFiles() {
	}

	static List<Runbook> read(Path directory) {
		if (!Files.isDirectory(directory)) {
			throw new IndexingException("Runbooks directory not found: " + directory);
		}
		List<Runbook> runbooks;
		try (Stream<Path> entries = Files.list(directory)) {
			runbooks = entries
				.filter(path -> path.getFileName().toString().endsWith(".md")
						&& Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
				.sorted()
				.map(RunbookFiles::runbook)
				.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		// Every indexed file would count as removed: a missing mount must not empty the index.
		if (runbooks.isEmpty()) {
			throw new IndexingException("No runbooks (*.md) in " + directory + "; is docs/runbooks/ mounted?");
		}
		return runbooks;
	}

	private static Runbook runbook(Path path) {
		try {
			byte[] bytes = Files.readAllBytes(path);
			String fileName = path.getFileName().toString();
			String baseName = fileName.substring(0, fileName.length() - ".md".length());
			List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
			List<MarkdownSections.Section> sections = MarkdownSections.of(lines);
			return new Runbook(SOURCE_DIR + fileName, SERVICES.contains(baseName) ? baseName : null, sha256(bytes),
					lines, sections,
					README.equals(fileName) ? MarkdownSections.named(sections, AUTHORING_RULE) : Set.of());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
