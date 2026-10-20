package de.afeher.slots.web;

import de.afeher.slots.domain.Meeting;

import java.util.Set;

public record MeetingRequest(String title, String description, Set<Long> participants) {

	Meeting toMeeting() {
		return new Meeting(title, description, participants);
	}

}
