
## Static asset caching and group list loading

The image build publishes app assets under `/static/<content-version>/`. The
version covers HTML, JS modules, CSS, and bundled images, so dependency changes
also change the entry URLs. HTML is never cached. Versioned assets are cached
for one year with `immutable`; legacy URLs revalidate using ETags. Static text
responses support gzip; API/SSE responses keep compression disabled.

Group names, balances, and actions render before optional member/avatar data.
Member enrichment uses `GET /upsert/groups/members?groupIds=...` in batches of
20 with at most two batches in flight. The backend accepts at most 50 IDs and
requires accepted membership in every group; member reads share one replica
wait budget. Delayed responses cannot overwrite a later view. Notification
badges use the unread count already included in the inbox response.
