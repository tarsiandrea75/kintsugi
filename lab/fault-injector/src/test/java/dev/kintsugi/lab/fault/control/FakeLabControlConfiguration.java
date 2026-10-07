package dev.kintsugi.lab.fault.control;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the configured {@link LabControl} with a {@link FakeLabControl}. */
@TestConfiguration(proxyBeanMethods = false)
public class FakeLabControlConfiguration {

	@Bean
	@Primary
	FakeLabControl fakeLabControl() {
		return new FakeLabControl();
	}

}
