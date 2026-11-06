-- Group activity feed: allow every ActivityType the code writes.
--
-- The original check constraint only allowed GROUP_CREATED, MEMBER_ADDED,
-- EXPENSE_ADDED, EXPENSE_DELETED and SETTLEMENT_RECORDED. Promoting/demoting a
-- member (ADMIN_CHANGED), removing a member (MEMBER_REMOVED) and editing an
-- expense (EXPENSE_UPDATED) therefore failed with a data-integrity error.

ALTER TABLE activity.group_activities
    DROP CONSTRAINT IF EXISTS group_activities_activity_type_check;

ALTER TABLE activity.group_activities
    ADD CONSTRAINT group_activities_activity_type_check
    CHECK (activity_type IN (
        'GROUP_CREATED',
        'MEMBER_ADDED',
        'MEMBER_REMOVED',
        'ADMIN_CHANGED',
        'EXPENSE_ADDED',
        'EXPENSE_UPDATED',
        'EXPENSE_DELETED',
        'SETTLEMENT_RECORDED'
    ));
