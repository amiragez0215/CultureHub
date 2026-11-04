"""Download pinned official portable broker packages into .tools; no system installation."""
from pathlib import Path
import hashlib
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2] / ".tools/rabbit-runtime"
PACKAGES = [
    ("erlang", "https://github.com/erlang/otp/releases/download/OTP-27.3.4.1/otp_win64_27.3.4.1.zip",
     "ad04aea938737d19847063aff67249dbc4e8195fca31ac73b541e5acea654dbc"),
    ("rabbitmq", "https://github.com/rabbitmq/rabbitmq-server/releases/download/v4.1.4/rabbitmq-server-windows-4.1.4.zip",
     "f32811c37f208f6a249ece70a10bc133d0b2e7983bdced7a697b83925f2edd68"),
]


def main():
    ROOT.mkdir(parents=True, exist_ok=True)
    for name, url, checksum in PACKAGES:
        archive = ROOT / (name + ".zip")
        if not archive.exists():
            with urllib.request.urlopen(url, timeout=120) as response, archive.open("wb") as output:
                while True:
                    block = response.read(1024 * 1024)
                    if not block:
                        break
                    output.write(block)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != checksum:
            raise RuntimeError("Package checksum mismatch; remove only the corrupt ZIP and retry: " + str(archive))
        target = (ROOT / name).resolve()
        if not target.exists():
            with zipfile.ZipFile(archive) as package:
                for member in package.namelist():
                    if target not in (target / member).resolve().parents:
                        raise RuntimeError("Unsafe ZIP member: " + member)
                package.extractall(target)
        print(name + ": pinned package SHA256 verified; " + str(target))


if __name__ == "__main__":
    main()
