# Varavu — design plan

## The idea

This app is a **personal ledger**, not a trading terminal. The redesign makes every
screen read like a well-kept account book: paper, ink, a single margin rule, amounts
that line up in a column, and one place where boldness is spent.

The user is one young professional in India checking "where did my money go, and am
I okay?" on a phone, between things. The interface should answer that in a glance and
then get out of the way.

---

## Colour

Five working colours plus two semantic tones. Named tokens, no scattered hex.

| Token | Light (paper) | Dark (ink) | Used for |
|---|---|---|---|
| `--bg` (paper / ink) | `#F2F3EE` faint green-grey paper | `#141A22` deep ink blue | page ground |
| `--surface` (sheet) | `#FBFBF7` | `#1B232C` | raised sheets, inputs |
| `--raised` | `#FFFFFF` | `#232D38` | menus, dialogs |
| `--ink` | `#1D2B32` deep blue-black | `#E9EDF1` | body text, headings |
| `--ink-soft` | `#5C6C6D` | `#9CA9B6` | secondary text |
| `--line` | `#DEE2D8` hairline | `#2C3746` | rules and dividers |
| `--accent` | `#2B4CC9` cobalt | `#8098FF` | the one primary action per screen |
| `--pos` | `#1F7A4D` deep green | `#63C295` | income, money kept |
| `--neg` | `#B4462F` brick red | `#E39580` | expense, overspend |
| `--warn` | `#8A5A00` ochre | `#E0B45C` | behind pace, near a limit |

Why these: the paper is faintly green-grey rather than cream, the ink is blue-black
rather than `#000`, and the accent is pen-nib cobalt used **once per screen** (the
primary button). Positive and negative amounts keep their sign (`+₹…` / `−₹…`), so
colour is never the only signal. All body text is ≥ 4.5:1 in both themes.

The dark theme is a deep ink blue, not a near-black with a green cast, and it shares
the same accent semantics rather than flipping to gold.

## Typography

Two families, clearly different jobs:

- **Fraunces** (variable serif, 9–144 optical size) — page headings and the large
  statement figures. It gives the ledger a printed, slightly warm voice.
- **Hanken Grotesk** — every UI label, control, table cell and small amount. Quiet,
  humanist, with a full weight range.

Neither is on the "avoid" list; numbers use `font-variant-numeric: tabular-nums`
everywhere, and the rupee glyph renders correctly in both faces (verified in-browser).

Fixed scale: **11 / 12 / 13 / 14 / 15 / 17 / 22 / 32 / 56 px**. Eleven is reserved for
axis ticks and calendar numerals; 14 is the dense-UI size (buttons, ledger rows,
table cells); 15 is body copy at 1.55 line height, measure capped at ~66ch. Nothing
is sized ad hoc. Sentence case everywhere; no tracked-out eyebrow labels, no coloured
word inside a heading.

## Layout

One idea per screen: a **statement** page, a **ledger** page, a **receipt** page, a
**balances** page, a **chart** page, a **calendar** page, a **plan** page.

- **Dashboard** — the month statement. Net figure, then a wide earned-vs-spent bar,
  then one plain sentence built from real totals. Below: recent ledger (wide column)
  and notes + coming up (narrow column). 2:1 grid, not two equal cards.
- **Transactions** — a ledger: date headings as quiet column breaks, category as a
  coloured dot, amounts right-aligned in tabular figures, tools as text buttons.
- **Bill scanner** — a receipt-shaped dropzone with one slightly rotated sample
  receipt; helper text says exactly what happens.
- **Split** — balances are the hero; people get name-derived avatar colours;
  settlements are a quiet one-liner, expenses are full rows.
- **Analytics** — the timeline chart takes the most room; categories become a real
  horizontal bar chart with labels on the bars; uncategorised spending is called out.
- **Subscriptions** — a month strip above a list sorted by next charge.
- **Goals** — the signature ruler track (see below), with a projection panel filling
  the right column; budgets sit below as compact rows, deliberately unlike goals.

Dashboard wireframe:

```
┌ sidebar ┬──────────────────────────────────────────────────────────┐
│ Varavu  │  November 2026                              [period ▾]   │
│         │                                                          │
│ Dashboard│  Net this month                             −₹3,240.00  │
│ Trans…  │  Earned ₹62,000  ██████████████████░░░░  Spent ₹65,240  │
│ Scanner │  You've spent ₹3,240 more than you earned this month.    │
│ Split   │  Most of it went to restaurants.                         │
│ …       │                                                          │
│         │  ── Recent transactions ────────  │  Notes               │
│         │  4 Nov ··············  −₹1,240    │  · two warnings      │
│         │  ● Coffee House      −₹180        │                      │
│         │  ● Laundry           −₹240        │  Coming up           │
│         │  ── 3 Nov ───────────  −₹2,980    │  Netflix  ₹649       │
│         │  …                                │                      │
└─────────┴───────────────────────────────────┴──────────────────────┘
```

## Principles

1. **Open by default.** Sections are separated by whitespace and one hairline, not by
   a wall of identical bordered cards. Cards are only for things that are a unit
   (a dialog, a receipt preview).
2. **One star per screen.** The statement figure on the dashboard, the ledger on
   transactions, the chart on analytics, the ruler on goals. Everything else is quiet.
3. **Money aligns.** Every column of amounts is right-aligned, tabular, two decimals,
   Indian grouping, true minus sign, `+` on income.
4. **State facts, not badges.** "₹49,057 left, 25 days to go" instead of an "On track"
   pill. Colour is a second signal, never the only one.
5. **Quiet chrome.** The sidebar is a margin, not a toolbar. Two radii (6px controls,
   14px panels), no shadows except one tinted shadow on floating menus.

## Signature detail

**The goals ruler.** A savings goal is a distance between today and a date, so it is
drawn as a measuring track: a thick rule with tick marks every 10%, a fill up to what
is saved, and a slim marker showing where the calendar says you should be. It makes
pace legible without a status badge, and it is the one place where the design is
allowed to be memorable. Nothing else on Goals, and nothing on any other page, gets
that treatment — budgets use plain compact rows.

---

## Critique: would I arrive at this for any finance app?

Partly, yes — paper + serif + one accent is a reasonable answer for *most* finance
apps, and that is the trap. Three changes came out of asking the question:

1. **Dropped the KPI-card dashboard.** A row of four stat cards is the generic answer.
   Replaced with a *statement*: net, a wide earned/spent bar on one axis, and a
   sentence in plain language. That is the ledger metaphor doing real work, not a
   decorative theme.
2. **Dropped the card grid on Transactions and Analytics.** Data belongs in lists and
   charts with direct labels, not in boxes with headers. Only genuinely grouped things
   keep a surface.
3. **Made the signature about *time*, not decoration.** A ruled-paper texture would
   have been charming and forgettable. The ruler measures pace against a deadline,
   which is the specific thing this audience worries about — and it fills the empty
   right half of Goals with a real projection instead of ornament.

I also rejected the obvious "bank" moves: progress percentages as pills, a pinned
full-width "Add transaction" button in the sidebar, and an always-there insights
skeleton. A pinned primary button competes with every page's own action; the one
primary action now lives where the task happens, and the `N` shortcut is a tooltip.

---

## Self-check

1. **Could this be any finance app?** The statement layout, the ruled ledger and the
   deadline ruler are specific to a personal daily ledger. The one thing I would change
   next is the sidebar wordmark, still plain type: it is the least specific element.
2. **More than two border radii?** No. `--r-control` 6px and `--r-panel` 14px, plus
   fully round avatars/dots (a shape, not a radius token).
3. **Any page still a stack of identical cards?** No. Dashboard, transactions, analytics,
   subscriptions and goals use open sections with hairline rules; only dialogs and the
   receipt preview use a panel surface.
4. **Tracked-out ALL-CAPS label, one-word accent, perpetual skeleton?** None. Labels are
   13px sentence case; the Notes panel is hidden until it has real content and says
   "Nothing unusual this week." when there is nothing to report; no decorative accent word.
5. **More than one showpiece?** No. The goals ruler is the only signature element.
6. **Do amounts align, does ₹ render?** Yes: right-aligned, tabular, two decimals,
   Indian grouping. ₹ verified in Fraunces and Hanken Grotesk.
7. **Contrast and keyboard?** Body text ≥ 4.5:1 in both themes; every control has a
   visible `:focus-visible` ring; the sidebar, bottom bar, menus, sheets and dialogs
   keep their focus traps and Escape handling.
8. **What did I remove at the end?** The filled logo tile — the wordmark is typographic
   now — and the rounded-square icon behind every row, replaced by a category dot.

---

## Screenshots

Captured with headless Chrome against mocked, representative data (no fixtures were
added to the app itself).

- Before: `docs/screenshots/before-{dashboard,transactions,analytics,goals}.png`
- After: `docs/screenshots/after-{dashboard,transactions,analytics,goals}.png`
- Dark theme: `docs/screenshots/after-dashboard-dark.png`
- Phone (390px): `docs/screenshots/after-{dashboard,analytics,goals}-mobile.png`
- Split group detail: `docs/screenshots/after-split-group.png`
- Subscriptions: `docs/screenshots/after-subscriptions.png`

## What changed, and what was left alone

Changed: the token layer and every component's surface, type, spacing and colour;
the dashboard's statement composition; the ledger rows and category markers; the
analytics chart order and the honest uncategorised callout; the subscriptions month
strip and list; the goals ruler and projection panel; the scanner dropzone; the
sidebar's active state and the removal of the pinned add button; the copy on
Dashboard, Transactions, Bill scanner, Subscriptions, Goals and the notes panel.

Deliberately left alone: all data fetching, routes, API contracts, view lifecycles,
keyboard shortcuts (including `N`, `/`, `?`, `G`-then-key), focus traps, mobile
navigation, CSV export, batch import, receipt upload and the profile flows. The
information architecture is unchanged: the same eight destinations, the same tabs
on Split, the same modals.
