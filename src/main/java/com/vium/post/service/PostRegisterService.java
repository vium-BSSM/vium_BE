package com.vium.post.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.image.service.ImageService;
import com.vium.inventory.dto.IngredientDetailResponse;
import com.vium.inventory.service.InventoryService;
import com.vium.post.dto.PostCreateRequest;
import com.vium.post.dto.PostCreateResponse;
import com.vium.post.dto.PostIneligibleReason;
import com.vium.post.dto.PostPriceEstimate;
import com.vium.post.entity.Post;
import com.vium.post.entity.PostImage;
import com.vium.post.entity.PostStatus;
import com.vium.post.entity.PostType;
import com.vium.post.repository.PostImageRepository;
import com.vium.post.repository.PostRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostRegisterService {

	private static final int MAX_TITLE_LENGTH = 50;

	private final InventoryService inventoryService;
	private final ImageService imageService;
	private final PostEligibilityService postEligibilityService;
	private final PostPricingService postPricingService;
	private final PostRepository postRepository;
	private final PostImageRepository postImageRepository;
	private final Clock clock;

	@Transactional
	public PostCreateResponse register(Long userId, PostCreateRequest request) {
		String title = normalizeTitle(request.title());
		String description = request.description() == null ? "" : request.description();
		List<Long> imageIds = request.imageIds() == null ? List.of() : request.imageIds();
		if (new HashSet<>(imageIds).size() != imageIds.size()) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "imageIds에 중복된 값이 있습니다");
		}

		IngredientDetailResponse detail = inventoryService.getDetail(userId, request.inventoryItemId());

		List<PostIneligibleReason> reasons = postEligibilityService.evaluate(detail);
		if (!reasons.isEmpty()) {
			throw new BusinessException(ErrorCode.POST_NOT_ALLOWED, ineligibleMessage(reasons.get(0)));
		}

		Optional<PostPriceEstimate> estimate = postPricingService.estimate(
			detail.ingredientCatalogId(), detail.unitId(), detail.remainingQuantity(), detail.expiresOn());
		boolean saleAllowed = estimate.isPresent() && estimate.get().recommendedPrice() > 0;
		Integer recommendedPrice = estimate.map(PostPriceEstimate::recommendedPrice).orElse(null);

		int storedPrice = resolvePrice(request.type(), request.price(), saleAllowed, estimate);

		for (Long imageId : imageIds) {
			imageService.requirePostImage(userId, imageId);
		}

		inventoryService.updateStorageMethod(userId, request.inventoryItemId(), request.storageMethodId());

		LocalDateTime now = LocalDateTime.now(clock);
		Post post = Post.builder()
			.authorId(userId)
			.inventoryItemId(request.inventoryItemId())
			.type(request.type())
			.status(PostStatus.OPEN)
			.title(title)
			.description(description)
			.price(storedPrice)
			.recommendedPrice(recommendedPrice)
			.isOpened(request.isOpened())
			.latitude(request.latitude())
			.longitude(request.longitude())
			.createdAt(now)
			.updatedAt(now)
			.build();
		Post saved = postRepository.save(post);

		List<PostImage> images = new ArrayList<>();
		for (int i = 0; i < imageIds.size(); i++) {
			images.add(PostImage.builder().postId(saved.getId()).imageId(imageIds.get(i)).sortOrder((short) i).build());
		}
		if (!images.isEmpty()) {
			postImageRepository.saveAll(images);
		}

		return new PostCreateResponse(saved.getId(), saved.getStatus().name());
	}

	private int resolvePrice(PostType type, Integer requestedPrice, boolean saleAllowed,
			Optional<PostPriceEstimate> estimate) {
		if (type == PostType.SALE) {
			if (!saleAllowed) {
				throw new BusinessException(ErrorCode.POST_NOT_ALLOWED, "이 재료는 나눔으로만 올릴 수 있습니다.");
			}
			if (requestedPrice == null) {
				throw new BusinessException(ErrorCode.INVALID_REQUEST, "판매가를 입력해 주세요");
			}
			PostPriceEstimate priceEstimate = estimate.orElseThrow();
			if (requestedPrice < priceEstimate.minPrice() || requestedPrice > priceEstimate.maxPrice()) {
				throw new BusinessException(ErrorCode.PRICE_OUT_OF_RANGE);
			}
			return requestedPrice;
		}
		if (requestedPrice != null && requestedPrice != 0) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "나눔 게시물은 가격을 지정할 수 없습니다");
		}
		return 0;
	}

	private String normalizeTitle(String rawTitle) {
		String trimmed = rawTitle == null ? "" : rawTitle.trim();
		if (trimmed.isEmpty() || trimmed.length() > MAX_TITLE_LENGTH) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST,
				"title은 공백을 제외하고 1자 이상 " + MAX_TITLE_LENGTH + "자 이하여야 합니다");
		}
		return trimmed;
	}

	private String ineligibleMessage(PostIneligibleReason reason) {
		return switch (reason) {
			case ALREADY_USED -> "이미 일부 사용한 재료는 게시할 수 없습니다.";
			case NOT_ACTIVE -> "보유 중인 재료만 게시할 수 있습니다.";
			case ALREADY_POSTED -> "이미 게시된 재료입니다.";
			case EXPIRES_TOO_SOON -> "소비기한이 1일 이상 남은 재료만 게시할 수 있습니다.";
			case NOT_IN_CATALOG -> "카탈로그에서 선택한 재료만 게시할 수 있습니다.";
			case PROHIBITED_ITEM -> "게시할 수 없는 품목입니다.";
		};
	}
}
