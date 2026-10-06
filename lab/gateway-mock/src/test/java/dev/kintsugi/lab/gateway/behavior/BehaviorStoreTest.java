package dev.kintsugi.lab.gateway.behavior;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

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
