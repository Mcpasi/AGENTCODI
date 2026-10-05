#!/usr/bin/env python3
"""Serve a CI snapshot with a local, explicitly trusted TLS certificate."""
import argparse
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import ssl

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--root", required=True)
parser.add_argument("--certificate", required=True)
parser.add_argument("--key", required=True)
args = parser.parse_args()
server = ThreadingHTTPServer(("127.0.0.1", 8443), partial(SimpleHTTPRequestHandler, directory=args.root))
tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
tls.load_cert_chain(args.certificate, args.key)
server.socket = tls.wrap_socket(server.socket, server_side=True)
server.serve_forever()
