package de.afeher.slots.persistence;

import de.afeher.slots.domain.Meeting;
import de.afeher.slots.domain.Slot;
import de.afeher.slots.domain.Status;
import de.afeher.slots.domain.TimeRange;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

@Repository
public class SlotRepository {

	private static final String COLUMNS = "id, user_id, begin_at, end_at, status, title, description, participants, version";

	private static final String NO_OVERLAP_CONSTRAINT = "slot_no_overlap";

	private static final String EXCLUSION_VIOLATION = "23P01";

	private final JdbcClient jdbc;

	public SlotRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public Slot insert(Slot slot) {
		lockCalendar(slot.userId());
		long id = checked(slot.userId(), () -> jdbc.sql("""
						insert into slot (user_id, begin_at, end_at, status, title, description, participants)
						values (:userId, :begin, :end, :status, :title, :description, :participants)
						returning id""")
				.params(parameters(slot))
				.query(Long.class)
				.single());
		return new Slot(id, slot.userId(), slot.range(), slot.status(), slot.meeting(), 0);
	}

	public Optional<Slot> findById(long id) {
		return jdbc.sql("select " + COLUMNS + " from slot where id = :id")
				.param("id", id)
				.query(this::toSlot)
				.optional();
	}

	public List<Slot> findByUser(long userId, TimeRange window, Status status) {
		return jdbc.sql("""
						select %s from slot
						where user_id = :userId
						  and tstzrange(begin_at, end_at, '[)') && tstzrange(:from, :to, '[)')
						  and (cast(:status as varchar) is null or status = :status)
						order by begin_at""".formatted(COLUMNS))
				.param("userId", userId)
				.param("from", timestamp(window.begin()))
				.param("to", timestamp(window.end()))
				.param("status", status == null ? null : status.name())
				.query(this::toSlot)
				.list();
	}

	@Transactional
	public Optional<Slot> update(Slot slot) {
		lockCalendar(slot.userId());
		int updated = checked(slot.userId(), () -> jdbc.sql("""
						update slot
						set begin_at = :begin, end_at = :end, status = :status,
						    title = :title, description = :description, participants = :participants,
						    version = version + 1
						where id = :id and version = :version""")
				.params(parameters(slot))
				.param("id", slot.id())
				.param("version", slot.version())
				.update());
		if (updated == 0) {
			return Optional.empty();
		}
		return Optional.of(new Slot(slot.id(), slot.userId(), slot.range(), slot.status(), slot.meeting(),
				slot.version() + 1));
	}

	public boolean delete(Slot slot) {
		return jdbc.sql("delete from slot where id = :id and version = :version")
				.param("id", slot.id())
				.param("version", slot.version())
				.update() > 0;
	}

	private void lockCalendar(long userId) {
		jdbc.sql("select pg_advisory_xact_lock(:userId)").param("userId", userId).query().singleRow();
	}

	private static Map<String, Object> parameters(Slot slot) {
		Meeting meeting = slot.meeting();
		Map<String, Object> parameters = new HashMap<>();
		parameters.put("userId", slot.userId());
		parameters.put("begin", timestamp(slot.range().begin()));
		parameters.put("end", timestamp(slot.range().end()));
		parameters.put("status", slot.status().name());
		parameters.put("title", meeting == null ? null : meeting.title());
		parameters.put("description", meeting == null ? null : meeting.description());
		parameters.put("participants", meeting == null ? new Long[0] : meeting.participants().toArray(Long[]::new));
		return parameters;
	}

	private static <T> T checked(long userId, Supplier<T> statement) {
		try {
			return statement.get();
		}
		catch (DataIntegrityViolationException e) {
			if (violatesNoOverlap(e)) {
				throw new SlotOverlapException(userId, e);
			}
			throw e;
		}
	}

	private static boolean violatesNoOverlap(Throwable e) {
		for (Throwable cause = e; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sql && EXCLUSION_VIOLATION.equals(sql.getSQLState())
					&& String.valueOf(sql.getMessage()).contains(NO_OVERLAP_CONSTRAINT)) {
				return true;
			}
		}
		return false;
	}

	private static OffsetDateTime timestamp(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private Slot toSlot(ResultSet rs, int row) throws SQLException {
		String title = rs.getString("title");
		Meeting meeting = title == null ? null
				: new Meeting(title, rs.getString("description"), participants(rs.getArray("participants")));
		return new Slot(
				rs.getLong("id"),
				rs.getLong("user_id"),
				new TimeRange(rs.getObject("begin_at", OffsetDateTime.class).toInstant(),
						rs.getObject("end_at", OffsetDateTime.class).toInstant()),
				Status.valueOf(rs.getString("status")),
				meeting,
				rs.getInt("version"));
	}

	private static Set<Long> participants(Array array) throws SQLException {
		return Set.of((Long[]) array.getArray());
	}

}
