package de.afeher.slots.web;

import de.afeher.slots.domain.Meeting;
import de.afeher.slots.domain.Slot;
import de.afeher.slots.domain.Status;

import java.time.Instant;

public record SlotResponse(long id, long userId, Instant begin, Instant end, Status status, Meeting meeting) {

	static SlotResponse of(Slot slot) {
		return new SlotResponse(slot.id(), slot.userId(), slot.range().begin(), slot.range().end(), slot.status(),
				slot.meeting());
	}

}
