package dev.kintsugi.lab.inventory.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.kintsugi.lab.inventory.TestcontainersConfiguration;
import dev.kintsugi.lab.inventory.domain.Stock;

@DataJdbcTest
@Import({ TestcontainersConfiguration.class, StockRepository.class })
class StockRepositoryTest {

	@Autowired
	private StockRepository repository;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void runsAgainstPostgres18WithTheSeededStock() {
		String version = jdbc.sql("select version()").query(String.class).single();
		List<Stock> seeded = jdbc.sql("select sku, available from stock where sku like 'SKU-%' order by sku")
			.query((rs, n) -> new Stock(rs.getString("sku"), rs.getInt("available")))
			.list();

		assertThat(version).startsWith("PostgreSQL 18.6");
		assertThat(seeded).containsExactly(new Stock("SKU-1", 1_000_000), new Stock("SKU-2", 1_000_000),
				new Stock("SKU-3", 1_000_000), new Stock("SKU-4", 1_000_000), new Stock("SKU-5", 1_000_000));
	}

	@Test
	void stockIsFoundBySku() {
		assertThat(repository.find("SKU-3")).contains(new Stock("SKU-3", 1_000_000));
		assertThat(repository.find("NO-SUCH-SKU")).isEmpty();
	}

	@Test
	void takeSubtractsOnlyWhenEnoughIsAvailable() {
		String sku = newSku(5);

		assertThat(repository.take(sku, 3)).isTrue();
		assertThat(repository.take(sku, 3)).isFalse();
		assertThat(repository.take(sku, 2)).isTrue();

		assertThat(repository.find(sku)).contains(new Stock(sku, 0));
	}

	@Test
	void takeOfAnUnknownSkuChangesNothing() {
		assertThat(repository.take("NO-SUCH-SKU", 1)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1 })
	void takeRejectsAQuantityThatIsNotPositive(int quantity) {
		String sku = newSku(5);

		assertThatIllegalArgumentException().isThrownBy(() -> repository.take(sku, quantity));
		assertThat(repository.find(sku)).contains(new Stock(sku, 5));
	}

	@Test
	void negativeStockIsRejectedByTheSchema() {
		String sku = newSku(1);

		assertThatExceptionOfType(DataIntegrityViolationException.class)
			.isThrownBy(() -> jdbc.sql("update stock set available = -1 where sku = ?").param(sku).update());
	}

	private String newSku(int available) {
		String sku = "TEST-" + UUID.randomUUID();
		jdbc.sql("insert into stock (sku, available) values (?, ?)").params(sku, available).update();
		return sku;
	}

}
