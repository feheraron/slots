package de.afeher.slots.persistence;

public class SlotOverlapException extends RuntimeException {

	public SlotOverlapException(long userId, Throwable cause) {
		super("Slot overlaps another slot of user " + userId, cause);
	}

}
