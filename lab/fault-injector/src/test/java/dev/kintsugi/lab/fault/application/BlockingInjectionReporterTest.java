package dev.kintsugi.lab.fault.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.kintsugi.lab.fault.TestcontainersConfiguration;
import dev.kintsugi.lab.fault.catalog.Fault;
import dev.kintsugi.lab.fault.control.FakeLabControl;
import dev.kintsugi.lab.fault.control.FakeLabControlConfiguration;
import dev.kintsugi.lab.fault.domain.Injection;
import dev.kintsugi.lab.fault.persistence.InjectionRepository;

/** A blocking injection left over from a crash is reported at startup, never restored silently (spec). */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, FakeLabControlConfiguration.class })
@ExtendWith(OutputCaptureExtension.class)
class BlockingInjectionReporterTest {

	@Autowired
	private BlockingInjectionReporter reporter;

	@Autowired
	private InjectionRepository injections;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private FakeLabControl lab;

	@BeforeEach
	void emptyLabAndTables() {
		jdbc.sql("TRUNCATE injection_events, injections").update();
		lab.reset();
	}

	@Test
	void leftoverActiveInjectionIsLoggedAsAStructuredWarnAndNotRestored(CapturedOutput output) {
		Injection leftover = Injection.start(Fault.F1, "{}", Instant.now().truncatedTo(ChronoUnit.MICROS));
		injections.insert(leftover);

		reporter.reportBlockingInjection();

		assertThat(output.getOut()).containsPattern("WARN .*event=blocking_injection_at_startup injection_id="
				+ leftover.id() + " fault=F1 status=ACTIVE");
		assertThat(lab.calls()).isEmpty();
		assertThat(injections.findBlocking()).map(Injection::id).contains(leftover.id());
	}

	@Test
	void nothingIsLoggedWhenNoInjectionIsBlocking(CapturedOutput output) {
		reporter.reportBlockingInjection();

		assertThat(output.getOut()).doesNotContain("blocking_injection_at_startup");
	}

}
