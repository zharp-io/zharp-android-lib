"""A stand-in for Zharp on CI runners. OTLP bodies (gunzipped) go to <out>/<path>-<ns>.bin;
artifact uploads (multipart: kind, debugId, name, arch, release, file) to <out>/artifacts/ as
<kind>-<debugId>-<name>, gunzipped, with their fields in a .json beside it. Answers on
0.0.0.0:4318, which the Android emulator reaches at 10.0.2.2."""
import gzip
import http.server
import json
import os
import sys
import time
from email.parser import BytesParser
from email.policy import default

OUT = sys.argv[1] if len(sys.argv) > 1 else "bodies"
ART = os.path.join(OUT, "artifacts")
os.makedirs(ART, exist_ok=True)


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        if "/artifacts" in self.path:
            self.artifact(body)
            return
        if self.headers.get("Content-Encoding") == "gzip" or body[:2] == b"\x1f\x8b":
            body = gzip.decompress(body)
        name = "%s-%d.bin" % (self.path.strip("/").replace("/", "_"), time.time_ns())
        with open(os.path.join(OUT, name), "wb") as f:
            f.write(body)
        print("POST", self.path, len(body), flush=True)
        self.answer(200, b"")

    def artifact(self, body):
        message = BytesParser(policy=default).parsebytes(
            b"Content-Type: " + self.headers.get("Content-Type", "").encode() + b"\r\n\r\n" + body)
        fields, data = {}, b""
        for part in message.iter_parts():
            name = part.get_param("name", header="content-disposition")
            payload = part.get_payload(decode=True) or b""
            if part.get_filename():
                data = gzip.decompress(payload) if payload[:2] == b"\x1f\x8b" else payload
            else:
                fields[name] = payload.decode("utf-8")
        fields["auth"] = self.headers.get("Authorization", "")
        fields["path"] = self.path
        base = "%s-%s-%s" % (fields.get("kind"), fields.get("debugId"), fields.get("name"))
        with open(os.path.join(ART, base), "wb") as f:
            f.write(data)
        with open(os.path.join(ART, base + ".json"), "w") as f:
            json.dump(fields, f)
        print("UPLOAD", base, len(data), flush=True)
        self.answer(201, b'{"data":{}}')

    def answer(self, status, payload):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *args):
        pass


http.server.ThreadingHTTPServer(("0.0.0.0", 4318), Handler).serve_forever()
