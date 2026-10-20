package de.afeher.slots.web;

import com.jayway.jsonpath.JsonPath;
import de.afeher.slots.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SlotApiIT {

	static final long OWNER = 1001;

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		jdbc.sql("delete from slot").update();
	}

	@Test
	void createAndGet() {
		MvcTestResult created = create(slot(OWNER, "09:00", "10:00", null));

		assertThat(created).hasStatus(HttpStatus.CREATED);
		long id = id(created);
		assertThat(created.getResponse().getHeader("Location")).endsWith("/api/slots/" + id);
		MvcTestResult fetched = mvc.get().uri("/api/slots/{id}", id).exchange();
		assertThat(fetched).hasStatusOk();
		assertThat(fetched).bodyJson().extractingPath("$.userId").isEqualTo(1001);
		assertThat(fetched).bodyJson().extractingPath("$.begin").isEqualTo("2026-11-02T09:00:00Z");
		assertThat(fetched).bodyJson().extractingPath("$.end").isEqualTo("2026-11-02T10:00:00Z");
		assertThat(fetched).bodyJson().extractingPath("$.status").isEqualTo("FREE");
		assertThat(fetched).bodyJson().extractingPath("$.meeting").isNull();
	}

	@Test
	void createsBusySlot() {
		MvcTestResult created = create(slot(OWNER, "09:00", "10:00", "BUSY"));

		assertThat(created).bodyJson().extractingPath("$.status").isEqualTo("BUSY");
	}

	@Test
	void overlappingSlotConflicts() {
		create(slot(OWNER, "09:00", "10:00", null));

		MvcTestResult overlapping = create(slot(OWNER, "09:30", "10:30", null));

		assertThat(overlapping).hasStatus(HttpStatus.CONFLICT);
		assertThat(overlapping).bodyJson().extractingPath("$.detail").asString().contains("overlaps");
	}

	@Test
	void invalidSlotsAreRejected() {
		assertThat(create(slot(OWNER, "10:00", "10:00", null))).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(create(slot(0, "09:00", "10:00", null))).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(create(slot(OWNER, "09:00", "10:00", "MAYBE"))).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(create("{\"userId\":1001,\"begin\":\"2026-11-02T09:00:00Z\"}")).hasStatus(HttpStatus.BAD_REQUEST);
	}

	@Test
	void unknownSlotIsNotFound() {
		assertThat(mvc.get().uri("/api/slots/{id}", 1).exchange()).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void rescheduleMovesSlotAndChangesStatus() {
		long id = id(create(slot(OWNER, "09:00", "10:00", null)));

		MvcTestResult moved = reschedule(id, range("10:00", "11:30", "BUSY"));

		assertThat(moved).hasStatusOk();
		assertThat(moved).bodyJson().extractingPath("$.begin").isEqualTo("2026-11-02T10:00:00Z");
		assertThat(moved).bodyJson().extractingPath("$.end").isEqualTo("2026-11-02T11:30:00Z");
		assertThat(moved).bodyJson().extractingPath("$.status").isEqualTo("BUSY");
		assertThat(moved).bodyJson().extractingPath("$.userId").isEqualTo(1001);
	}

	@Test
	void rescheduleOntoAnotherSlotConflicts() {
		create(slot(OWNER, "09:00", "10:00", null));
		long id = id(create(slot(OWNER, "10:00", "11:00", null)));

		assertThat(reschedule(id, range("09:30", "11:00", "FREE"))).hasStatus(HttpStatus.CONFLICT);
	}

	@Test
	void rescheduleValidatesRequest() {
		long id = id(create(slot(OWNER, "09:00", "10:00", null)));

		assertThat(reschedule(id, range("10:00", "09:00", "FREE"))).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(reschedule(id, "{\"begin\":\"%s\",\"end\":\"%s\"}".formatted(at("09:00"), at("10:00"))))
				.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(reschedule(id + 1, range("09:00", "10:00", "FREE"))).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void deleteRemovesSlot() {
		long id = id(create(slot(OWNER, "09:00", "10:00", null)));

		assertThat(delete("/api/slots/{id}", id)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(mvc.get().uri("/api/slots/{id}", id).exchange()).hasStatus(HttpStatus.NOT_FOUND);
		assertThat(delete("/api/slots/{id}", id)).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void bookingFreeSlotCreatesMeeting() {
		long id = id(create(slot(OWNER, "09:00", "10:00", null)));

		MvcTestResult booked = book(id, meeting("Design review", 1003, 1002));

		assertThat(booked).hasStatus(HttpStatus.CREATED);
		assertThat(booked.getResponse().getHeader("Location")).endsWith("/api/slots/" + id);
		assertThat(booked).bodyJson().extractingPath("$.status").isEqualTo("BUSY");
		assertThat(booked).bodyJson().extractingPath("$.meeting.title").isEqualTo("Design review");
		assertThat(booked).bodyJson().extractingPath("$.meeting.description").isEqualTo("Agenda");
		assertThat(booked).bodyJson().extractingPath("$.meeting.participants").asArray().containsExactly(1002, 1003);
	}

	@Test
	void bookingMeetingSlotAgainConflicts() {
		long id = id(create(slot(OWNER, "09:00", "10:00", null)));
		book(id, meeting("Design review", 1002));

		MvcTestResult again = book(id, meeting("Retro", 1004));

		assertThat(again).hasStatus(HttpStatus.CONFLICT);
		assertThat(again).bodyJson().extractingPath("$.detail").asString().contains("already holds a meeting");
		assertThat(mvc.get().uri("/api/slots/{id}", id).exchange())
				.bodyJson().extractingPath("$.meeting.title").isEqualTo("Design review");
	}

	@Test
	void editingMeetingReplacesIt() {
		long id = id(create(slot(OWNER, "09:00", "10:00", null)));
		book(id, meeting("Design review", 1002));

		MvcTestResult edited = edit(id, meeting("Retro", 1004));

		assertThat(edited).hasStatusOk();
		assertThat(edited).bodyJson().extractingPath("$.status").isEqualTo("BUSY");
		assertThat(edited).bodyJson().extractingPath("$.meeting.title").isEqualTo("Retro");
		assertThat(edited).bodyJson().extractingPath("$.meeting.participants").asArray().containsExactly(1004);
	}

	@Test
	void editingWithoutMeetingConflicts() {
		long id = id(create(slot(OWNER, "09:00", "10:00", null)));

		assertThat(edit(id, meeting("Retro"))).hasStatus(HttpStatus.CONFLICT);
		assertThat(edit(id + 1, meeting("Retro"))).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void bookingBusySlotConflicts() {
		long id = id(create(slot(OWNER, "09:00", "10:00", "BUSY")));

		assertThat(book(id, meeting("Design review"))).hasStatus(HttpStatus.CONFLICT);
	}

	@Test
	void bookingValidatesMeeting() {
		long id = id(create(slot(OWNER, "09:00", "10:00", null)));

		assertThat(book(id, "{\"participants\":[1002]}")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(book(id, meeting("x".repeat(256)))).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(book(id + 1, meeting("Design review"))).hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void meetingCannotBeRescheduled() {
		long id = id(create(slot(OWNER, "09:00", "10:00", null)));
		book(id, meeting("Design review"));

		MvcTestResult moved = reschedule(id, range("10:00", "11:00", "BUSY"));

		assertThat(moved).hasStatus(HttpStatus.CONFLICT);
		assertThat(moved).bodyJson().extractingPath("$.detail").asString().contains("cancel the meeting first");
	}

	@Test
	void cancellingMeetingFreesSlot() {
		long id = id(create(slot(OWNER, "09:00", "10:00", null)));
		book(id, meeting("Design review", 1002));

		assertThat(delete("/api/slots/{id}/meeting", id)).hasStatus(HttpStatus.NO_CONTENT);

		MvcTestResult fetched = mvc.get().uri("/api/slots/{id}", id).exchange();
		assertThat(fetched).bodyJson().extractingPath("$.status").isEqualTo("FREE");
		assertThat(fetched).bodyJson().extractingPath("$.meeting").isNull();
		assertThat(delete("/api/slots/{id}/meeting", id)).hasStatus(HttpStatus.CONFLICT);
	}

	@Test
	void deletingMeetingSlotConflicts() {
		long id = id(create(slot(OWNER, "09:00", "10:00", null)));
		book(id, meeting("Design review"));

		MvcTestResult deleted = delete("/api/slots/{id}", id);

		assertThat(deleted).hasStatus(HttpStatus.CONFLICT);
		assertThat(deleted).bodyJson().extractingPath("$.detail").asString().contains("cancel the meeting first");
		assertThat(mvc.get().uri("/api/slots/{id}", id).exchange()).hasStatusOk();
	}

	@Test
	void listsUserSlotsInWindow() {
		create(slot(OWNER, "13:00", "14:00", "BUSY"));
		create(slot(OWNER, "09:00", "10:00", null));
		create(slot(OWNER, "06:00", "07:00", null));
		create(slot(OWNER + 1, "09:00", "10:00", null));

		MvcTestResult all = list("/api/slots", "08:00", "18:00", null);
		MvcTestResult busy = list("/api/slots", "08:00", "18:00", "BUSY");

		assertThat(all).hasStatusOk();
		assertThat(all).bodyJson().extractingPath("$[*].begin").asArray()
				.containsExactly(at("09:00"), at("13:00"));
		assertThat(busy).bodyJson().extractingPath("$[*].begin").asArray().containsExactly(at("13:00"));
	}

	@Test
	void blockViewMergesSlotsInWindow() {
		create(slot(OWNER, "07:00", "09:00", null));
		create(slot(OWNER, "09:00", "10:00", null));
		long meeting = id(create(slot(OWNER, "10:00", "11:00", null)));
		book(meeting, meeting("Design review"));
		create(slot(OWNER, "11:00", "12:00", "BUSY"));
		create(slot(OWNER, "14:00", "15:00", null));

		MvcTestResult view = list("/api/slots/block-view", "08:00", "18:00", null);

		assertThat(view).hasStatusOk();
		assertThat(view).bodyJson().isEqualTo("""
				[{"begin":"2026-11-02T08:00:00Z","end":"2026-11-02T10:00:00Z","status":"FREE"},
				 {"begin":"2026-11-02T10:00:00Z","end":"2026-11-02T12:00:00Z","status":"BUSY"},
				 {"begin":"2026-11-02T14:00:00Z","end":"2026-11-02T15:00:00Z","status":"FREE"}]""");
	}

	@Test
	void queriesValidateWindow() {
		for (String uri : new String[] {"/api/slots", "/api/slots/block-view"}) {
			assertThat(list(uri, "10:00", "09:00", null)).hasStatus(HttpStatus.BAD_REQUEST);
			assertThat(mvc.get().uri(uri).param("userId", "1001").param("from", at("09:00"))
					.param("to", "2026-12-04T09:00:00Z").exchange()).hasStatus(HttpStatus.BAD_REQUEST);
			assertThat(mvc.get().uri(uri).param("userId", "0").param("from", at("09:00"))
					.param("to", at("10:00")).exchange()).hasStatus(HttpStatus.BAD_REQUEST);
			assertThat(mvc.get().uri(uri).param("userId", "1001").exchange()).hasStatus(HttpStatus.BAD_REQUEST);
		}
	}

	MvcTestResult create(String json) {
		return mvc.post().uri("/api/slots").contentType(MediaType.APPLICATION_JSON).content(json).exchange();
	}

	MvcTestResult reschedule(long id, String json) {
		return mvc.put().uri("/api/slots/{id}", id).contentType(MediaType.APPLICATION_JSON).content(json).exchange();
	}

	MvcTestResult book(long id, String json) {
		return mvc.post().uri("/api/slots/{id}/meeting", id)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json)
				.exchange();
	}

	MvcTestResult edit(long id, String json) {
		return mvc.put().uri("/api/slots/{id}/meeting", id)
				.contentType(MediaType.APPLICATION_JSON)
				.content(json)
				.exchange();
	}

	MvcTestResult list(String uri, String from, String to, String status) {
		var request = mvc.get().uri(uri).param("userId", String.valueOf(OWNER)).param("from", at(from))
				.param("to", at(to));
		if (status != null) {
			request = request.param("status", status);
		}
		return request.exchange();
	}

	MvcTestResult delete(String uri, long id) {
		return mvc.delete().uri(uri, id).exchange();
	}

	static String range(String begin, String end, String status) {
		return "{\"begin\":\"%s\",\"end\":\"%s\",\"status\":\"%s\"}".formatted(at(begin), at(end), status);
	}

	static String meeting(String title, long... participants) {
		String ids = Arrays.stream(participants)
				.mapToObj(String::valueOf)
				.collect(Collectors.joining(","));
		return "{\"title\":\"%s\",\"description\":\"Agenda\",\"participants\":[%s]}".formatted(title, ids);
	}

	static String slot(long userId, String begin, String end, String status) {
		String statusField = status == null ? "" : ",\"status\":\"" + status + "\"";
		return "{\"userId\":%d,\"begin\":\"%s\",\"end\":\"%s\"%s}"
				.formatted(userId, at(begin), at(end), statusField);
	}

	static String at(String time) {
		return "2026-11-02T" + time + ":00Z";
	}

	static long id(MvcTestResult result) {
		try {
			Number id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
			return id.longValue();
		}
		catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

}
