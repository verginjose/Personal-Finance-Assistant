package com.upsertservice.repository;

import com.upsertservice.model.ExpenseGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ExpenseGroupRepository extends JpaRepository<ExpenseGroup, Long> {

    /** Groups created by this user */
    List<ExpenseGroup> findByCreatedByOrderByCreatedAtDesc(UUID userId);

    /** Groups where the user is a member (created or joined) */
    @Query("SELECT DISTINCT g FROM ExpenseGroup g " +
           "JOIN GroupMember m ON m.groupId = g.id " +
           "WHERE m.userId = :userId AND g.isDeleted = false " +
           "ORDER BY g.createdAt DESC")
    List<ExpenseGroup> findGroupsByMember(@Param("userId") UUID userId);
}
