"""Local receipt/CSV parsing and descriptive transaction summaries."""
import collections
import csv
import io
import re
from decimal import Decimal, InvalidOperation

from receipt_parser import classify, extract_date, parse_document


from accounting import (INCOME_CATEGORIES, EXPENSE_CATEGORIES, MOVEMENT_CATEGORIES,
                        INVESTMENT_CATEGORIES, TRANSACTION_KINDS)

def _money(value):
    try:
        amount = Decimal(str(value).strip().replace(",", ""))
        if not amount.is_finite() or abs(amount) >= 10_000_000:
            raise ValueError("Amount is not finite or is out of range")
        return float(amount)
    except (InvalidOperation, TypeError) as exc:
        raise ValueError("Invalid amount") from exc


def parse_file_text(text, *, is_csv=False, is_batch=False, default_currency="INR",
                    date_order="auto", category_search=None):
    if is_csv:
        reader = csv.DictReader(io.StringIO(text.lstrip("\ufeff")))
        if not reader.fieldnames:
            raise ValueError("CSV needs a header row")
        results = []
        for number, raw in enumerate(reader, 2):
            if not any(raw.values()):
                continue
            if None in raw:
                raise ValueError(f"CSV row {number} has more values than headers")
            row = {re.sub(r"[^a-z]", "", k.lower()): (v or "").strip()
                   for k, v in raw.items()}
            try:
                vendor = row.get("vendor") or row.get("name") or row.get("description")
                if not vendor:
                    raise ValueError("Missing vendor/name/description")
                debit, credit = row.get("debit"), row.get("credit")
                if debit or credit:
                    debit, credit = _money(debit or 0), _money(credit or 0)
                    if debit < 0 or credit < 0 or bool(debit) == bool(credit):
                        raise ValueError("Exactly one positive debit or credit is required")
                    amount, kind = debit or credit, "Expense" if debit else "Income"
                else:
                    amount = _money(row.get("amount", ""))
                    kind = (row.get("transactiontype") or row.get("type") or
                            ("Income" if amount < 0 else "Expense")).title()
                if kind not in ("Income", "Expense"):
                    raise ValueError("Type must be Income or Expense")
                date_text = row.get("date") or row.get("createdat")
                date = extract_date(date_text, date_order)[0] if date_text else None
                if date_text and not date:
                    raise ValueError("Invalid transaction date")
                currency = (row.get("currency") or default_currency).upper()
                if not re.fullmatch(r"[A-Z]{3}", currency):
                    raise ValueError("Currency must be a three-letter code")
                body = vendor + "\n" + row.get("description", "")
                doc, _, _ = parse_document(body, convert=False, default_currency=currency,
                                            category_search=category_search)
                # A known debit/credit column overrides textual direction hints.
                _, expense, income = classify(body, vendor, -abs(amount) if kind == "Income" else abs(amount))
                if doc.get("transactionKind") in ("TRANSFER", "INVESTMENT"):
                    if kind == "Income": income = doc.get("category")
                    else: expense = doc.get("category")
                elif doc.get("transactionKind") in ("REFUND", "REIMBURSEMENT"):
                    expense = doc.get("category") or "OTHERS"
                if kind == "Expense":
                    expense = doc.get("expenseCategory") or expense or "OTHERS"
                treatment = row.get("transactionkind", "").upper() or doc.get("transactionKind", "STANDARD")
                if treatment not in TRANSACTION_KINDS:
                    raise ValueError("Unsupported transaction treatment")
                # An explicit bank debit is authoritative; descriptive words alone
                # must not turn an outgoing payment into an expense recovery.
                if treatment in ("REFUND", "REIMBURSEMENT") and kind != "Income":
                    raise ValueError("Refunds and reimbursements must be incoming payments")
                explicit = row.get("category") or (row.get("expensecategory") if kind == "Expense" else row.get("incomecategory"))
                if explicit:
                    valid = (EXPENSE_CATEGORIES if kind == "Expense" or treatment in ("REFUND", "REIMBURSEMENT")
                             else INCOME_CATEGORIES)
                    valid = valid | MOVEMENT_CATEGORIES
                    if explicit.upper() not in valid:
                        raise ValueError("Unsupported transaction category")
                    if kind == "Expense" or treatment in ("REFUND", "REIMBURSEMENT"): expense = explicit.upper()
                    else: income = explicit.upper()
                category = expense if kind == "Expense" or treatment in ("REFUND", "REIMBURSEMENT") else income
                if category in MOVEMENT_CATEGORIES and treatment == "STANDARD":
                    treatment = "INVESTMENT" if category in INVESTMENT_CATEGORIES else "TRANSFER"
                original_id = row.get("originaltransactionid")
                if original_id and (not original_id.isdigit() or int(original_id) <= 0):
                    raise ValueError("Original transaction ID must be a positive integer")
                if original_id and treatment not in ("REFUND", "REIMBURSEMENT"):
                    raise ValueError("Only refunds and reimbursements can link to an expense")
                subcategory = row.get("subcategory") or None
                if subcategory and len(subcategory) > 80:
                    raise ValueError("Subcategory cannot exceed 80 characters")
                recurring = row.get("recurring", "false").lower()
                if recurring not in ("true", "false", "1", "0", "yes", "no", ""):
                    raise ValueError("Invalid recurring value")
                if treatment in ("REFUND", "REIMBURSEMENT") and recurring in ("true", "1", "yes"):
                    raise ValueError("Refunds and reimbursements cannot recur")
                recurring_period = row.get("recurringperiod", "").upper() or None
                if recurring_period not in (None, "DAILY", "WEEKLY", "MONTHLY", "YEARLY"):
                    raise ValueError("Unsupported recurrence period")
                doc.update(recurringPeriod=recurring_period, transactionKind=treatment, originalTransactionId=int(original_id) if original_id else None,
                           subcategory=subcategory, category=category,
                           vendor=vendor, amount=abs(amount), transactionType=kind,
                           expenseCategory=expense if kind == "Expense" or treatment in ("REFUND", "REIMBURSEMENT") else None,
                           incomeCategory=(income or "OTHER_INCOME") if kind == "Income" and treatment not in ("REFUND", "REIMBURSEMENT") else None,
                           currency=currency, date=date, description=row.get("description") or vendor,
                           recurring=recurring in ("true", "1", "yes"))
                results.append(doc)
            except ValueError as exc:
                raise ValueError(f"CSV row {number}: {exc}") from exc
        if not results:
            raise ValueError("CSV contains no transactions")
        return results if is_batch else results[0] if len(results) == 1 else _single_csv_error()
    if is_batch:
        raise ValueError("Unstructured statement batches are unsupported by the local parser; upload a CSV with date, description/name, amount and type, or debit/credit columns")
    doc, evidence, warnings = parse_document(text, convert=False, default_currency=default_currency,
                                           date_order=date_order, category_search=category_search)
    if "no_amount" in warnings or "amount_out_of_range" in warnings:
        raise ValueError("Receipt has no reliable amount; review or upload a clearer image")
    # Keep evidence available to callers without changing the callback document schema.
    return doc


def _single_csv_error():
    raise ValueError("CSV has multiple transactions; select batch import")


def analyze_history(transactions):
    if not isinstance(transactions, list):
        raise ValueError("transactions must be a list")
    groups = collections.defaultdict(lambda: collections.defaultdict(float))
    for row in transactions:
        if not isinstance(row, dict):
            raise ValueError("Each transaction must be an object")
        # Compact query-service payload has no currency. Do not label its totals
        # with a guessed currency or mix them with explicitly labelled currencies.
        currency = str(row.get("currency") or "unspecified")
        treatment = str(row.get("transactionKind") or "STANDARD").upper()
        category = row.get("c") or row.get("category") or row.get("expenseCategory") or row.get("incomeCategory")
        if treatment in ("TRANSFER", "INVESTMENT") or category in MOVEMENT_CATEGORIES:
            continue
        amount = abs(_money(row.get("a", row.get("amount", ""))))
        kind = str(row.get("t", row.get("type", ""))).upper()
        if kind not in ("EXPENSE", "INCOME"):
            raise ValueError("Each transaction needs an Expense or Income type")
        if treatment in ("REFUND", "REIMBURSEMENT"):
            kind, amount = "EXPENSE", -amount
        groups[currency][kind] += amount
    insights, warnings = [], []
    for currency, totals in sorted(groups.items()):
        label = "" if currency == "unspecified" else " " + currency
        expense, income = totals["EXPENSE"], totals["INCOME"]
        insights.append(f"Recorded expenses: {expense:.2f}{label}; recorded income: {income:.2f}{label}.")
        if expense > income and income:
            warnings.append(f"Recorded expenses exceed recorded income by {expense - income:.2f}{label}.")
    if not transactions:
        insights.append("No transactions available for this period.")
    return {"insights": insights, "warnings": warnings,
            "suggestions": ["Review your largest expense categories before setting next period's budget."] if transactions else []}
