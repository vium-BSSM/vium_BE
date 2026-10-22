package com.vium.receipt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.receipt.dto.ParsedItem;
import com.vium.receipt.dto.ParsedPurchase;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PurchaseScanNormalizerTest {
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 9, 0);
	private final PurchaseScanNormalizer normalizer = new PurchaseScanNormalizer();

	@ParameterizedTest
	@CsvSource({"1.2344,1.234,2", "1.2345,1.235,2", "999999999.999,999999999.999,2",
		"999999999.9995,1.000,1", "1000000000,1.000,1", "0.0005,0.001,2"})
	void roundsQuantityAndFallsBackOnOverflow(String quantity, String expected, short unit) {
		var result = normalize(List.of(item("두부", quantity, "1000")), null);
		assertThat(result.items()).singleElement().satisfies(item -> {
			assertThat(item.quantity()).isEqualByComparingTo(expected);
			assertThat(item.quantity().scale()).isEqualTo(3);
			assertThat(item.unitId()).isEqualTo(unit);
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-1", "0.0004"})
	void removesQuantitiesThatCannotBeStoredAsPositive(String quantity) {
		assertThatThrownBy(() -> normalize(List.of(item("두부", quantity, null)), null))
			.isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SCAN_UNRECOGNIZABLE));
	}

	@Test
	void truncatesLongNamesAndCapsValidItemsAtOneHundred() {
		String name = "두부" + "가".repeat(250);
		var result = normalize(IntStream.range(0, 101).mapToObj(i -> item(name + i, "1", "1000")).toList(), null);
		assertThat(result.items()).hasSize(100).allSatisfy(item -> assertThat(item.rawName()).hasSize(200));
		assertThat(result.totalAmount()).isEqualByComparingTo("100000");
	}

	@Test
	void dropsUnusableNamesBeforeCountingValidItems() {
		var result = normalize(List.of(item("파", "1", null), item("12345", "1", null),
			item("  두부  ", "1", "1000")), null);
		assertThat(result.items()).singleElement().satisfies(item -> assertThat(item.rawName()).isEqualTo("두부"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"-1", "100000001"})
	void clearsOutOfRangeAmountsAndFallsBackToValidItemSum(String invalid) {
		var first = new ParsedItem("두부", BigDecimal.ONE, (short) 2, new BigDecimal(invalid), new BigDecimal(invalid));
		var result = normalize(List.of(first, item("우유", "1", "3500")), invalid);
		assertThat(result.items().getFirst().unitPrice()).isNull();
		assertThat(result.items().getFirst().amount()).isNull();
		assertThat(result.totalAmount()).isEqualByComparingTo("3500");
	}

	@Test
	void keepsInclusiveAmountLimitsAndDoesNotInventMissingAmounts() {
		assertThat(normalize(List.of(item("두부", "1", "0")), "100000000").totalAmount())
			.isEqualByComparingTo("100000000");
		assertThat(normalize(List.of(item("두부", "1", null)), null).totalAmount()).isNull();
		assertThat(normalize(List.of(item("두부", "1", "100000000"), item("우유", "1", "1")), null).totalAmount()).isNull();
	}

	@Test
	void usesScanTimeForMissingOrFutureDatesAndPreservesPastDate() {
		for (var date : List.of(NOW.plusSeconds(1), NOW.minusDays(1), NOW)) {
			var result = normalizer.normalize(new ParsedPurchase("etc", date, null, List.of(item("두부", "1", null))), NOW);
			assertThat(result.purchasedAt()).isEqualTo(date.isAfter(NOW) ? NOW : date);
		}
		assertThat(normalize(List.of(item("두부", "1", null)), null).purchasedAt()).isEqualTo(NOW);
	}

	@Test
	void emptyResultRaisesUnrecognizable() {
		assertThatThrownBy(() -> normalize(List.of(), null)).isInstanceOfSatisfying(BusinessException.class,
			ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SCAN_UNRECOGNIZABLE));
	}

	private ParsedPurchase normalize(List<ParsedItem> items, String total) {
		return normalizer.normalize(new ParsedPurchase("etc", null, total == null ? null : new BigDecimal(total), items), NOW);
	}

	private ParsedItem item(String name, String quantity, String amount) {
		return new ParsedItem(name, new BigDecimal(quantity), (short) 2, null, amount == null ? null : new BigDecimal(amount));
	}
}
