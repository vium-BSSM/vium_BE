package com.vium.receipt.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.vium.receipt.dto.OcrLine;
import java.util.List;
import org.junit.jupiter.api.Test;

class OcrRowAssemblerTest {
	@Test
	void restoresColumnsRegardlessOfInputOrder() throws Exception {
		var rows = ParserFixtures.load("receipt-emart.json");
		assertThat(rows).hasSize(13);
		assertThat(rows.get(3).text()).isEqualTo("01 서울우유 1L 3,500 1 3,500");
		assertThat(rows.get(3).lines()).hasSize(4);
		assertThat(rows.get(3).top()).isEqualTo(220);
	}

	@Test
	void mergesExactlyHalfOverlapButNotLess() throws Exception {
		var rows = ParserFixtures.load("receipt-tilted.json");
		assertThat(rows).extracting(OcrRowAssembler.Row::text)
			.containsExactly("우유 1L 3,500", "두부 300g", "2,000");
		assertThat(rows.getFirst().lines()).hasSize(2);
	}

	@Test
	void usesShorterHeightAndIgnoresWhitespaceWithoutMutatingInput() {
		var lines = List.of(new OcrLine("  가격  ", 200, 15, 300, 25),
			new OcrLine("이름", 0, 0, 100, 20), new OcrLine("  ", 0, 100, 10, 120));
		assertThat(new OcrRowAssembler().assemble(lines)).singleElement()
			.satisfies(row -> assertThat(row.text()).isEqualTo("이름 가격"));
		assertThat(lines.getFirst().text()).isEqualTo("  가격  ");
	}

	@Test
	void handlesEmptyAndZeroHeightBoxes() {
		var assembler = new OcrRowAssembler();
		assertThat(assembler.assemble(List.of())).isEmpty();
		assertThat(assembler.assemble(List.of(new OcrLine("우유", 0, 10, 100, 10),
			new OcrLine("1000", 200, 30, 300, 30)))).hasSize(2);
	}
}
