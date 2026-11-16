package com.vium.post.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.image.service.ImageService;
import com.vium.post.dto.PostDetailResponse;
import com.vium.post.dto.PostUpdateCommand;
import com.vium.post.entity.Post;
import com.vium.post.entity.PostImage;
import com.vium.post.entity.PostStatus;
import com.vium.post.entity.PostType;
import com.vium.post.repository.PostDetailQueryRepository;
import com.vium.post.repository.PostImageRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostUpdateService {

	private static final int MAX_TITLE_LENGTH = 50;
	private static final int MAX_DESCRIPTION_LENGTH = 1000;
	private static final int MAX_IMAGE_COUNT = 5;

	private final PostStateService postStateService;
	private final ImageService imageService;
	private final PostImageRepository postImageRepository;
	private final PostPricingService postPricingService;
	private final PostDetailAssembler postDetailAssembler;
	private final PostDetailQueryRepository postDetailQueryRepository;
	private final Clock clock;

	@Transactional
	public PostDetailResponse update(Long userId, Long postId, PostUpdateCommand command) {
		if (command.isEmpty()) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "수정할 필드가 없습니다");
		}

		Post post = postStateService.lockForUpdate(postId);
		if (!post.isOwnedBy(userId)) {
			throw new BusinessException(ErrorCode.POST_NOT_FOUND);
		}
		if (post.getStatus() != PostStatus.OPEN) {
			throw new BusinessException(ErrorCode.POST_LOCKED);
		}
		if (postDetailQueryRepository.existsActiveTrade(postId)) {
			throw new BusinessException(ErrorCode.POST_LOCKED);
		}

		LocalDateTime now = LocalDateTime.now(clock);

		command.title().ifPresent(title -> post.changeTitle(normalizeTitle(title), now));
		command.description().ifPresent(description -> {
			if (description.length() > MAX_DESCRIPTION_LENGTH) {
				throw new BusinessException(ErrorCode.INVALID_REQUEST,
					"description은 " + MAX_DESCRIPTION_LENGTH + "자 이하여야 합니다");
			}
			post.changeDescription(description, now);
		});
		command.price().ifPresent(price -> applyPrice(post, price, now));
		command.imageIds().ifPresent(imageIds -> replaceImages(userId, post, imageIds));

		return postDetailAssembler.assemble(post, userId);
	}

	private void applyPrice(Post post, int price, LocalDateTime now) {
		if (post.getType() != PostType.SALE) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "나눔 게시물은 가격을 수정할 수 없습니다");
		}
		Integer recommendedPrice = post.getRecommendedPrice();
		if (recommendedPrice == null) {
			throw new BusinessException(ErrorCode.PRICE_OUT_OF_RANGE);
		}
		int minPrice = postPricingService.minPrice(recommendedPrice);
		int maxPrice = postPricingService.maxPrice(recommendedPrice);
		if (price < minPrice || price > maxPrice) {
			throw new BusinessException(ErrorCode.PRICE_OUT_OF_RANGE);
		}
		post.changePrice(price, now);
	}

	private void replaceImages(Long userId, Post post, List<Long> imageIds) {
		if (imageIds.size() > MAX_IMAGE_COUNT) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "imageIds는 최대 " + MAX_IMAGE_COUNT + "개까지 가능합니다");
		}
		if (new HashSet<>(imageIds).size() != imageIds.size()) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "imageIds에 중복된 값이 있습니다");
		}
		for (Long imageId : imageIds) {
			imageService.requirePostImage(userId, imageId);
		}

		postImageRepository.deleteByPostId(post.getId());
		List<PostImage> images = new ArrayList<>();
		for (int i = 0; i < imageIds.size(); i++) {
			images.add(PostImage.builder().postId(post.getId()).imageId(imageIds.get(i)).sortOrder((short) i).build());
		}
		if (!images.isEmpty()) {
			postImageRepository.saveAll(images);
		}
		// 응답 조립이 post_images를 JdbcTemplate(별도 세션 상태)로 다시 읽으므로, 반영 전에 반드시 flush한다.
		postImageRepository.flush();
	}

	private String normalizeTitle(String rawTitle) {
		String trimmed = rawTitle.trim();
		if (trimmed.isEmpty() || trimmed.length() > MAX_TITLE_LENGTH) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST,
				"title은 공백을 제외하고 1자 이상 " + MAX_TITLE_LENGTH + "자 이하여야 합니다");
		}
		return trimmed;
	}
}
