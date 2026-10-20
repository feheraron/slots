package de.afeher.slots.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Calendar {

	private Calendar() {
	}

	public static List<Block> of(TimeRange window, List<Slot> slots) {
		List<Block> blocks = new ArrayList<>();
		slots.stream()
				.sorted(Comparator.comparing(slot -> slot.range().begin()))
				.forEach(slot -> slot.range().clip(window)
						.ifPresent(range -> append(blocks, new Block(range, slot.status()))));
		return List.copyOf(blocks);
	}

	private static void append(List<Block> blocks, Block block) {
		if (!blocks.isEmpty()) {
			Block last = blocks.getLast();
			if (last.status() == block.status() && last.range().end().equals(block.range().begin())) {
				blocks.set(blocks.size() - 1,
						new Block(new TimeRange(last.range().begin(), block.range().end()), block.status()));
				return;
			}
		}
		blocks.add(block);
	}

}
