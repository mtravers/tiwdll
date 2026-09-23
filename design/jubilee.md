

## Frameworks

**Agents.jl (Julia)** is the best fit if the deliverable is the *capture half-life* number from your slide 17. That metric requires thousands of runs across a parameter grid with replicate seeds, and Agents.jl has `paramscan`/`ensemblerun!` built in, returns tidy DataFrames, and is roughly two orders of magnitude faster than NetLogo.

**Mesa (Python)** is the default choice — more prior art to crib from, `batch_run` for sweeps, pairs with SALib for proper sensitivity analysis. Slower than Julia but this model is small.

**NetLogo** is worth keeping as a *second* implementation purely for the talk: the visual is legible to a live audience in a way a notebook isn't, and its BehaviorSpace handles modest sweeps. Its Models Library already contains "Wealth Distribution" (a Sugarscape variant) you can fork.

Honestly, though: for a non-spatial debt economy the framework buys you very little. There's no environment, no movement, no scheduler subtleties — a step is a reduce over a vector of agent structs. What you actually need is the *experiment harness*: config-as-data, seeded replicates, results as tidy tables, and a plotting path. If you'd rather stay in Clojure, writing the core as a pure `(fn [state params] state')` plus tech.ml.dataset for results and Vega for output is entirely reasonable and probably faster for you than learning Mesa's API. The thing to avoid is hand-rolling the sweep machinery badly.

## Prior art

The literature splits into three piles, and only the third is about Jubilee.

**Wealth-concentration models (your Phase 1 — this is solved, don't rebuild it).**
- *Sugarscape*, Epstein & Axtell 1996, Chapter V ("Credit") is the canonical agent-based credit model: explicit lender/borrower matching, loan duration, interest, inheritance of outstanding loans on death. Directly liftable.
- Kinetic exchange models: Drăgulescu & Yakovenko (1999) shows random pairwise exchange gives an *exponential* distribution, not Pareto — worth knowing, because it means "simple exchange rules" alone won't reproduce the real tail. Chakraborti–Chakrabarti add a saving propensity; Bouchaud–Mézard (2000) get Pareto from multiplicative returns plus exchange. The Pareto tail requires r>g-style multiplicative dynamics, not just trade.
- **Boghosian's Yard-Sale and Affine Wealth Model** (Boghosian, Devitt-Lee, Wang et al., ~2014–2019) is the best single hit for you. It proves wealth condensation is inevitable under *fair* random trade, and the "affine" version adds two parameters — χ (redistribution) and ζ (wealth-attained advantage) — that fit actual US and EU wealth distributions to within a fraction of a percent. You have a validated base model with a redistribution knob already in it; a Jubilee is a periodic impulse added to that knob.

**Macro ABMs with real credit.** Delli Gatti et al.'s CATS model (*Macroeconomics from the Bottom Up*), EURACE@Unibi, Ashraf–Gershman–Howitt on banks. Steve Keen's Minsky is system dynamics rather than ABM but is the canonical debt-deflation mechanism. These are heavyweight; useful as reference for how firms/banks/credit rationing are usually specified, not to fork.

**Jubilee specifically.** Essentially nothing. Some sovereign-debt-relief and strategic-default work (Eaton–Gersovitz lineage) captures the anticipation problem formally, and there's a small literature on bankruptcy moral hazard. This gap is your opening — but it also means you should assume no one has built the thing you want and budget accordingly.

## What to vary

**Layer 1 — the bug generator.** Return on wealth r vs income growth g; multiplicative vs additive shocks; shock variance; saving propensity; a subsistence floor (below which an agent *must* borrow); interaction topology (well-mixed vs. network). Land as a lumpy, foreclosable asset if you want the Leviticus land-restoration provision to mean anything.

**Layer 2 — the reset.** Trigger architecture straight off your slide 16: calendrical (period T), stochastic (Poisson hazard, Dorman's 2%), threshold (fires when Gini > x), discretionary. Synchronization: collective vs. individual spins. Completeness: haircut fraction φ ∈ [0,1]. Scope: debt only vs. debt + land vs. debt + land + bondservice. Announcement lead time and credibility.

**Layer 3 — adaptation. This is where the model earns its keep.** Everything above is bookkeeping; the interesting dynamics come from agents responding to the reset's existence:
- Lender anticipation: credit supply as a function of expected time-to-reset (the Hillel/Deuteronomy 15:9 problem).
- Prosbul agents: a fraction who can convert claims into reset-immune vehicles at cost c. Vary c and the fraction; capture half-life falls out of this.
- Strategic overborrowing by debtors near a known date.
- Whether agents estimate the hazard rate from observed history (this is what makes stochastic triggers testable — a learnable hazard is only partially unpredictable).

**The parameter that matters most, and that no existing model has:** the fraction of debt that is *arrears* rather than *contracted loans*. Hudson's central empirical claim is that agrarian debts were mostly unpaid bills — tax, irrigation, beer tabs — not voluntary credit. If debt is arrears, the anticipation critique collapses, because there is no lender deciding whether to extend credit. Making that a slider from 0 to 1 turns the entire Hillel objection into a measurable regime boundary rather than a debating point. That's the result worth having.

**Metrics.** Gini and top-1% share over time; Pareto α; reconcentration half-life after each reset; your capture half-life; credit volume (does lending actually dry up, and at what prosbul cost?); agents below subsistence; output.

Two warnings. First, the model returns whatever the exchange rule assumes — condensation under yard-sale rules is a theorem, not a finding, so don't present it as one. Second, "forgiving debt reduces inequality for one period" is trivially true and not worth simulating; the claim only becomes non-obvious at the third layer, where adaptation and arms-race dynamics live.
