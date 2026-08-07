#!/usr/bin/env bash
# The inverse of `claim setup`: remove exactly what it wrote, by the same
# markers it wrote them with (hook entries, the .mcp.json registration, the
# agent skill, the gitignore block, the compiled view in the inject file).
# Idempotent — a second run finds nothing left to do and reports nothing.
#
# Usage: uninstall.sh [PROJECT_DIR] [--purge] [--global]
#   PROJECT_DIR  the project `claim setup` ran in (default: cwd)
#   --purge      also remove .claimgraph/ (the store + its config); without
#                it the store is left alone and its path is printed
#   --global     remove the `claim` launcher, but only when it is a symlink
#                into THIS checkout — bb and dtlv are shared tools and stay
set -euo pipefail

PURGE=0; GLOBAL=0; PROJECT_ARG=""
for arg in "$@"; do
  case "$arg" in
    --purge) PURGE=1 ;;
    --global) GLOBAL=1 ;;
    -*) echo "error: unknown flag $arg" >&2; exit 2 ;;
    *) PROJECT_ARG="$arg" ;;
  esac
done

# Follow a symlink chain to its final target — this checkout's own location
# (so bin/claim resolves from anywhere) and --global's launcher both need it.
real_path() {
  local p="$1" dir link
  while [ -L "$p" ]; do
    dir="$(cd -P "$(dirname "$p")" && pwd)"
    link="$(readlink "$p")"
    [[ $link != /* ]] && link="$dir/$link"
    p="$link"
  done
  printf '%s\n' "$p"
}

ROOT="$(cd -P "$(dirname "$(real_path "${BASH_SOURCE[0]}")")/.." && pwd)"
PROJECT="$(cd "${PROJECT_ARG:-.}" && pwd -P)"

# Strip a managed block's begin..end lines, keeping every other line verbatim
# plus the file's own trailing-newline habit (a harness re-injects the inject
# file character for character, so it is not a file to add a stray newline
# to). mode=exact matches the gitignore's literal marker lines; mode=prefix
# matches the inject file's versioned markers (…begin:v2…) by prefix.
strip_block() {
  local file="$1" begin="$2" end="$3" mode="$4" tmp nl
  [ -f "$file" ] || return 1
  nl=1; [ -n "$(tail -c1 "$file" 2>/dev/null)" ] && nl=0
  tmp="$(mktemp)"
  awk -v b="$begin" -v e="$end" -v mode="$mode" -v nl="$nl" '
    function hit(m) { return mode == "prefix" ? (index($0, m) == 1) : ($0 == m) }
    hit(b) { skip = 1; next }
    hit(e) { skip = 0; next }
    !skip { if (started) printf "%s\n", buf; buf = $0; started = 1 }
    END { if (started) printf "%s%s", buf, (nl == 1 ? "\n" : "") }
  ' "$file" > "$tmp"
  if cmp -s "$file" "$tmp"; then rm -f "$tmp"; return 1; fi
  mv "$tmp" "$file"
}

# JSON surgery via bb (cheshire), never hand-rolled in awk/sed — these are
# files a harness reads on every session start. mode=hooks drops any entry
# whose inner command matches one of `hooks install`'s own two idempotency
# markers; mode=mcp drops mcpServers.claimgraph. Either way: drop the parent
# key once it empties out, write back pretty-printed, exit 1 if unchanged.
strip_json() {
  local mode="$1" f="$2"
  [ -f "$f" ] || return 1
  bb -e '
    (require (quote [cheshire.core :as json]))
    (let [[mode f] *command-line-args*, s (json/parse-string (slurp f) true)
          marked? (fn [e] (some #(re-find #"hooks run|coach --hook" (str (:command %))) (:hooks e)))
          s2 (case mode
               "hooks" (let [hooks (into {} (keep (fn [[k v]] (when-let [kept (seq (remove marked? v))] [k (vec kept)]))) (:hooks s))]
                         (if (seq hooks) (assoc s :hooks hooks) (dissoc s :hooks)))
               "mcp" (let [srv (dissoc (:mcpServers s) :claimgraph)]
                       (if (seq srv) (assoc s :mcpServers srv) (dissoc s :mcpServers))))]
      (if (= s s2) (System/exit 1)
          (do (spit f (str (json/generate-string s2 {:pretty true}) "\n")) (System/exit 0))))
  ' "$mode" "$f"
}

# 1-2. Resolve the paths setup wrote, the way `claim config` would — run
# from inside PROJECT so every relative default resolves against it, not
# against wherever this script was invoked from. Without bb (claim itself
# cannot run without it), fall back to the documented defaults.
SETTINGS_FILE="$PROJECT/.claude/settings.json"
DB="$PROJECT/.claimgraph/db"
INJECT_FILE=""
HAVE_BB=0
if command -v bb >/dev/null 2>&1; then
  HAVE_BB=1
  RAW="$(cd "$PROJECT" && "$ROOT/bin/claim" config --project "$PROJECT" 2>/dev/null || true)"
  RESOLVED="$(printf '%s' "$RAW" | bb -e '
    (require (quote [cheshire.core :as json]))
    (when-let [r (:resolved (try (json/parse-string (slurp *in*) true) (catch Exception _ nil)))]
      (println (str (:inject-file r) "\t" (:settings-file r) "\t" (:db r))))' 2>/dev/null || true)"
  if [ -n "$RESOLVED" ]; then
    IFS=$'\t' read -r INJECT_FILE SETTINGS_FILE DB <<<"$RESOLVED"
  else
    echo "note: claim config didn't resolve — using default paths" >&2
  fi
else
  echo "note: bb not on PATH — using default paths; hook/MCP/inject-file cleanup needs it and is skipped" >&2
fi

# 3-4. Hooks + MCP.
if [ "$HAVE_BB" = 1 ]; then
  SEEN=""
  for f in "$SETTINGS_FILE" "$PROJECT/.claude/settings.json" "$PROJECT/.claude/settings.local.json"; do
    case " $SEEN " in *" $f "*) continue ;; esac
    SEEN="$SEEN $f"
    strip_json hooks "$f" && echo "hooks: removed claimgraph entries from $f"
  done
  strip_json mcp "$PROJECT/.mcp.json" && echo "mcp: removed claimgraph from $PROJECT/.mcp.json"
fi

# 5. Skill.
SKILL_DIR="$PROJECT/.claude/skills/claimgraph"
[ -d "$SKILL_DIR" ] && { rm -rf "$SKILL_DIR"; echo "skill: removed $SKILL_DIR"; }

# 6-7. Gitignore block + compiled view — both marker-delimited regions.
strip_block "$PROJECT/.gitignore" "# claimgraph:managed:begin" "# claimgraph:managed:end" exact \
  && echo "gitignore: removed the claimgraph block from $PROJECT/.gitignore"
strip_block "$INJECT_FILE" "<!-- claimgraph:managed:begin" "<!-- claimgraph:managed:end" prefix \
  && echo "inject-file: removed the claimgraph section from $INJECT_FILE"

# 8. Store: never deleted by default, and never deleted at all when it lives
# outside the project — a --db pointed elsewhere is not this script's to
# reach for, only to name.
CLAIMGRAPH_DIR="$PROJECT/.claimgraph"
case "$DB" in
  "$CLAIMGRAPH_DIR"/*|"$CLAIMGRAPH_DIR") DB_INSIDE=1 ;;
  *) DB_INSIDE=0 ;;
esac
if [ "$PURGE" != 1 ]; then
  [ -e "$CLAIMGRAPH_DIR" ] && echo "store: kept at $CLAIMGRAPH_DIR — remove with --purge, or rm -rf $CLAIMGRAPH_DIR"
elif [ "$DB_INSIDE" = 1 ]; then
  [ -e "$CLAIMGRAPH_DIR" ] && { rm -rf "$CLAIMGRAPH_DIR"; echo "store: removed $CLAIMGRAPH_DIR"; }
else
  echo "store: $DB lives outside the project — not deleted; remove it and its siblings yourself: $DB $DB.lock $DB.curate.lock $DB.curate.log $DB.evidence $DB.oplog $DB.retrievals $DB.version"
fi

# 9. Global launcher — only ours to remove if it resolves into this checkout.
if [ "$GLOBAL" = 1 ]; then
  CLAIM_BIN="$(command -v claim || true)"
  if [ -n "$CLAIM_BIN" ]; then
    case "$(real_path "$CLAIM_BIN")" in
      "$ROOT"/*) rm -f "$CLAIM_BIN"; echo "global: removed $CLAIM_BIN (-> $ROOT)" ;;
      *) echo "global: claim at $CLAIM_BIN is not this checkout's launcher — left in place" ;;
    esac
  fi
  echo "global: bb and dtlv are shared tools and stay — brew uninstall borkdude/brew/babashka huahaiy/brew/datalevin removes them deliberately"
fi
