package com.vium.dispose.dto;

import java.util.List;

public record GetConsumptionEventsResponse(
	List<ConsumptionEventDto> events
) {
}
