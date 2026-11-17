package com.vium.post.service;

import com.vium.global.exception.BusinessException;
import com.vium.global.exception.ErrorCode;
import com.vium.post.dto.PostCursor;
import com.vium.post.dto.PostListItem;
import com.vium.post.dto.PostListResponse;
import com.vium.post.dto.PostSort;
import com.vium.post.entity.PostType;
import com.vium.post.repository.PostListQueryRepository;
import com.vium.safety.service.UserBlockService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PostListService {

	private static final int DEFAULT_SIZE = 20;
	private static final int MAX_SIZE = 50;
	private static final int DEFAULT_DISTANCE = 3000;
	private static final Set<Integer> ALLOWED_DISTANCES = Set.of(1000, 3000, 5000, 10000);

	private final PostListQueryRepository postListQueryRepository;
	private final UserBlockService userBlockService;
	private final PostCursorCodec postCursorCodec;
	private final Clock clock;

	@Transactional(readOnly = true)
	public PostListResponse list(Long userId, Double latitude, Double longitude, Integer distance, PostSort sort,
			PostType type, Long ingredientCatalogId, String cursor, Integer size) {
		if (latitude == null || longitude == null) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "latitude, longitude는 필수입니다");
		}

		int effectiveDistance = distance == null ? DEFAULT_DISTANCE : distance;
		if (!ALLOWED_DISTANCES.contains(effectiveDistance)) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "distance는 1000|3000|5000|10000 중 하나여야 합니다");
		}

		int effectiveSize = size == null ? DEFAULT_SIZE : size;
		if (effectiveSize < 1 || effectiveSize > MAX_SIZE) {
			throw new BusinessException(ErrorCode.INVALID_REQUEST, "size는 1~" + MAX_SIZE + " 사이여야 합니다");
		}

		PostSort effectiveSort = sort == null ? PostSort.DISTANCE : sort;
		PostCursor decodedCursor = (cursor == null || cursor.isBlank())
			? null : postCursorCodec.decode(cursor, effectiveSort);

		Set<Long> excludedAuthorIds = userBlockService.findRelatedUserIds(userId);
		LocalDate today = LocalDate.now(clock);

		List<PostListItem> rows = postListQueryRepository.findPosts(userId, latitude, longitude, effectiveDistance,
			effectiveSort, type, ingredientCatalogId, excludedAuthorIds, today, decodedCursor, effectiveSize + 1);

		boolean hasNext = rows.size() > effectiveSize;
		List<PostListItem> page = hasNext ? rows.subList(0, effectiveSize) : rows;

		String nextCursor = null;
		if (hasNext) {
			PostListItem last = page.get(page.size() - 1);
			PostCursor lastCursor = new PostCursor(last.distanceMeters(), last.createdAt(), last.postId());
			nextCursor = postCursorCodec.encode(effectiveSort, lastCursor);
		}

		return new PostListResponse(page, nextCursor, hasNext);
	}
}
