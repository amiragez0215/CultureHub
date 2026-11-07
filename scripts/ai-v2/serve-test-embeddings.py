"""Loopback-only OpenAI-compatible test bridge for the real ONNX MiniLM model."""
import argparse
import hashlib
import json
import shutil
import tarfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer


MODEL = "all-MiniLM-L6-v2"
ARCHIVE_SHA = "913d7300ceae3b2dbc2c50d1de4baacab4be7b9380491c27fab7418616a16ec3"


def load_model(cache):
    archive = cache / "onnx.tar.gz"
    with archive.open("rb") as source:
        if hashlib.file_digest(source, "sha256").hexdigest() != ARCHIVE_SHA:
            raise ValueError("Chroma ONNX archive checksum does not match")
    with tarfile.open(archive) as bundle:
        for name in ("onnx/tokenizer.json", "onnx/model.onnx"):
            entry = next(m for m in bundle.getmembers() if m.name.removeprefix("./") == name)
            target = cache / name
            target.parent.mkdir(parents=True, exist_ok=True)
            if not entry.isfile():
                raise ValueError("Unexpected archive member")
            with bundle.extractfile(entry) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output)
    tokenizer = Tokenizer.from_file(str(cache / "onnx/tokenizer.json"))
    tokenizer.enable_truncation(max_length=256)
    tokenizer.enable_padding(pad_id=0, pad_token="[PAD]")
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    model = ort.InferenceSession(
        str(cache / "onnx/model.onnx"), options, providers=["CPUExecutionProvider"]
    )

    def embed(texts):
        result = []
        for start in range(0, len(texts), 16):
            encoded = tokenizer.encode_batch(texts[start:start + 16])
            ids = np.asarray([item.ids for item in encoded], dtype=np.int64)
            mask = np.asarray([item.attention_mask for item in encoded], dtype=np.int64)
            hidden = model.run(None, {
                "input_ids": ids,
                "attention_mask": mask,
                "token_type_ids": np.zeros_like(ids),
            })[0]
            pooled = (hidden * mask[:, :, None]).sum(1) / np.maximum(mask.sum(1)[:, None], 1)
            result.extend(pooled / np.maximum(np.linalg.norm(pooled, axis=1)[:, None], 1e-12))
        return result

    return embed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=18091)
    parser.add_argument("--model-cache", type=Path, required=True)
    options = parser.parse_args()
    model = load_model(options.model_cache.resolve())
    model(["warmup"])
    lock = threading.Lock()

    class Handler(BaseHTTPRequestHandler):
        def read_body(self):
            limit = 2_000_000
            if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
                body = bytearray()
                while True:
                    line = self.rfile.readline(64)
                    length = int(line.split(b";", 1)[0].strip(), 16)
                    if length == 0:
                        self.rfile.readline(64)
                        return body
                    if length < 0 or len(body) + length > limit:
                        raise ValueError("body size")
                    body.extend(self.rfile.read(length))
                    if self.rfile.read(2) != b"\r\n":
                        raise ValueError("chunk delimiter")
            length = int(self.headers.get("Content-Length", 0))
            if not 0 < length <= limit:
                raise ValueError("body size")
            return self.rfile.read(length)

        def do_POST(self):
            if self.path != "/v1/embeddings":
                self.send_error(404)
                return
            try:
                request = json.loads(self.read_body())
                texts = request["input"]
                if isinstance(texts, str):
                    texts = [texts]
                if (
                    not 0 < len(texts) <= 256
                    or any(not isinstance(value, str) or len(value) > 10000 for value in texts)
                    or request["model"] != MODEL
                ):
                    raise ValueError("input")
            except (ValueError, TypeError, KeyError):
                self.send_error(400)
                return
            with lock:
                vectors = model(texts)
            response = json.dumps({
                "object": "list",
                "model": MODEL,
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
