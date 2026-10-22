package com.vium.receipt.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.vium.receipt.dto.ParsedItem;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OnlineOrderTextParserTest {
	private final OnlineOrderTextParser parser = new OnlineOrderTextParser(ParserFixtures.CLOCK);

	@Test
	void parsesCoupangCardsDiscountedPriceQuantityAndCancellation() throws Exception {
		var result = parser.parse(ParserFixtures.load("online-coupang.json"));
		assertThat(result.purchaseSourceCode()).isEqualTo("coupang");
		assertThat(result.purchasedAt()).isEqualTo(LocalDateTime.of(2026, 10, 19, 0, 0));
		assertThat(result.totalAmount()).isEqualByComparingTo("15000");
		assertThat(result.items()).extracting(ParsedItem::rawName)
			.containsExactly("서울우유 멸균우유 1L 2팩", "친환경 감자 1kg");
		assertThat(result.items().getFirst().quantity()).isEqualByComparingTo("2");
		assertThat(result.items().getFirst().unitId()).isEqualTo((short) 5);
		assertThat(result.items().getFirst().amount()).isEqualByComparingTo("7000");
		assertThat(result.items().getLast().quantity()).isEqualByComparingTo("2");
		assertThat(result.items().getLast().unitId()).isEqualTo((short) 3);
		assertThat(result.items()).allSatisfy(item -> assertThat(item.unitPrice()).isNull());
	}

	@Test
	void usesOnlineFallbackAndDateOnRowAfterOrderLabel() throws Exception {
		var result = parser.parse(ParserFixtures.load("online-unknown-mall.json"));
		assertThat(result.purchaseSourceCode()).isEqualTo("online");
		assertThat(result.purchasedAt()).isEqualTo(LocalDateTime.of(2026, 10, 18, 0, 0));
		assertThat(result.items()).singleElement().satisfies(item -> {
			assertThat(item.rawName()).isEqualTo("양파 1봉");
			assertThat(item.quantity()).isEqualByComparingTo("2");
			assertThat(item.unitId()).isEqualTo((short) 7);
		});
		assertThat(result.totalAmount()).isEqualByComparingTo("3000");
	}

	@ParameterizedTest
	@ValueSource(strings = {"취소완료", "반품완료", "취소됨"})
	void removesOnlyMostRecentItemEvenWithRepeatedCancellation(String status) {
		var result = parser.parse(ParserFixtures.rows("우유", "1000원", "두부", "2000원", status, status, "2개"));
		assertThat(result.items()).singleElement().satisfies(item -> {
			assertThat(item.rawName()).isEqualTo("우유");
			assertThat(item.quantity()).isEqualByComparingTo("1");
		});
	}

	@Test
	void buffersAtMostTwoNameRowsAndDoesNotMultiplyTwiceOnRepeatedCounts() {
		var result = parser.parse(ParserFixtures.rows("광고", "두부", "300g", "2000원", "2개", "3개"));
		assertThat(result.items()).singleElement().satisfies(item -> {
			assertThat(item.rawName()).isEqualTo("두부 300g");
			assertThat(item.quantity()).isEqualByComparingTo("900");
		});
	}

	@ParameterizedTest
	@ValueSource(strings = {"배송", "도착", "주문상세", "장바구니", "리뷰", "교환", "반품", "취소", "쿠폰", "할인", "적립",
		"결제", "배송비", "판매자", "문의", "재구매", "담기"})
	void ignoresButtonAndShippingPrices(String keyword) {
		var result = parser.parse(ParserFixtures.rows(keyword + " 500원"));
		assertThat(result.items()).isEmpty();
	}

	@Test
	void invalidPriceDoesNotBecomeNextProductName() {
		var result = parser.parse(ParserFixtures.rows("우유", "12345678원", "두부", "2000원"));
		assertThat(result.items()).singleElement().satisfies(item -> assertThat(item.rawName()).isEqualTo("두부"));
	}

	@Test
	void ignoresPricesWithoutNamesAndUnrelatedText() throws Exception {
		assertThat(parser.parse(ParserFixtures.rows("3000원", "2개")).items()).isEmpty();
		assertThat(parser.parse(ParserFixtures.load("not-a-receipt.json")).items()).isEmpty();
	}
}
