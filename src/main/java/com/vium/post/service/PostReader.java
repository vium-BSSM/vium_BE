package com.vium.post.service;

import com.vium.post.dto.PostBasicInfo;
import com.vium.post.repository.PostQueryRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 다른 도메인(거래·채팅 등)이 게시물 기본 정보를 읽을 때 쓰는 조회 전용 서비스.
 * 삭제된 게시물은 조회되지 않는다(Optional.empty()). 존재·삭제 여부에 따른 처리(404 등)는 호출자가 정한다.
 */
@Service
@RequiredArgsConstructor
public class PostReader {

	private final PostQueryRepository postQueryRepository;

	@Transactional(readOnly = true)
	public Optional<PostBasicInfo> findBasicInfo(Long postId) {
		return postQueryRepository.findBasicInfo(postId);
	}
}
