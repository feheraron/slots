package de.afeher.slots;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SlotsApplicationTests {

	@Autowired
	JdbcClient jdbc;

	@Test
	void migratesSlotSchema() {
		Long slots = jdbc.sql("select count(*) from slot").query(Long.class).single();

		assertThat(slots).isNotNull();
	}

}
