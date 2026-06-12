package com.upsertservice.service;

import com.upsertservice.model.GroupActivity;
import com.upsertservice.model.GroupActivity.ActivityType;
import com.upsertservice.repository.GroupActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CacheEvict;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GroupActivityService {

    private final GroupActivityRepository activityRepo;

    @Transactional
    @CacheEvict(value = "group-activity", key = "#groupId")
    public void log(Long groupId, UUID actorUserId, String actorName,
                    ActivityType type, String message, Long referenceId) {
        GroupActivity activity = new GroupActivity();
        activity.setGroupId(groupId);
        activity.setActorUserId(actorUserId);
        activity.setActorName(actorName);
        activity.setActivityType(type);
        activity.setMessage(message);
        activity.setReferenceId(referenceId);
        activityRepo.save(activity);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "group-activity", key = "#groupId", sync = true)
    public List<GroupActivity> getGroupActivity(Long groupId) {
        return activityRepo.findByGroupIdOrderByCreatedAtDesc(groupId);
    }
}
