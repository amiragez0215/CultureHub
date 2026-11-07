"""Loopback-only acceptance bridge for Chroma's real bundled ONNX MiniLM model."""
import argparse
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from chromadb.utils.embedding_functions.onnx_mini_lm_l6_v2 import ONNXMiniLM_L6_V2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18091)
    parser.add_argument("--model-cache", type=Path, required=True)
    options = parser.parse_args()
    model = ONNXMiniLM_L6_V2()
    model.DOWNLOAD_PATH = options.model_cache.resolve()
    model(["warmup"])
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            if self.path != "/v1/embeddings":
                self.send_error(404)
                return
            try:
                length = int(self.headers.get("Content-Length", 0))
                if not 0 < length <= 2_000_000:
                    raise ValueError("body size")
                request = json.loads(self.rfile.read(length))
                texts = request["input"]
                if isinstance(texts, str):
                    texts = [texts]
                if (
                    not 0 < len(texts) <= 256
                    or any(not isinstance(value, str) or len(value) > 10000 for value in texts)
                    or request["model"] != "all-MiniLM-L6-v2"
                ):
                    raise ValueError("input")
            except (ValueError, TypeError, KeyError):
                self.send_error(400)
                return
            with lock:
                vectors = model(texts)
            response = json.dumps({
                "object": "list",
                "model": "all-MiniLM-L6-v2",
                "data": [
                    {"object": "embedding", "index": index, "embedding": vector.tolist()}
                    for index, vector in enumerate(vectors)
                ],
            }).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(response)))
            self.end_headers()
            self.wfile.write(response)

    with ThreadingHTTPServer(("127.0.0.1", options.port), Handler) as server:
        print("ONNX embedding endpoint ready", flush=True)
        server.serve_forever()


if __name__ == "__main__":
    main()
