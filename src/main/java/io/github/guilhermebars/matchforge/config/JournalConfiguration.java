package io.github.guilhermebars.matchforge.config;

import io.github.guilhermebars.matchforge.journal.InMemoryJournal;
import io.github.guilhermebars.matchforge.journal.Journal;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class JournalConfiguration {
    @Bean
    @ConditionalOnProperty(name = "matchforge.journal.type", havingValue = "memory")
    Journal inMemoryJournal() { return new InMemoryJournal(); }
}
