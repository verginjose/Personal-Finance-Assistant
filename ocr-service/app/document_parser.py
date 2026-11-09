"""Local receipt/CSV parsing and descriptive transaction summaries."""
import collections
import csv
import io
import re
from decimal import Decimal, InvalidOperation

from receipt_parser import CATEGORY_RULES, classify, extract_date, parse_document


INCOME_CATEGORIES = frozenset(['BONUS', 'BUSINESS', 'CASHBACK_REWARDS', 'DIVIDENDS', 'FREELANCE', 'GIFTS_RECEIVED', 'GOVT_BENEFITS', 'INTEREST', 'INVESTMENTS', 'OTHER_INCOME', 'PENSION', 'RENTAL_INCOME', 'SALARY', 'SIDE_HUSTLE', 'TAX_REFUND'])
EXPENSE_CATEGORIES = frozenset(['BOOKS_AND_COURSES', 'BOOKS_AND_MAGAZINES', 'CAB_AND_AUTO', 'CHARITY_AND_DONATIONS', 'CHILDCARE', 'CLOTHING', 'COACHING', 'COFFEE_AND_SNACKS', 'CREDIT_CARD_PAYMENT', 'CRYPTO', 'DOCTOR_AND_CLINIC', 'ELDER_CARE', 'ELECTRICITY', 'ELECTRONICS', 'EMERGENCY_FUND', 'FINES_AND_PENALTIES', 'FIXED_DEPOSIT', 'FLIGHT_AND_TRAIN', 'FOOD_DELIVERY', 'FUEL', 'GAMING', 'GAS', 'GIFTS_GIVEN', 'GOAL', 'GROCERIES', 'GYM_AND_FITNESS', 'HEALTH_INSURANCE', 'HOME_APPLIANCES', 'HOME_LOAN_EMI', 'HOME_MAINTENANCE', 'HOTEL_AND_STAYS', 'INTERNET', 'LIFE_INSURANCE', 'LOAN_REPAYMENT', 'MEDICINES', 'MENTAL_WELLNESS', 'MOBILE_PHONE', 'MOVIES_AND_EVENTS', 'MUTUAL_FUNDS_SIP', 'OTHERS', 'OTT_SUBSCRIPTIONS', 'PARKING_AND_TOLLS', 'PERSONAL_CARE', 'PETS', 'PROPERTY_TAX', 'PUBLIC_TRANSPORT', 'RENT', 'RESTAURANTS', 'SETTLEMENT', 'SPORTS_AND_HOBBIES', 'STOCKS_AND_TRADING', 'STUDENT_LOAN_EMI', 'TAXES', 'TRAVEL_VACATION', 'TUITION_AND_FEES', 'VEHICLE_EMI', 'VEHICLE_INSURANCE', 'VEHICLE_MAINTENANCE', 'WATER'])

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
                # A known debit/credit column overrides textual income hints.
                _, expense, income = classify(body, vendor, -abs(amount) if kind == "Income" else abs(amount))
                if kind == "Expense":
                    expense = doc.get("expenseCategory") or expense or "OTHERS"
                explicit = row.get("expensecategory") if kind == "Expense" else row.get("incomecategory")
                if explicit:
                    valid = EXPENSE_CATEGORIES if kind == "Expense" else INCOME_CATEGORIES
                    if explicit.upper() not in valid:
                        raise ValueError("Unsupported transaction category")
                    if kind == "Expense": expense = explicit.upper()
                    else: income = explicit.upper()
                recurring = row.get("recurring", "false").lower()
                if recurring not in ("true", "false", "1", "0", "yes", "no", ""):
                    raise ValueError("Invalid recurring value")
                doc.update(vendor=vendor, amount=abs(amount), transactionType=kind,
                           expenseCategory=expense if kind == "Expense" else None,
                           incomeCategory=(income or "OTHER_INCOME") if kind == "Income" else None,
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
        amount = abs(_money(row.get("a", row.get("amount", ""))))
        kind = str(row.get("t", row.get("type", ""))).upper()
        if kind not in ("EXPENSE", "INCOME"):
            raise ValueError("Each transaction needs an Expense or Income type")
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
