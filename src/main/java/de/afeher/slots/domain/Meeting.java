package de.afeher.slots.domain;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

public record Meeting(String title, String description, Set<Long> participants) {

	public static final int TITLE_MAX = 255;
	public static final int DESCRIPTION_MAX = 1000;

	public Meeting {
		if (title == null || title.isBlank()) {
			throw new IllegalArgumentException("'title' must not be blank");
		}
		if (title.length() > TITLE_MAX) {
			throw new IllegalArgumentException("'title' must be at most " + TITLE_MAX + " characters");
		}
		if (description != null && description.length() > DESCRIPTION_MAX) {
			throw new IllegalArgumentException("'description' must be at most " + DESCRIPTION_MAX + " characters");
		}
		if (participants != null && participants.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("'participants' must not contain null");
		}
		participants = participants == null ? Set.of() : Collections.unmodifiableSet(new TreeSet<>(participants));
		if (participants.stream().anyMatch(participant -> participant <= 0)) {
			throw new IllegalArgumentException("'participants' must be positive user ids");
		}
	}

}
