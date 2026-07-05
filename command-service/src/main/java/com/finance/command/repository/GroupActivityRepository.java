package com.finance.command.repository;

import com.finance.command.model.GroupActivity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GroupActivityRepository extends JpaRepository<GroupActivity, Long> {
    Page<GroupActivity> findByGroupIdOrderByCreatedAtDesc(Long groupId, Pageable pageable);
    List<GroupActivity> findByGroupIdOrderByCreatedAtDesc(Long groupId);
}
