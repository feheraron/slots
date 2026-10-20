package de.afeher.slots.web;

import de.afeher.slots.domain.Block;
import de.afeher.slots.domain.Status;

import java.time.Instant;

public record BlockResponse(Instant begin, Instant end, Status status) {

	static BlockResponse of(Block block) {
		return new BlockResponse(block.range().begin(), block.range().end(), block.status());
	}

}
