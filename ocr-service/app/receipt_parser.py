"""Shared deterministic receipt extraction; no network or model calls."""
import datetime as dt
import re

# ── Vendor / category knowledge ──────────────────────────────────────────────
# Checked against the first lines of the receipt; first match wins.
BRANDS = [
    (r"mc\s*dona\s*ld", "McDonald's"),
    (r"grand\s+lux", "Grand Lux Cafe"),
    (r"grotto\s+pizzer", "Grotto Pizzeria & Tavern"),
    (r"green\s*field", "Green Field"),
    (r"starbucks", "Starbucks"),
    (r"dunkin", "Dunkin'"),
    (r"denny", "Denny's"),
    (r"subway", "Subway"),
    (r"domino", "Domino's"),
    (r"pizza\s+hut", "Pizza Hut"),
    (r"burger\s+king", "Burger King"),
    (r"kfc", "KFC"),
    (r"chipotle", "Chipotle"),
    (r"taco\s+bell", "Taco Bell"),
    (r"wendy", "Wendy's"),
    (r"whole\s+foods", "Whole Foods"),
    (r"trader\s+joe", "Trader Joe's"),
    (r"wal[- ]?mart", "Walmart"),
    (r"target\b", "Target"),
    (r"costco", "Costco"),
    (r"shell\b", "Shell"),
    (r"chevron", "Chevron"),
    (r"netflix", "Netflix"),
    (r"spotify", "Spotify"),
    (r"yogurt\s*land", "Yogurtland"),
    (r"bella\s+pasta", "Bella Pasta"),
    (r"popeye", "Popeye's"),
    (r"in-?n-?out", "In-N-Out Burger"),
]

# First match wins. Patterns are applied to vendor + receipt text, lowercased.
CATEGORY_RULES = [
    ("OTT_SUBSCRIPTIONS", r"netflix|spotify|hotstar|prime\s+video|disney\+|hulu|"
                          r"youtube\s+premium|apple\s+music|sonyliv|zee5"),
    ("FOOD_DELIVERY", r"swiggy|zomato|doordash|door\s+dash|uber\s*eats|grubhub|"
                      r"deliveroo|postmates|instacart"),
    ("RESTAURANTS", r"restaurant|ristorante|caf[eé]\b|\bcafe\b|coffee|pizzer|pizza|"
                    r"grill|tavern|\bbar\b|diner|bistro|kitchen|eatery|sushi|"
                    r"bakery|burger|mc\s*donald|mcnugget|mcdelivery|french\s+fries|"
                    r"nugget|kfc|subway|domino|starbucks|dunkin|teriyaki|"
                    r"taqueria|burrito|marisqueria|bar\s+tab|bowl\b|"
                    r"chipotle|taco\s+bell|wendy|panda\s+express|grand\s+lux|"
                    r"grotto|green\s*field|\bnorma\b|denny|ihop|applebee|"
                    r"olive\s+garden|buffet|steakhouse|noodle|ramen|deli\b|"
                    r"bills?\s+bar|brasserie|trattoria|osteria"),
    ("GROCERIES", r"grocer|super\s*market|hyper\s*market|whole\s+foods|"
                  r"trader\s+joe|safeway|kroger|costco|\baldi\b|\blidl\b|\btesco\b|"
                  r"bigbasket|big\s+basket|dmart|reliance\s+fresh|"
                  r"food\s+lion|publix|wegmans|sprouts\s+market|"
                  r"market\s+basket"),
    ("FUEL", r"\bfuel\b|petrol|diesel|gas\s+station|\bshell\b|chevron|exxon|"
             r"\bmobil\b|texaco|citgo|speedway|\bbp\b|indian\s+oil|"
             r"hp\s+petrol|bharat\s+petroleum|kwick\s+star"),
    ("CAB_AND_AUTO", r"\buber\b|\blyft\b|\bola\b|\btaxi\b|auto\s+rickshaw|"
                     r"yellow\s+cab|cab\s+(ride|fare|service)"),
    ("PUBLIC_TRANSPORT", r"\bmetro\b|transit|railway|rail\s+ticket|amtrak|"
                         r"bus\s+ticket|irctc|bart\b"),
    ("PARKING_AND_TOLLS", r"parking|\btoll\b|fastag"),
    ("ELECTRICITY", r"electric(ity)?\b|power\s+bill|bescom|tneb|"
                    r"adani\s+electricity|duke\s+energy"),
    ("WATER", r"water\s+bill|water\s+works|municipal\s+water"),
    ("GAS", r"\bgas\s+bill\b|indane|hp\s+gas|bharat\s+gas"),
    ("INTERNET", r"internet|broadband|\bfiber\b|comcast|xfinity|"
                 r"verizon\s+fios|airtel|jio\s+fiber|act\s+fibernet"),
    ("MOBILE_PHONE", r"\bmobile\b|cellular|vodafone|\bairtel\b|jio\b|"
                     r"t-mobile|at&t|verizon\s+wireless"),
    ("HEALTH_INSURANCE", r"health\s+insurance|mediclaim"),
    ("MEDICINES", r"pharmacy|chemist|medical\s+store|medicines|\bcvs\b|"
                  r"walgreens|rite\s+aid|apollo\s+pharmacy"),
    ("DOCTOR_AND_CLINIC", r"hospital|clinic|doctor|dentist|dental|"
                          r"diagnostic|lab\s+test|physician"),
    ("GYM_AND_FITNESS", r"\bgym\b|fitness|cult\.?fit|planet\s+fitness"),
    ("TUITION_AND_FEES", r"tuition|school\s+fee|college\s+fee|semester\s+fee|"
                         r"university"),
    ("BOOKS_AND_COURSES", r"udemy|coursera|kindle|book\s*store|course\s+fee|"
                          r"online\s+course"),
    ("CLOTHING", r"clothing|apparel|zara|h&m|uniqlo|\bgap\b|levi|nike|adidas|puma"),
    ("ELECTRONICS", r"electronics|best\s+buy|apple\s+store|samsung|lenovo|\bdell\b"),
    ("PERSONAL_CARE", r"salon|\bspa\b|barber|haircut|beauty"),
    ("MOVIES_AND_EVENTS", r"cinema|movie|theat(er|re)|\bamc\b|\bregal\b|"
                          r"\bpvr\b|inox|concert|ticketmaster"),
    ("TRAVEL_VACATION", r"airbnb|booking\.com|makemytrip|expedia|trivago"),
    ("HOTEL_AND_STAYS", r"hotel|resort|\binn\b|marriott|hilton|\boyo\b"),
    ("FLIGHT_AND_TRAIN", r"airline|flight|airways|\bdelta\b|united\s+airlines|"
                         r"emirates|indigo|ryanair|easyjet"),
    ("LIFE_INSURANCE", r"life\s+insurance|term\s+plan"),
    ("VEHICLE_INSURANCE", r"vehicle\s+insurance|car\s+insurance|motor\s+insurance|"
                          r"bike\s+insurance"),
    ("MUTUAL_FUNDS_SIP", r"mutual\s+fund|\bsip\b|zerodha\s+coin|groww"),
    ("STOCKS_AND_TRADING", r"zerodha|upstox|groww|robinhood|e\*?trade|"
                           r"schwab|fidelity|brokerage\s+account|"
                           r"stock\s+(purchase|trade)"),
    ("CRYPTO", r"crypto|bitcoin|binance|coinbase|wazirx"),
    ("PETS", r"pet\s+store|veterinary|\bvet\b|pet\s+food"),
    ("CHARITY_AND_DONATIONS", r"donation|charity|\bngo\b|temple\s+donation"),
    ("CHILDCARE", r"childcare|daycare|creche"),
    ("TAXES", r"income\s+tax|advance\s+tax|tax\s+payment"),
    ("RENT", r"house\s+rent|rent\s+due|rent\s+payment|\brent\b"),
]
CATEGORY_RULES = [(name, re.compile(pat, re.I)) for name, pat in CATEGORY_RULES]

# Menu items only count as a restaurant when no stronger category matched.
FOOD_ITEM_RE = re.compile(
    r"\b(soup|salad|steak|ribs?|catfish|salmon|shrimp|prawn|chicken|burger|"
    r"sandwich|pasta|noodle|ramen|rice|curry|biryani|taco|burrito|quesadilla|"
    r"pizza|omelet|omelette|pancake|waffle|fries|coffee|latte|espresso|"
    r"beer|wine|vodka|whiskey|cocktail|dessert|cake|souffle|bisque|gumbo|"
    r"jambalaya|etouffee|tiramisu|bruschetta|calamari|sushi|teriyaki|"
    r"pho\b|goi\b|samosa|caldo|farsan|chole|dosa|idli|tikka|tandoori|kebab|"
    r"paneer|naan|bbq|perrier|cola|soda|juice|yogurt|japanese|"
    r"chinese|thai|mexican|italian|indian|korean|vietnamese|wings?\b|"
    r"ribeye|strip\s+steak|empanada|falafel|goulash|koobideh|sabzi|"
    r"hummus|shawarma|gyro|chicharron|maduros|yams|artichok|carciofi|"
    r"branzino|scallop|polenta|risotto|lasagna|ravioli|enchilada|"
    r"tamale|pupusa|jerk|oxtail|brisket|pulled\s+pork|seafood|oyster|"
    r"lobster|crab|clams?|mussels|tuna|salmon|fish|cajun|kajun|"
    r"mac\s+sauce|albondigas|mojito)\b", re.I)
FOOD_SUBSTRING_RE = re.compile(r"bbq", re.I)  # "SmokeBoxBBQ"
BUSINESS_RE = re.compile(
    r"restaurant|ristorante|grill|caf[eé]|coffee|pizzer|tavern|bistro|diner|"
    r"kitchen|bar\b|house|hotel|market|store|cuisine|food|teriyaki|buffet|"
    r"steakhouse|bakery|shop|lounge|pub\b|bar\s+&|eatery", re.I)

INCOME_RE = re.compile(r"salary|payroll|payslip|refund|reimbursement|cashback|"
                       r"interest\s+credit|dividend|payment\s+received|"
                       r"amount\s+credited|credited\s+to", re.I)
RECURRING_RE = re.compile(r"netflix|spotify|hotstar|prime\s+video|disney\+|hulu|"
                          r"subscription|membership|internet\s+bill|"
                          r"insurance\s+premium|monthly\s+plan|annual\s+plan|"
                          r"rent\s+due|house\s+rent", re.I)
RENT_EXCEPTION_RE = re.compile(r"rental\s+car|car\s+rental|rent\s+a\s+car", re.I)

GENERIC_HEADER = re.compile(
    r"^(receipt|invoice|duplicate|copy|bill|billa|customer|order|table|tab|server|"
    r"station|guests?|date|time|thank|welcome|tel|phone|store|sale|cash|change|"
    r"subtotal|sub\s+total|total|tax|gst|hst|pst|vat|visa|mastercard|debit|"
    r"credit|card|amount|item|qty|price|ks#|settled|dine\s*in|take\s*-?out|"
    r"bar\s+tab|survey|approval|cashier|manager|drive\s*thru|check|seat|please|"
    r"party|employee|servers?|sales?|tickets?|your\s+guest|guest\s+number)\b",
    re.I)
# A bare "RESTAURANT" line is a label, not a vendor name.
GENERIC_WORD_RE = re.compile(
    r"^(restaurant|ristorante|caf[eé]|bar|grill|kitchen|diner|bistro|"
    r"shop|store|market|bakery|tavern|dining\s+room|pizzer[i]?a)\s*$", re.I)
BARE_PRICE_RE = re.compile(r"^[$₹€£¥]?\s*\d+[.,]\d{2}\s*[A-Za-z]?$")
ADDRESS_RE = re.compile(
    r"\b\d+\s+\w*\s*(st|street|rd|road|ave|avenue|blvd|boulevard|hwy|highway|"
    r"dr|drive|ln|lane|way|pkwy|parkway|pl|place|ct|court|cir|circle|ter|"
    r"terrace|trl|trail)\b", re.I)
PHONE_RE = re.compile(r"(\(?\d{3}\)?[-.\s]\d{3}[-.\s]\d{4}|\d{3}[-.]\d{4})")

MONTH_NAMES = {
    "jan": 1, "feb": 2, "mar": 3, "apr": 4, "may": 5, "jun": 6,
    "jul": 7, "aug": 8, "sep": 9, "oct": 10, "nov": 11, "dec": 12,
}
MONTH_ALT = (r"January|February|March|April|May|June|July|August|September|"
             r"October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|"
             r"Oct|Nov|Dec")

NUM_TOKEN = r"-?(?:[$₹€£¥]\s*)?-?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{1,2})?"
# Not part of a longer alphanumeric token ("Acctxxxxxxx2276", "Order#8262").
NUMBER_RE = re.compile(r"(?<![A-Za-z0-9.])(" + NUM_TOKEN + r")(?![A-Za-z0-9.])")

TOTAL_STRONG_RE = re.compile(
    r"\b(grand\s+total|total\s+due|amount\s+due|amt\s+due|balance\s+due|"
    r"check\s+total|total\s+amount|net\s+total|total\s+payable)\b", re.I)
AMOUNT_EXCLUDE_RE = re.compile(
    r"(sub\s*-?\s*total|subtotal|savings|you\s+saved|\btax\b|\bgst\b|\bhst\b|"
    r"\bpst\b|\bvat\b|gratuity|\btip\b|\bchange\b|tendered?|\bcash\b|"
    r"balance\s+forward|discount|visa|mastercard|amex|debit|\bcredit\b|"
    r"\bqty\b|\bitem\b|\bprice\b)", re.I)

ITEM_SKIP_RE = re.compile(
    r"(sub\s*-?\s*total|subtotal|\btotal\b|\btax\b|\bgst\b|\bhst\b|\bpst\b|"
    r"\bvat\b|gratuity|\btip\b|balance|\bchange\b|tendered?|\bcash\b|"
    r"visa|mastercard|amex|debit|\bcredit\b|discount|savings)", re.I)


# ── Text helpers ─────────────────────────────────────────────────────────────

def clean_line(line):
    line = line.replace(" ", " ").strip()
    line = re.sub(r"\s+", " ", line)
    return line.strip(" *_=~|<>[]\t")


def _expand_year(y):
    y = int(y)
    if y < 100:
        return 2000 + y if y < 70 else 1900 + y
    return y


def _mk_date(y, m, d):
    try:
        return dt.date(y, m, d)
    except ValueError:
        return None


def _ok_year(y):
    return 1990 <= y <= dt.date.today().year + 1


# A year may touch a clock (Jun281705:36P / 08/09/19428PM).
# Require a complete clock suffix before accepting a shorter year.
DATE_YEAR = (r"(?:199\d|20\d{2}|\d{2})(?="
             r"\d{1,2}[:.]\d{2}|\d{1,4}\s*(?:AM|PM)\b|[^\d:]|$)")


def extract_date(text, order="auto"):
    # Common character confusions, restricted to a month followed by a day.
    normalized = re.sub(r"\bJu[1I](?=\d)", "Jul", text, flags=re.I)
    normalized = re.sub(r"\bApri1(?=\s*\d)", "April", normalized, flags=re.I)
    normalized = re.sub(r"\b0ct(?=\d)", "Oct", normalized, flags=re.I)
    result, match = _extract_date(normalized, order)
    if match and match in normalized:
        start = normalized.index(match)
        match = text[start:start + len(match)]
    return result, match


def _extract_date(text, order="auto"):
    """Return (iso_date, matched_text) or (None, None)."""
    # Some OCR output breaks decimals with spaces: "69. 25".
    t = re.sub(r"(?<=\d)\.\s+(?=\d)", ".", text)

    for m in re.finditer(r"\b((?:19|20)\d{2})[-/.](\d{1,2})[-/.](\d{1,2})(?!\d{1,2}[-/])", t):
        y = int(m.group(1))
        if _ok_year(y):
            d = _mk_date(y, int(m.group(2)), int(m.group(3)))
            if d:
                return d.isoformat(), m.group(0)

    # "Jun 24 20:49:53 2018" — a time between the day and the year.  Must be
    # tried before the general form, which would read "20" as the year.
    for m in re.finditer(
            r"\b(" + MONTH_ALT + r")\.?(?![A-Za-z])\s*(\d{1,2})\s+"
            r"\d{1,2}:\d{2}(?::\d{2})?\s*(?:AM|PM)?\s+(\d{4})", t, re.I):
        y = int(m.group(3))
        if not _ok_year(y):
            continue
        month = MONTH_NAMES[m.group(1)[:3].lower()]
        d = _mk_date(y, month, int(m.group(2)))
        if d:
            return d.isoformat(), m.group(0)

    # "Apr 25, 2015", "Jun 26.15", "Feb23'19", "Date:Apr 012019" (no space
    # between day and year). Separator can be comma, dot or apostrophe.
    # (?![A-Za-z]) keeps "MART" from matching "Mar".
    for m in re.finditer(
            r"\b(" + MONTH_ALT + r")\.?(?![A-Za-z])\s*(\d{1,2})(?:st|nd|rd|th)?"
            r"\s*[,.'’]?\s*(" + DATE_YEAR + r")", t, re.I):
        y = _expand_year(m.group(3))
        if not _ok_year(y):
            continue
        month = MONTH_NAMES[m.group(1)[:3].lower()]
        d = _mk_date(y, month, int(m.group(2)))
        if d:
            return d.isoformat(), m.group(0)

    # "15 Nov 2015", "04-Nov-20189:07" (hyphens, year glued to the time).
    for m in re.finditer(
            r"\b(\d{1,2})(?:st|nd|rd|th)?\s*[-/.]?\s*(" + MONTH_ALT + r")"
            r"\.?(?![A-Za-z])\s*[-/.,]?\s*(" + DATE_YEAR + r")", t, re.I):
        y = _expand_year(m.group(3))
        if not _ok_year(y):
            continue
        month = MONTH_NAMES[m.group(2)[:3].lower()]
        d = _mk_date(y, month, int(m.group(1)))
        if d:
            return d.isoformat(), m.group(0)

    # No word boundaries: OCR often glues the date to neighbouring text,
    # e.g. "16:4304/25/15" or "5/26/201612:53:10PM".
    for pattern in (r"(\d{1,2})[/.-](\d{1,2})[/.-](" + DATE_YEAR + r")",
                    # OCR drops the second separator: "02-082015".
                    r"(\d{1,2})[/.-](\d{1,2})((?:19|20)\d{2})"):
        for m in re.finditer(pattern, t):
            a, b, y = int(m.group(1)), int(m.group(2)), int(m.group(3))
            y = _expand_year(y)
            if not _ok_year(y):
                continue
            if a > 12 and b <= 12:
                day, month = a, b
            elif b > 12 and a <= 12:
                month, day = a, b
            elif order == "dmy":
                day, month = a, b
            else:  # mdy / auto: this dataset is US, so prefer month first
                month, day = a, b
            d = _mk_date(y, month, day)
            if d:
                return d.isoformat(), m.group(0)

    return None, None


def _num_value(token):
    t = token.strip().replace(",", "").replace(" ", "")
    t = re.sub(r"[$₹€£¥]", "", t)
    try:
        return float(t)
    except ValueError:
        return None


# Repair only the total label, never digits in the monetary value.
TOTAL_LABEL_RE = re.compile(
    r"(?<![A-Za-z])(?:grand\s+total|total\s+due|amount\s+due|amt\s+due|"
    r"balance\s+due|check\s+total|total\s+amount|net\s+total|"
    r"total\s+payable|ant\s+due|take[- ]?out\s+total|total\s+paid|"
    r"total\s+to\s+pay|tota[l1i]|totl)(?![A-Za-z])", re.I)
TIME_RE = re.compile(r"\b\d{1,2}:\d{2}(?::\d{2})?\s*(?:AM|PM|PH)?", re.I)
MONEY_ONLY_RE = re.compile(r"^\s*(" + NUM_TOKEN + r")\s*[A-Za-z]?\s*$")


def extract_amount(text):
    """Return (signed amount, evidence line, source), preferring final totals."""
    lines = [ln for ln in (clean_line(x) for x in text.splitlines()) if ln]

    def candidates(line):
        return [(m.group(1), _num_value(m.group(1)))
                for m in NUMBER_RE.finditer(TIME_RE.sub(" ", line))]

    totals = []
    for i, line in enumerate(lines):
        label = TOTAL_LABEL_RE.search(line)
        if not label or AMOUNT_EXCLUDE_RE.search(line) or re.search(r"total\s+items?", line, re.I):
            continue
        # A number before the label may belong to the preceding tax column.
        suffix = re.sub(r"\d+(?:\.\d+)?\s*%", " ", line[label.end():])
        nums = candidates(suffix)
        decimals = [n for n in nums if "." in n[0]]
        pick = decimals[-1] if decimals else (nums[-1] if nums else None)
        evidence, source = line, "total_line"
        if pick is None:
            nearby = []
            following_lines = lines[i + 1:i + 4]
            if following_lines and re.fullmatch(r"due", following_lines[0], re.I):
                following_lines = following_lines[1:]
            if following_lines and re.search(r"visa|mastercard|credit card", following_lines[0], re.I):
                following_lines = following_lines[1:]
            for following in following_lines:
                match = MONEY_ONLY_RE.fullmatch(following)
                if not match or "." not in match.group(1):
                    break  # do not cross a cash/tax/item label
                nearby.append((_num_value(match.group(1)), following))
            if nearby:
                value, evidence = nearby[0]
                # OCR may interleave subtotal/tax/total columns. Prefer a
                # value supported by addition, otherwise preserve reading order.
                prior = [v for previous in lines[max(0, i - 8):i]
                         for token, v in candidates(previous) if "." in token]
                pool = prior + [v for v, _ in nearby]
                for candidate, candidate_line in nearby:
                    if any(abs(a + b - candidate) < 0.011
                           for j, a in enumerate(pool) for b in pool[j + 1:]
                           if a > 0 and b > 0):
                        value, evidence = candidate, candidate_line
                        break
                pick, source = ("", value), "total_next_line"
            else:
                # OCR can also put the value immediately before its label.
                nums = [n for n in candidates(line[:label.start()]) if "." in n[0]]
                if nums:
                    pick = nums[-1]
                elif i and MONEY_ONLY_RE.fullmatch(lines[i - 1]):
                    nums = [n for n in candidates(lines[i - 1]) if "." in n[0]]
                    if nums:
                        previous = [(v, lines[j]) for j in range(max(0, i - 2), i)
                                    if MONEY_ONLY_RE.fullmatch(lines[j])
                                    for token, v in candidates(lines[j]) if "." in token]
                        value, evidence = max(previous, key=lambda n: abs(n[0]))
                        pick, source = ("", value), "total_previous_line"
        if pick and pick[1] is not None and 0 < abs(pick[1]) < 10_000_000:
            strong = bool(TOTAL_STRONG_RE.search(line))
            # A paid/final total includes service charges or gratuity added later.
            paid = bool(re.search(r"total\s+paid", label.group(), re.I))
            totals.append(((paid, strong, i), pick[1], evidence, source))
    if totals:
        _, value, line, source = max(totals, key=lambda n: n[0])
        return value, line, source

    best = None
    for i, line in enumerate(lines):
        if AMOUNT_EXCLUDE_RE.search(line):
            continue
        # A standalone amount below a payment label is tender, not the bill.
        if i and re.search(r"cash|tender|change", lines[i - 1], re.I) and MONEY_ONLY_RE.fullmatch(line):
            continue
        for token, value in candidates(line):
            if re.search(r"\.\d{2}$", token.strip()) and value is not None and 0 < abs(value) < 10_000_000:
                if best is None or abs(value) > abs(best[0]):
                    best = (value, line)
    if best:
        return best[0], best[1], "max_amount"
    return None, None, "none"


def _titleish(value):
    value = re.sub(r"\s+", " ", value).strip(" *_=~-|.,:;\t<>[]")
    value = re.sub(r"\s*#\s*\d{2,6}\s*$", "", value)   # "Denny's #8129"
    value = re.sub(r"(?i)^e1\s+", "El ", value)        # OCR: El -> E1
    letters = [c for c in value if c.isalpha()]
    upper_ratio = (sum(c.isupper() for c in letters) / len(letters)
                   if letters else 0)
    if upper_ratio >= 0.7 or value.islower():
        value = " ".join(
            w.capitalize() if not w.startswith(("Mc", "O'")) else w
            for w in value.split())
    return value


def extract_vendor(text):
    lines = [ln for ln in (clean_line(x) for x in text.splitlines()) if ln]
    head = lines[:15]
    joined = " ".join(head).lower()
    for pattern, name in BRANDS:
        if re.search(pattern, joined, re.I):
            return name

    candidates = []
    for i, line in enumerate(lines[:10]):
        core = re.sub(r"\s*#\s*\d{2,6}\s*$", "", line)  # store number suffix
        low = core.lower()
        if i > 2 and re.fullmatch(r"qty|item|amt|description|price", low):
            break
        letters = sum(c.isalpha() for c in core)
        if letters < 3 or len(core) > 60:
            continue
        if len(set(low.replace(" ", ""))) < 3:
            continue  # "xxxxxxxxxxx", "--------"
        if re.match(r"^#?\d+\s+(pickup|takeout)\b", low):
            continue
        if GENERIC_HEADER.match(low) or GENERIC_WORD_RE.match(low):
            continue
        if ADDRESS_RE.search(core) or PHONE_RE.search(core):
            continue
        if "www." in low or re.search(r"\.(com|net|org|con|co|io)\b", low):
            continue  # website, not the vendor name
        if extract_date(core)[0]:
            continue
        if BUSINESS_RE.search(core):
            core = re.sub(r"(?i)(?<=[A-Za-z])\d{4,}.*$", "", core).strip()
        if core[:1].isdigit() or re.search(r"\d{4,}", core):
            continue
        if re.match(r"^.*\s[$₹€£¥]?\d+[.,]\d{2}\s*[A-Za-z]?$", core):
            break  # reached the item block; the vendor is above it
        if i + 1 < len(lines) and BARE_PRICE_RE.match(lines[i + 1]):
            break  # the next line is an item price; vendor is above
        candidates.append(core)

    if not candidates:
        return None

    def score(line):
        alpha = [c for c in line if c.isalpha()]
        upper_ratio = sum(c.isupper() for c in alpha) / len(alpha)
        return (3 if BUSINESS_RE.search(line) else 0,
                1 if len(line.split()) >= 2 else 0,
                1 if upper_ratio >= 0.7 else 0)

    # Ties go to the line nearer the top of the receipt.
    best = max(enumerate(candidates), key=lambda pair: (score(pair[1]), -pair[0]))
    return _titleish(best[1])


PRICE_LINE_RE = re.compile(
    r"^[$₹€£¥]?\s*(\d{1,3}(?:,\d{3})*(?:\.\d{2})|\d+\.\d{2})\s*[A-Za-z]?$")


def extract_items(text):
    lines = [ln for ln in (clean_line(x) for x in text.splitlines()) if ln]
    items, seen = [], set()

    def add(name):
        name = re.sub(r"^(?:\d{1,4}\s*[#):.,-]*\s*)+", "", name)
        name = name.strip(" .,:;#-|")
        if len(name) < 2 or not re.search(r"[A-Za-z]", name):
            return
        key = name.lower()
        if key in seen:
            return
        seen.add(key)
        items.append(name)

    for i, line in enumerate(lines):
        if ITEM_SKIP_RE.search(line):
            continue
        m = re.match(r"^(.*?)\s+[$₹€£¥]?\s*(\d+(?:[.,]\d{2}))\s*[A-Za-z]?$", line)
        if m:
            if _num_value(m.group(2)) not in (None, 0):
                add(m.group(1))
            continue
        # PaddleOCR often puts the item name and its price on separate lines.
        if i + 1 >= len(lines):
            continue
        pm = PRICE_LINE_RE.match(lines[i + 1])
        if not pm or _num_value(pm.group(1)) in (None, 0):
            continue
        if (ADDRESS_RE.search(line) or PHONE_RE.search(line)
                or GENERIC_HEADER.match(line) or len(line) > 60):
            continue
        if not re.search(r"[A-Za-z]{2}", line):
            continue
        add(line)
    return items


def detect_currency(text):
    """Return (iso_code, source)."""
    upper = text.upper()
    if "₹" in text or re.search(r"\bRS\.?\b", upper):
        return "INR", "symbol"
    if re.search(r"\bEURO?\b", upper):
        return "EUR", "explicit_code"
    for code in ("USD", "CAD", "EUR", "GBP", "AUD", "SGD", "AED", "INR", "JPY"):
        if re.search(r"\b" + code + r"\b", upper):
            return code, "explicit_code"
    if "€" in text:
        return "EUR", "symbol"
    if "£" in text:
        return "GBP", "symbol"
    if "¥" in text:
        return "JPY", "symbol"
    # Canadian postal code (A1A 1A1) or GST/HST with a province code.
    if re.search(r"\b[ABCEGHJ-NPRSTVXY]\d[A-Z]\s?\d[A-Z]\d\b", upper):
        return "CAD", "country_hint"
    if re.search(r"\b(AL|AK|AZ|AR|CA|CO|CT|DE|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|"
                 r"ME|MD|MA|MI|MN|MS|MO|MT|NE|NV|NH|NJ|NM|NY|NC|ND|OH|OK|OR|"
                 r"PA|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY)\s*\d{5}\b", upper):
        return "USD", "country_hint"
    if "$" in text:
        return "USD", "symbol"
    return "INR", "default"


def _head_text(text, lines=15):
    """Header + item region; excludes marketing footers ("Now on Netflix")."""
    return "\n".join(text.splitlines()[:lines])


def classify(text, vendor, amount, items=None):
    haystack = " ".join(filter(None, [vendor, _head_text(text),
                                      " ".join(items or [])]))
    tx_type = "Income" if INCOME_RE.search(haystack) else "Expense"
    if amount is not None and amount < 0:
        tx_type = "Income"

    if tx_type == "Income":
        low = haystack.lower()
        income = "OTHER_INCOME"
        for name, pattern in (("SALARY", r"salary|payroll|payslip"),
                              ("FREELANCE", r"freelance"),
                              ("BUSINESS", r"business"),
                              ("DIVIDENDS", r"dividend"),
                              ("INTEREST", r"interest"),
                              ("RENTAL_INCOME", r"rent"),
                              ("CASHBACK_REWARDS", r"cashback|reward"),
                              ("TAX_REFUND", r"tax\s+refund"),
                              ("GIFTS_RECEIVED", r"gift")):
            if re.search(pattern, low, re.I):
                income = name
                break
        return tx_type, None, income

    category = "OTHERS"
    for name, pattern in CATEGORY_RULES:
        if pattern.search(haystack):
            if name == "RENT" and RENT_EXCEPTION_RE.search(haystack):
                continue
            category = name
            break
    if category == "OTHERS" and (FOOD_ITEM_RE.search(haystack)
                                 or FOOD_SUBSTRING_RE.search(haystack)):
        category = "RESTAURANTS"
    return tx_type, category, None


def is_recurring(text, vendor, items=None):
    haystack = " ".join(filter(None, [vendor, _head_text(text),
                                      " ".join(items or [])]))
    if RENT_EXCEPTION_RE.search(haystack):
        return False
    return bool(RECURRING_RE.search(haystack))


def convert_amount(amount, currency, target, rates):
    """Mirror ocr-service ``_convert_currency``."""
    normalized = "INR" if currency == "₹" else (currency or "INR").upper()
    target = (target or "INR").upper()
    if target != "USD" and target not in rates:
        target = "INR"
    in_usd = amount if normalized == "USD" else amount / rates.get(normalized, 1.0)
    in_inr = in_usd * rates.get("INR", 83.0)
    return {
        "amountInr": round(in_inr, 2),
        "amountUsd": round(in_usd, 2),
        "amountDefault": round(in_usd * rates.get(target, 1.0), 2),
        "defaultCurrency": target,
    }


def parse_document(text, date_order="auto", rates=None, target_currency="INR",
                   convert=True, default_currency="INR", category_search=None, example_id=None):
    """Turn OCR text into the receipt document plus evidence/checks."""
    text = text or ""
    vendor = extract_vendor(text)
    amount, amount_line, amount_source = extract_amount(text)
    date, date_match = extract_date(text, date_order)
    currency, currency_source = detect_currency(text)
    if currency_source == "default":
        currency = (default_currency or "INR").upper()
    items = extract_items(text)
    tx_type, expense_category, income_category = classify(
        text, vendor, amount, items)
    category_source = "rule" if tx_type == "Income" or expense_category != "OTHERS" else "default"
    category_evidence = None
    if category_search is not None and tx_type == "Expense" and expense_category == "OTHERS":
        query = " ".join(filter(None, [vendor, " ".join(items), _head_text(text)]))
        category, category_evidence = category_search.search(query, exclude_id=example_id)
        if category:
            expense_category, category_source = category, "vector"
    recurring = is_recurring(text, vendor, items)

    if items:
        description = "Items: " + ", ".join(items[:6])
    elif vendor:
        label = (expense_category or income_category or "OTHERS").replace("_", " ").title()
        description = f"{label} at {vendor}"
    else:
        description = ""

    doc = {
        "vendor": vendor,
        "amount": abs(amount) if amount is not None else 0.0,
        "transactionType": tx_type,
        "expenseCategory": expense_category,
        "incomeCategory": income_category,
        "currency": currency,
        "description": description,
        "date": date,
        "recurring": recurring,
    }
    if convert and amount is not None and rates:
        doc.update(convert_amount(abs(amount), currency, target_currency, rates))

    warnings = []
    if not vendor:
        warnings.append("no_vendor")
    if amount is None:
        warnings.append("no_amount")
    elif not (0 <= abs(amount) < 10_000_000):
        warnings.append("amount_out_of_range")
    if not date:
        warnings.append("no_date")
    elif not (1990 <= int(date[:4]) <= dt.date.today().year + 1):
        warnings.append("date_out_of_range")
    if currency_source == "default":
        warnings.append("currency_defaulted")
    if not items:
        warnings.append("no_line_items")

    evidence = {
        "amount_source": amount_source,
        "amount_line": amount_line,
        "date_match": date_match,
        "currency_source": currency_source,
        "items": items[:10],
        "category_source": category_source,
    }
    if category_evidence is not None:
        evidence["category_search"] = category_evidence
    return doc, evidence, warnings
