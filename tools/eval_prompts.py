#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""翻譯品質回歸測試（eval harness）。

## 兩種模式

- `--check`：**離線**驗證提示詞契約。不需要 API key，CI 的每個 PR 都會跑。
  它抓的是「提示詞被改壞」這類問題：語氣規則不見了、目標語言沒帶進去、
  診斷的三段格式不見了、上下文沒被塞進去、提示詞突然暴增……
- `--live`：真的呼叫模型，驗證**輸出**契約：是不是只輸出譯文、有沒有翻成目標語言、
  診斷有沒有三段、有沒有把原文原封不動吐回來。需要 API key。

## 提示詞從哪來

由 `PromptSnapshotTest`（Kotlin）用 App 真正的 `DeepSeekEngine.systemPrompt()` 產生，
輸出到 `app/build/eval/prompts.json`。**不是另外抄一份** —— 抄一份一定會跟 App 漂移。

## 用法

    ./gradlew :app:testDebugUnitTest --tests com.dsh.overlaytranslator.PromptSnapshotTest
    python3 tools/eval_prompts.py --check

    OPENAI_API_KEY=... python3 tools/eval_prompts.py --live \\
        --base-url https://api.deepseek.com --model deepseek-flash

離開碼：0 = 全部通過，1 = 有違規（CI 會紅）。
"""

from __future__ import annotations

import argparse
import difflib
import json
import os
import re
import sys
import urllib.error
import urllib.request

DEFAULT_PROMPTS = os.path.join("app", "build", "eval", "prompts.json")
MAX_PROMPT_CHARS = 24_000
MAX_SYSTEM_CHARS = 8_000

HAN = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]")
LATIN = re.compile(r"[A-Za-z]")

TONES = ("熟人閒聊", "普通閒聊", "正式禮貌")

# 這些字串出現在提示詞裡通常代表有人忘了替換佔位符
PLACEHOLDERS = ("$myLanguage", "$target", "%s", "{language}", "TODO", "FIXME")


def load(path: str) -> list[dict]:
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    cases = data.get("cases") or []
    if not cases:
        raise SystemExit(f"[eval] {path} 裡沒有任何案例")
    return cases


# ───────────────────────────── 離線契約 ─────────────────────────────


def check_case(case: dict) -> list[str]:
    problems: list[str] = []
    cid = case.get("id", "?")
    system = case.get("system") or ""
    user = case.get("user") or ""
    direction = case.get("direction", "")
    tone = case.get("tone", "")
    my_language = case.get("myLanguage", "")

    if not system.strip():
        problems.append(f"{cid}: system prompt 是空的")
    if not user.strip():
        problems.append(f"{cid}: user 內容是空的")
    if len(system) + len(user) > MAX_PROMPT_CHARS:
        problems.append(f"{cid}: 提示詞過大（{len(system) + len(user)} 字）")
    if len(system) > MAX_SYSTEM_CHARS:
        problems.append(f"{cid}: system prompt 異常長（{len(system)} 字）")

    # 語氣在三個方向裡的角色不一樣（這也是實際的產品設計）：
    #   對方訊息→我的語言：**不套用關係語氣**，只要能忠实保留對方原本的語氣
    #   我的話→對方語言：套用關係語氣（registerRule 的原文）
    #   診斷：以「與對方的關係」為判斷標準（提示詞裡會出現語氣名稱）
    tone_rule = case.get("toneRule") or ""
    if "MY_TEXT_TO_THEM" in direction:
        if tone_rule and tone_rule not in system:
            problems.append(f"{cid}: 翻譯我的訊息時沒有套用語氣「{tone}」的規則")
    elif "DIAGNOSE" in direction:
        if tone and tone not in system:
            problems.append(f"{cid}: 診斷提示詞沒有把「與對方的關係＝{tone}」帶進去")
    else:
        if "保留原本的語氣" not in system:
            problems.append(f"{cid}: 翻譯對方訊息時缺少「保留原本語氣」這條規則")
        if tone_rule and tone_rule in system:
            problems.append(f"{cid}: 翻譯對方訊息時不該套用關係語氣規則")

    # 語言：翻譯成本端看方向 —— 對方訊息→我的語言；我要說的話→對方語言；診斷→我的語言
    if "MY_TEXT_TO_THEM" in direction:
        wanted, label = case.get("theirLanguage", ""), "對方語言"
    else:
        wanted, label = my_language, "我的語言"
    if wanted and wanted not in system:
        problems.append(f"{cid}: system prompt 沒有提到{label}「{wanted}」")

    is_diagnose = "DIAGNOSE" in direction
    if is_diagnose:
        for marker in ("【診斷】", "【建議】", "【建議翻譯】"):
            if marker not in system:
                problems.append(f"{cid}: 診斷提示詞缺少 {marker}")
        if "【待翻譯】" in user:
            problems.append(f"{cid}: 診斷案例用了翻譯任務的 user 格式")
    else:
        if "只輸出譯文" not in system:
            problems.append(f"{cid}: 翻譯提示詞沒有「只輸出譯文」這條規則")
        if "【待翻譯】" not in user:
            problems.append(f"{cid}: user 內容缺少【待翻譯】區塊")

    # 佔位符沒替換
    for placeholder in PLACEHOLDERS:
        if placeholder in system or placeholder in user:
            problems.append(f"{cid}: 提示詞裡還有未替換的佔位符 {placeholder}")

    return problems


def check_context(cases: list[dict]) -> list[str]:
    """有附上下文的案例，user 內容必須真的有那一段。"""
    problems = []
    with_context = [c for c in cases if "【對話上下文】" in (c.get("user") or "")]
    if not with_context:
        problems.append("沒有任何案例測到「對話上下文」—— 這條路徑等於沒被驗證")
    for case in with_context:
        for line in re.findall(r"對方：(.+)", case["user"]):
            if not line.strip():
                problems.append(f"{case['id']}: 上下文有空行")
    return problems


def run_check(path: str) -> int:
    cases = load(path)
    problems: list[str] = []
    if len(cases) < 6:
        problems.append(f"案例太少（{len(cases)} 個），至少 6 個才有意義")
    for case in cases:
        problems.extend(check_case(case))
    problems.extend(check_context(cases))

    print(f"[eval] 離線契約檢查：{len(cases)} 個案例")
    for case in cases:
        print("  %-24s %-18s %-22s system=%4d user=%3d" % (
            case.get("id"), case.get("tone"),
            case.get("direction", "").split(".")[-1],
            len(case.get("system") or ""), len(case.get("user") or ""),
        ))

    if problems:
        print("\n[eval] ✗ 有 %d 個問題：" % len(problems))
        for problem in problems:
            print("   -", problem)
        return 1

    print("\n[eval] ✓ 全部通過（提示詞契約沒有退化）")
    return 0


# ───────────────────────────── 實際呼叫模型 ─────────────────────────────


def call_model(base_url: str, api_key: str, model: str, system: str, user: str,
               timeout: int = 90) -> str:
    payload = {
        "model": model,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
        "temperature": 0.2,
        "stream": False,
    }
    request = urllib.request.Request(
        base_url.rstrip("/") + "/chat/completions",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = json.loads(response.read().decode("utf-8"))
    return (body["choices"][0]["message"]["content"] or "").strip()


def check_output(case: dict, output: str) -> list[str]:
    problems = []
    cid = case.get("id", "?")
    text = case.get("text") if "text" in case else None
    direction = case.get("direction", "")
    expect = case.get("expectScript", "han")

    if not output:
        return [f"{cid}: 模型回傳空字串"]
    if "【待翻譯】" in output or "【要檢查的訊息】" in output:
        problems.append(f"{cid}: 輸出把任務標記也吐出來了")
    if expect == "han" and not HAN.search(output):
        problems.append(f"{cid}: 期待中文輸出，但沒有任何漢字")
    if expect == "latin":
        if HAN.search(output):
            problems.append(f"{cid}: 期待拉丁字母輸出，卻出現漢字")
        if not LATIN.search(output):
            problems.append(f"{cid}: 期待拉丁字母輸出，卻沒有拉丁字母")

    if "DIAGNOSE" in direction:
        for marker in ("【診斷】", "【建議】", "【建議翻譯】"):
            if marker not in output:
                problems.append(f"{cid}: 診斷輸出缺少 {marker}")
    elif len(output) > 400:
        problems.append(f"{cid}: 翻譯輸出過長（{len(output)} 字），可能夾帶解釋")

    return problems


def run_live(path: str, base_url: str, api_key: str, model: str,
             baseline: str | None, record: str | None) -> int:
    cases = load(path)
    old: dict[str, str] = {}
    if baseline and os.path.exists(baseline):
        with open(baseline, encoding="utf-8") as handle:
            old = json.load(handle).get("outputs", {})

    problems: list[str] = []
    outputs: dict[str, str] = {}
    drifts: list[str] = []

    print(f"[eval] 實測 {len(cases)} 個案例（model={model}, endpoint={base_url}）")
    for case in cases:
        cid = case["id"]
        try:
            output = call_model(base_url, api_key, model, case["system"], case["user"])
        except (urllib.error.URLError, KeyError, TimeoutError) as error:
            problems.append(f"{cid}: 呼叫失敗 {error}")
            print("  %-24s ✗ %s" % (cid, error))
            continue
        outputs[cid] = output
        case_problems = check_output(case, output)
        problems.extend(case_problems)

        drift = ""
        if cid in old and old[cid]:
            ratio = difflib.SequenceMatcher(None, old[cid], output).ratio()
            if ratio < 0.55:
                drift = f"  ⚠ 與基準差異 {(1 - ratio) * 100:.0f}%"
                drifts.append(cid)
        status = "✓" if not case_problems else "✗"
        preview = output.replace("\n", " ")[:60]
        print("  %-24s %s %s%s" % (cid, status, preview, drift))

    if record:
        with open(record, "w", encoding="utf-8") as handle:
            json.dump({"outputs": outputs}, handle, ensure_ascii=False, indent=2)
        print(f"\n[eval] 已寫入基準檔 {record}")

    if drifts:
        print("\n[eval] 注意：%d 個案例的輸出與基準差異很大（不是失敗，但值得看一眼）：%s"
              % (len(drifts), ", ".join(drifts)))
    if problems:
        print("\n[eval] ✗ 有 %d 個輸出違規：" % len(problems))
        for problem in problems:
            print("   -", problem)
        return 1
    print("\n[eval] ✓ 輸出契約全部通過")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="翻譯品質回歸測試")
    parser.add_argument("--prompts", default=DEFAULT_PROMPTS, help="prompts.json 路徑")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="離線驗證提示詞契約")
    mode.add_argument("--live", action="store_true", help="實際呼叫模型驗證輸出")
    parser.add_argument("--base-url", default=os.environ.get("EVAL_BASE_URL", "https://api.deepseek.com"))
    parser.add_argument("--model", default=os.environ.get("EVAL_MODEL", "deepseek-flash"))
    parser.add_argument("--api-key", default=os.environ.get("EVAL_API_KEY") or os.environ.get("OPENAI_API_KEY"))
    parser.add_argument("--baseline", default=None, help="要比對的基準檔")
    parser.add_argument("--record", default=None, help="把這次輸出寫成基準檔")
    args = parser.parse_args()

    if not os.path.exists(args.prompts):
        print(f"[eval] 找不到 {args.prompts}\n"
              "        先跑：./gradlew :app:testDebugUnitTest --tests "
              "com.dsh.overlaytranslator.PromptSnapshotTest", file=sys.stderr)
        return 1

    if args.check:
        return run_check(args.prompts)

    if not args.api_key:
        print("[eval] --live 需要 API key（--api-key 或環境變數 EVAL_API_KEY）", file=sys.stderr)
        return 1
    return run_live(args.prompts, args.base_url, args.api_key, args.model,
                    args.baseline, args.record)


if __name__ == "__main__":
    sys.exit(main())
