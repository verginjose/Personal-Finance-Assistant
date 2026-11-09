package com.finance.command.repository;

import com.finance.command.model.SharedExpense;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SharedExpenseRepository extends JpaRepository<SharedExpense, Long> {
    @org.springframework.data.jpa.repository.Query("SELECT COUNT(e) > 0 FROM SharedExpense e JOIN GroupMember m ON m.groupId = e.groupId WHERE e.receiptUrl = :url AND m.userId = :userId AND m.status = com.finance.command.model.GroupMember.InvitationStatus.ACCEPTED")
    boolean canReadReceipt(@org.springframework.data.repository.query.Param("url") String url, @org.springframework.data.repository.query.Param("userId") UUID userId);
    Page<SharedExpense> findByGroupIdOrderByCreatedAtDesc(Long groupId, Pageable pageable);
    List<SharedExpense> findByGroupIdOrderByCreatedAtDesc(Long groupId);
    List<SharedExpense> findByGroupIdAndPaidBy(Long groupId, UUID paidBy);
    List<SharedExpense> findByGroupIdIn(List<Long> groupIds);
}
