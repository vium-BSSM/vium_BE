package com.vium.post.dto;

public record PostLikeResponse(Long postId, boolean liked, int likeCount) {
}
