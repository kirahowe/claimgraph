# Preface {.unnumbered}

claimgraph is a memory system for AI coding agents. It stores what an agent
and its human learn about a codebase (decisions, preferences, dependencies,
failure modes, history) as a knowledge graph rather than as a pile of
markdown files. Every fact in the graph knows when it was true, when it was
recorded, how confident anyone should be in it, what kind of claim it is,
and where it came from. Nothing is ever deleted: when the world changes, the
old fact's validity interval closes and the new one opens, so the graph can
answer both "what do we believe now" and "what did we believe in March, and
why did it change."

The system is a Babashka CLI backed by [Datalevin](https://github.com/juji-io/datalevin),
wrapped in an agent skill, an MCP server, and a set of hooks that let it run
with zero effort on the user's part. This book covers the project's background
and rationale, its design, the benchmarks, and how to use it.

## How to read this book

The book has three parts.

**Part I — Foundations** is prose. It explains the problem agent memory is
trying to solve, what the research literature settled in 2025 and 2026, and the
mental model behind claimgraph's design. If you read nothing else, read the
mental model chapter. It's the foundation for understanding everything else.

**Part II — The System in Practice** is a hands-on tour of the working system,
one behavior per chapter. These chapters use the in-memory store backend, which
shares every line of decision logic with the Datalevin backend through a
storage protocol. The CLI equivalents appear alongside as shell blocks.

**Part III — Operations and Reference** is operational: advanced usage, a
hands-on guide to extending the code tier to a new language (and merging
that work upstream), the benchmark and its results, a comparison with the
other memory systems in the field as of July 2026, a CLI reference, and the
bibliography.

## Building the book

The rendered book is generated from `book/` in the repository:

```bash
bb book            # render to book/rendered/_book/index.html
bb book:preview    # render, then serve with quarto preview
```

The build needs a JVM (the book chapters evaluate on real Clojure, not on
Babashka) and the [Quarto](https://quarto.org) CLI. The claimgraph tool itself
needs neither; it runs on two native binaries.

## Status

Everything described here is implemented and tested. There is also a new
benchmark included in the product, with 33 questions about a made-up project.
All pass and gate regressions in CI. The end-task A/B and its numbers appear
in the benchmark chapter, including the arms where claimgraph loses and the
one where the best available answer is "the graph does not know."
