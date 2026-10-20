package de.afeher.slots.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static de.afeher.slots.domain.Status.BUSY;
import static de.afeher.slots.domain.Status.FREE;
import static de.afeher.slots.domain.TimeRangeTest.range;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlotTest {

	private static final Meeting MEETING = new Meeting("Review", "Agenda", Set.of(2L, 3L));

	@Test
	void createdSlotIsNewWithoutMeeting() {
		Slot slot = Slot.create(1, range("09:00", "10:00"), FREE);

		assertThat(slot.id()).isNull();
		assertThat(slot.version()).isZero();
		assertThat(slot.hasMeeting()).isFalse();
	}

	@Test
	void rejectsNonPositiveUser() {
		assertThatThrownBy(() -> Slot.create(0, range("09:00", "10:00"), FREE))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("'userId'");
	}

	@Test
	void rejectsFreeSlotWithMeeting() {
		assertThatThrownBy(() -> new Slot(1L, 1, range("09:00", "10:00"), FREE, MEETING, 0))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rescheduleChangesRangeAndStatus() {
		Slot slot = stored(FREE).reschedule(range("11:00", "12:00"), BUSY);

		assertThat(slot.range()).isEqualTo(range("11:00", "12:00"));
		assertThat(slot.status()).isEqualTo(BUSY);
		assertThat(slot.id()).isEqualTo(7L);
		assertThat(slot.version()).isEqualTo(4);
	}

	@Test
	void rescheduleOfMeetingConflicts() {
		Slot meeting = stored(FREE).book(MEETING);

		assertThatThrownBy(() -> meeting.reschedule(range("11:00", "12:00"), BUSY))
				.isInstanceOf(SlotConflictException.class)
				.hasMessageContaining("cancel the meeting first");
	}

	@Test
	void bookingFreeSlotMakesItBusy() {
		Slot slot = stored(FREE).book(MEETING);

		assertThat(slot.status()).isEqualTo(BUSY);
		assertThat(slot.meeting()).isEqualTo(MEETING);
	}

	@Test
	void bookingBusySlotConflicts() {
		assertThatThrownBy(() -> stored(BUSY).book(MEETING))
				.isInstanceOf(SlotConflictException.class)
				.hasMessageContaining("is busy");
	}

	@Test
	void bookingMeetingAgainConflicts() {
		Slot meeting = stored(FREE).book(MEETING);

		assertThatThrownBy(() -> meeting.book(MEETING))
				.isInstanceOf(SlotConflictException.class)
				.hasMessageContaining("already holds a meeting");
	}

	@Test
	void cancellingMeetingFreesSlot() {
		Slot slot = stored(FREE).book(MEETING).cancelMeeting();

		assertThat(slot.status()).isEqualTo(FREE);
		assertThat(slot.hasMeeting()).isFalse();
	}

	@Test
	void cancellingWithoutMeetingConflicts() {
		assertThatThrownBy(() -> stored(BUSY).cancelMeeting())
				.isInstanceOf(SlotConflictException.class)
				.hasMessageContaining("holds no meeting");
	}

	private static Slot stored(Status status) {
		return new Slot(7L, 1, range("09:00", "10:00"), status, null, 4);
	}

}
