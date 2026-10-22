package com.vium.receipt.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.vium.receipt.dto.ParsedItem;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReceiptTextParserTest {
	private final ReceiptTextParser parser = new ReceiptTextParser(ParserFixtures.CLOCK);

	@Test
	void parsesEmartColumnsDiscountAndTotalWithoutBagOrPaymentInformation() throws Exception {
		var result = parser.parse(ParserFixtures.load("receipt-emart.json"));
		assertThat(result.purchaseSourceCode()).isEqualTo("emart");
		assertThat(result.purchasedAt()).isEqualTo(LocalDateTime.of(2026, 10, 20, 18, 32));
		assertThat(result.totalAmount()).isEqualByComparingTo("22100");
		assertThat(result.items()).extracting(ParsedItem::rawName)
			.containsExactly("서울우유 1L", "대란 30구", "두부 300g", "감자 1kg");
		assertItem(result.items().get(0), "1", 5, "3500", "3500");
		assertItem(result.items().get(1), "30", 1, "9000", "9000");
		assertItem(result.items().get(2), "300", 2, "2000", "1500");
		assertItem(result.items().get(3), "2", 3, "4000", "8000");
	}

	@Test
	void parsesTwoLineItemsAndDiscardsBarcodes() throws Exception {
		var result = parser.parse(ParserFixtures.load("receipt-two-line.json"));
		assertThat(result.purchasedAt()).isEqualTo(LocalDateTime.of(2026, 10, 19, 10, 20, 30));
		assertThat(result.items()).extracting(ParsedItem::rawName).containsExactly("깐마늘", "양파");
		assertItem(result.items().getFirst(), "2", 1, "3000", "6000");
		assertItem(result.items().getLast(), "1", 1, "1500", "1500");
		assertThat(result.totalAmount()).isEqualByComparingTo("7500");
	}

	@ParameterizedTest
	@CsvSource({"서울우유 1L,1,5", "두부 300g,300,2", "대란 30구,30,1"})
	void parsesTwoLineNamesContainingPackageNumbers(String name, String quantity, int unit) {
		var result = parser.parse(ParserFixtures.rows(name, "8801234567890 3,500 1 3,500"));
		assertThat(result.items()).singleElement().satisfies(item -> {
			assertThat(item.rawName()).isEqualTo(name);
			assertItem(item, quantity, unit, "3500", "3500");
		});
	}

	@Test
	void doesNotMergeCompletedItemWithFollowingNumericLine() {
		var result = parser.parse(ParserFixtures.rows("우유 3500", "8801234567890 2000 1 2000"));
		assertThat(result.items()).singleElement().satisfies(item -> assertItem(item, "1", 1, null, "3500"));
	}

	@Test
	void parsesConvenienceItemsAndKeepsPackMultiplier() throws Exception {
		var result = parser.parse(ParserFixtures.load("receipt-convenience.json"));
		assertThat(result.purchaseSourceCode()).isEqualTo("convenience");
		assertThat(result.items()).hasSize(2);
		assertItem(result.items().getFirst(), "2130", 4, null, "6000");
		assertItem(result.items().getLast(), "2", 1, null, "5000");
	}

	@ParameterizedTest
	@CsvSource({"우유 3 9000,3,,9000", "우유 3000 9000,1,3000,9000",
		"우유 3000 0 9000,1,3000,9000", "우유 3000 100 9000,1,3000,9000",
		"우유 3000 99 9000,99,3000,9000", "우유 3000원,1,,3000", "우유 3000 원,1,,3000"})
	void appliesTrailingNumberRules(String line, String quantity, String unitPrice, String amount) {
		assertThat(parser.parse(ParserFixtures.rows(line)).items()).singleElement()
			.satisfies(item -> assertItem(item, quantity, 1, unitPrice, amount));
	}

	@Test
	void discountsOnlyPrecedingItemAndClampsAtZero() {
		var result = parser.parse(ParserFixtures.rows("할인 500", "우유 1000", "-200", "할인 900",
			"두부 2000", "봉투 100", "할인 100", "합계 2000", "양파 5000"));
		assertThat(result.items()).hasSize(2);
		assertThat(result.items().getFirst().amount()).isEqualByComparingTo("0");
		assertThat(result.items().getLast().amount()).isEqualByComparingTo("2000");
	}

	@Test
	void startsAfterHeaderStopsAtSubtotalAndChoosesLargestTotal() {
		var result = parser.parse(ParserFixtures.rows("잘못된 상호 100", "상품명 단가 수량 금액",
			"01 #우유 1000", "소계 1000", "양파 5000", "합계 1000", "결제금액 1200", "받을금액 1100"));
		assertThat(result.items()).singleElement().satisfies(item -> assertThat(item.rawName()).isEqualTo("우유"));
		assertThat(result.totalAmount()).isEqualByComparingTo("1200");
	}

	@ParameterizedTest
	@ValueSource(strings = {"봉투", "쇼핑백", "종량제", "쿠폰", "포인트", "적립", "면세", "과세", "부가세", "부가가치세",
		"결제", "카드", "승인", "할부", "거스름", "현금", "받은금액", "영수증", "교환", "환불", "사업자", "대표", "전화", "TEL", "주소", "POS", "계산원"})
	void excludesNonItemLines(String keyword) {
		assertThat(parser.parse(ParserFixtures.rows(keyword + " 1000")).items()).isEmpty();
	}

	@Test
	void removesDecorativeSymbolsWithoutLosingPackQuantity() {
		var result = parser.parse(ParserFixtures.rows("# 01 *콜라 355ml*6* 6000"));
		assertThat(result.items()).singleElement().satisfies(item -> {
			assertThat(item.rawName()).isEqualTo("콜라 355ml×6");
			assertThat(item.quantity()).isEqualByComparingTo("2130");
		});
	}

	@Test
	void returnsNoItemsForUnrelatedText() throws Exception {
		var result = parser.parse(ParserFixtures.load("not-a-receipt.json"));
		assertThat(result.items()).isEmpty();
		assertThat(result.purchasedAt()).isNull();
		assertThat(result.totalAmount()).isNull();
	}

	private void assertItem(ParsedItem item, String quantity, int unit, String unitPrice, String amount) {
		assertThat(item.quantity()).isEqualByComparingTo(quantity);
		assertThat(item.unitId()).isEqualTo((short) unit);
		if (unitPrice == null) assertThat(item.unitPrice()).isNull();
		else assertThat(item.unitPrice()).isEqualByComparingTo(unitPrice);
		assertThat(item.amount()).isEqualByComparingTo(amount);
	}
}
