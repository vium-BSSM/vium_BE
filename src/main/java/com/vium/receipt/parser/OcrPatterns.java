package com.vium.receipt.parser;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class OcrPatterns {

	private static final String NUMBER = "-?(?:\\d{1,3}(?:,\\d{3})+|\\d+)";
	private static final Pattern MONEY = Pattern.compile("(?<![\\p{L}\\d,.-])(" + NUMBER + ")(?:\\s*원)?(?![\\p{L}\\d,.-])");
	private static final Pattern WON = Pattern.compile("(?<![\\d,.-])(" + NUMBER + ")\\s*원");
	private static final Pattern TRAILING = Pattern.compile("(?:^|\\s)(" + NUMBER + ")(?:\\s*원)?$");
	private static final Pattern DATE = Pattern.compile(
		"(?<!\\d)(20\\d{2}|\\d{2})\\s*[-./년]\\s*(\\d{1,2})\\s*[-./월]\\s*(\\d{1,2})(?!\\d)"
			+ "(?:일)?(?:\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?");
	private static final Pattern HEADER = Pattern.compile("상품명|품명|단가|수량|금액");
	private static final Pattern LETTER = Pattern.compile("[가-힣a-zA-Z]");
	private static final Pattern DIGIT = Pattern.compile("\\d");
	private static final Pattern LONG_DIGITS = Pattern.compile("\\d{8,}");
	private static final String[] RECEIPT_EXCLUSIONS = {
		"봉투", "쇼핑백", "종량제", "할인", "쿠폰", "포인트", "적립", "면세", "과세", "부가세", "부가가치세",
		"합계", "총액", "소계", "결제", "카드", "승인", "할부", "거스름", "현금", "받을금액", "받은금액",
		"영수증", "교환", "환불", "사업자", "대표", "전화", "TEL", "주소", "POS", "계산원"
	};
	private static final String[] ONLINE_EXCLUSIONS = {
		"배송", "도착", "주문상세", "장바구니", "리뷰", "교환", "반품", "취소", "쿠폰", "할인", "적립",
		"결제", "배송비", "판매자", "문의", "재구매", "담기"
	};

	private OcrPatterns() {
	}

	static String compact(String text) {
		return text.replaceAll("\\s+", "");
	}

	static boolean hasLetters(String text) {
		return LETTER.matcher(text).find();
	}

	static boolean hasDigits(String text) {
		return DIGIT.matcher(text).find();
	}

	static boolean hasDate(String text) {
		return DATE.matcher(text).find();
	}

	static boolean excluded(String text, boolean receipt) {
		String normalized = compact(text).toUpperCase(Locale.ROOT);
		for (String word : receipt ? RECEIPT_EXCLUSIONS : ONLINE_EXCLUSIONS) {
			if (normalized.contains(word)) return true;
		}
		return false;
	}

	static boolean receiptTotal(String text) {
		return compact(text).matches(".*(?:합계|총액|결제금액|받을금액|총구매액).*");
	}

	static boolean receiptEnd(String text) {
		return receiptTotal(text) || compact(text).contains("소계");
	}

	static boolean onlineTotal(String text) {
		return compact(text).matches(".*(?:총결제금액|결제금액|총상품금액).*");
	}

	static boolean header(String text) {
		var matcher = HEADER.matcher(text);
		return matcher.find() && matcher.find();
	}

	static BigDecimal number(String token) {
		String digits = token.replace(",", "").replace("-", "");
		if (digits.length() >= 13 || LONG_DIGITS.matcher(token).find()) return null;
		return new BigDecimal(token.replace(",", ""));
	}

	static boolean hasWonAmount(String text) {
		return WON.matcher(text).find();
	}

	static BigDecimal lastAmount(String text, boolean requireWon) {
		var matcher = (requireWon ? WON : MONEY).matcher(text);
		BigDecimal last = null;
		while (matcher.find()) {
			var value = number(matcher.group(1));
			if (value != null) last = value;
		}
		return last;
	}

	static Tail trailingNumbers(String text) {
		String remaining = text.strip();
		List<BigDecimal> values = new ArrayList<>();
		while (values.size() < 3) {
			var matcher = TRAILING.matcher(remaining);
			if (!matcher.find()) break;
			BigDecimal value = number(matcher.group(1));
			if (value == null) break;
			values.add(value);
			remaining = remaining.substring(0, matcher.start()).stripTrailing();
		}
		Collections.reverse(values);
		return new Tail(remaining, List.copyOf(values));
	}

	static String withoutBarcodes(String text) {
		return String.join(" ", List.of(text.strip().split("\\s+")).stream()
			.filter(token -> !token.matches(NUMBER) || number(token) != null).toList());
	}

	static LocalDateTime firstDate(List<String> texts, boolean shortYear, Clock clock) {
		var now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"));
		for (String text : texts) {
			var matcher = DATE.matcher(text);
			while (matcher.find()) {
				if (matcher.group(1).length() == 2 && !shortYear) continue;
				// 짧은 연도는 영수증의 구분자(-./) 표기만 허용한다.
				if (matcher.group(1).length() == 2 && matcher.group().matches(".*[년월].*")) continue;
				try {
					int year = Integer.parseInt(matcher.group(1));
					if (year < 100) year += 2000;
					var date = LocalDateTime.of(year, Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)),
						part(matcher.group(4)), part(matcher.group(5)), part(matcher.group(6)));
					if (!date.isAfter(now)) return date;
				} catch (DateTimeException ignored) {
					// OCR의 잘못된 날짜는 다음 후보로 넘어간다.
				}
			}
		}
		return null;
	}

	private static int part(String value) {
		return value == null ? 0 : Integer.parseInt(value);
	}

	record Tail(String name, List<BigDecimal> numbers) {
	}
}
