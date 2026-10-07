package dev.kintsugi.lab.gateway.behavior;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class BehaviorStoreTest {

	private final BehaviorStore store = new BehaviorStore();

	@Test
	void startsWithTheDefaultBehavior() {
		assertThat(store.get()).isEqualTo(GatewayBehavior.DEFAULT);
	}

	@Test
	void setReplacesTheBehaviorAndReturnsIt() {
		GatewayBehavior slow = new GatewayBehavior(10_000);

		assertThat(store.set(slow)).isEqualTo(slow);
		assertThat(store.get()).isEqualTo(slow);
	}

	@Test
	void resetRestoresTheDefaultAndReturnsIt() {
		store.set(new GatewayBehavior(10_000));

		assertThat(store.reset()).isEqualTo(GatewayBehavior.DEFAULT);
		assertThat(store.get()).isEqualTo(GatewayBehavior.DEFAULT);
	}

	@Test
	void changesAndResetsAreLoggedWithTheOldAndNewLatency(CapturedOutput output) {
		store.set(new GatewayBehavior(750));
		store.reset();

		assertThat(output.getOut()).containsSubsequence("INFO", "Gateway behavior changed: latencyMs 0 -> 750", "INFO",
				"Gateway behavior reset: latencyMs 750 -> 0");
	}

	@Test
	void errorStatusChangesAndResetsAreLogged(CapturedOutput output) {
		store.set(new GatewayBehavior(0, 429));
		store.reset();

		assertThat(output.getOut()).containsSubsequence("INFO",
				"Gateway behavior changed: latencyMs 0 -> 0, errorStatus none -> 429", "INFO",
				"Gateway behavior reset: latencyMs 0 -> 0, errorStatus 429 -> none");
	}

	@Test
	void resetClearsTheErrorStatus() {
		store.set(new GatewayBehavior(300, 503));

		assertThat(store.reset().errorStatus()).isNull();
		assertThat(store.get()).isEqualTo(GatewayBehavior.DEFAULT);
	}

	@Test
	void concurrentChangesLeaveOneOfTheWrittenBehaviors() throws InterruptedException {
		try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
			for (int i = 1; i <= 1_000; i++) {
				long latencyMs = i;
				executor.execute(() -> store.set(new GatewayBehavior(latencyMs)));
			}
			executor.shutdown();
			assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(store.get().latencyMs()).isBetween(1L, 1_000L);
	}

}
