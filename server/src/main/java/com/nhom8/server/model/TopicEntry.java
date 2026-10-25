package com.nhom8.server.model;

import jakarta.persistence.*;

/** A keyword managed from the administrator screen and retained for historical paintings. */
@Entity
@Table(name = "topic_entries")
public class TopicEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100)
    private String word;
    @Column(nullable = false)
    private boolean active = true;

    public TopicEntry() {}
    public TopicEntry(String word, boolean active) {
        this.word = word;
        this.active = active;
    }
    public Long getId() { return id; }
    public String getWord() { return word; }
    public void setWord(String word) { this.word = word; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
