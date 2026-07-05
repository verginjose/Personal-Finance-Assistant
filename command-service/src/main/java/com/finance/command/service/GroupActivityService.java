package com.finance.command.service;

import com.finance.command.model.GroupActivity;
import com.finance.command.model.GroupActivity.ActivityType;
import com.finance.command.repository.ExpenseGroupRepository;
import com.finance.command.repository.GroupActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GroupActivityService {

    private final GroupActivityRepository activityRepo;
    private final ExpenseGroupRepository groupRepo;

    @Transactional
    @CacheEvict(value = "group-activity", key = "#groupId")
    public void log(Long groupId, UUID actorUserId, String actorName,
                    ActivityType type, String message, Long referenceId) {
        GroupActivity activity = new GroupActivity();
        activity.setGroup(groupRepo.getReferenceById(groupId));
        activity.setActorUserId(actorUserId);
        activity.setActorName(actorName);
        activity.setActivityType(type);
        activity.setMessage(message);
        activity.setReferenceId(referenceId);
        activityRepo.save(activity);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "group-activity", key = "#groupId + '-' + #page + '-' + #size", sync = true)
    public Page<GroupActivity> getGroupActivity(Long groupId, int page, int size) {
        return activityRepo.findByGroupIdOrderByCreatedAtDesc(groupId, PageRequest.of(page, size));
    }
}
