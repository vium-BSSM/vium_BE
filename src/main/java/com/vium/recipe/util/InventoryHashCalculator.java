package com.vium.recipe.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.stream.Collectors;

public class InventoryHashCalculator {

	/**
	 * 사용자 재료 구성의 SHA-256 해시를 계산합니다.
	 *
	 * @param ingredientCatalogIds 사용자가 보유한 재료 카탈로그 ID 목록
	 * @return SHA-256 해시값 (hex 64자)
	 */
	public static String calculateHash(Collection<Long> ingredientCatalogIds) {
		String sortedIds = ingredientCatalogIds.stream()
			.sorted()
			.map(String::valueOf)
			.collect(Collectors.joining(","));

		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(sortedIds.getBytes(StandardCharsets.UTF_8));
			return bytesToHex(hash);
		} catch (Exception e) {
			throw new RuntimeException("해시 계산 실패", e);
		}
	}

	private static String bytesToHex(byte[] bytes) {
		StringBuilder hexString = new StringBuilder();
		for (byte b : bytes) {
			String hex = Integer.toHexString(0xff & b);
			if (hex.length() == 1) hexString.append('0');
			hexString.append(hex);
		}
		return hexString.toString();
	}
}
