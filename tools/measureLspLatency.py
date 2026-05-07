#!/usr/bin/env python3
"""Drive ltex-ls-plus over LSP stdio and measure didOpen → publishDiagnostics.

Reports the latency from sending textDocument/didOpen to receiving the
matching textDocument/publishDiagnostics for one or more files, all driven
through a single warm server process so JVM/LanguageTool startup is paid
only once. Useful for diagnosing whether a slow check is server-side and
for finding fragments of the pipeline that scale poorly with input size.

Usage:
  tools/measureLspLatency.py [--server PATH] [--language-id ID] FILE [FILE ...]

Examples:
  # default: org language id, server from target/appassembler/bin
  tools/measureLspLatency.py test2.org test1.org test2.org

  # markdown with a custom server binary
  tools/measureLspLatency.py --language-id markdown --server ./ltex-ls-plus a.md

The first run on each cold server pays JVM + LanguageTool warmup (~1-2s).
Repeat a file to see warm-server latency.
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

    for i, path_str in enumerate(args.files):
        path = Path(path_str)
        text = path.read_text(encoding="utf-8")
        # Unique URI per iteration so didOpen always opens a fresh document.
        uri = f"file://{path.resolve()}?iter={i}"

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
                items = msg.get("params", {}).get("items", [])
                send({
                    "jsonrpc": "2.0",
                    "id": msg["id"],
                    "result": [{} for _ in items],
                })
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
            f"{path}: {size:>8} bytes  →  {elapsed_ms:8.1f} ms  "
            f"({diag_count} diagnostics)"
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
