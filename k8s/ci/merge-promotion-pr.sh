#!/usr/bin/env bash
# Merge only the validated, checked manifest commit produced by this build.
# No review token is used: the promotion is gated by manifest validation and by
# the required checks that branch protection enforces at merge time.
set -euo pipefail
: "${PR_NUMBER:?PR_NUMBER is required}"
: "${GITHUB_TOKEN:?GITHUB_TOKEN is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${GITHUB_REF_NAME:?GITHUB_REF_NAME is required}"
: "${EXPECTED_HEAD_SHA:?EXPECTED_HEAD_SHA is required}"
: "${SOURCE_SHA:?SOURCE_SHA is required}"

python3 "$(dirname "$0")/validate-promotion.py" "$EXPECTED_HEAD_SHA" "$SOURCE_SHA"
api="${GITHUB_API_URL:-https://api.github.com}/repos/${GITHUB_REPOSITORY}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
headers=(-H "Authorization: Bearer $GITHUB_TOKEN" -H 'Accept: application/vnd.github+json')

# CodeQL may start a few seconds after PR creation. Wait for at least one check
# and for every reported check/status to finish; never bypass branch rules.
for ((attempt=1; attempt<=${MERGE_POLL_ATTEMPTS:-60}; attempt++)); do
  curl -fsS "${headers[@]}" "$api/pulls/$PR_NUMBER" > "$tmp/pr.json"
  jq -e --arg sha "$EXPECTED_HEAD_SHA" --arg repo "$GITHUB_REPOSITORY" --arg base "$GITHUB_REF_NAME" '
    .state == "open" and .draft == false and .head.sha == $sha and .commits == 1
    and .head.repo.full_name == $repo and .base.repo.full_name == $repo and .base.ref == $base
    and (.head.ref | test("^ci/tag-bump-[0-9]+-[0-9]+$"))
    and .user.login == "github-actions[bot]"
  ' "$tmp/pr.json" >/dev/null || { echo '::error::Promotion PR identity or commit changed'; exit 1; }

  curl -fsS "${headers[@]}" "$api/commits/$EXPECTED_HEAD_SHA/check-runs?per_page=100" > "$tmp/checks.json"
  curl -fsS "${headers[@]}" "$api/commits/$EXPECTED_HEAD_SHA/status?per_page=100" > "$tmp/status.json"
  jq -e '.total_count <= 100' "$tmp/checks.json" >/dev/null || { echo '::error::Too many checks to validate'; exit 1; }
  jq -e '.total_count <= 100' "$tmp/status.json" >/dev/null || { echo '::error::Too many statuses to validate'; exit 1; }
  if jq -e 'any(.check_runs[]; .status == "completed" and (.conclusion | IN("success", "neutral", "skipped") | not))' "$tmp/checks.json" >/dev/null \
      || jq -e '.state == "failure" or .state == "error"' "$tmp/status.json" >/dev/null; then
    echo '::error::Promotion checks failed; leaving the PR open'
    exit 1
  fi
  if jq -e '.total_count > 0 and all(.check_runs[]; .status == "completed")' "$tmp/checks.json" >/dev/null \
      && jq -e '(.statuses | length) == 0 or .state == "success"' "$tmp/status.json" >/dev/null \
      && jq -e '.mergeable == true and .mergeable_state == "clean"' "$tmp/pr.json" >/dev/null; then
    jq -n --arg sha "$EXPECTED_HEAD_SHA" '{sha:$sha, merge_method:"merge"}' > "$tmp/merge.json"
    curl --fail-with-body -sS "${headers[@]}" -H 'Content-Type: application/json' \
      --request PUT "$api/pulls/$PR_NUMBER/merge" --data-binary "@$tmp/merge.json" > "$tmp/result.json"
    jq -e '.merged == true' "$tmp/result.json" >/dev/null || { echo '::error::GitHub refused the promotion merge'; exit 1; }
    echo "Merged promotion PR #$PR_NUMBER at $EXPECTED_HEAD_SHA"
    if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
      printf '\n**Promotion merged:** #%s (`%s`). Argo CD will sync the new images.\n' "$PR_NUMBER" "$EXPECTED_HEAD_SHA" >> "$GITHUB_STEP_SUMMARY"
    fi
    exit 0
  fi
  echo "Waiting for promotion PR #$PR_NUMBER checks and merge readiness ($attempt)"
  if (( attempt < ${MERGE_POLL_ATTEMPTS:-60} )); then sleep "${MERGE_POLL_SECONDS:-10}"; fi
done
echo '::error::Timed out waiting for promotion checks; leaving the PR open'
exit 1
