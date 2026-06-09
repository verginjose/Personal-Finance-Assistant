# API Endpoints (Markdown)

| Service | Method | Path | Required Headers | Request Body (schema) | Response Body (schema) |
|---|---|---|---|---|---|
| **Auth** | POST | /auth/login | – | `LoginRequest` { email:string, password:string } | `LoginResponse` { token:string, refreshToken:string, userId:string, userId (UUID) } |
| **Auth** | POST | /auth/register | – | `RegisterRequest` { email:string, password:string, role:string } | `RegisterResponse` { id:string, email:string, role:string } |
| **Auth** | GET | /auth/health | – | – | `{ status:"UP", service:"auth-service" }` |
| **Auth** | GET | /auth/me | `Authorization: Bearer <jwt>` | – | `{ email:string, role:string }` |
| **Auth** | POST | /auth/refresh | – | `RefreshTokenRequest` { refreshToken:string } | `LoginResponse` (new tokens) |
| **Auth** | POST | /auth/logout | `Authorization: Bearer <jwt>` | `LogoutRequest` { refreshToken:string } | `{ message:"Logged out successfully" }` |
| **Auth** | POST | /auth/change-password | `Authorization: Bearer <jwt>` | `ChangePasswordRequest` { currentPassword:string, newPassword:string } | `{ message:"Password changed successfully" }` |
| **Upsert** | POST | /upsert/create | `X-User-Id: <uuid>` | `CreateEntryRequest` (see DTO) | `CreateEntryResponse` (full entry) |
| **Upsert** | PUT | /upsert/update | `X-User-Id: <uuid>` | `UpdateEntryRequest` (full fields) | `CreateEntryResponse` |
| **Upsert** | PATCH | /upsert/entries/{id}/amount | `id` path, `userId` query | `PatchAmountRequest` { amount:number } | `CreateEntryResponse` |
| **Upsert** | DELETE | /upsert/delete/{id} | `id` path, `userId` query | – | `{ message:"Entry deleted successfully" }` |
| **Upsert** | POST | /upsert/delete/bulk | `userId` query | `[id1, id2, …]` (array) | `{ message:"Bulk deletion result", count:number }` |
| **Upsert** | GET | /upsert/entries/{id} | `id` path, `userId` query | – | `CreateEntryResponse` |
| **Upsert** | GET | /upsert/entries | `userId` query, optional filters (type,startDate,endDate,page,size) | – | `Page<TransactionEntry>` |
| **Upsert** | GET | /upsert/search | `userId` query, `q` query, pagination | – | `Page<TransactionEntry>` |
| **Upsert** | GET | /upsert/summary | `userId` query | – | `{ totalIncome:number, totalExpense:number, netBalance:number }` |
| **Upsert** | GET | /upsert/entries/export | `userId` query | – | CSV file (`text/csv`) |
| **Upsert** | POST | /upsert/goals | `X-User-Id` header | `SavingsGoalRequest` | `SavingsGoalResponse` |
| **Upsert** | GET | /upsert/goals | `X-User-Id` header, `userId` query | – | `[SavingsGoalResponse]` |
| **Upsert** | PATCH | /upsert/goals/{id}/contribute | `X-User-Id` header, `userId` query, `amount` query | – | `SavingsGoalResponse` |
| **Upsert** | DELETE | /upsert/goals/{id} | `X-User-Id` header, `userId` query | – | 204 No Content |
| **Upsert** | POST | /upsert/budgets | `X-User-Id` header | `CategoryBudgetRequest` | `BudgetUtilizationResponse` |
| **Upsert** | GET | /upsert/budgets | `X-User-Id` header, `userId` query | – | `[BudgetUtilizationResponse]` |
| **Upsert** | DELETE | /upsert/budgets/{id} | `X-User-Id` header, `userId` query | – | 204 No Content |
| **Split** | POST | /upsert/groups | – | `CreateGroupRequest` | `ExpenseGroup` |
| **Split** | GET | /upsert/groups | `userId` query | – | `[ExpenseGroup]` |
| **Split** | GET | /upsert/groups/{groupId} | `groupId` path | – | `ExpenseGroup` |
| **Split** | POST | /upsert/groups/{groupId}/members | `groupId` path | `AddMemberRequest` | `GroupMember` |
| **Split** | GET | /upsert/groups/{groupId}/members | `groupId` path | – | `[GroupMember]` |
| **Split** | POST | /upsert/groups/{groupId}/expenses | `groupId` path | `CreateSharedExpenseRequest` | `SharedExpense` |
| **Split** | GET | /upsert/groups/{groupId}/expenses | `groupId` path | – | `[SharedExpense]` |
| **Split** | GET | /upsert/groups/{groupId}/balances | `groupId` path | – | `GroupBalanceResponse` |
| **Split** | POST | /upsert/groups/{groupId}/settle | `groupId` path, `fromUserId` query, `toUserId` query | – | `{ message:"Settlement recorded" }` |
| **Bill OCR** | POST | /bill/process/{userId} | `Authorization: Bearer <jwt>` | multipart `file` (image/pdf) | `CreateEntryResponse` |
| **Analytics** | GET | /analytics/category-pie-chart | `Authorization: Bearer <jwt>`, `userId` query, optional filters | – | `ChartData` |
| **Analytics** | GET | /analytics/timeline-chart | `Authorization: Bearer <jwt>`, `userId` query, optional timelineType, filters | – | `ChartData` |
| **Analytics** | GET | /analytics/comprehensive | `Authorization: Bearer <jwt>`, `userId` query, optional timeline/filter params | – | Full analytics payload |
| **Analytics** | POST | /analytics/custom-analytics | `Authorization: Bearer <jwt>` | `AnalyticsRequest` | Same as comprehensive |
| **Analytics** | GET | /analytics/transaction-entries | `Authorization: Bearer <jwt>`, `userId` query, pagination | – | `TransactionEntryPage` |
| **Analytics** | GET | /analytics/transactions/income-by-category | `Authorization: Bearer <jwt>`, `userId` query, `incomeCategory` query, optional dates, pagination | – | `TransactionEntryPage` |
| **Analytics** | GET | /analytics/transactions/by-type | `Authorization: Bearer <jwt>`, `userId` query, `type` query, optional dates, pagination | – | `TransactionEntryPage` |
| **Analytics** | GET | /analytics/health-score | `Authorization: Bearer <jwt>`, `userId` query | – | `HealthScoreResponse` |
| **Analytics** | GET | /analytics/ai-insights | `Authorization: Bearer <jwt>`, `userId` query | – | `AiInsightResponse` |
| **Analytics** | GET | /analytics/health | – | – | `{ status:"UP", service:"analytics-service" }` |
