"""Export a committed CultureHub source tree with synthetic knowledge and no local data."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import subprocess
import zipfile


def main():
    root = Path(__file__).resolve().parents[2]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--ref", default="HEAD")
    args = parser.parse_args()
    def git(*arguments):
        return subprocess.check_output(["git", *arguments], cwd=root)

    commit = git("rev-parse", "--verify", args.ref + "^{commit}").decode().strip()
    archived = git("archive", "--format=zip", "--prefix=CultureHub/", commit)
    with zipfile.ZipFile(io.BytesIO(archived)) as source:
        files = {item.filename: source.read(item) for item in source.infolist() if not item.is_dir()}
    required = ["pom.xml", "compose.yaml", "web/nginx.conf", "web/package-lock.json", ".env.example"]
    knowledge = "culturehub-business/src/main/resources/knowledge/"
    required.extend(knowledge + entry["file"] for entry in json.loads(files["CultureHub/" + knowledge + "manifest.json"]))
    for path in required:
        if "CultureHub/" + path not in files:
            raise ValueError(f"Missing deliverable: {path}")
    for name in files:
        path = PurePosixPath(name)
        private = {".git", ".tools", "target", "dist", "node_modules", "logs", "__pycache__"}
        runtime = {".zip", ".jar", ".class", ".log", ".rdb", ".aof", ".db", ".sqlite", ".sqlite3", ".pem", ".key"}
        if (private.intersection(path.parts) or path.suffix.lower() in runtime
            or any(part.startswith(".env") and part != ".env.example" for part in path.parts)):
            raise ValueError(f"Unexpected runtime/private file: {name}")
    files["CultureHub/SOURCE-VERSION.txt"] = f"Git commit: {commit}\n".encode()
    output = args.output.resolve()
    temp = output.with_suffix(".zip.tmp")
    with zipfile.ZipFile(temp, "w", zipfile.ZIP_DEFLATED) as result:
        for path, content in files.items():
            result.writestr(path, content)
    with zipfile.ZipFile(temp) as result:
        if result.testzip() is not None or set(result.namelist()) != set(files):
            raise ValueError("Source archive failed validation")
    temp.replace(output)
    print(f"{output}  {len(files)} files  SHA-256 {hashlib.sha256(output.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
