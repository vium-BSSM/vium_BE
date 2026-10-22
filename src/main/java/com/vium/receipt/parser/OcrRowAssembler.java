package com.vium.receipt.parser;

import com.vium.receipt.dto.OcrLine;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class OcrRowAssembler {

	public List<Row> assemble(List<OcrLine> lines) {
		var sorted = lines.stream().filter(line -> !line.text().isBlank())
			.sorted(Comparator.comparingDouble((OcrLine line) -> ((long) line.top() + line.bottom()) / 2.0)
				.thenComparingInt(OcrLine::left)).toList();
		List<Row> rows = new ArrayList<>();
		List<OcrLine> current = new ArrayList<>();
		int top = 0;
		int bottom = 0;
		for (var line : sorted) {
			long overlap = Math.min(bottom, line.bottom()) - (long) Math.max(top, line.top());
			long minHeight = Math.min((long) bottom - top, (long) line.bottom() - line.top());
			// 높이 0인 상자는 같은 y 좌표일 때만 묶는다.
			boolean sameRow = minHeight == 0 ? top == line.top() && bottom == line.bottom()
				: overlap > 0 && overlap * 2 >= minHeight;
			if (!current.isEmpty() && !sameRow) {
				rows.add(row(current, top));
				current.clear();
			}
			if (current.isEmpty()) {
				top = line.top();
				bottom = line.bottom();
			} else {
				top = Math.min(top, line.top());
				bottom = Math.max(bottom, line.bottom());
			}
			current.add(line);
		}
		if (!current.isEmpty()) {
			rows.add(row(current, top));
		}
		return List.copyOf(rows);
	}

	private Row row(List<OcrLine> lines, int top) {
		var ordered = lines.stream().sorted(Comparator.comparingInt(OcrLine::left)).toList();
		return new Row(ordered.stream().map(line -> line.text().strip())
			.collect(Collectors.joining(" ")), ordered, top);
	}

	public record Row(String text, List<OcrLine> lines, int top) {
		public Row {
			lines = List.copyOf(lines);
		}
	}
}
