package com.vium.notification.dto;

import java.time.LocalDate;

public record ConsumptionCheckKeepResponse(Long inventoryItemId, LocalDate keepConfirmedExpiresOn) {
}
