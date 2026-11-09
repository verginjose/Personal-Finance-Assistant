# Private receipt migration

Receipt URLs keep their existing `/api/storage/receipts/...` shape. No database
rewrite is needed: the command service checks an existing transaction or shared
expense reference, and recognizes existing upload names prefixed with the
uploader's UUID (including `-batch-` uploads). An unattached upload is visible
only to its uploader. A linked shared receipt is visible to accepted group
members; removing a member revokes that access on the next request.

At gateway startup, the legacy MinIO public-read policy on the receipts bucket
is deleted. Startup fails if this cannot be done. Profile pictures remain in the
separate profiles bucket and continue to load publicly. Existing receipt object
keys and database URLs do not change.

After deployment, verify that a direct unauthenticated request to a receipt
returns 401, a transaction owner and an accepted group member can open their
receipts, and an unrelated or removed user gets 404. Also verify the receipts
bucket has no public policy in MinIO, including for direct object requests.
