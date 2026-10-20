package de.afeher.slots.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

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

	public Optional<TimeRange> clip(TimeRange window) {
		if (!overlaps(window)) {
			return Optional.empty();
		}
		Instant clippedBegin = begin.isAfter(window.begin) ? begin : window.begin;
		Instant clippedEnd = end.isBefore(window.end) ? end : window.end;
		return Optional.of(new TimeRange(clippedBegin, clippedEnd));
	}

}
