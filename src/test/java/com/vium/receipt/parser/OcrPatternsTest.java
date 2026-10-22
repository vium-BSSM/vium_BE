package com.vium.receipt.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OcrPatternsTest {
	@ParameterizedTest
	@ValueSource(strings = {"2026-10-20 18:32:10", "2026/10/20 18:32:10", "2026.10.20 18:32:10",
		"2026년 10월 20일 18:32:10", "26-10-20 18:32:10"})
	void parsesSupportedDates(String date) {
		assertThat(OcrPatterns.firstDate(List.of(date), true, ParserFixtures.CLOCK))
			.isEqualTo(LocalDateTime.of(2026, 10, 20, 18, 32, 10));
	}

	@Test
	void skipsFutureAndInvalidDatesAndUsesSeoulTimeEvenWithUtcClock() {
		assertThat(OcrPatterns.firstDate(List.of("2027-01-01", "2026-02-30", "2026-10-22 09:01",
			"2026-10-22 08:59", "2026-10-20"), true, ParserFixtures.CLOCK))
			.isEqualTo(LocalDateTime.of(2026, 10, 22, 8, 59));
		assertThat(OcrPatterns.firstDate(List.of("2026-10-22 09:00"), true, ParserFixtures.CLOCK))
			.isEqualTo(LocalDateTime.of(2026, 10, 22, 9, 0));
		assertThat(OcrPatterns.firstDate(List.of("2026-10-20 25:00", "2026-10-19"), true, ParserFixtures.CLOCK))
			.isEqualTo(LocalDateTime.of(2026, 10, 19, 0, 0));
	}

	@Test
	void shortYearIsReceiptOnlyAndDoesNotMatchInsideLongNumbers() {
		assertThat(OcrPatterns.firstDate(List.of("26/10/20"), false, ParserFixtures.CLOCK)).isNull();
		assertThat(OcrPatterns.firstDate(List.of("1232026/10/20", "2026/10/200"), true, ParserFixtures.CLOCK)).isNull();
	}

	@ParameterizedTest
	@CsvSource(value = {"3,500|3500", "-1,500|-1500", "100,000,000|100000000", "9999999|9999999"}, delimiter = '|')
	void readsIntegerAmounts(String input, String expected) {
		assertThat(OcrPatterns.number(input)).isEqualByComparingTo(expected);
	}

	@ParameterizedTest
	@ValueSource(strings = {"12345678", "8801234567890", "1,234,567,890,123"})
	void excludesBarcodes(String input) {
		assertThat(OcrPatterns.number(input)).isNull();
	}

	@Test
	void headerNeedsTwoNonOverlappingLabels() {
		assertThat(OcrPatterns.header("상품명")).isFalse();
		assertThat(OcrPatterns.header("상품명 금액")).isTrue();
	}

	@Test
	void amountMatcherDoesNotSplitBarcodesDatesOrCapacityIntoMoney() {
		assertThat(OcrPatterns.lastAmount("우유 1L 3,500원", false)).isEqualByComparingTo("3500");
		assertThat(OcrPatterns.lastAmount("2026-10-20 300g 8801234567890", false)).isNull();
		assertThat(OcrPatterns.lastAmount("8,000원 7,000원", true)).isEqualByComparingTo("7000");
	}
}
