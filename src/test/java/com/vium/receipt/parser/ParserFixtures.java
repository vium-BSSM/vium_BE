package com.vium.receipt.parser;

import com.vium.receipt.dto.OcrLine;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

final class ParserFixtures {
	static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneOffset.UTC);

	static List<OcrRowAssembler.Row> load(String filename) throws IOException {
		try (var input = ParserFixtures.class.getResourceAsStream("/ocr/" + filename)) {
			if (input == null) throw new IOException("Missing fixture: " + filename);
			var tree = JsonMapper.builder().build().readTree(input);
			List<OcrLine> lines = new ArrayList<>();
			for (var line : tree.get("lines")) {
				lines.add(new OcrLine(line.get("text").asString(), line.get("left").asInt(),
					line.get("top").asInt(), line.get("right").asInt(), line.get("bottom").asInt()));
			}
			return new OcrRowAssembler().assemble(lines);
		}
	}

	static List<OcrRowAssembler.Row> rows(String... texts) {
		List<OcrLine> lines = new ArrayList<>();
		for (int i = 0; i < texts.length; i++) {
			lines.add(new OcrLine(texts[i], 0, i * 40, 1000, i * 40 + 20));
		}
		return new OcrRowAssembler().assemble(lines);
	}
}
