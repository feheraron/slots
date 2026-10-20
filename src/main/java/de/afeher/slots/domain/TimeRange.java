package de.afeher.slots.domain;

import java.time.Instant;
import java.util.Objects;

public record TimeRange(Instant begin, Instant end) {

	public TimeRange {
		Objects.requireNonNull(begin, "begin");
		Objects.requireNonNull(end, "end");
		if (!end.isAfter(begin)) {
			throw new IllegalArgumentException("'end' must be after 'begin'");
		}
	}

	public boolean overlaps(TimeRange other) {
		return begin.isBefore(other.end) && other.begin.isBefore(end);
	}

}
