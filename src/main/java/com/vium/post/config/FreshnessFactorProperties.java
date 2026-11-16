package com.vium.post.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 추천가 계산의 신선도 계수. 명세에 공식이 없어 제안값을 기본으로 둔다(확정되면 설정값만 바꾸면 된다).
 * 남은 일수 D: D>=7 -> daysAtLeast7, 4~6 -> days4To6, 2~3 -> days2To3, 1 이하 -> day1OrLess.
 */
@ConfigurationProperties("post.pricing.freshness-factor")
public record FreshnessFactorProperties(
	@DefaultValue("1.0") BigDecimal daysAtLeast7,
	@DefaultValue("0.9") BigDecimal days4To6,
	@DefaultValue("0.8") BigDecimal days2To3,
	@DefaultValue("0.6") BigDecimal day1OrLess
) {
}
