#!/usr/bin/env bash
# Enable native GitHub auto-merge for the exact promotion commit. GitHub then
# performs the merge once the required checks pass, so this job (and its
# short-lived token) ends immediately instead of polling for ten minutes.
set -euo pipefail
: "${PR_NUMBER:?PR_NUMBER is required}"
: "${PR_NODE_ID:?PR_NODE_ID is required}"
: "${GITHUB_TOKEN:?GITHUB_TOKEN is required}"
: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${GITHUB_REF_NAME:?GITHUB_REF_NAME is required}"
: "${EXPECTED_HEAD_SHA:?EXPECTED_HEAD_SHA is required}"
: "${SOURCE_SHA:?SOURCE_SHA is required}"

python3 "$(dirname "$0")/validate-promotion.py" "$EXPECTED_HEAD_SHA" "$SOURCE_SHA"
api="${GITHUB_API_URL:-https://api.github.com}/repos/${GITHUB_REPOSITORY}"
graphql="${GITHUB_GRAPHQL_URL:-https://api.github.com/graphql}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
headers=(-H "Authorization: Bearer $GITHUB_TOKEN" -H 'Accept: application/vnd.github+json')

# Re-check the PR at enable time: only this run's generated commit, authored by
# the Actions bot on a ci/tag-bump branch in this repository, may auto-merge.
curl -fsS "${headers[@]}" "$api/pulls/$PR_NUMBER" > "$tmp/pr.json"
jq -e --arg sha "$EXPECTED_HEAD_SHA" --arg repo "$GITHUB_REPOSITORY" --arg base "$GITHUB_REF_NAME" '
  .state == "open" and .draft == false and .head.sha == $sha and .commits == 1
  and .head.repo.full_name == $repo and .base.repo.full_name == $repo and .base.ref == $base
  and (.head.ref | test("^ci/tag-bump-[0-9]+-[0-9]+$"))
  and .user.login == "github-actions[bot]"
' "$tmp/pr.json" >/dev/null || { echo '::error::Promotion PR identity or commit changed'; exit 1; }

jq -n --arg id "$PR_NODE_ID" \
  '{query:"mutation($id:ID!){enablePullRequestAutoMerge(input:{pullRequestId:$id,mergeMethod:MERGE}){pullRequest{number mergeStateStatus}}}",variables:{id:$id}}' \
  > "$tmp/graphql.json"
curl -fsS -H "Authorization: Bearer $GITHUB_TOKEN" -H 'Content-Type: application/json' \
  --request POST "$graphql" --data-binary "@$tmp/graphql.json" > "$tmp/result.json"

if jq -e '.data.enablePullRequestAutoMerge.pullRequest.number' "$tmp/result.json" >/dev/null; then
  echo "Auto-merge enabled for promotion PR #$PR_NUMBER at $EXPECTED_HEAD_SHA"
elif jq -e '(.errors // []) | any(.message | test("already"; "i"))' "$tmp/result.json" >/dev/null; then
  echo "Auto-merge was already enabled for promotion PR #$PR_NUMBER"
else
  cat "$tmp/result.json" >&2
  echo '::error::Could not enable auto-merge for the promotion PR'
  exit 1
fi

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  printf '\n**Promotion auto-merge enabled:** #%s (`%s`). GitHub merges it when the required checks pass; Argo CD then deploys.\n' \
    "$PR_NUMBER" "$EXPECTED_HEAD_SHA" >> "$GITHUB_STEP_SUMMARY"
fi
