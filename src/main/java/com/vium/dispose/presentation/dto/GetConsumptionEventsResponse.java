package com.vium.dispose.presentation.dto;

import java.util.List;

public record GetConsumptionEventsResponse(
	List<ConsumptionEventDto> events
) {
}
