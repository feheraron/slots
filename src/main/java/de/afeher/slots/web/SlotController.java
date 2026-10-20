package de.afeher.slots.web;

import de.afeher.slots.domain.Calendar;
import de.afeher.slots.domain.Slot;
import de.afeher.slots.domain.SlotConflictException;
import de.afeher.slots.domain.Status;
import de.afeher.slots.domain.TimeRange;
import de.afeher.slots.persistence.SlotRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;

@RestController
@RequestMapping("/api/slots")
public class SlotController {

	static final Duration MAX_WINDOW = Duration.ofDays(31);

	private final SlotRepository repository;

	public SlotController(SlotRepository repository) {
		this.repository = repository;
	}

	@PostMapping
	public ResponseEntity<SlotResponse> create(@Valid @RequestBody SlotRequest request) {
		Slot slot = repository.insert(request.toSlot());
		var location = ServletUriComponentsBuilder.fromCurrentRequest()
				.path("/{id}")
				.buildAndExpand(slot.id())
				.toUri();
		return ResponseEntity.created(location).body(SlotResponse.of(slot));
	}

	@GetMapping("/{id}")
	public SlotResponse get(@PathVariable long id) {
		return SlotResponse.of(find(id));
	}

	@GetMapping
	public List<SlotResponse> list(@RequestParam @Positive long userId, @RequestParam Instant from,
			@RequestParam Instant to, @RequestParam(required = false) Status status) {
		return repository.findByUser(userId, window(from, to), status).stream().map(SlotResponse::of).toList();
	}

	@GetMapping("/block-view")
	public List<BlockResponse> blockView(@RequestParam @Positive long userId, @RequestParam Instant from,
			@RequestParam Instant to) {
		TimeRange window = window(from, to);
		return Calendar.of(window, repository.findByUser(userId, window, null)).stream()
				.map(BlockResponse::of)
				.toList();
	}

	@PutMapping("/{id}")
	public SlotResponse reschedule(@PathVariable long id, @Valid @RequestBody RescheduleRequest request) {
		return SlotResponse.of(change(id, slot -> slot.reschedule(request.range(), request.status())));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable long id) {
		Slot slot = find(id);
		if (!repository.delete(slot)) {
			throw concurrentChange(id);
		}
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/meeting")
	public ResponseEntity<SlotResponse> book(@PathVariable long id, @RequestBody MeetingRequest request) {
		Slot slot = change(id, current -> current.book(request.toMeeting()));
		var location = ServletUriComponentsBuilder.fromCurrentContextPath()
				.path("/api/slots/{id}")
				.buildAndExpand(slot.id())
				.toUri();
		return ResponseEntity.created(location).body(SlotResponse.of(slot));
	}

	@DeleteMapping("/{id}/meeting")
	public ResponseEntity<Void> cancelMeeting(@PathVariable long id) {
		change(id, Slot::cancelMeeting);
		return ResponseEntity.noContent().build();
	}

	private static TimeRange window(Instant from, Instant to) {
		TimeRange window = new TimeRange(from, to);
		if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
			throw new IllegalArgumentException("The window must be at most " + MAX_WINDOW.toDays() + " days");
		}
		return window;
	}

	private Slot find(long id) {
		return repository.findById(id).orElseThrow(() -> new SlotNotFoundException(id));
	}

	private Slot change(long id, UnaryOperator<Slot> change) {
		return repository.update(change.apply(find(id))).orElseThrow(() -> concurrentChange(id));
	}

	private static SlotConflictException concurrentChange(long id) {
		return new SlotConflictException("Slot " + id + " was changed concurrently: retry");
	}

}
