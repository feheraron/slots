package de.afeher.slots.web;

import de.afeher.slots.domain.Slot;
import de.afeher.slots.domain.SlotConflictException;
import de.afeher.slots.persistence.SlotRepository;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.function.UnaryOperator;

@RestController
@RequestMapping("/api/slots")
public class SlotController {

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
