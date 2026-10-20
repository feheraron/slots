package de.afeher.slots.web;

import de.afeher.slots.domain.Slot;
import de.afeher.slots.domain.Status;
import de.afeher.slots.domain.TimeRange;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

public record SlotRequest(@NotNull @Positive Long userId, @NotNull Instant begin, @NotNull Instant end, Status status) {

	public SlotRequest {
		status = status == null ? Status.FREE : status;
	}

	Slot toSlot() {
		return Slot.create(userId, new TimeRange(begin, end), status);
	}

}
