package de.afeher.slots.web;

import de.afeher.slots.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static de.afeher.slots.web.SlotApiIT.OWNER;
import static de.afeher.slots.web.SlotApiIT.id;
import static de.afeher.slots.web.SlotApiIT.meeting;
import static de.afeher.slots.web.SlotApiIT.range;
import static de.afeher.slots.web.SlotApiIT.slot;
import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ConcurrencyIT {

	private static final int CONTENDERS = 8;
	private static final int REPETITIONS = 5;

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void clean() {
		jdbc.sql("delete from slot").update();
	}

	@RepeatedTest(REPETITIONS)
	void overlappingCreatesLetExactlyOneSlotIn() {
		List<Integer> statuses = concurrently(Collections.nCopies(CONTENDERS,
				() -> send(mvc.post().uri("/api/slots"), slot(OWNER, "09:00", "10:00", null))));

		assertThat(statuses).containsExactlyInAnyOrderElementsOf(oneWinner(CONTENDERS, 201, 409));
		assertThat(jdbc.sql("select count(*) from slot").query(Long.class).single()).isEqualTo(1);
	}

	@RepeatedTest(REPETITIONS)
	void overlappingCreatesAreRejectedAsOverlapsNotDeadlocks() {
		List<Supplier<String>> requests = IntStream.range(0, 4 * CONTENDERS)
				.<Supplier<String>>mapToObj(i -> () -> {
					MvcTestResult result = mvc.post().uri("/api/slots")
							.contentType(MediaType.APPLICATION_JSON)
							.content(slot(OWNER, i % 2 == 0 ? "09:00" : "09:30", i % 2 == 0 ? "10:00" : "10:30", null))
							.exchange();
					return result.getResponse().getStatus() + " " + body(result);
				})
				.toList();

		List<String> outcomes = concurrently(requests);

		assertThat(outcomes).filteredOn(outcome -> outcome.startsWith("201")).hasSize(1);
		assertThat(outcomes).filteredOn(outcome -> !outcome.startsWith("201"))
				.allSatisfy(outcome -> assertThat(outcome).startsWith("409").contains("overlaps"));
	}

	@RepeatedTest(REPETITIONS)
	void markingBusyAndBookingHaveOneWinningKind() {
		long id = createFreeSlot();
		List<Supplier<Integer>> requests = new ArrayList<>();
		for (int i = 0; i < CONTENDERS / 2; i++) {
			requests.add(() -> send(mvc.put().uri("/api/slots/{id}", id), range("09:00", "10:00", "BUSY")));
			requests.add(() -> send(mvc.post().uri("/api/slots/{id}/meeting", id), meeting("Design review", 1002)));
		}

		List<Integer> statuses = concurrently(requests);

		List<Integer> markBusy = everyOther(statuses, 0);
		List<Integer> booking = everyOther(statuses, 1);
		assertThat(markBusy).isSubsetOf(200, 409);
		assertThat(booking).isSubsetOf(201, 409);
		String title = jdbc.sql("select title from slot where id = :id")
				.param("id", id)
				.query(String.class)
				.optional()
				.orElse(null);
		if (title == null) {
			assertThat(markBusy).contains(200);
			assertThat(booking).containsOnly(409);
		}
		else {
			assertThat(booking).containsExactlyInAnyOrderElementsOf(oneWinner(CONTENDERS / 2, 201, 409));
			assertThat(markBusy).containsOnly(409);
		}
	}

	@RepeatedTest(REPETITIONS)
	void concurrentBookingsLetExactlyOneMeetingIn() {
		long id = createFreeSlot();
		List<Supplier<Integer>> requests = IntStream.range(0, CONTENDERS)
				.<Supplier<Integer>>mapToObj(i -> () -> send(mvc.post().uri("/api/slots/{id}/meeting", id),
						meeting("Meeting " + i, 2000 + i)))
				.toList();

		List<Integer> statuses = concurrently(requests);

		assertThat(statuses).containsExactlyInAnyOrderElementsOf(oneWinner(CONTENDERS, 201, 409));
		var row = jdbc.sql("select title, participants[1] as participant from slot where id = :id")
				.param("id", id)
				.query()
				.singleRow();
		String number = ((String) row.get("title")).substring("Meeting ".length());
		assertThat(((Number) row.get("participant")).longValue()).isEqualTo(2000 + Long.parseLong(number));
	}

	@RepeatedTest(REPETITIONS)
	void bookingAndDeletingHaveOneWinner() {
		long id = createFreeSlot();
		List<Supplier<Integer>> requests = new ArrayList<>();
		for (int i = 0; i < CONTENDERS / 2; i++) {
			requests.add(() -> send(mvc.post().uri("/api/slots/{id}/meeting", id), meeting("Design review", 1002)));
			requests.add(() -> mvc.delete().uri("/api/slots/{id}", id).exchange().getResponse().getStatus());
		}

		List<Integer> statuses = concurrently(requests);

		assertThat(statuses).isSubsetOf(201, 204, 404, 409);
		assertThat(statuses.stream().filter(status -> status == 201 || status == 204)).hasSize(1);
		boolean exists = jdbc.sql("select count(*) from slot where id = :id").param("id", id).query(Long.class)
				.single() == 1;
		assertThat(exists).isEqualTo(statuses.contains(201));
	}

	private long createFreeSlot() {
		return id(mvc.post().uri("/api/slots")
				.contentType(MediaType.APPLICATION_JSON)
				.content(slot(OWNER, "09:00", "10:00", null))
				.exchange());
	}

	private static int send(MockMvcTester.MockMvcRequestBuilder request, String json) {
		MvcTestResult result = request.contentType(MediaType.APPLICATION_JSON).content(json).exchange();
		return result.getResponse().getStatus();
	}

	private static List<Integer> everyOther(List<Integer> statuses, int first) {
		return IntStream.iterate(first, i -> i < statuses.size(), i -> i + 2).mapToObj(statuses::get).toList();
	}

	private static List<Integer> oneWinner(int contenders, int winner, int loser) {
		List<Integer> expected = new ArrayList<>(Collections.nCopies(contenders - 1, loser));
		expected.add(winner);
		return expected;
	}

	private static String body(MvcTestResult result) {
		try {
			return result.getResponse().getContentAsString();
		}
		catch (UnsupportedEncodingException e) {
			throw new IllegalStateException(e);
		}
	}

	private static <T> List<T> concurrently(List<Supplier<T>> requests) {
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(requests.size())) {
			List<Future<T>> futures = requests.stream()
					.map(request -> executor.submit(() -> {
						start.await();
						return request.get();
					}))
					.toList();
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get());
			}
			return results;
		}
		catch (InterruptedException | ExecutionException e) {
			throw new IllegalStateException(e);
		}
	}

}
