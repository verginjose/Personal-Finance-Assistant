package com.upsertservice.repository;

import com.upsertservice.model.TransactionGoalAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionGoalAllocationRepository extends JpaRepository<TransactionGoalAllocation, Long> {
    List<TransactionGoalAllocation> findByTransactionId(Long transactionId);
    List<TransactionGoalAllocation> findByGoalId(Long goalId);
}
