package com.finance.query.service;

import com.finance.query.cache.CacheConsistencyGuard;
import com.finance.query.model.GroupMember;
import com.finance.query.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class GroupMembersBatchTest {
    @Mock ExpenseGroupRepository groupRepo;
    @Mock GroupMemberRepository memberRepo;
    @Mock SharedExpenseRepository expenseRepo;
    @Mock ExpenseSplitRepository splitRepo;
    @Mock CacheManager cacheManager;
    @Mock GroupActivityRepository groupActivityRepo;
    @Mock CacheConsistencyGuard consistencyGuard;
    @InjectMocks SplitService service;
    final UUID user = UUID.randomUUID();

    GroupMember member(long group, GroupMember.InvitationStatus status) {
        GroupMember m = new GroupMember();
        m.setGroupId(group); m.setUserId(user); m.setStatus(status);
        return m;
    }

    @Test void readsMultipleGroupsWithOneMemberQuery() {
        var a = member(1, GroupMember.InvitationStatus.ACCEPTED);
        var b = member(2, GroupMember.InvitationStatus.ACCEPTED);
        when(memberRepo.findByGroupIdInAndUserId(List.of(1L, 2L), user)).thenReturn(List.of(a, b));
        when(memberRepo.findByGroupIdIn(List.of(1L, 2L))).thenReturn(List.of(a, b));
        var result = service.getGroupMembersBatch(user, List.of(1L, 2L, 1L));
        assertThat(result).containsOnlyKeys(1L, 2L);
        assertThat(result.get(1L)).containsExactly(a);
        verify(memberRepo).findByGroupIdIn(List.of(1L, 2L));
        verify(memberRepo, never()).findByGroupId(anyLong());
    }

    @Test void refusesAnUnauthorizedGroupWithoutReturningAnyOtherMembers() {
        when(memberRepo.findByGroupIdInAndUserId(List.of(1L, 2L), user))
                .thenReturn(List.of(member(1, GroupMember.InvitationStatus.ACCEPTED)));
        assertThatThrownBy(() -> service.getGroupMembersBatch(user, List.of(1L, 2L)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                        assertThat(e.getStatusCode().value()).isEqualTo(403));
        verify(memberRepo, never()).findByGroupIdIn(anyList());
    }

    @Test void aPendingInvitationDoesNotGrantAccess() {
        when(memberRepo.findByGroupIdInAndUserId(List.of(1L), user))
                .thenReturn(List.of(member(1, GroupMember.InvitationStatus.PENDING)));
        assertThatThrownBy(() -> service.getGroupMembersBatch(user, List.of(1L)))
                .isInstanceOf(ResponseStatusException.class);
        verify(memberRepo, never()).findByGroupIdIn(anyList());
    }

    @Test void rejectsInvalidOrUnboundedBatchesBeforeQuerying() {
        for (List<Long> ids : List.of(List.<Long>of(), List.of(0L), Collections.nCopies(51, 1L))) {
            assertThatThrownBy(() -> service.getGroupMembersBatch(user, ids))
                    .isInstanceOfSatisfying(ResponseStatusException.class, e ->
                            assertThat(e.getStatusCode().value()).isEqualTo(400));
        }
        verifyNoInteractions(memberRepo, consistencyGuard);
    }

    @Test void waitsForRecentWritesBeforeCheckingMembershipAndLoading() {
        var a = member(1, GroupMember.InvitationStatus.ACCEPTED);
        when(consistencyGuard.snapshot("group:1"))
                .thenReturn(new CacheConsistencyGuard.Snapshot("1", "0/123", false));
        when(memberRepo.findByGroupIdInAndUserId(List.of(1L), user)).thenReturn(List.of(a));
        when(memberRepo.findByGroupIdIn(List.of(1L))).thenReturn(List.of(a));
        service.getGroupMembersBatch(user, List.of(1L));
        InOrder order = inOrder(consistencyGuard, memberRepo);
        order.verify(consistencyGuard).awaitReplica(eq("0/123"), longThat(n -> n > 0 && n <= 1000));
        order.verify(memberRepo).findByGroupIdInAndUserId(List.of(1L), user);
        order.verify(memberRepo).findByGroupIdIn(List.of(1L));
    }
}
