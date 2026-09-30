package io.github.guilhermebars.matchforge;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.guilhermebars.matchforge.config.MatchforgeProperties;
import io.github.guilhermebars.matchforge.domain.Symbol;
import io.github.guilhermebars.matchforge.journal.InMemoryJournal;
import io.github.guilhermebars.matchforge.journal.Journal;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("memory")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MemoryContextTest {
    @Autowired
    ApplicationContext context;

    @Autowired
    MatchforgeProperties properties;

    @Autowired
    Journal journal;

    @Test
    void loadsWithRealMemoryJournalAndNoDatabase() {
        assertThat(journal).isInstanceOf(InMemoryJournal.class);
        assertThat(context.getBeansOfType(org.springframework.kafka.core.KafkaTemplate.class))
                .isEmpty();
        assertThat(context.getBean(io.github.guilhermebars.matchforge.service.EngineService.class)
                        .available())
                .isTrue();
        assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
        assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
        assertThat(properties.journal().type()).isEqualTo("memory");
        assertThat(properties.events().publisher()).isEqualTo("in-process");
        assertThat(properties.snapshot().interval()).isPositive();
        assertThat(properties.instruments())
                .extracting(i -> i.toDomain().symbol())
                .containsExactly(new Symbol("BTC-USD"), new Symbol("ETH-USD"));
    }
}
