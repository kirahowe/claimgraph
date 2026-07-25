#!/usr/bin/env bash
# Install claimgraph: its two native-binary dependencies — babashka (bb) and
# the Datalevin pod binary (dtlv), both GraalVM native images, no JVM — plus
# a global `claim` launcher pointing at this checkout.
#
# Homebrew is the recommended route and is used automatically when `brew` is
# on PATH, via each project's official tap (borkdude/brew for bb,
# huahaiy/brew for dtlv). Without brew — most Linux boxes, CI — the pinned
# GitHub release binaries are downloaded instead.
#
# Overridable: USE_BREW=0 forces the pinned downloads even when brew exists
# (CI does this for reproducibility); INSTALL_DIR relocates the downloaded
# binaries and the `claim` launcher (default: brew's bin dir when brew is
# used, else ~/.local/bin); BB_VERSION / DTLV_VERSION pin the downloads.
set -euo pipefail

BB_VERSION="${BB_VERSION:-1.12.218}"
DTLV_VERSION="${DTLV_VERSION:-1.0.0}"

BREW=""
if [ "${USE_BREW:-1}" != "0" ] && command -v brew >/dev/null 2>&1; then
  BREW="$(command -v brew)"
fi

if [ -n "$BREW" ]; then
  INSTALL_DIR="${INSTALL_DIR:-$(brew --prefix)/bin}"
else
  INSTALL_DIR="${INSTALL_DIR:-$HOME/.local/bin}"
fi

# Resolve this checkout's root, so the `claim` launcher works from anywhere.
SOURCE="${BASH_SOURCE[0]}"
while [ -L "$SOURCE" ]; do
  DIR="$(cd -P "$(dirname "$SOURCE")" && pwd)"
  SOURCE="$(readlink "$SOURCE")"
  [[ $SOURCE != /* ]] && SOURCE="$DIR/$SOURCE"
done
ROOT="$(cd -P "$(dirname "$SOURCE")/.." && pwd)"

mkdir -p "$INSTALL_DIR"
if [ ! -w "$INSTALL_DIR" ]; then
  echo "error: $INSTALL_DIR is not writable — re-run with INSTALL_DIR set to a user-writable dir, e.g." >&2
  echo "  INSTALL_DIR=\$HOME/.local/bin $0" >&2
  exit 1
fi

# Remember whether INSTALL_DIR was already on PATH (warn at the end if not),
# then prepend it so the just-installed binaries verify below.
case ":$PATH:" in
  *":$INSTALL_DIR:"*) ON_PATH=1 ;;
  *) ON_PATH=0 ;;
esac
PATH="$INSTALL_DIR:$PATH"

if ! command -v bb >/dev/null 2>&1; then
  if [ -n "$BREW" ]; then
    echo "Installing babashka (brew install borkdude/brew/babashka)..."
    brew install borkdude/brew/babashka
  else
    echo "Installing babashka ${BB_VERSION}..."
    curl -sLO https://raw.githubusercontent.com/babashka/babashka/master/install
    chmod +x install
    ./install --dir "$INSTALL_DIR" --version "$BB_VERSION"
    rm -f install
  fi
fi
bb --version

if ! command -v dtlv >/dev/null 2>&1; then
  if [ -n "$BREW" ]; then
    echo "Installing dtlv (brew install huahaiy/brew/datalevin)..."
    brew install huahaiy/brew/datalevin
  else
    echo "Installing dtlv ${DTLV_VERSION}..."
    case "$(uname -sm)" in
      "Linux x86_64")  ASSET="dtlv-${DTLV_VERSION}-ubuntu-22.04-amd64.zip" ;;
      "Darwin arm64")  ASSET="dtlv-${DTLV_VERSION}-macos-14-aarch64.zip" ;;
      "Linux aarch64") ASSET="dtlv-${DTLV_VERSION}-ubuntu-24.04-arm-aarch64.zip" ;;
      *) echo "Unsupported platform: $(uname -sm)" >&2; exit 1 ;;
    esac
    TMP="$(mktemp -d)"
    curl -sL -o "$TMP/dtlv.zip" "https://github.com/datalevin/datalevin/releases/download/${DTLV_VERSION}/${ASSET}"
    (cd "$TMP" && unzip -oq dtlv.zip && chmod +x dtlv && mv dtlv "$INSTALL_DIR/dtlv")
    rm -rf "$TMP"
  fi
fi
dtlv help >/dev/null && echo "dtlv OK ($(command -v dtlv))"

ln -sf "$ROOT/bin/claim" "$INSTALL_DIR/claim"
echo "claim -> $ROOT/bin/claim (via $INSTALL_DIR/claim)"

if [ "$ON_PATH" = 0 ]; then
  echo "note: $INSTALL_DIR is not on your PATH — add:  export PATH=\"$INSTALL_DIR:\$PATH\""
fi

echo
echo "Done. claimgraph is installed: a bi-temporal, epistemically-typed knowledge"
echo "graph that gives coding agents real project memory — queryable facts with"
echo "provenance and history instead of an ever-growing markdown pile."
echo
echo "In the project you want it to remember:"
echo
echo "  claim audit   # start here: a read-only consistency scorecard of the"
echo "                # memory pile you already have (CLAUDE.md, rules files,"
echo "                # auto-memory notes) — no store, nothing written"
echo "  claim setup   # then: wire the graph — store, agent skill, ambient loop"
