#!/usr/bin/env python3
"""Serves a directory and stores one JSON report: POST /result -> <out>/worker-result.json."""
import json, os, sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

root, out, port = sys.argv[1], sys.argv[2], int(sys.argv[3])


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **k):
        super().__init__(*a, directory=root, **k)

    def do_POST(self):
        length = int(self.headers.get("content-length") or 0)
        body = self.rfile.read(length)
        if self.path == "/result":
            try:
                json.loads(body)
                with open(os.path.join(out, "worker-result.json"), "wb") as f:
                    f.write(body)
            except ValueError:
                pass
        self.send_response(204)
        self.end_headers()

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def log_message(self, fmt, *args):
        sys.stderr.write("report-server: " + (fmt % args) + "\n")


ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
