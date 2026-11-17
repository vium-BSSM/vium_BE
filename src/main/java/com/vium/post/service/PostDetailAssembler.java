package com.vium.post.service;

import com.vium.image.service.ImageService;
import com.vium.inventory.dto.IngredientDetailResponse;
import com.vium.inventory.service.InventoryService;
import com.vium.post.dto.MannerStats;
import com.vium.post.dto.PostAuthorInfo;
import com.vium.post.dto.PostDetailResponse;
import com.vium.post.dto.PostIngredientInfo;
import com.vium.post.entity.Post;
import com.vium.post.repository.PostDetailQueryRepository;
import com.vium.post.repository.PostLikeRepository;
import com.vium.user.dto.UserProfileRow;
import com.vium.user.service.UserProfileService;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 게시물 상세 응답(PostDetail)을 조립한다. PATCH 응답이 먼저 필요해 만들었고,
 * 목록/상세 GET(3단계)도 이 조립기를 그대로 재사용할 수 있다.
 * distanceMeters는 좌표가 주어질 때만 계산한다(PATCH는 좌표를 받지 않으므로 항상 null).
 */
@Service
@RequiredArgsConstructor
public class PostDetailAssembler {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final InventoryService inventoryService;
	private final UserProfileService userProfileService;
	private final ImageService imageService;
	private final PostDetailQueryRepository postDetailQueryRepository;
	private final PostLikeRepository postLikeRepository;

	/**
	 * 좌표가 주어지면 Haversine으로 distanceMeters를 계산해 채운다(목록/상세 GET이 쓴다).
	 * 좌표가 없으면 2-인자 assemble과 동일하게 distanceMeters는 null이다.
	 */
	@Transactional(readOnly = true)
	public PostDetailResponse assemble(Post post, Long viewerId, Double latitude, Double longitude) {
		PostDetailResponse base = assemble(post, viewerId);
		if (latitude == null || longitude == null) {
			return base;
		}
		int distanceMeters = haversineMeters(latitude, longitude, post.getLatitude(), post.getLongitude());
		return new PostDetailResponse(base.postId(), base.type(), base.status(), base.title(), base.description(),
			base.images(), base.author(), base.ingredient(), base.price(), distanceMeters, base.likeCount(),
			base.likedByMe(), base.isMine(), base.activeTradeId(), base.createdAt(), base.updatedAt());
	}

	private static int haversineMeters(double lat1, double lng1, double lat2, double lng2) {
		double earthRadiusMeters = 6_371_000;
		double dLat = Math.toRadians(lat2 - lat1);
		double dLng = Math.toRadians(lng2 - lng1);
		double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
			+ Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
			* Math.sin(dLng / 2) * Math.sin(dLng / 2);
		double c = 2 * Math.asin(Math.sqrt(a));
		return (int) Math.round(earthRadiusMeters * c);
	}

	@Transactional(readOnly = true)
	public PostDetailResponse assemble(Post post, Long viewerId) {
		IngredientDetailResponse ingredientDetail = inventoryService.getDetail(post.getAuthorId(), post.getInventoryItemId());
		UserProfileRow authorProfile = userProfileService.getProfileView(post.getAuthorId());
		MannerStats mannerStats = postDetailQueryRepository.findMannerStats(post.getAuthorId());

		PostAuthorInfo author = new PostAuthorInfo(
			authorProfile.id(),
			authorProfile.displayName(),
			imageService.findProfileImageUrl(authorProfile.profileImageId()),
			mannerStats.mannerScore(),
			mannerStats.reviewCount());

		PostIngredientInfo ingredient = new PostIngredientInfo(
			ingredientDetail.inventoryItemId(),
			ingredientDetail.ingredientCatalogId(),
			ingredientDetail.name(),
			ingredientDetail.categoryName(),
			ingredientDetail.remainingQuantity(),
			ingredientDetail.unitId(),
			ingredientDetail.unit(),
			ingredientDetail.expiresOn(),
			ingredientDetail.storageMethodId(),
			ingredientDetail.storageMethodName(),
			post.getIsOpened());

		Long activeTradeId = postDetailQueryRepository.findActiveTradeId(post.getId(), viewerId);
		boolean likedByMe = postLikeRepository.existsByUserIdAndPostId(viewerId, post.getId());

		return new PostDetailResponse(
			post.getId(),
			post.getType().name(),
			post.getStatus().name(),
			post.getTitle(),
			post.getDescription(),
			postDetailQueryRepository.findImages(post.getId()),
			author,
			ingredient,
			post.getPrice(),
			null,
			post.getLikeCount(),
			likedByMe,
			post.isOwnedBy(viewerId),
			activeTradeId,
			post.getCreatedAt().atZone(SEOUL).toOffsetDateTime(),
			post.getUpdatedAt().atZone(SEOUL).toOffsetDateTime());
	}
}
