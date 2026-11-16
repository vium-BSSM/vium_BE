package com.vium.post.dto;

import java.util.List;

public record PostListResponse(List<PostListItem> posts, String nextCursor, boolean hasNext) {
}
