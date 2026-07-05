package com.finance.query.repository;

import com.finance.query.model.GroupMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GroupMemberRepository extends JpaRepository<GroupMember, Long> {
    List<GroupMember> findByGroupId(Long groupId);
    Optional<GroupMember> findByGroupIdAndUserId(Long groupId, UUID userId);
    boolean existsByGroupIdAndUserId(Long groupId, UUID userId);
    List<GroupMember> findByGroupIdInAndUserId(List<Long> groupIds, UUID userId);
}
