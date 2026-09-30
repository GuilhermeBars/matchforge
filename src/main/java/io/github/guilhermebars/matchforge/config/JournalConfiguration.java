package io.github.guilhermebars.matchforge.config;

import io.github.guilhermebars.matchforge.journal.InMemoryJournal;
import io.github.guilhermebars.matchforge.journal.InMemorySnapshotStore;
import io.github.guilhermebars.matchforge.journal.Journal;
import io.github.guilhermebars.matchforge.journal.PersistenceCodec;
import io.github.guilhermebars.matchforge.journal.PostgresJournal;
import io.github.guilhermebars.matchforge.journal.PostgresSnapshotStore;
import io.github.guilhermebars.matchforge.journal.SnapshotStore;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
public class JournalConfiguration {
    @Bean
    PersistenceCodec persistenceCodec() {
        return new PersistenceCodec();
    }

    @Bean
    @ConditionalOnProperty(name = "matchforge.journal.type", havingValue = "memory")
    Journal inMemoryJournal() {
        return new InMemoryJournal();
    }

    @Bean
    @ConditionalOnProperty(name = "matchforge.journal.type", havingValue = "memory")
    SnapshotStore inMemorySnapshotStore() {
        return new InMemorySnapshotStore();
    }

    @Bean
    @DependsOn("flywayInitializer")
    @ConditionalOnProperty(name = "matchforge.journal.type", havingValue = "postgres")
    Journal postgresJournal(DataSource dataSource) {
        return new PostgresJournal(dataSource);
    }

    @Bean
    @ConditionalOnProperty(name = "matchforge.journal.type", havingValue = "postgres")
    SnapshotStore postgresSnapshotStore(JdbcClient jdbc, PersistenceCodec codec) {
        return new PostgresSnapshotStore(jdbc, codec);
    }
}
