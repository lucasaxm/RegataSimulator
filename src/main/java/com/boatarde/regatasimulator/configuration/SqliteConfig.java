package com.boatarde.regatasimulator.configuration;

import com.boatarde.regatasimulator.repository.*;
import com.boatarde.regatasimulator.repository.sqlite.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name="regata-simulator.database.engine",havingValue="sqlite")
public class SqliteConfig {
    @Bean(destroyMethod="close") SqliteStore sqliteStore(DatabaseProperties properties) {
        return new SqliteStore(properties.getSqliteFile(),properties.getBusyTimeoutMillis());
    }
    @Bean SourceRepository sourceRepository(SqliteStore store,ObjectMapper mapper) { return new SqliteSourceRepository(store,mapper); }
    @Bean TemplateRepository templateRepository(SqliteStore store,ObjectMapper mapper) { return new SqliteTemplateRepository(store,mapper); }
    @Bean AuthorRepository authorRepository(SqliteStore store) { return new SqliteAuthorRepository(store); }
    @Bean AuditRepository auditRepository(SqliteStore store) { return new SqliteAuditRepository(store); }
    @Bean MemeHistoryRepository memeHistoryRepository(SqliteStore store,ObjectMapper mapper) { return new SqliteMemeHistoryRepository(store,mapper); }
    @Bean MetadataUnitOfWork sqliteMetadataUnitOfWork(SqliteStore store) {
        return writes -> store.transactions().executeWithoutResult(tx -> writes.run());
    }
}