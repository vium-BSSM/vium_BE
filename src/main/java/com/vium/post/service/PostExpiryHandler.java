package com.vium.post.service;

/** 게시물을 잠근 호출자의 트랜잭션 안에서 만료 직전 정리를 수행한다. */
public interface PostExpiryHandler {
	void beforeExpire(Long postId);
}
