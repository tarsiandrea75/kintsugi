package dev.kintsugi.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/**
 * Splits a Markdown file into sections, one per heading, with the CommonMark parser that Spring AI's Markdown reader
 * uses. Each section keeps its raw Markdown lines (tables and inline code included), from its heading to the next
 * heading of any level. Only top-level headings count, so a {@code #} line inside a code block or a quote never
 * starts a section.
 */
final class MarkdownSections {

	private static final Parser PARSER = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build();

	private MarkdownSections() {
	}

	/**
	 * A section: lines {@code firstLine} (its heading) up to {@code endLine}, exclusive; 0-based. Text before the
	 * first heading is a section of level 0 with an empty title.
	 */
	record Section(String title, int level, int firstLine, int endLine) {
	}

	static List<Section> of(List<String> lines) {
		Node document = PARSER.parse(String.join("\n", lines));
		List<Heading> headings = new ArrayList<>();
		for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
			if (node instanceof Heading heading) {
				headings.add(heading);
			}
		}
		List<Section> sections = new ArrayList<>();
		int firstHeading = headings.isEmpty() ? lines.size() : line(headings.getFirst());
		if (lines.subList(0, firstHeading).stream().anyMatch(line -> !line.isBlank())) {
			sections.add(new Section("", 0, 0, firstHeading));
		}
		for (int i = 0; i < headings.size(); i++) {
			Heading heading = headings.get(i);
			int end = i + 1 < headings.size() ? line(headings.get(i + 1)) : lines.size();
			sections.add(new Section(title(heading), heading.getLevel(), line(heading), end));
		}
		return sections;
	}

	/** Sections titled {@code title}, each with every deeper section below it, up to the next heading as high. */
	static Set<Section> named(List<Section> sections, String title) {
		Set<Section> named = new LinkedHashSet<>();
		for (int i = 0; i < sections.size(); i++) {
			Section section = sections.get(i);
			if (section.level() > 0 && section.title().equalsIgnoreCase(title)) {
				named.add(section);
				for (int j = i + 1; j < sections.size() && sections.get(j).level() > section.level(); j++) {
					named.add(sections.get(j));
				}
			}
		}
		return named;
	}

	private static int line(Heading heading) {
		return heading.getSourceSpans().getFirst().getLineIndex();
	}

	/** The heading's text: plain text and inline code, without Markdown markup. */
	private static String title(Heading heading) {
		StringBuilder title = new StringBuilder();
		heading.accept(new AbstractVisitor() {
			@Override
			public void visit(Text text) {
				title.append(text.getLiteral());
			}

			@Override
			public void visit(Code code) {
				title.append(code.getLiteral());
			}

			@Override
			public void visit(SoftLineBreak softLineBreak) {
				title.append(' ');
			}
		});
		return title.toString().strip();
	}

}
