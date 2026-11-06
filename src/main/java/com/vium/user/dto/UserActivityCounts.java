package com.vium.user.dto;

public record UserActivityCounts(long activeToday, long activeLast7Days, long registered, long signupsToday) {
}
