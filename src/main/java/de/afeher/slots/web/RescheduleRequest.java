package de.afeher.slots.web;

import de.afeher.slots.domain.Status;
import de.afeher.slots.domain.TimeRange;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record RescheduleRequest(@NotNull Instant begin, @NotNull Instant end, @NotNull Status status) {

	TimeRange range() {
		return new TimeRange(begin, end);
	}

}
