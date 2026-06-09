package com.upsertservice.repository;

import com.upsertservice.model.GroupActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GroupActivityRepository extends JpaRepository<GroupActivity, Long> {
    List<GroupActivity> findByGroupIdOrderByCreatedAtDesc(Long groupId);
}
