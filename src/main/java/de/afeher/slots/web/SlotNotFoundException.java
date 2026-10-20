package de.afeher.slots.web;

public class SlotNotFoundException extends RuntimeException {

	public SlotNotFoundException(long id) {
		super("Slot " + id + " not found");
	}

}
