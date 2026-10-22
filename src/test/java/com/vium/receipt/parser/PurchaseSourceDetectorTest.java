package com.vium.receipt.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.vium.receipt.entity.PurchaseType;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PurchaseSourceDetectorTest {
	@ParameterizedTest
	@CsvSource({"홈플러스 BISCUIT,etc", "롯데마트 CUSTOMER COPY,etc", "홈플러스 시장로 123,etc",
		"롯데마트 시장길 20,etc", "주소: 동네시장 1층,etc", "CU 서울점,convenience",
		"(CU),convenience", "cu강남점,convenience", "CU STORE,convenience",
		"동네시장,market", "광장시장 식품점,market"})
	void respectsStoreBoundariesAndIgnoresMarketAddresses(String text, String expected) {
		assertThat(new PurchaseSourceDetector().detect(ParserFixtures.rows(text), PurchaseType.RECEIPT)).isEqualTo(expected);
	}

	@ParameterizedTest
	@CsvSource({"이마트24,convenience", "이 마 트,emart", "EMART,emart", "emart,emart",
		"쿠팡,coupang", "Coupang,coupang", "GS25,convenience", "CU,convenience",
		"세븐일레븐,convenience", "7-ELEVEN,convenience", "미니스톱,convenience", "동네시장,market"})
	void detectsStoresInPriorityOrder(String text, String expected) {
		assertThat(new PurchaseSourceDetector().detect(ParserFixtures.rows(text), PurchaseType.RECEIPT)).isEqualTo(expected);
	}

	@ParameterizedTest
	@CsvSource({"RECEIPT,etc", "ONLINE_ORDER,online"})
	void fallsBackByPurchaseType(PurchaseType type, String expected) {
		assertThat(new PurchaseSourceDetector().detect(ParserFixtures.rows("알 수 없는 매장"), type)).isEqualTo(expected);
	}
}
