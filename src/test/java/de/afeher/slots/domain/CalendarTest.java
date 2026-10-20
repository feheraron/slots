package de.afeher.slots.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static de.afeher.slots.domain.Status.BUSY;
import static de.afeher.slots.domain.Status.FREE;
import static de.afeher.slots.domain.TimeRangeTest.range;
import static org.assertj.core.api.Assertions.assertThat;

class CalendarTest {

	private static final TimeRange WINDOW = range("08:00", "18:00");

	@Test
	void noSlotsIsNoBlocks() {
		assertThat(Calendar.of(WINDOW, List.of())).isEmpty();
	}

	@Test
	void touchingSlotsWithSameStatusMerge() {
		List<Block> blocks = Calendar.of(WINDOW, List.of(slot("10:00", "11:00", FREE), slot("09:00", "10:00", FREE)));

		assertThat(blocks).containsExactly(block("09:00", "11:00", FREE));
	}

	@Test
	void differentStatusesStaySeparate() {
		List<Block> blocks = Calendar.of(WINDOW, List.of(slot("09:00", "10:00", FREE), slot("10:00", "11:00", BUSY)));

		assertThat(blocks).containsExactly(block("09:00", "10:00", FREE), block("10:00", "11:00", BUSY));
	}

	@Test
	void gapSeparatesSlotsWithSameStatus() {
		List<Block> blocks = Calendar.of(WINDOW, List.of(slot("09:00", "10:00", BUSY), slot("11:00", "12:00", BUSY)));

		assertThat(blocks).containsExactly(block("09:00", "10:00", BUSY), block("11:00", "12:00", BUSY));
	}

	@Test
	void meetingCountsAsBusy() {
		Slot meeting = slot("10:00", "11:00", FREE).book(new Meeting("Review", null, Set.of()));

		List<Block> blocks = Calendar.of(WINDOW, List.of(slot("09:00", "10:00", BUSY), meeting));

		assertThat(blocks).containsExactly(block("09:00", "11:00", BUSY));
	}

	@Test
	void slotsAreClippedToWindow() {
		List<Block> blocks = Calendar.of(WINDOW,
				List.of(slot("07:00", "09:00", FREE), slot("17:00", "19:00", BUSY), slot("19:00", "20:00", FREE)));

		assertThat(blocks).containsExactly(block("08:00", "09:00", FREE), block("17:00", "18:00", BUSY));
	}

	private static Slot slot(String begin, String end, Status status) {
		return Slot.create(1, range(begin, end), status);
	}

	private static Block block(String begin, String end, Status status) {
		return new Block(range(begin, end), status);
	}

}
