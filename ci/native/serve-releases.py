#!/usr/bin/env python3
"""Serves a directory laid out as GitHub Releases over HTTPS on 127.0.0.1, for `lighten update` tests.

    serve-releases.py <dir> <latest-version> <port-file>

<dir> holds download/v<version>/<asset>. As on GitHub, latest/download/<asset> redirects to
download/v<latest-version>/<asset>. The certificate is tls/localhost.pem, which tls/truststore.p12
(password changeit) trusts; it is for tests only. The chosen port is written to <port-file>.
"""
import functools
import http.server
import os
import ssl
import sys

root, latest, port_file = sys.argv[1:4]
here = os.path.dirname(os.path.abspath(__file__))


class Handler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        prefix = "/latest/download/"
        if self.path.startswith(prefix):
            self.send_response(302)
            self.send_header("Location", f"/download/v{latest}/{self.path[len(prefix):]}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        super().do_GET()


server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Handler, directory=root))
context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(os.path.join(here, "tls/localhost.pem"), os.path.join(here, "tls/localhost-key.pem"))
server.socket = context.wrap_socket(server.socket, server_side=True)
with open(port_file + ".part", "w") as f:
    f.write(str(server.server_address[1]))
os.rename(port_file + ".part", port_file)
server.serve_forever()
