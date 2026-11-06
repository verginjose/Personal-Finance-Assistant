-- Group admins + persistent notification inbox.
--
-- command-service and query-service run with Hibernate ddl-auto=update, so new
-- entities are created automatically. This migration covers the one change that
-- must be applied to *existing rows*: the group_members.role column. Run it
-- before deploying the services that map the new column.

-- 1. Every member has a role. Existing rows default to MEMBER.
ALTER TABLE groups.group_members
    ADD COLUMN IF NOT EXISTS role VARCHAR(20);

UPDATE groups.group_members
   SET role = 'MEMBER'
 WHERE role IS NULL;

-- 2. The creator of an existing group becomes its first admin.
UPDATE groups.group_members gm
   SET role = 'ADMIN'
  FROM groups.expense_groups g
 WHERE gm.group_id = g.id
   AND gm.user_id = g.created_by
   AND gm.role = 'MEMBER';

CREATE INDEX IF NOT EXISTS idx_group_members_admin
    ON groups.group_members (group_id, role);

-- 3. Notification inbox. Hibernate also creates this from AppNotification;
--    the statement is idempotent so either path is safe.
CREATE TABLE IF NOT EXISTS finance.notifications (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'INFO',
    event VARCHAR(60),
    message TEXT NOT NULL,
    group_id BIGINT,
    payload TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    read_at TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_notifications_user_created
    ON finance.notifications (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_notifications_user_unread
    ON finance.notifications (user_id, read_at)
    WHERE read_at IS NULL;
