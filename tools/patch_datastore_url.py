#!/usr/bin/env python3
"""
直接修改 App 的 DataStore 設定檔，把 API 端點還原成 DeepSeek。

## 為什麼需要這支腳本

自動化 UI 測試要改一個文字欄位時，`adb shell input text` 會被裝置上的中文輸入法
（Gboard / Lenovo 手寫）攔截並轉成中文亂碼；就算停用輸入法，Compose 的捲動與
點擊也不一定每次都聽話。與其跟 UI 纏鬥，不如直接改資料檔 —— 這是確定性的做法。

## DataStore Preferences 的格式

檔案是 protobuf，結構等價於：

    message PreferenceMap { map<string, Value> preferences = 1; }
    message Value { oneof value { ... string string = 5; ... } }

map 在 wire format 上就是「field 1 = key、field 2 = value」的重複訊息：

    0x0A <entryLen>  0x0A <keyLen> <key>  0x12 <valLen>  0x2A <strLen> <字串>

所以改字串時必須同時更新三層長度。這支腳本會正確重算。

## 用法

    python tools/patch_datastore_url.py --device HA262RWW --url https://api.deepseek.com

改完之後必須**重新啟動 App**，DataStore 才會重新讀檔。
"""

import argparse
import subprocess
import sys

DEFAULT_ADB = r"B:\Android\Sdk\platform-tools\adb.exe"
DEFAULT_PKG = "com.dsh.overlaytranslator"
PB_REL = "files/datastore/overlay_translator_settings.preferences_pb"
TMP_DEV = "/data/local/tmp/dsh_prefs.pb"


def encode_varint(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        out.append(byte | (0x80 if value else 0))
        if not value:
            return bytes(out)


def decode_varint(buf: bytes, index: int):
    shift = 0
    value = 0
    while True:
        byte = buf[index]
        index += 1
        value |= (byte & 0x7F) << shift
        if not (byte & 0x80):
            return value, index
        shift += 7


def parse_entry(entry: bytes):
    """回傳 (key bytes, value-message bytes)。"""
    key = None
    value = None
    i = 0
    while i < len(entry):
        tag, i = decode_varint(entry, i)
        field, wire = tag >> 3, tag & 7
        if wire != 2:
            raise ValueError(f"未預期的 wire type {wire}")
        length, i = decode_varint(entry, i)
        chunk = entry[i:i + length]
        i += length
        if field == 1:
            key = chunk
        elif field == 2:
            value = chunk
    return key, value


def build_entry(key: bytes, value_message: bytes) -> bytes:
    return (b"\x0a" + encode_varint(len(key)) + key
            + b"\x12" + encode_varint(len(value_message)) + value_message)


def run(args, check=True):
    result = subprocess.run(args, capture_output=True)
    if check and result.returncode != 0:
        print("  命令失敗:", " ".join(args), file=sys.stderr)
        print("  stderr:", result.stderr.decode("utf-8", "replace"), file=sys.stderr)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--device", required=True)
    parser.add_argument("--adb", default=DEFAULT_ADB)
    parser.add_argument("--package", default=DEFAULT_PKG)
    parser.add_argument("--url", required=True)
    parser.add_argument("--key", default="base_url", help="要修改的欄位名稱")
    parser.add_argument("--local", default="artifacts/prefs.pb")
    args = parser.parse_args()

    new_value = args.url.encode("utf-8")

    print(f"[1] 停止 App（讓 DataStore 不再持有記憶體中的舊值）")
    run([args.adb, "-s", args.device, "shell", "am", "force-stop", args.package])

    print(f"[2] 從裝置取出設定檔")
    run([args.adb, "-s", args.device, "shell",
         f"run-as {args.package} cat {PB_REL} > {TMP_DEV}"])
    run([args.adb, "-s", args.device, "pull", TMP_DEV, args.local])

    with open(args.local, "rb") as handle:
        data = handle.read()
    print(f"    讀到 {len(data)} bytes")

    print(f"[3] 修改 {args.key} → {args.url}")
    out = bytearray()
    index = 0
    patched = 0
    while index < len(data):
        tag, index = decode_varint(data, index)
        field, wire = tag >> 3, tag & 7
        if wire != 2 or field != 1:
            raise ValueError(f"最外層結構不符預期（field={field} wire={wire}）")
        length, index = decode_varint(data, index)
        entry = data[index:index + length]
        index += length

        key, value = parse_entry(entry)
        if key is not None and key.decode("utf-8", "replace") == args.key:
            old = b""
            if value:
                try:
                    str_len, j = decode_varint(value, 0)
                    old = value[j:j + str_len]
                except Exception:
                    pass
            value = b"\x2a" + encode_varint(len(new_value)) + new_value
            entry = build_entry(key, value)
            patched += 1
            print(f"    舊值 = {old.decode('utf-8', 'replace')!r}")

        out += b"\x0a" + encode_varint(len(entry)) + entry

    if patched == 0:
        print(f"  ❌ 找不到欄位 {args.key}", file=sys.stderr)
        sys.exit(1)

    with open(args.local, "wb") as handle:
        handle.write(bytes(out))
    print(f"    已更新 {patched} 個欄位，新檔 {len(out)} bytes")

    print("[4] 寫回裝置")
    run([args.adb, "-s", args.device, "push", args.local, TMP_DEV])
    run([args.adb, "-s", args.device, "shell",
         f"run-as {args.package} sh -c 'cat {TMP_DEV} > {PB_REL}'"])

    print("[5] 驗證")
    verify = run([args.adb, "-s", args.device, "shell",
                  f"run-as {args.package} cat {PB_REL}"], check=False)
    print(f"    裝置上的檔案大小 = {len(verify.stdout)} bytes")

    print("[6] 啟動 App（讓它重新讀取設定）")
    run([args.adb, "-s", args.device, "shell", "am", "start", "--user", "0",
         "-n", f"{args.package}/.ui.MainActivity"])
    print("完成。")


if __name__ == "__main__":
    main()
