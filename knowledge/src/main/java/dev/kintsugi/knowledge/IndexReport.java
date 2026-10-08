package dev.kintsugi.knowledge;

import java.util.List;

/**
 * What a run did, by repository path.
 *
 * @param embedded files embedded and written
 * @param skipped files whose content hash was unchanged
 * @param removed files no longer present, whose chunks were deleted
 * @param fullReindex whether the model, digest, dimensions or prefixes changed, so every file was embedded
 * @param chunks chunks written
 */
public record IndexReport(List<String> embedded, List<String> skipped, List<String> removed, boolean fullReindex,
		int chunks) {
}
