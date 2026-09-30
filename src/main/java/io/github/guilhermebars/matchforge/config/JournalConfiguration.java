package io.github.guilhermebars.matchforge.config;

import io.github.guilhermebars.matchforge.journal.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class JournalConfiguration {
    @Bean PersistenceCodec persistenceCodec() { return new PersistenceCodec(); }
    @Bean @ConditionalOnProperty(name = "matchforge.journal.type", havingValue = "memory")
    Journal inMemoryJournal() { return new InMemoryJournal(); }
    @Bean @ConditionalOnProperty(name = "matchforge.journal.type", havingValue = "memory")
    SnapshotStore inMemorySnapshotStore() { return new InMemorySnapshotStore(); }
    @Bean @DependsOn("flywayInitializer")
    @ConditionalOnProperty(name = "matchforge.journal.type", havingValue = "postgres")
    Journal postgresJournal(DataSource dataSource) { return new PostgresJournal(dataSource); }
    @Bean @ConditionalOnProperty(name = "matchforge.journal.type", havingValue = "postgres")
    SnapshotStore postgresSnapshotStore(JdbcClient jdbc, PersistenceCodec codec) { return new PostgresSnapshotStore(jdbc, codec); }
}
