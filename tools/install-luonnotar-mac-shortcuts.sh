#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd -P)"
ZSHRC="${ZDOTDIR:-$HOME}/.zshrc"
mkdir -p "$(dirname "$ZSHRC")"
touch "$ZSHRC"

PROJECT_ROOT="$PROJECT_ROOT" ZSHRC="$ZSHRC" python3 - <<'PY'
import os
from pathlib import Path

start = "# >>> luonnotar remote shortcuts >>>"
end = "# <<< luonnotar remote shortcuts <<<"
root = os.environ["PROJECT_ROOT"]
zshrc = Path(os.environ["ZSHRC"])
text = zshrc.read_text() if zshrc.exists() else ""

while start in text and end in text:
    a = text.index(start)
    b = text.index(end, a) + len(end)
    text = text[:a].rstrip() + "\n\n" + text[b:].lstrip("\n")

escaped = root.replace("'", "'\\''")
block = f'''{start}
export LUONNOTAR_PROJECT_ROOT='{escaped}'
luoterm() {{
  bash "$LUONNOTAR_PROJECT_ROOT/tools/luonnotar-remote-termux.sh" "$@"
}}
{end}
'''

if text and not text.endswith("\n"):
    text += "\n"
text = text.rstrip() + "\n\n" + block
zshrc.write_text(text)
PY

echo "MAC_SHORTCUT_OK: installed luoterm -> $PROJECT_ROOT/tools/luonnotar-remote-termux.sh"
echo "Run: source \"$ZSHRC\""
echo "Then: luoterm --iq   (or simply: luoterm)"
