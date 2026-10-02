# Benchmarks

**Status: no results published.** The harness and cases exist; the claim that a multi-model council beats a single model has not been tested with published numbers. Treat it as a hypothesis.

## What is measured

`dialexbench` runs each case twice:

- **Arm A, solo baseline:** one model answers the dilemma.
- **Arm B, council:** several models debate for N rounds and a moderator synthesizes.

A judge model scores both deliverables on four dimensions: factuality, blind spots, trade-offs, actionability. Each case carries ground-truth traps, required trade-off axes and mandatory boundary conditions, so the judge has a checklist. Cost (tokens) and latency are recorded per arm.

## Run it

```bash
cd socratix-engine
go run ./cmd/dialexbench list
go run ./cmd/dialexbench run --case all --rounds 2 --format markdown
```

It uses the providers and keys configured in your Dialex config directory. A full run costs real API money, and the council arm costs roughly (seats x rounds) times the solo arm.

## Known limits of the method

- The judge is itself a model and can favor longer, more structured answers. Use a judge from a different vendor than the solo model, and report length alongside scores.
- Ten cases are not a statistically meaningful sample. Report per-case results, not only averages.
- The cases are architectural dilemmas. They say nothing about other domains.

## Results

| Date | Solo model | Council | Judge | Cases | Council win rate | Cost ratio | Latency ratio |
|---|---|---|---|---|---|---|---|
| _none yet_ | | | | | | | |

Add a row only with the raw `--format json` output committed next to it.
