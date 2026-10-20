package de.afeher.slots.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeRangeTest {

	@Test
	void rejectsEndNotAfterBegin() {
		assertThatThrownBy(() -> range("10:00", "10:00"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("'end'");
		assertThatThrownBy(() -> range("10:00", "09:00")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsMissingBounds() {
		assertThatThrownBy(() -> new TimeRange(null, at("10:00"))).isInstanceOf(NullPointerException.class);
	}

	@Test
	void overlappingRangesOverlap() {
		assertThat(range("09:00", "10:00").overlaps(range("09:30", "11:00"))).isTrue();
		assertThat(range("09:00", "12:00").overlaps(range("10:00", "11:00"))).isTrue();
	}

	@Test
	void backToBackRangesDoNotOverlap() {
		assertThat(range("09:00", "10:00").overlaps(range("10:00", "11:00"))).isFalse();
		assertThat(range("10:00", "11:00").overlaps(range("09:00", "10:00"))).isFalse();
	}

	@Test
	void clipCutsRangeToWindow() {
		assertThat(range("08:00", "10:00").clip(range("09:00", "18:00"))).contains(range("09:00", "10:00"));
		assertThat(range("17:00", "19:00").clip(range("09:00", "18:00"))).contains(range("17:00", "18:00"));
		assertThat(range("10:00", "11:00").clip(range("09:00", "18:00"))).contains(range("10:00", "11:00"));
	}

	@Test
	void clipOutsideWindowIsEmpty() {
		assertThat(range("07:00", "09:00").clip(range("09:00", "18:00"))).isEmpty();
	}

	static TimeRange range(String begin, String end) {
		return new TimeRange(at(begin), at(end));
	}

	static Instant at(String time) {
		return Instant.parse("2026-11-02T" + time + ":00Z");
	}

}
