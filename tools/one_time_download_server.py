"""
一次性链接下载测试服务器。

用法:
    python tools/one_time_download_server.py

页面每次加载都会生成一个全新的一次性下载链接（token）。
同一 token 的下载地址只有第一次请求会返回真实文件内容；
如果再次请求同一个地址（说明应用对下载 URL 发起了第二次请求），
服务器直接返回 409 "ONE-TIME LINK ALREADY USED"。

判定方法:
  - 下载得到的文件大小 = 8MB、SHA-256 与页面显示一致 -> 复用原始连接成功。
  - 下载失败 / 文件内容是错误提示 -> 应用重新请求了 URL。
  - 服务器控制台每个 token 只允许出现一行 "FIRST-USE"；
    出现 "REJECTED-REPEAT" 即说明发生了二次请求。
"""

import hashlib
import random
import secrets
import socket
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

HOST = "0.0.0.0"
PORT = 8721
SIZE_MB = 8

# 固定种子生成确定性内容，可反复校验
random.seed(20260906)
PAYLOAD = random.randbytes(SIZE_MB * 1024 * 1024)
PAYLOAD_SHA256 = hashlib.sha256(PAYLOAD).hexdigest()

tokens_lock = threading.Lock()
tokens = {}  # token -> {"created": float, "used": bool}


def log(message: str) -> None:
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {message}", flush=True)


PAGE_TEMPLATE = """<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>一次性链接下载测试</title>
<style>
  body {{ font-family: sans-serif; background: #141218; color: #e6e0e9;
         margin: 0; padding: 24px; }}
  .card {{ max-width: 480px; margin: 0 auto; }}
  h1 {{ font-size: 20px; }}
  p, li {{ font-size: 14px; line-height: 1.6; word-break: break-all; }}
  .btn {{ display: block; text-align: center; background: #d0bcff; color: #1d1b20;
          text-decoration: none; font-weight: bold; font-size: 16px;
          padding: 14px; border-radius: 12px; margin: 20px 0; }}
  .hash {{ background: #2b2930; padding: 10px; border-radius: 8px;
           font-family: monospace; font-size: 12px; }}
  code {{ background: #2b2930; padding: 2px 6px; border-radius: 6px; }}
</style>
</head>
<body>
<div class="card">
  <h1>一次性链接下载测试</h1>
  <p>下面的下载地址是<b>一次性</b>的：只有第一次请求会返回真实文件（{size_mb} MB 随机数据），
     再次请求同一地址会返回 409 错误。</p>
  <p>正常结果：浏览器下载的文件大小为 {size_mb} MB、SHA-256 与下方一致（说明复用了触发下载时的原始连接）。
     如果下载失败或内容是“ONE-TIME LINK ALREADY USED”，说明应用重新请求了下载地址。</p>
  <a class="btn" href="/download?t={token}">开始下载（{size_mb} MB）</a>
  <p>本次下载地址：<code>/download?t={token}</code></p>
  <p>文件 SHA-256：<span class="hash">{sha256}</span></p>
</div>
</body>
</html>
"""


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass  # 使用自定义日志

    def _send_text(self, code, text, attachment=False):
        data = text.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        if attachment:
            self.send_header(
                "Content-Disposition",
                'attachment; filename="onetimetest.bin"',
            )
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        parsed = urlparse(self.path)
        remote = self.client_address[0]

        if parsed.path == "/":
            token = secrets.token_urlsafe(12)
            with tokens_lock:
                tokens[token] = {"created": time.time(), "used": False}
            log(f"PAGE     new token {token}")
            body = PAGE_TEMPLATE.format(
                token=token, sha256=PAYLOAD_SHA256, size_mb=SIZE_MB
            ).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return

        if parsed.path == "/download":
            token = parse_qs(parsed.query).get("t", [""])[0]
            with tokens_lock:
                info = tokens.get(token)
                if info is None:
                    log(f"DOWNLOAD {token} UNKNOWN-TOKEN from {remote}")
                    self._send_text(404, "UNKNOWN TOKEN")
                    return
                if info["used"]:
                    log(f"DOWNLOAD {token} REJECTED-REPEAT (app re-requested the URL) from {remote}")
                    self._send_text(
                        409,
                        "ONE-TIME LINK ALREADY USED — the app re-requested the download URL.",
                        attachment=True,
                    )
                    return
                info["used"] = True
            log(f"DOWNLOAD {token} FIRST-USE from {remote} -> sending {SIZE_MB}MB")
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(PAYLOAD)))
            self.send_header(
                "Content-Disposition",
                'attachment; filename="onetimetest.bin"',
            )
            self.end_headers()
            self.wfile.write(PAYLOAD)
            return

        if parsed.path == "/status":
            with tokens_lock:
                lines = [
                    f"{t}  used={i['used']}" for t, i in sorted(tokens.items())
                ]
            self._send_text(200, "\n".join(lines) or "(no tokens yet)")
            return

        self._send_text(404, "NOT FOUND")


def main():
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    log(f"listening on http://0.0.0.0:{PORT}")
    log(f"payload: {SIZE_MB}MB  sha256={PAYLOAD_SHA256}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
