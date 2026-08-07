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
# used, else ~/.local/bin); BB_VERSION / DTLV_VERSION pin the downloads;
# CLAIMGRAPH_DTLV names a dtlv you already have, skipping that install.
set -euo pipefail

BB_VERSION="${BB_VERSION:-1.12.218}"
DTLV_VERSION="${DTLV_VERSION:-1.0.0}"

# Every download goes through here so no route can skip the hardening: -f
# fails on an HTTP error here, rather than saving the 404 body or a rate-limit
# page as the artifact and surfacing later as a baffling unzip/exec error.
# The proto flags keep the whole redirect chain on TLS.
fetch() {
  if ! curl -fsSL --proto '=https' --proto-redir '=https' --tlsv1.2 --retry 3 -o "$1" "$2"; then
    echo "error: download failed: $2" >&2
    return 1
  fi
}

# Downloads land in a temp dir, never the caller's cwd: fetching into cwd
# clobbers an existing ./install, fails outright in a read-only cwd, and
# (under set -e) leaves the file behind when a later step fails.
WORK=""
cleanup() {
  if [ -n "$WORK" ]; then rm -rf "$WORK"; fi
}
trap cleanup EXIT

workspace() {
  if [ -z "$WORK" ]; then
    WORK="$(mktemp -d "${TMPDIR:-/tmp}/claimgraph-setup.XXXXXX")"
  fi
}

# A minimal container (debian-slim, alpine) has none of the download tooling,
# and without this the script dies mid-way with a bare "command not found".
# Called from inside each download, never up front: a slim CI image with bb and
# dtlv already baked in downloads nothing, and must not be failed for lacking
# the tools that route would have needed.
require_tools() {
  what="$1"; shift
  missing=""
  for tool in "$@"; do
    if ! command -v "$tool" >/dev/null 2>&1; then
      missing="$missing $tool"
    fi
  done
  if [ -n "$missing" ]; then
    echo "error: ${what} needs:${missing}" >&2
    echo "  install first, e.g.  apt-get install -y${missing}  |  apk add${missing}  |  dnf install${missing}" >&2
    exit 1
  fi
}

# Dotted numeric compare ("is $1 at least $2"), in awk because `sort -V` is
# absent from BusyBox and older BSD userlands.
version_at_least() {
  awk -v have="$1" -v want="$2" 'BEGIN {
    nh = split(have, h, "."); nw = split(want, w, ".");
    n = (nh > nw) ? nh : nw;
    for (i = 1; i <= n; i++) {
      hi = (i <= nh) ? h[i] + 0 : 0; wi = (i <= nw) ? w[i] + 0 : 0;
      if (hi > wi) exit 0;
      if (hi < wi) exit 1;
    }
    exit 0
  }'
}

BREW=""
BREW_PREFIX=""
if [ "${USE_BREW:-1}" != "0" ] && command -v brew >/dev/null 2>&1; then
  BREW="$(command -v brew)"
  BREW_PREFIX="$(brew --prefix)"
fi

if [ -n "$BREW" ]; then
  INSTALL_DIR="${INSTALL_DIR:-$BREW_PREFIX/bin}"
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

# bb.edn's :min-bb-version is the version the project actually enforces, and it
# is read here rather than restated: BB_VERSION is overridable, so a pin below
# bb.edn's floor would install a bb that warns on every later `claim` call —
# precisely what pinning a version is meant to prevent. awk, not sed, because
# version_at_least already makes awk a dependency and sed is one more.
BB_MIN="$(awk -F'"' '/^[^;]*:min-bb-version[ \t]*"/ { print $2; exit }' \
  "$ROOT/bb.edn" 2>/dev/null || true)"
if [ -n "$BB_MIN" ] && ! version_at_least "$BB_VERSION" "$BB_MIN"; then
  echo "error: BB_VERSION=${BB_VERSION} is below the ${BB_MIN} that ${ROOT}/bb.edn requires" >&2
  echo "  installing it would leave every claim call printing bb's min-version warning" >&2
  echo "  pin ${BB_MIN} or newer, or unset BB_VERSION to take the default" >&2
  exit 1
fi

mkdir -p "$INSTALL_DIR" 2>/dev/null || true
if [ ! -d "$INSTALL_DIR" ] || [ ! -w "$INSTALL_DIR" ]; then
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

install_bb_pinned() {
  # babashka's own installer fetches a .tar.gz over curl on every unix target.
  require_tools "the pinned babashka download" curl tar
  workspace
  echo "Installing babashka ${BB_VERSION}..."
  # The installer CODE is pinned to the same tag as the binary it installs:
  # from master, any change there lands in every fresh install unreviewed.
  # Run it through `bash` rather than chmod+exec, which a noexec TMPDIR blocks.
  fetch "$WORK/install" "https://raw.githubusercontent.com/babashka/babashka/v${BB_VERSION}/install"
  bash "$WORK/install" --dir "$INSTALL_DIR" --version "$BB_VERSION"
}

BB_FRESH=0
if ! command -v bb >/dev/null 2>&1; then
  if [ -n "$BREW" ]; then
    echo "Installing babashka (brew install borkdude/brew/babashka)..."
    brew install borkdude/brew/babashka
  else
    install_bb_pinned
  fi
  BB_FRESH=1
fi

if ! bb --version >/dev/null 2>&1; then
  echo "error: bb at $(command -v bb || echo '?') is installed but does not run" >&2
  echo "  see the error with:  bb --version" >&2
  exit 1
fi

BB_PATH="$(command -v bb)"
# Unlike the download tools this one is needed on every route, including the
# one that installs nothing: without it version_at_least dies at 127 and the
# next check blames bb for an empty version string.
require_tools "the babashka version check" awk
bb_installed_version() { bb --version 2>/dev/null | awk '{ v = $NF; sub(/^v/, "", v); print v }'; }
BB_HAVE="$(bb_installed_version)"
if [ -z "$BB_HAVE" ]; then
  echo "error: bb at ${BB_PATH} answered 'bb --version' with nothing — a broken install" >&2
  echo "  remove or repair it, then re-run this script" >&2
  exit 1
fi

# Presence on PATH is not enough: an older bb runs claimgraph's code against
# vars it does not have, which surfaces far from here and reads like a bug in
# claimgraph. Replacing a stale bb is only ours to do when this script put it
# there; anything else is the user's toolchain to move.
if ! version_at_least "$BB_HAVE" "$BB_VERSION"; then
  if [ "$BB_FRESH" = 0 ] && [ -z "$BREW" ] && [ "$BB_PATH" = "$INSTALL_DIR/bb" ]; then
    echo "bb ${BB_HAVE} at ${BB_PATH} predates the pinned ${BB_VERSION} — reinstalling..."
    install_bb_pinned
    BB_HAVE="$(bb_installed_version)"
  fi
  if ! version_at_least "$BB_HAVE" "$BB_VERSION"; then
    echo "error: bb ${BB_HAVE} (${BB_PATH}) is older than the ${BB_VERSION} this run requires" >&2
    # Name the value actually compared against: under an overridden BB_VERSION
    # the number above is not what bb.edn says, and blaming bb.edn for it sends
    # the reader to a file that disagrees with the error.
    if [ "$BB_VERSION" = "${BB_MIN:-}" ]; then
      echo "  ${BB_VERSION} is bb.edn's :min-bb-version — below it every claim call warns, and fails obscurely" >&2
    else
      echo "  \$BB_VERSION set that bar; bb.edn's own :min-bb-version is ${BB_MIN:-unreadable}" >&2
    fi
    # Only brew's own bb is brew's to upgrade. A bb further forward on PATH than
    # brew's prefix answers `brew upgrade` with "No available formula", so the
    # advice has to follow the binary rather than the mere presence of brew.
    BB_IS_BREW=0
    if [ -n "$BREW_PREFIX" ]; then
      case "$BB_PATH" in "$BREW_PREFIX"/*) BB_IS_BREW=1 ;; esac
    fi
    if [ "$BB_IS_BREW" = 1 ]; then
      echo "  upgrade it:  brew upgrade borkdude/brew/babashka" >&2
    else
      echo "  remove ${BB_PATH} (or put ${INSTALL_DIR} ahead of it on PATH), then re-run this script" >&2
    fi
    exit 1
  fi
fi
echo "bb OK (${BB_PATH}, $(bb --version))"

# $CLAIMGRAPH_DTLV is the project's documented override for a dtlv that is not
# on PATH — claimgraph.config resolves it on every run, and the remediation
# below tells people to set it — so it has to be the binary this script detects
# and verifies too, or following that advice reproduces the same error. Set but
# unusable is fatal rather than ignored: installing a second dtlv beside it
# changes nothing, since every later `claim` still resolves the variable.
DTLV_BIN=""
if [ -n "${CLAIMGRAPH_DTLV:-}" ]; then
  if [ -f "$CLAIMGRAPH_DTLV" ] && [ -x "$CLAIMGRAPH_DTLV" ]; then
    DTLV_BIN="$CLAIMGRAPH_DTLV"
  else
    DTLV_BIN="$(command -v "$CLAIMGRAPH_DTLV" 2>/dev/null || true)"
  fi
  if [ -z "$DTLV_BIN" ]; then
    echo "error: \$CLAIMGRAPH_DTLV is '${CLAIMGRAPH_DTLV}', which is neither an executable file nor on PATH" >&2
    echo "  point it at the dtlv binary, or unset it to install dtlv into ${INSTALL_DIR}" >&2
    exit 1
  fi
else
  DTLV_BIN="$(command -v dtlv 2>/dev/null || true)"
fi

if [ -z "$DTLV_BIN" ]; then
  if [ -n "$BREW" ]; then
    echo "Installing dtlv (brew install huahaiy/brew/datalevin)..."
    brew install huahaiy/brew/datalevin
    DTLV_BIN="$(command -v dtlv 2>/dev/null || echo "$BREW_PREFIX/bin/dtlv")"
  else
    echo "Installing dtlv ${DTLV_VERSION}..."
    # Asset names are read off the pinned release, never guessed. datalevin
    # 1.0.0 publishes linux amd64/aarch64 and macOS aarch64 only — there is no
    # macOS x86_64 build, and the huahaiy/brew tap ships that same aarch64 zip
    # to every Mac, so Homebrew is not a way around it on Intel.
    case "$(uname -sm)" in
      "Linux x86_64")  ASSET="dtlv-${DTLV_VERSION}-ubuntu-22.04-amd64.zip" ;;
      "Darwin arm64")  ASSET="dtlv-${DTLV_VERSION}-macos-14-aarch64.zip" ;;
      "Linux aarch64") ASSET="dtlv-${DTLV_VERSION}-ubuntu-24.04-arm-aarch64.zip" ;;
      "Darwin x86_64")
        echo "error: datalevin ${DTLV_VERSION} publishes no macOS x86_64 (Intel) binary, and the" >&2
        echo "  huahaiy/brew tap installs the aarch64 build on every Mac, so brew will not help." >&2
        echo "  Build dtlv from source (https://github.com/datalevin/datalevin), or point" >&2
        echo "  \$CLAIMGRAPH_DTLV at a dtlv you already have, then re-run this script." >&2
        exit 1 ;;
      *)
        echo "error: no pinned dtlv build for $(uname -sm)" >&2
        echo "  install dtlv yourself and put it on PATH (or set \$CLAIMGRAPH_DTLV), then re-run" >&2
        exit 1 ;;
    esac
    # After the platform check, not before it: "no build for your machine" is
    # the more useful answer than "install curl" when both are true.
    require_tools "the dtlv download" curl unzip
    workspace
    fetch "$WORK/dtlv.zip" "https://github.com/datalevin/datalevin/releases/download/${DTLV_VERSION}/${ASSET}"
    unzip -oq "$WORK/dtlv.zip" -d "$WORK"
    if [ ! -f "$WORK/dtlv" ]; then
      echo "error: ${ASSET} contains no dtlv binary — the release layout changed" >&2
      exit 1
    fi
    chmod +x "$WORK/dtlv"
    mv "$WORK/dtlv" "$INSTALL_DIR/dtlv"
    DTLV_BIN="$INSTALL_DIR/dtlv"
  fi
fi

# A bare `dtlv help >/dev/null` under set -e kills the script silently, which
# is the least helpful thing to do with the one dependency claimgraph cannot
# open a store without. Verify the binary claimgraph will actually resolve —
# under $CLAIMGRAPH_DTLV that is not whatever `dtlv` happens to hit on PATH.
if "$DTLV_BIN" help >/dev/null 2>&1; then
  echo "dtlv OK (${DTLV_BIN})"
else
  echo "error: dtlv at ${DTLV_BIN} is installed but 'dtlv help' failed" >&2
  echo "  see the error with:  ${DTLV_BIN} help" >&2
  echo "  a wrong-architecture binary is the usual cause (an aarch64 dtlv on an x86_64 machine)" >&2
  exit 1
fi

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
echo "                # memory pile you already have (auto-memory notes),"
echo "                # audited together with CLAUDE.md/rules files —"
echo "                # no store, nothing written"
echo "  claim setup   # then: wire the graph — store, agent skill, ambient loop"
