package de.afeher.slots.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MeetingTest {

	@Test
	void keepsParticipantsOrderedAndImmutable() {
		Meeting meeting = new Meeting("Review", null, Set.of(3L, 1L, 2L));

		assertThat(meeting.participants()).containsExactly(1L, 2L, 3L);
		assertThatThrownBy(() -> meeting.participants().add(4L)).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void missingParticipantsAreEmpty() {
		assertThat(new Meeting("Review", null, null).participants()).isEmpty();
	}

	@Test
	void rejectsBlankTitle() {
		assertThatThrownBy(() -> new Meeting(" ", null, Set.of()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("'title'");
	}

	@Test
	void rejectsTooLongTitleAndDescription() {
		assertThatThrownBy(() -> new Meeting("t".repeat(Meeting.TITLE_MAX + 1), null, Set.of()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("'title'");
		assertThatThrownBy(() -> new Meeting("Review", "d".repeat(Meeting.DESCRIPTION_MAX + 1), Set.of()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("'description'");
	}

	@Test
	void rejectsNonPositiveParticipant() {
		assertThatThrownBy(() -> new Meeting("Review", null, Set.of(0L)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("'participants'");
	}

}
