# Airline PSS Demo — Presenter Guide

Everything runs from **one internet link**. No installations, no logins, no passwords — you present entirely from the browser.

---

## 1. Before you start (5 minutes)

1. **Get today's demo link from the operator.** Links change per session — today's looks like:
   **`https://motel-whenever-retro-evans.trycloudflare.com`**
   If a link doesn't open, ask the operator for the current one. Everything below hangs off this one address.
2. **Open four browser tabs:**
   - Tab 1 — **Portal** (the link itself): your home base and what you screen-share.
   - Tab 2 — **Carrier console**: `<link>/console/`
   - Tab 3 — **Airport check-in**: `<link>/agent/`
   - Tab 4 — **WhatsApp simulator**: `<link>/book/whatsapp.html` — best on a phone or in the browser's phone frame (F12 → device toolbar).
3. **Know the refresh rule:** over the internet, the carrier console's numbers update **when you refresh the tab (F5)** — so after every booking, refund, or decline: flip to the console tab and press F5 before pointing at it.
4. **Confirm with the operator** that the environment is up and reset ("green gate passed"). That's their job, not yours.

## 2. What to type — cheat card

| Where | Type |
|---|---|
| Flight search | Route: **BLR → HYD** (or BLR → DEL / BOM → DEL …), any date in the **next 6 weeks**, 2 adults |
| Payment (success) | On the Razorpay page: select **Netbanking** (⚠ the only active method — do NOT pick UPI or Cards) → any bank → click **Success** on the mock bank page |
| Payment (decline demo) | On the Razorpay page: select **Netbanking** → any bank → click **Failure** on the mock bank page |
| Passenger surname | Any name you choose — **remember it**, you reuse it for retrieval and WhatsApp |
| Loyalty page | Member **`LOY-DEMO-001`** (auto-loads) |
| WhatsApp chat | `hi` → then the **surname** → then `check in` |

## 3. The six tiles on the portal

**Book a flight** (the retail journey) · **Carrier console** (the airline's live view) · **Airport check-in** (the gate agent's screen) · **My loyalty** (member balance, tier, redeem) · **WhatsApp** (check in by chatting) · **Trade / NDC agent** (API-live label — shown via console tooling, not in this walkthrough).

## 4. Step-by-step scenario (~30 min)

**A — Front door (1 min).** Share Tab 1.
> SAY: "One platform, every surface — customer, airline, airport, trade. Everything you'll see is live, right now, against one running system."

**B — Book a flight (8 min).**
1. Tile **Book a flight** → search BLR→HYD, a date a few weeks out, 2 adults.
2. Results show **Economy and Business fares** — pick an Economy fare (Business comes later).
3. **Seat map:** click passenger P1, tap a seat; then P2, tap another. Point at the greyed Business rows:
   > SAY: "The map is scoped to the fare you bought — Business seats come with Business fares. And every seat you see taken is genuinely sold, live inventory."
4. Add a bag for P1. The total on screen is **server-priced** — mention it.
5. Passenger details (remember the surname) → **Continue to payment** → you're redirected to Razorpay: select **Netbanking only** (other methods aren't active yet) → pick any bank → click **Success** on the mock bank page.
6. Confirmation screen: point at **Booking reference + surname**.
   > SAY: "No PNR — one order reference. That's IATA ONE Order, natively, not bolted on."
7. Flip to Tab 2 (console) → **F5** → bookings +1, revenue up.

**C — The no-oversell beat (2 min).** Search the **same flight** again → your chosen seats now show **taken**.
> SAY: "One inventory. The seats you just bought are gone for everyone else — no oversell, no sync jobs. That's the hardest engineering in a PSS, in two clicks."

**D — Payment integrity (3 min).** Start another booking → on the Razorpay page select **Netbanking** → any bank → click **Failure** on the mock bank page → declined.
> SAY: "The payment failed — so the order simply doesn't exist, and the held seats went straight back on sale. We never book without charging, never charge without booking."

**E — Money back (3 min).** Portal → **Manage booking** → retrieve with the reference + surname → **cancel**. Console tab → F5 → refund appears as its own line.
> SAY: "Refund issued on real payment rails. Note the headline is gross bookings — refunds show separately; we don't quietly net the topline."

**F — Airport check-in (4 min).** Tab 3 (`/agent/`): open the departure, **check in passenger 1**, assign **the exact seat they paid for**, issue the boarding pass.
> SAY: "The seat they bought online is honored at the airport as a paid claim — the agent confirms it against the same order record."

**G — WhatsApp check-in (3 min).** Tab 4 / phone: send `hi` → the **surname** → `check in` → ✓. Flip to Tab 3: **passenger 2 is CHECKED_IN**.
> SAY: "A passenger checked in by chatting. Same check-in the airport desk uses — two channels, one departure system."
> ⚠ **Do this BEFORE boarding is opened** in the agent view — after boarding opens, chat check-in correctly refuses.

**H — Loyalty (3 min).** Cue the operator: *"fire the loyalty booking."* Then tile **My loyalty** → refresh → balance jumped and the **tier crossed to SILVER** → click **Redeem** → voucher issued.
> SAY: "The booking earned automatically off the same event stream the dashboard consumes — no loyalty posting job. Cross a threshold, the tier moves; redeem against the balance."

**I — Carrier console tour (3 min).** Tab 2: Reports; then **Revenue Mgmt** — run a compute, point at **`applied: false`**.
> SAY: "RM here is decision support — it forecasts and recommends seat protection levels, and records them. Feeding that into live inventory is a deliberate roadmap step; it never moves a seat without you."

**J — Close (1 min).** Back to the portal.
> SAY: "Everything you watched was real, end to end, over the public internet. What's next is sequenced on the roadmap — not hand-waved."

**Optional showpieces if time allows:** book a **Business fare** (BLR→DEL or any network flight) and watch rows 1–3 light up on the map; or hand the client the link and let them make a booking from **their own device**.

## 5. If asked — honest one-liners

- **"Where's the PNR?"** — "There isn't one, by design: the order reference is the single reference (ONE Order). Legacy PNR mirrors exist via a bridge for partners that need them."
- **"Is the seat auto-assigned at check-in?"** — "The paid seat is honored as a claim the agent confirms — automated lookup at check-in is a next increment."
- **"Is that real WhatsApp?"** — "The conversation engine, session and check-in are real; the last hop to Meta's gateway is configuration we've deliberately left for onboarding."
- **"Is the RM forecast ML?"** — "It's a rule-based recommendation engine behind a port — the forecasting model is swappable; the point is the advisory loop."

## 6. Do / Don't

- **Don't** open boarding in the agent view before the WhatsApp check-in.
- **Don't** click the *traveler profile* selector on the search page — it's under review; personalization is demoed separately by the operator.
- **Don't** promise live RM-to-inventory steering, scannable boarding-pass barcodes, or real Meta delivery.
- **Don't** select UPI or Cards on the Razorpay page — they're not active on the test account yet; **Netbanking is the only working method**.
- **Do** refresh the console tab before pointing at numbers.
- **Do** remember: both payment outcomes are driven from the Netbanking mock bank page — **Success** button to pay, **Failure** button for the decline demo.

## 7. If something goes wrong

- **Payment page spins for more than a minute after paying** → tell the operator ("webhook") and move to the next beat; they can settle it in seconds.
- **Any page won't load** → refresh once; if still down, the operator re-issues the link (takes ~1 min) — narrate the roadmap slide meanwhile.
- **A beat misbehaves** → skip it with one calm line ("we'll pick that up at the desk / in Q&A") — never improvise a workaround on screen.
- **Golden rule:** the operator is on standby; nothing in this demo requires you to touch a terminal.
