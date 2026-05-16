#!/usr/bin/env python3
"""Drive ltex-ls-plus over LSP stdio and measure didOpen → publishDiagnostics.

Reports the latency from sending textDocument/didOpen to receiving the
matching textDocument/publishDiagnostics for one or more files, all driven
through a single warm server process so JVM/LanguageTool startup is paid
only once. Useful for diagnosing whether a slow check is server-side and
for finding fragments of the pipeline that scale poorly with input size.

Usage:
  tools/measureLspLatency.py [--server PATH] [--language-id ID]
                             [--chunk-size BYTES] FILE [FILE ...]

Examples:
  # default: org language id, server from target/appassembler/bin
  tools/measureLspLatency.py test2.org test1.org test2.org

  # markdown with a custom server binary
  tools/measureLspLatency.py --language-id markdown --server ./ltex-ls-plus a.md

  # split each file into ~19 KB chunks; time each chunk against a warm server
  tools/measureLspLatency.py --language-id markdown --chunk-size 19000 README.md

  # supply ltex settings (e.g. premium API credentials) like ltex-cli-plus does
  tools/measureLspLatency.py --client-config cfg.json --language-id markdown a.md

Example --client-config JSON (premium endpoint with env-var-expanded creds;
generate with `envsubst < this-as-template > cfg.json`):

  {
    "ltex": {
      "languageToolHttpServerUri": "https://api.languagetoolplus.com",
      "languageToolOrg": {
        "username": "${LANGUAGETOOL_USERNAME}",
        "apiKey": "${LANGUAGETOOL_API_KEY}"
      },
      "language": "en-US"
    }
  }

The first run on each cold server pays JVM + LanguageTool warmup (~1-2s).
Repeat a file, or use --chunk-size, to see warm-server per-request latency.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Iterator

DEFAULT_SERVER = (
    Path(__file__).resolve().parent.parent
    / "target"
    / "appassembler"
    / "bin"
    / "ltex-ls-plus"
)


def encode(msg: dict) -> bytes:
    body = json.dumps(msg).encode("utf-8")
    header = f"Content-Length: {len(body)}\r\n\r\n".encode("ascii")
    return header + body


def chunk_by_bytes(text: str, target: int) -> list[str]:
    """Split text into pieces of roughly `target` UTF-8 bytes.

    Preferred boundary is a blank line (``\\n\\n``); falls back to any
    newline, and finally to a UTF-8-safe hard cut for the pathological case
    of a single line longer than the budget.
    """
    data = text.encode("utf-8")
    if len(data) <= target:
        return [text]
    chunks: list[str] = []
    start = 0
    while start < len(data):
        if len(data) - start <= target:
            chunks.append(data[start:].decode("utf-8"))
            break
        end = start + target
        cut = data.rfind(b"\n\n", start, end)
        if cut > start:
            # End chunk after the first \n; skip the blank line entirely.
            chunks.append(data[start : cut + 1].decode("utf-8"))
            start = cut + 2
            continue
        cut = data.rfind(b"\n", start, end)
        if cut > start:
            chunks.append(data[start : cut + 1].decode("utf-8"))
            start = cut + 1
            continue
        # No newline at all in this window: hard-cut at `end`, but back off
        # so we never split a UTF-8 multi-byte sequence (continuation bytes
        # match 10xxxxxx).
        hard = end
        while hard > start and (data[hard] & 0xC0) == 0x80:
            hard -= 1
        if hard == start:
            hard = end
        chunks.append(data[start:hard].decode("utf-8", errors="replace"))
        start = hard
    return chunks


def iter_chunks(path: Path, chunk_size: int) -> Iterator[tuple[str, str]]:
    """Yield (label, text) pairs for `path`, chunked if chunk_size > 0."""
    text = path.read_text(encoding="utf-8")
    if chunk_size <= 0:
        yield (str(path), text)
        return
    pieces = chunk_by_bytes(text, chunk_size)
    width = len(str(len(pieces)))
    for k, piece in enumerate(pieces, 1):
        yield (f"{path}[{k:0{width}d}/{len(pieces)}]", piece)


def read_message(stream) -> dict | None:
    headers = b""
    while b"\r\n\r\n" not in headers:
        chunk = stream.read(1)
        if not chunk:
            return None
        headers += chunk
    head_part = headers.split(b"\r\n\r\n", 1)[0]
    length = 0
    for line in head_part.split(b"\r\n"):
        if line.lower().startswith(b"content-length:"):
            length = int(line.split(b":", 1)[1].strip())
    body = b""
    while len(body) < length:
        chunk = stream.read(length - len(body))
        if not chunk:
            break
        body += chunk
    return json.loads(body.decode("utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Measure ltex-ls-plus didOpen → publishDiagnostics latency.",
    )
    parser.add_argument(
        "--server",
        default=str(DEFAULT_SERVER),
        help=f"Path to ltex-ls-plus launcher (default: {DEFAULT_SERVER}).",
    )
    parser.add_argument(
        "--language-id",
        default="org",
        help="LSP languageId for the documents (default: org).",
    )
    parser.add_argument(
        "--client-config",
        metavar="FILE",
        help=(
            "JSON file with client settings, same shape as ltex-cli-plus's "
            "--client-configuration (e.g. {\"ltex\": {...}}). Returned to the "
            "server on workspace/configuration requests."
        ),
    )
    parser.add_argument(
        "--chunk-size",
        type=int,
        default=0,
        metavar="BYTES",
        help=(
            "If > 0, split each file into pieces of roughly this many UTF-8 "
            "bytes (on newline boundaries) and time each piece separately. "
            "Useful for probing per-request latency against a warm server. "
            "Default: 0 (no chunking)."
        ),
    )
    parser.add_argument(
        "files",
        nargs="+",
        help="Files to check. Repeat a file to measure warm-server latency.",
    )
    args = parser.parse_args()

    server_path = Path(args.server)
    if not server_path.exists():
        print(f"server not found: {server_path}", file=sys.stderr)
        print("hint: run `mvn package -DskipTests` first", file=sys.stderr)
        return 1

    client_config: dict = {}
    if args.client_config:
        client_config = json.loads(Path(args.client_config).read_text(encoding="utf-8"))

    proc = subprocess.Popen(
        [str(server_path)],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )

    # Drain stderr in background so the pipe never fills up.
    def drain() -> None:
        for _ in iter(proc.stderr.readline, b""):
            pass

    threading.Thread(target=drain, daemon=True).start()

    next_id = 1

    def send(msg: dict) -> None:
        proc.stdin.write(encode(msg))
        proc.stdin.flush()

    # initialize
    send({
        "jsonrpc": "2.0",
        "id": next_id,
        "method": "initialize",
        "params": {
            "processId": None,
            "rootUri": None,
            "capabilities": {
                "workspace": {"configuration": True},
                "textDocument": {
                    "publishDiagnostics": {},
                    "synchronization": {},
                },
            },
            "initializationOptions": {},
        },
    })
    init_id = next_id
    next_id += 1

    while True:
        msg = read_message(proc.stdout)
        if msg is None:
            print("server died during initialize", file=sys.stderr)
            return 1
        if msg.get("id") == init_id and "result" in msg:
            break

    send({"jsonrpc": "2.0", "method": "initialized", "params": {}})

    iter_index = 0
    for path_str in args.files:
        path = Path(path_str)
        items = list(iter_chunks(path, args.chunk_size))
        file_bytes = 0
        file_ms = 0.0
        for label, text in items:
            # Unique URI per iteration so didOpen always opens a fresh document.
            uri = f"file://{path.resolve()}?iter={iter_index}"
            iter_index += 1

            t_start = time.perf_counter()
            send({
                "jsonrpc": "2.0",
                "method": "textDocument/didOpen",
                "params": {
                    "textDocument": {
                        "uri": uri,
                        "languageId": args.language_id,
                        "version": 1,
                        "text": text,
                    },
                },
            })

            diag_count = 0
            while True:
                msg = read_message(proc.stdout)
                if msg is None:
                    print("server died", file=sys.stderr)
                    return 1
                method = msg.get("method")
                if method == "workspace/configuration":
                    cfg_items = msg.get("params", {}).get("items", [])
                    reply = []
                    for it in cfg_items:
                        section = it.get("section", "")
                        value: object = client_config
                        for part in section.split(".") if section else []:
                            if isinstance(value, dict) and part in value:
                                value = value[part]
                            else:
                                value = {}
                                break
                        reply.append(value)
                    send({"jsonrpc": "2.0", "id": msg["id"], "result": reply})
                elif method == "window/workDoneProgress/create":
                    send({"jsonrpc": "2.0", "id": msg["id"], "result": None})
                elif method == "textDocument/publishDiagnostics":
                    p = msg.get("params", {})
                    if p.get("uri") == uri:
                        diag_count = len(p.get("diagnostics", []))
                        break

            elapsed_ms = (time.perf_counter() - t_start) * 1000
            size = len(text.encode("utf-8"))
            print(
                f"{label}: {size:>8} bytes  →  {elapsed_ms:8.1f} ms  "
                f"({diag_count} diagnostics)"
            )
            file_bytes += size
            file_ms += elapsed_ms

        if len(items) > 1:
            mean_ms = file_ms / len(items)
            print(
                f"  {path} total: {file_bytes} bytes across {len(items)} chunks "
                f" →  {file_ms:.1f} ms  (mean {mean_ms:.1f} ms/chunk)"
            )

    # shutdown
    shutdown_id = next_id
    next_id += 1
    send({"jsonrpc": "2.0", "id": shutdown_id, "method": "shutdown", "params": None})
    while True:
        msg = read_message(proc.stdout)
        if msg is None or msg.get("id") == shutdown_id:
            break
    send({"jsonrpc": "2.0", "method": "exit", "params": None})
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.kill()
    return 0


if __name__ == "__main__":
    sys.exit(main())
