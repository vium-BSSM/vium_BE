package com.vium.receipt.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ItemNameQuantityParserTest {
	@ParameterizedTest
	@CsvSource({
		"대란 30구,1,30,1", "두부 300g,1,300,2", "감자 1kg,2,2,3",
		"콜라 355ml*6,1,2130,4", "양파,3,3,1", "우유 1L,2,2,5",
		"우유 1.5리터×2,3,9,5", "감자 2 KG,2,4,3", "음료 200mL,2,400,4",
		"두부 300G 2팩,1,300,2", "계란 10개입 X 2,2,40,1",
		"만두 3봉,2,6,7", "두부 2팩,3,6,6", "주스 2병,2,4,8",
		"참치 3캔,2,6,9", "김치 2통,2,4,10", "과일 5알,1,5,1",
		"과자 6입,1,6,1", "양파 2개,2,4,1", "양파 x3,2,2,1"
	})
	void convertsCapacityOrPackCountBeforePurchaseQuantity(String name, String purchased, String expected, short unit) {
		var result = new ItemNameQuantityParser().parse(name, new BigDecimal(purchased));
		assertThat(result.quantity()).isEqualByComparingTo(expected);
		assertThat(result.unitId()).isEqualTo(unit);
	}
}
