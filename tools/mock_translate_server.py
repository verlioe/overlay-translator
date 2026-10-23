#!/usr/bin/env python3
"""
本機 mock 翻譯伺服器。

## 為什麼需要這個

開發懸浮翻譯時，最麻煩的是「沒有 API Key 就測不了」——
但 API Key 要花錢，而且不該為了測試就把金鑰放進自動化流程裡。

這個伺服器假裝是 OpenAI 相容端點，回傳一眼看得出來是假的譯文，
所以可以完整驗證「擷取 → 送出 → 顯示譯文 → 寫回輸入框」整條管線，
完全不需要真實金鑰、也不會產生任何費用。

## 用法

    # 1. 啟動伺服器
    python tools/mock_translate_server.py --port 8099

    # 2. 讓 Android 裝置可以連到電腦的 localhost
    adb reverse tcp:8099 tcp:8099

    # 3. 在 App 的「設定」頁填：
    #    API 端點 = http://127.0.0.1:8099
    #    API Key  = sk-mock（隨便填，非空即可）

## 端點

    POST /chat/completions   回傳 {"choices":[{"message":{"content":"〔模擬譯文〕…"}}]}
    GET  /models             回傳 {"data":[{"id":"mock-translator"}, …]}

完成測試後記得把 App 的 Base URL 改回 https://api.deepseek.com，
並填入真實金鑰。
"""

import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TRANSLATION_MARKER = "【待翻譯】"
OUTPUT_INSTRUCTION = "只輸出譯文本身。"


def extract_target_text(prompt: str) -> str:
    """從我們自己組的提示詞裡，把真正要翻的那一段撈出來。"""
    text = prompt
    if TRANSLATION_MARKER in text:
        text = text.split(TRANSLATION_MARKER, 1)[1]
    if OUTPUT_INSTRUCTION in text:
        text = text.split(OUTPUT_INSTRUCTION, 1)[0]
    return text.strip()


def last_user_content(messages) -> str:
    for message in reversed(messages):
        if message.get("role") == "user":
            return message.get("content", "") or ""
    return ""


class MockHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send_json(self, payload, status=200):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/models"):
            self._send_json({
                "object": "list",
                "data": [
                    {"id": "mock-translator", "object": "model"},
                    {"id": "mock-translator-pro", "object": "model"},
                ],
            })
        else:
            self._send_json({"error": {"message": "not found: " + self.path}}, 404)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        raw = self.rfile.read(length).decode("utf-8", "replace")
        try:
            payload = json.loads(raw)
        except Exception:
            payload = {}

        messages = payload.get("messages", [])
        target = extract_target_text(last_user_content(messages))
        # 刻意做成一眼看得出是假的，方便在截圖裡確認真的走了這條路
        translated = "〔模擬譯文〕" + " ".join(target.split())

        print(f"[mock] 翻譯請求：model={payload.get('model')} 原文={target[:60]!r}", flush=True)

        self._send_json({
            "id": "mock-completion",
            "object": "chat.completion",
            "created": 0,
            "model": payload.get("model", "mock-translator"),
            "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": translated},
                "finish_reason": "stop",
            }],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        })

    def log_message(self, fmt, *args):
        # 預設的 log 很吵，這裡只留我們自己印的
        pass


def main():
    parser = argparse.ArgumentParser(description="本機 mock 翻譯伺服器")
    parser.add_argument("--port", type=int, default=8099)
    parser.add_argument("--host", default="127.0.0.1")
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), MockHandler)
    print(f"[mock] 開始監聽 http://{args.host}:{args.port}", flush=True)
    print("[mock] 記得執行：adb reverse tcp:%d tcp:%d" % (args.port, args.port), flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[mock] 停止", flush=True)


if __name__ == "__main__":
    main()
