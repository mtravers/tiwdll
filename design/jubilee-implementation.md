# Jubilee Lab: Implementation & Design Notes

Companion to `jubilee.md` (the pre-implementation research notes). This
records what was actually built, the bugs found along the way, and why the
defaults are what they are — so the reasoning survives past the session that
produced it.

## Architecture

Non-spatial: no grid, no movement. Core model is pure data + pure functions
in `src/social_abm/jubilee.cljc` (portable clj/cljs, unit-tested via
`lein test`). Browser wiring/rendering is a thin layer in
`src/social_abm/browser/jubilee.cljs`. `core.cljs` dispatches between this
lab and Sugarscape based on which canvas id is present on the page — one
shadow-cljs build, two HTML entry points.

Agents are plain maps, population fixed for the run (no birth/death), so
`:agents` is a vector indexed by id — no need for id→agent maps except when
aggregating loan claims/debts.

```
agent:  {:id :wealth :arrears :distress-streak :exclusion-remaining}
loan:   {:id :lender :borrower :balance :rate}
```

### Per-tick pipeline

1. **Returns + income + consumption.** Wealth earns a noisy multiplicative
   return, receives flat income, then pays a noisy cost-of-living bill
   *capped at available cash* — you can't pay more than you have. The unpaid
   remainder is the tick's shortfall.
2. **Interest accrual** on all outstanding loan balances.
3. **Borrowing resolution.** The shortfall splits into *arrears* (a claim
   held by an abstract king — no cash moves, it's just an unpaid bill) and a
   *loan* from a real lender with wealth above their reserve (a real cash
   transfer, real risk to the lender). Ratio set by `arrears-fraction`. If no
   lender has spare reserve, the whole shortfall becomes arrears instead
   (credit rationing falls out of the model rather than being imposed).
4. **Repayment.** Agents with wealth above their reserve pay down loans
   first (to actual lenders), then arrears (to the king — that cash leaves
   the modeled economy, it doesn't recirculate).
5. **Jubilee check** (collective, see below).
6. **Bankruptcy check** (individual, see below).
7. **Metrics** computed from the post-jubilee-and-bankruptcy state.

### Conservation invariant

The only operations that move cash between agents are the loan transfer in
step 3 and the loan/arrears repayment in step 4. Arrears creation and
forgiveness never touch `:wealth` — they're a pure liability, not a cash
flow. This was load-bearing for catching bugs (see below) and is worth
preserving if this gets extended further.

## Two independent debt-relief mechanisms

**Jubilee (collective).** A king resets debt for *everyone at once*.
Trigger modes: `:periodic` (every N ticks), `:stochastic` (Poisson hazard
per tick), `:threshold` (fires when the inequality index crosses a bound,
with a cooldown), `:manual` (button only). `haircut` (φ) controls what
fraction of every loan balance and every agent's arrears gets forgiven.

**Bankruptcy (individual).** Orthogonal to jubilee — can run together or
separately. Any agent whose total owed (debt-as-borrower + arrears) exceeds
`bankruptcy-debt-multiple × subsistence-cost` for `bankruptcy-streak`
consecutive ticks gets their own discharge: `bankruptcy-haircut` fraction of
their loans + arrears forgiven, on their own timeline, no king involved.
Cost: `bankruptcy-exclusion` ticks locked out of new loans afterward (arrears
can still accrue — they still have to eat, nobody will just voluntarily
extend them new credit). The streak only advances while
`bankruptcy-enabled?` is true (see bug #5 below); the exclusion countdown
keeps ticking regardless, since a lockout already incurred is a consequence,
not an ongoing policy — freezing it would strand an agent locked out
forever if the feature got toggled off mid-run.

## The inequality metric isn't textbook Gini

Standard Gini divides by the population's *mean* wealth and assumes nobody's
wealth is negative. Once agents carry debt, net worth goes negative and the
mean can sit near zero or flip sign — the plain formula blew up into wild
swings (observed: values from -6 to +14 tick to tick) instead of tracking
concentration. Fix: normalize by mean **absolute** net worth instead of the
signed mean. This is numerically stable and still rises with concentration
and dips on jubilee, but it is *not* bounded to [0,1] and isn't directly
comparable to a textbook Gini figure. It's labeled "Inequality idx*" in the
UI, not "Gini," with a footnote explaining why — and the top-decile-share
and %-underwater charts are the cleanly-bounded readings to trust for a
precise number.

## Bugs found during implementation (chronological)

1. **Duplicate DOM id.** `population` was used by both a stat-display
   `<span>` and the editable number input; `getElementById` silently
   returned the wrong element. Caught by grepping ids before the Chrome
   extension was even available to click through it — worth doing that
   check on any hand-written HTML+ClojureScript pairing, since a stray
   duplicate id fails silently rather than throwing.

2. **Gini formula instability**, described above.

3. **Defaults too comfortable.** First cut had `base-income == subsistence-cost`
   and `init-wealth` at 5× subsistence — nobody ever ran low enough on cash
   to actually borrow, so there was no debt, so a jubilee had nothing to
   forgive and the net-worth ladder never showed red. First fix attempt:
   drop `base-income` below `subsistence-cost` to force a structural
   deficit. This visibly worked short-term but caused bug #6.

4. **Loans were silently wiped every tick.** `resolve-borrowing` never
   actually received the previous tick's loan list — its internal
   accumulator started from `[]` every call, and the caller discarded the
   interest-accrued existing loans (`loans1`) instead of threading them
   through. Loans could never persist or compound interest across ticks;
   what looked like "credit drying up" (n-loans staying near 0) was this
   bug, not an emergent economic property. Found while wiring bankruptcy's
   debt-threshold check through the loan list and noticing the numbers
   didn't add up. Fixed by adding `existing-loans` as an explicit parameter
   and seeding the loop's accumulator with it.

5. **Bankruptcy streak kept advancing while disabled.** `resolve-bankruptcy`
   gated the *discharge* on `bankruptcy-enabled?` but not the streak
   increment itself. Default state ships with the bankruptcy checkbox off;
   a user running for a while, then checking the box, would trigger a mass
   simultaneous discharge on the very next tick for every agent whose streak
   had already silently climbed past the trigger — functionally a hidden
   global jubilee, exactly what bankruptcy mode exists to be distinct from.
   Fixed by making the over-threshold check itself require
   `bankruptcy-enabled?`, so the streak only starts counting once the
   feature is actually on.

6. **The "fixed" defaults drained the whole economy.** With `base-income`
   permanently below `subsistence-cost` (bug #3's fix) and loans now
   correctly persisting (bug #4's fix), total wealth collapsed from ~375 to
   ~10-15 by tick 100 and stayed pinned there — in *every* trigger regime,
   jubilee or bankruptcy. Once an agent's wealth hits ~0 they earn
   negligible return (return is multiplicative on wealth), so the
   structural income deficit becomes a one-way trap with no way back; given
   enough ticks essentially the whole population falls in. This confounds
   the exact comparison the lab exists to make — if both policies converge
   to the same universal-poverty floor, you can't tell them apart. See next
   section for the fix.

## Why the defaults are what they are

Empirically probed several parameterizations (200-tick traces of
`total-wealth`, `frac-negative`, inequality index) before settling:

- **Breakeven income + big cushion** (`base-income = subsistence-cost`,
  `init-wealth` 3-5×): too comfortable, no one ever visibly borrows.
- **Structural deficit** (`base-income < subsistence-cost`, any gap): not a
  steady state — it's a one-way ramp to universal poverty, just a matter of
  how many ticks it takes. Confounds any jubilee-vs-bankruptcy comparison
  once both regimes hit the same floor.
- **Breakeven income + thin cushion** (`base-income = subsistence-cost = 1.0`,
  `init-wealth = 0.5`): the shipped default. Debt emerges from idiosyncratic
  noise (consumption-bill variance, return variance) rather than a
  guaranteed aggregate deficit — visible distress in the first ~50 ticks
  (10-25% of the population underwater), and total wealth *grows* in both
  regimes rather than collapsing, so a jubilee-vs-bankruptcy comparison over
  a full run stays meaningful. Debt does eventually become rare after
  roughly tick 100 as the (unopposed, positive-mean-return) economy
  compounds past the point where consumption noise can dent anyone's
  cushion — that resolution is a property of `return-mean > 0` with nothing
  offsetting it long-run, not a finding about either relief mechanism. A
  "Total wealth (cash)" stat tile was added specifically so this is visible
  live instead of being a silent confound again.

At these defaults, one observed contrast (a property of this
parameterization, not a general law): periodic jubilee produces sharp
resets that decay toward low inequality as growth outpaces debt; bankruptcy
-only holds the inequality index around 0.51-0.55 through tick 150 via a
steady trickle of ~21 individual discharges over 200 ticks rather than one
synchronized event.

## Known gaps (Layer 3 from the original research notes, not built)

- No lender anticipation or strategic behavior around a known jubilee date
  (the Deuteronomy 15:9 / Hillel problem).
- No prosbul-style debt-shielding vehicle.
- No heterogeneous income across agents — everyone draws from the same
  `base-income`/`consumption-vol` distribution, so inequality is driven by
  accumulated luck (return variance, consumption variance) rather than any
  structural difference between agents. Giving agents individually-varying
  income would be the natural next step if the "some people are structurally
  poor" story matters more than the "bad luck compounds" story.
- `arrears-fraction` is a fixed dial, not something lenders or borrowers
  learn or adapt in response to jubilee/bankruptcy frequency.
