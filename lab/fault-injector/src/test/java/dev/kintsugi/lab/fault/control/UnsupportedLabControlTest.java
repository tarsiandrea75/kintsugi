package dev.kintsugi.lab.fault.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import dev.kintsugi.lab.fault.catalog.Fault;

/**
 * Until T4 adds the real Docker and gateway control, {@code kintsugi.lab-control.mode=unsupported} (the default) lets
 * the application start: activation fails before anything is applied, so the compensating restore has nothing to do.
 */
class UnsupportedLabControlTest {

	@Configuration(proxyBeanMethods = false)
	@Import(UnsupportedLabControl.class)
	static class Config {

	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(Config.class);

	@Test
	void isTheDefaultLabControl() {
		runner.run(context -> assertThat(context).getBean(LabControl.class).isInstanceOf(UnsupportedLabControl.class));
	}

	@Test
	void isAbsentWhenAnotherModeIsConfigured() {
		runner.withPropertyValues("kintsugi.lab-control.mode=docker")
			.run(context -> assertThat(context).doesNotHaveBean(UnsupportedLabControl.class));
	}

	@Test
	void activationFailsAndRestoreHasNothingToUndo() {
		UnsupportedLabControl control = new UnsupportedLabControl();

		assertThatExceptionOfType(LabControlException.class).isThrownBy(() -> control.activate(Fault.F1))
			.withMessageContaining("kintsugi.lab-control.mode");
		assertThatNoException().isThrownBy(() -> control.restore(Fault.F1));
	}

}
