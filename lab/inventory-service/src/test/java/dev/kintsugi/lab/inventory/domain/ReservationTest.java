package dev.kintsugi.lab.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReservationTest {

	private static final UUID ORDER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

	@Test
	void validReservationKeepsItsLinesInOrder() {
		List<ReservationLine> lines = List.of(new ReservationLine("SKU-1", 2), new ReservationLine("SKU-2", 1));

		Reservation reservation = new Reservation(ORDER_ID, lines);

		assertThat(reservation.orderId()).isEqualTo(ORDER_ID);
		assertThat(reservation.lines()).containsExactlyElementsOf(lines);
	}

	@Test
	void linesAreCopiedSoTheCallerCannotChangeThem() {
		List<ReservationLine> lines = new ArrayList<>(List.of(new ReservationLine("SKU-1", 1)));

		Reservation reservation = new Reservation(ORDER_ID, lines);
		lines.clear();

		assertThat(reservation.lines()).hasSize(1);
	}

	@Test
	void orderIdIsRequired() {
		assertThatNullPointerException()
			.isThrownBy(() -> new Reservation(null, List.of(new ReservationLine("SKU-1", 1))));
	}

	@Test
	void atLeastOneLineIsRequired() {
		assertThatIllegalArgumentException().isThrownBy(() -> new Reservation(ORDER_ID, List.of()));
	}

	@Test
	void atMostOneHundredLinesAreAccepted() {
		List<ReservationLine> hundred = java.util.stream.IntStream.range(0, Reservation.MAX_LINES)
			.mapToObj(i -> new ReservationLine("SKU-1", 1))
			.toList();
		List<ReservationLine> tooMany = new ArrayList<>(hundred);
		tooMany.add(new ReservationLine("SKU-1", 1));

		assertThat(new Reservation(ORDER_ID, hundred).lines()).hasSize(100);
		assertThatIllegalArgumentException().isThrownBy(() -> new Reservation(ORDER_ID, tooMany));
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, -1 })
	void quantityMustBePositive(int quantity) {
		assertThatIllegalArgumentException().isThrownBy(() -> new ReservationLine("SKU-1", quantity));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "  " })
	void skuMustNotBeBlank(String sku) {
		assertThatIllegalArgumentException().isThrownBy(() -> new ReservationLine(sku, 1));
	}

	@Test
	void skuIsAtMostSixtyFourCharacters() {
		assertThat(new ReservationLine("S".repeat(64), 1).sku()).hasSize(64);
		assertThatIllegalArgumentException().isThrownBy(() -> new ReservationLine("S".repeat(65), 1));
		assertThatNullPointerException().isThrownBy(() -> new ReservationLine(null, 1));
	}

}
