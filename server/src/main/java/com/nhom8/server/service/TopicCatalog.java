package com.nhom8.server.service;

import com.nhom8.server.model.TopicEntry;
import com.nhom8.server.repository.TopicEntryRepository;
import com.nhom8.server.util.TopicPool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class TopicCatalog {
    private final TopicEntryRepository entries;
    private final JdbcTemplate jdbc;

    public TopicCatalog(TopicEntryRepository entries, JdbcTemplate jdbc) {
        this.entries = entries;
        this.jdbc = jdbc;
    }

    /** Import legacy keywords, keep historical painting links, and remove the old keyword classification. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void initialize() {
        Map<String, String> overrides = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (tableExists("built_in_topic_overrides")) {
            jdbc.query("select original_word,replacement_word from built_in_topic_overrides",
                    (org.springframework.jdbc.core.RowCallbackHandler) r -> overrides.put(r.getString(1), r.getString(2)));
        }
        List<TopicEntry> existing = entries.findAll();
        boolean legacySourceColumn = columnExists("topic_entries", "source_word");
        Map<String, String> legacyWords = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (legacySourceColumn) {
            jdbc.query("select source_word,word from topic_entries where source_word is not null",
                    (org.springframework.jdbc.core.RowCallbackHandler) r -> legacyWords.put(r.getString(1), r.getString(2)));
        }
        List<TopicEntry> missing = new ArrayList<>();
        if (existing.isEmpty() || legacySourceColumn || tableExists("built_in_topic_overrides")) {
            for (String source : TopicPool.getSourceBuiltInTopics()) {
                boolean imported = legacyWords.containsKey(source)
                        || existing.stream().anyMatch(t -> source.equalsIgnoreCase(t.getWord())
                        || (overrides.containsKey(source) && overrides.get(source) != null
                        && overrides.get(source).equalsIgnoreCase(t.getWord())));
                if (!imported) {
                    String replacement = overrides.containsKey(source) ? overrides.get(source) : source;
                    missing.add(new TopicEntry(replacement == null ? source : replacement,
                            replacement != null && !replacement.isBlank()));
                }
            }
        }
        if (tableExists("custom_topics")) {
            jdbc.query("select word from custom_topics",
                    (org.springframework.jdbc.core.RowCallbackHandler) r -> {
                        String word = r.getString(1);
                        boolean imported = existing.stream().anyMatch(t -> t.getWord().equalsIgnoreCase(word));
                        if (!imported && missing.stream().noneMatch(t -> t.getWord().equalsIgnoreCase(word)))
                            missing.add(new TopicEntry(word, true));
                    });
        }
        entries.saveAll(missing);
        List<String> historicalWords = new ArrayList<>();
        jdbc.query("select distinct p.topic from paintings p left join topic_entries t "
                        + "on lower(trim(t.word))=lower(trim(p.topic)) where p.topic_entry_id is null and t.id is null",
                (org.springframework.jdbc.core.RowCallbackHandler) r -> historicalWords.add(r.getString(1)));
        for (String word : historicalWords) entries.save(new TopicEntry(word, false));
        jdbc.update("update paintings p set topic_entry_id=(select min(t.id) from topic_entries t "
                + "where lower(trim(t.word))=lower(trim(p.topic))) where p.topic_entry_id is null "
                + "and exists(select 1 from topic_entries t where lower(trim(t.word))=lower(trim(p.topic)) )");
        if (tableExists("built_in_topic_overrides")) jdbc.execute("drop table built_in_topic_overrides");
        if (tableExists("custom_topics")) jdbc.execute("drop table custom_topics");
        if (legacySourceColumn) jdbc.execute("alter table topic_entries drop column source_word");
        refresh();
    }

    private boolean columnExists(String table, String column) {
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try (java.sql.ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, "%", "%")) {
                while (columns.next()) {
                    if (table.equalsIgnoreCase(columns.getString("TABLE_NAME"))
                            && column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return true;
                }
                return false;
            }
        }));
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection -> {
            try (java.sql.ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
                while (tables.next()) if (table.equalsIgnoreCase(tables.getString("TABLE_NAME"))) return true;
                return false;
            }
        }));
    }

    public void refresh() {
        List<TopicEntry> all = entries.findAll();
        TopicPool.setBuiltInTopics(List.of());
        TopicPool.setCustomTopics(all.stream().filter(TopicEntry::isActive).map(TopicEntry::getWord).toList());
    }

    public List<TopicEntry> all() { return entries.findAll(); }
    public void save(TopicEntry topic) { entries.save(topic); refresh(); }
    public void delete(TopicEntry topic) { entries.delete(topic); refresh(); }
    public TopicEntry findActiveByWord(String word) { return entries.findAll().stream()
            .filter(t -> t.isActive() && t.getWord().equalsIgnoreCase(word)).findFirst().orElse(null); }
    public TopicEntry findOrCreateHistoryWord(String word) {
        if (word == null || word.isBlank()) return null;
        return entries.findAll().stream().filter(t -> t.getWord().equalsIgnoreCase(word)).findFirst()
                .orElseGet(() -> {
                    TopicEntry historical = new TopicEntry(word, false);
                    entries.save(historical);
                    return historical;
                });
    }
    public long countCustom() { return entries.findAll().stream().filter(TopicEntry::isActive).count(); }
}
