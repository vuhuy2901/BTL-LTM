package com.nhom8.server;

import com.nhom8.server.model.TopicEntry;
import com.nhom8.server.repository.TopicEntryRepository;
import com.nhom8.server.service.TopicCatalog;
import com.nhom8.server.util.TopicPool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TopicCatalogMigrationTest {
    @Autowired TopicCatalog catalog;
    @Autowired TopicEntryRepository entries;
    @Autowired JdbcTemplate jdbc;

    @Test void catalogIsDatabaseBackedAndInitializationIsIdempotent() {
        entries.deleteAll();
        catalog.initialize();
        assertTrue(TopicPool.getBuiltInTopics().isEmpty());
        assertFalse(TopicPool.getCustomTopics().isEmpty());
        long count = entries.count();
        catalog.initialize();
        assertEquals(count, entries.count());
        TopicEntry edited = entries.findAll().get(0);
        edited.setActive(false);
        catalog.save(edited);
        assertFalse(TopicPool.getCustomTopics().contains(edited.getWord()));
        jdbc.execute("alter table topic_entries add column source_word varchar(100) unique");
        jdbc.update("update topic_entries set word='mèo do admin sửa', source_word='con mèo' where word='con mèo'");
        catalog.initialize();
        assertEquals(0, jdbc.queryForObject("select count(*) from information_schema.columns "
                + "where lower(table_name)='topic_entries' and lower(column_name)='source_word'", Integer.class));
        assertEquals(count, entries.count());
        assertTrue(entries.findAll().stream().anyMatch(t -> "mèo do admin sửa".equals(t.getWord())));
        assertFalse(entries.findAll().stream().anyMatch(t -> "con mèo".equals(t.getWord())));
    }
}
