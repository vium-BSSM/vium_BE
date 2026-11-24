package com.vium.chat.service;

import com.vium.trade.service.TradeSystemEvent;

public record TradeStatusPushEvent(Long chatRoomId, Long recipientUserId, TradeSystemEvent event) {
}
