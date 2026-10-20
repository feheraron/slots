package de.afeher.slots.domain;

import java.util.Objects;

public record Slot(Long id, long userId, TimeRange range, Status status, Meeting meeting, int version) {

	public Slot {
		if (userId <= 0) {
			throw new IllegalArgumentException("'userId' must be positive");
		}
		Objects.requireNonNull(range, "range");
		Objects.requireNonNull(status, "status");
		if (meeting != null && status != Status.BUSY) {
			throw new IllegalArgumentException("A slot with a meeting must be busy");
		}
	}

	public static Slot create(long userId, TimeRange range, Status status) {
		return new Slot(null, userId, range, status, null, 0);
	}

	public boolean hasMeeting() {
		return meeting != null;
	}

	public Slot reschedule(TimeRange range, Status status) {
		requireNoMeeting();
		return new Slot(id, userId, range, status, null, version);
	}

	public void ensureDeletable() {
		requireNoMeeting();
	}

	public Slot book(Meeting meeting) {
		Objects.requireNonNull(meeting, "meeting");
		if (hasMeeting()) {
			throw new SlotConflictException("Slot " + id + " already holds a meeting");
		}
		if (status == Status.BUSY) {
			throw new SlotConflictException("Slot " + id + " is busy");
		}
		return new Slot(id, userId, range, Status.BUSY, meeting, version);
	}

	public Slot editMeeting(Meeting meeting) {
		Objects.requireNonNull(meeting, "meeting");
		if (!hasMeeting()) {
			throw new SlotConflictException("Slot " + id + " holds no meeting");
		}
		return new Slot(id, userId, range, status, meeting, version);
	}

	private void requireNoMeeting() {
		if (hasMeeting()) {
			throw new SlotConflictException("Slot " + id + " holds a meeting, cancel the meeting first");
		}
	}

	public Slot cancelMeeting() {
		if (!hasMeeting()) {
			throw new SlotConflictException("Slot " + id + " holds no meeting");
		}
		return new Slot(id, userId, range, Status.FREE, null, version);
	}

}
