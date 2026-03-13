package com.upsertservice.repository;

import com.upsertservice.model.ExpenseSplit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ExpenseSplitRepository extends JpaRepository<ExpenseSplit, Long> {
    List<ExpenseSplit> findBySharedExpenseId(Long sharedExpenseId);
    List<ExpenseSplit> findBySharedExpenseIdIn(List<Long> expenseIds);

    @Query("SELECT s FROM ExpenseSplit s WHERE s.sharedExpenseId IN " +
           "(SELECT e.id FROM SharedExpense e WHERE e.groupId = :groupId) " +
           "AND s.userId = :userId AND s.settled = false")
    List<ExpenseSplit> findUnsettledSplitsForUserInGroup(@Param("groupId") Long groupId,
                                                         @Param("userId") UUID userId);
}
