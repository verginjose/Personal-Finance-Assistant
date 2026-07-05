package com.finance.query.repository;

import com.finance.query.model.TransactionGoalAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

import java.util.Optional;

@Repository
public interface TransactionGoalAllocationRepository extends JpaRepository<TransactionGoalAllocation, Long> {
    Optional<TransactionGoalAllocation> findByTransactionId(Long transactionId);
    List<TransactionGoalAllocation> findByGoalId(Long goalId);
}
