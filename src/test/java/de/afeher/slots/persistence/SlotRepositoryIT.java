package de.afeher.slots.persistence;

import de.afeher.slots.TestcontainersConfiguration;
import de.afeher.slots.domain.Meeting;
import de.afeher.slots.domain.Slot;
import de.afeher.slots.domain.Status;
import de.afeher.slots.domain.TimeRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.util.Set;

import static de.afeher.slots.domain.Status.BUSY;
import static de.afeher.slots.domain.Status.FREE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SlotRepositoryIT {

	private static final long USER = 1001;

	@Autowired
	SlotRepository repository;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		jdbc.sql("delete from slot").update();
	}

	@Test
	void roundTripsSlotWithMeeting() {
		Slot slot = repository.insert(Slot.create(USER, range("09:00", "10:00"), FREE));
		Slot booked = repository.update(slot.book(new Meeting("Review", "Agenda", Set.of(1003L, 1002L)))).orElseThrow();

		assertThat(repository.findById(slot.id())).contains(booked);
		assertThat(booked.version()).isEqualTo(1);
		assertThat(booked.meeting().participants()).containsExactly(1002L, 1003L);
	}

	@Test
	void findByIdOfUnknownSlotIsEmpty() {
		assertThat(repository.findById(-1)).isEmpty();
	}

	@Test
	void rejectsOverlappingSlotOfSameUser() {
		repository.insert(Slot.create(USER, range("09:00", "10:00"), FREE));

		assertThatThrownBy(() -> repository.insert(Slot.create(USER, range("09:30", "10:30"), BUSY)))
				.isInstanceOf(SlotOverlapException.class);
	}

	@Test
	void allowsBackToBackAndOtherUsersSlots() {
		repository.insert(Slot.create(USER, range("09:00", "10:00"), FREE));
		repository.insert(Slot.create(USER, range("10:00", "11:00"), FREE));
		repository.insert(Slot.create(USER + 1, range("09:00", "10:00"), FREE));

		assertThat(jdbc.sql("select count(*) from slot").query(Long.class).single()).isEqualTo(3);
	}

	@Test
	void rejectsOverlapOnUpdate() {
		repository.insert(Slot.create(USER, range("09:00", "10:00"), FREE));
		Slot other = repository.insert(Slot.create(USER, range("10:00", "11:00"), FREE));

		assertThatThrownBy(() -> repository.update(other.reschedule(range("09:30", "11:00"), FREE)))
				.isInstanceOf(SlotOverlapException.class);
	}

	@Test
	void staleVersionDoesNotUpdate() {
		Slot slot = repository.insert(Slot.create(USER, range("09:00", "10:00"), FREE));
		repository.update(slot.reschedule(slot.range(), BUSY)).orElseThrow();

		assertThat(repository.update(slot.reschedule(range("11:00", "12:00"), FREE))).isEmpty();
		assertThat(repository.findById(slot.id()).orElseThrow().status()).isEqualTo(BUSY);
	}

	@Test
	void findsUserSlotsInWindowOrderedByBegin() {
		repository.insert(Slot.create(USER, range("13:00", "14:00"), BUSY));
		repository.insert(Slot.create(USER, range("09:00", "10:00"), FREE));
		repository.insert(Slot.create(USER, range("07:00", "08:00"), FREE));
		repository.insert(Slot.create(USER + 1, range("09:00", "10:00"), FREE));

		assertThat(repository.findByUser(USER, range("08:00", "18:00"), null))
				.extracting(Slot::range)
				.containsExactly(range("09:00", "10:00"), range("13:00", "14:00"));
		assertThat(repository.findByUser(USER, range("08:00", "18:00"), Status.BUSY))
				.extracting(Slot::range)
				.containsExactly(range("13:00", "14:00"));
	}

	@Test
	void deleteRemovesSlot() {
		Slot slot = repository.insert(Slot.create(USER, range("09:00", "10:00"), FREE));

		assertThat(repository.delete(slot)).isTrue();
		assertThat(repository.findById(slot.id())).isEmpty();
		assertThat(repository.delete(slot)).isFalse();
	}

	@Test
	void staleVersionDoesNotDelete() {
		Slot slot = repository.insert(Slot.create(USER, range("09:00", "10:00"), FREE));
		repository.update(slot.book(new Meeting("Review", null, Set.of()))).orElseThrow();

		assertThat(repository.delete(slot)).isFalse();
		assertThat(repository.findById(slot.id())).isPresent();
	}

	private static TimeRange range(String begin, String end) {
		return new TimeRange(Instant.parse("2026-11-02T" + begin + ":00Z"), Instant.parse("2026-11-02T" + end + ":00Z"));
	}

}
