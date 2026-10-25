package com.nhom8.server.repository;

import com.nhom8.server.model.TopicEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TopicEntryRepository extends JpaRepository<TopicEntry, Long> {
}
