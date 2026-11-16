#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 DeepSeek 官方导出格式（export_format_version = 3）的超大压力测试归档。

产物结构与真实导出逐字同构（见 app/src/main/java/com/simplechat/app/data/import/DeepSeekHistory.kt）：

    manifest.json                                     {"export_format_version": 3}
    chat_session_index.json                           [{id, title, pinned, message_count, ...}, ...]
    chat_sessions/{session_id}.json                   会话（含 messages 树）
    chat_sessions/{session_id}_files/{file_id}_{name} 图片原件

覆盖的测试元素：

* 单窗口超长上下文      一条会话几百条消息、十几万字节的回复
* 超多窗口              几千条小会话
* 超多长上下文窗口      上百条 MB 级会话
* 单窗口超多图片        一条会话里几百张图
* 单消息巨长            一条消息十几 MB
* 单消息单行            一条几 MB、没有换行的正文
* 分支 / 变体           同父多回复、current_message_id 指向中间节点
* 状态 / 思考 / 模板     FINISHED / INCOMPLETE / FAILED / CONTENT_FILTER、THINK、TEMPLATE_RESPONSE
* Unicode 边界           emoji、RTL、组合字符、罕见汉字、制表符
* Markdown / 代码        表格、嵌套列表、代码围栏、LaTeX、HTML

体积控制：目标体积按**压缩后**的 ZIP 字节算。图片是不可压缩字节，承担大部分体积；
最后再用一张精确到字节的填充图片把总大小钉到目标值。

用法：
    python tools/gen_deepseek_stress.py --out build/stress/deepseek_data.zip
    python tools/gen_deepseek_stress.py --out out.zip --scale 0.02   # 冒烟测试
"""

from __future__ import annotations

import argparse
import io
import json
import math
import os
import random
import re
import struct
import sys
import time
import zipfile
import zlib
from dataclasses import dataclass, field
from pathlib import Path

from PIL import Image

GIB = 1024 ** 3
MIB = 1024 ** 2


# ══════════════════════════════════════════════════════════════
#  文本工厂：真的"像人写的"长文，而不是 repeat("长", n)
# ══════════════════════════════════════════════════════════════

TECH = [
    "索引", "缓存", "事务", "事件循环", "序列化", "连接池", "调度器", "虚拟线程",
    "协程", "垃圾回收", "页缓存", "写放大", "锁竞争", "背压", "限流", "快照隔离",
    "预写日志", "内存屏障", "向量化执行", "列式存储", "倒排索引", "布隆过滤器",
    "一致性哈希", "LSM 树", "B+ 树", "零拷贝", "冷启动", "热更新", "灰度发布",
    "可观测性", "熔断器", "幂等键", "分片", "副本", "共识算法", "增量快照",
    "流式解析", "二分查找", "拓扑排序", "引用计数", "写时复制", "对象池",
]
TECH2 = [
    "尾延迟", "吞吐", "内存占用", "重试风暴", "线程饥饿", "长尾请求", "回压信号",
    "GC 停顿", "磁盘抖动", "网络分区", "缓存击穿", "脏读", "写偏斜", "锁粒度",
    "热点 key", "时钟漂移", "重复消费", "消息堆积", "雪崩", "冷数据",
]
SOFT = ["其实", "不过", "换句话说", "更准确地说", "从结果看", "我个人倾向于",
        "有点意外的是", "换个角度看", "说白一点", "退一步说"]
NAMES = ["小林", "阿澈", "Nora", "老周", "Mika", "青禾", "阿岚", "Yuki", "陈序", "苏禾"]
PROJS = ["simple-chat", "vector-store", "gateway", "importd", "animus", "chat-archive",
         "kv-bench", "logpipe", "tinyraft", "ui-kit"]
LANGS = ["Python", "Kotlin", "TypeScript", "Rust", "Go", "Java", "C++", "SQL"]

CN_TEMPLATES = [
    "先把{w}拆成可以单独验证的几块，再回头看{w2}是否还站得住。",
    "如果只看吞吐，{w}的提升很明显；但把尾延迟拉出来，问题就换了一个位置。",
    "这一段我改了三遍：第一版直接上{w}，第二版退回最笨的循环，第三版才把{w2}补上。",
    "{soft}，{proj} 里真正难的不是{w}，而是让{w2}在异常路径上也不出错。",
    "线上那台机器的{w}一直是满的，排查到最后发现是{w2}把请求串成了串行。",
    "把{n}条记录放进{w}之后，{w2}反而下降了约{n}%，因为少了一次全量扫描。",
    "我不太喜欢在热路径里做{w}，那等于把{w2}的成本摊到每一次调用上。",
    "这里有个坑：{w}看着是常数时间，实际在扩容的时候会触发一次全量{w2}。",
    "如果一定要在两者之间选，我会保留{w}，把{w2}挪到后台线程，代价是复杂度。",
    "写测试的时候特意构造了 n={n} 的输入，{w}的行为与文档里写的不太一样。",
    "refactor 到第三轮才发现，{w}和{w2}其实是同一个问题的两面。",
    "把日志级别调到 debug 之后，{w2}的抖动就藏不住了：每个周期都有一次 {n}ms 的毛刺。",
    "这个设计的好处是简单，坏处是{soft}会把{w}的复杂度转移到{w2}上。",
    "{name}提了一个很尖锐的问题：如果{w}失败了，{w2}靠什么恢复？",
    "我先给结论：{w}不是瓶颈，{w2}才是；把采样打开就能看到那条长尾。",
    "把这部分抽成接口之后，{w}可以替换成任意实现，{w2}也就不必绑死。",
    "这段代码在 {lang} 里只用了 {n} 行，但注释里把{w}的边界条件写了整整一屏。",
    "讨论到一半大家都沉默了，因为谁也说不清{w}在极端输入下会不会退化成{w2}。",
    "从我自己的经验看，{w}的问题往往不是性能，而是它让{w2}变得不可观测。",
    "改完之后重跑了 {n} 次，{w}的耗时稳定在 {n}ms 上下，尾部的毛刺没了。",
    "我倾向于先不做{w}，等{w2}真的成为瓶颈再动；提前优化只会带来更多状态。",
    "{proj} 的这次事故报告我看了两遍，根因是{w}与{w2}的假设不一致。",
    "如果{w}是幂等的，重试就只是浪费；可惜它现在不是，{w2}必须自己兜。",
    "把 {n} 个会话导入进来之后，{w}的内存曲线是阶梯状的，说明{w2}没有及时释放。",
    "顺手把{w}的注释补全了：原来那句'临时方案'已经存在了 {n} 个月。",
    "这个接口的语义我一直记不住，每次都要翻文档：{w}在失败时会回滚，{w2}不会。",
    "上线前我最担心的是{w}，结果真正出问题的是{w2}，而且只在低端机上复现。",
    "把 batch 调到 {n} 之后，{w}的吞吐上去了，{w2}却开始周期性超时。",
    "我们最后没有引入新的依赖，而是用最朴素的{w}换掉了原来的{w2}。",
    "写到这里我意识到，{w}其实是{w2}的一个特例，只是名字起得不一样。",
    "会话越多，{w}越容易被忽略；等到用户反馈'打不开'，{w2}已经积累了几百兆。",
    "这个问题不是{w}本身，而是{proj}在 {n} 个地方各自复制了一份{w2}的逻辑。",
    "如果下次还做类似的功能，我会先把{w}的观测埋好，再谈{w2}的优化。",
    "把 {name} 的补丁合进去之后，{w}的失败率从 {n}‰ 降到了个位数。",
    "我试着把{w}换成流式实现，{w2}的内存峰值立刻从 {n}MB 降到了几十兆。",
]
EN_TEMPLATES = [
    "The interesting part is not the {w} itself but how {w2} behaves when the input is malformed.",
    "I rewrote the {w} three times; the last version finally stopped allocating inside the hot loop.",
    "We measured {n} samples and the tail latency of {w} was consistently worse than {w2}.",
    "There is a subtle invariant here: {w} must never observe a partially applied {w2}.",
    "Honestly, {w} looks fine until you push {n} concurrent sessions through it.",
    "The fix was boring: batch the writes, then let {w2} drain in the background.",
    "If {w} is idempotent, retries are free; otherwise {w2} has to keep its own ledger.",
    "This is the kind of bug that only shows up after {n} imports on a low-end device.",
    "I would rather keep {w} dumb and put all the policy into {w2}.",
    "Note that {w} is O(1) amortized, but a resize can still trigger a full {w2}.",
]
NARRATIVE = [
    "窗外的雨把玻璃敲成一片模糊的白噪音，{name}把杯子推到桌角，又推回来。",
    "屏幕的光落在{name}的脸上，那段没有发出去的话在输入框里闪了很久。",
    "走廊尽头的灯忽明忽暗，{name}数着自己的脚步，数到第{n}级台阶时停了下来。",
    "\"你确定要这么做吗？\"{name}的声音很轻，像是怕把什么惊醒。",
    "空气里有一股旧纸和冷咖啡混在一起的味道，让人想起很多年前的那个下午。",
    "他把外套搭在椅背上，坐下来，像是对接下来要说的话做了很久的准备。",
    "远处传来列车进站的声音，{name}抬头看了一眼钟，又低下头继续写。",
]
EMOJI = ["🌧️", "🧪", "🧩", "🛠️", "🔥", "🌊", "🪵", "🎛️", "📦", "🗂️", "🧠", "⚙️"]
ODD = ["𠮷", "𰻞", "ﷺ", "e\u0301", "한", "العربية", "עברית", "ᚠᚢᚦ", "⿰木木", "\u200b", "\ufeff"]


class TextFactory:
    """按字节数产出内容：段落 / 代码 / 表格 / 列表 / 公式，混合编码与标点。"""

    def __init__(self, rng: random.Random):
        self.rng = rng
        self._slot = re.compile(r"\{(w2|w|n|name|proj|soft|lang)\}")

    # ── 基本片段 ───────────────────────────────────────────
    def _fill(self, template: str) -> str:
        rng = self.rng
        values = {
            "w": rng.choice(TECH),
            "w2": rng.choice(TECH2),
            "n": str(rng.choice([2, 3, 5, 8, 12, 17, 24, 37, 64, 128, 256, 512, 1024])),
            "name": rng.choice(NAMES),
            "proj": rng.choice(PROJS),
            "soft": rng.choice(SOFT),
            "lang": rng.choice(LANGS),
        }
        return self._slot.sub(lambda m: values[m.group(1)], template)

    def paragraph(self, sentences: int | None = None) -> str:
        rng = self.rng
        count = sentences if sentences is not None else rng.randint(3, 9)
        parts = []
        for _ in range(count):
            roll = rng.random()
            if roll < 0.72:
                parts.append(self._fill(rng.choice(CN_TEMPLATES)))
            elif roll < 0.9:
                parts.append(self._fill(rng.choice(EN_TEMPLATES)))
            else:
                parts.append(self._fill(rng.choice(NARRATIVE)))
        return "".join(parts) + "\n\n"

    def _ident(self) -> str:
        return self.rng.choice(
            ["alpha", "beta", "gamma", "delta", "omega", "pipe", "stage", "batch",
             "cursor", "digest", "shard", "frame", "token", "anchor", "spool"],
        ) + "_" + str(self.rng.randint(1, 999))

    def code_block(self) -> str:
        rng = self.rng
        lang = rng.choice(["python", "kotlin", "typescript", "rust", "sql", "bash", "go", "json"])
        n = rng.randint(8, 40)
        a, b = self._ident(), self._ident()
        num = rng.randint(2, 512)
        if lang == "python":
            body = (
                f"def {a}(items, limit={num}):\n"
                f"    \"\"\"把 items 压成一张索引表，供 {b} 使用。\"\"\"\n"
                f"    seen = {{}}\n"
                f"    for item in items:\n"
                f"        key = item.get(\"{b}\")\n"
                f"        if key is None:\n"
                f"            continue\n"
                f"        seen[key] = seen.get(key, 0) + 1\n"
                f"    ordered = sorted(seen.items(), key=lambda pair: pair[1], reverse=True)\n"
                f"    return ordered[:limit]\n"
            )
        elif lang == "kotlin":
            body = (
                f"fun {a}(items: List<Item>, limit: Int = {num}): List<Pair<String, Int>> {{\n"
                f"    val seen = LinkedHashMap<String, Int>()\n"
                f"    for (item in items) {{\n"
                f"        val key = item.{b} ?: continue\n"
                f"        seen[key] = (seen[key] ?: 0) + 1\n"
                f"    }}\n"
                f"    return seen.entries.sortedByDescending {{ it.value }}.take(limit)\n"
                f"        .map {{ it.key to it.value }}\n"
                f"}}\n"
            )
        elif lang == "typescript":
            body = (
                f"export function {a}(items: Item[], limit = {num}) {{\n"
                f"  const seen = new Map<string, number>();\n"
                f"  for (const item of items) {{\n"
                f"    const key = item.{b};\n"
                f"    if (!key) continue;\n"
                f"    seen.set(key, (seen.get(key) ?? 0) + 1);\n"
                f"  }}\n"
                f"  return [...seen.entries()].sort((x, y) => y[1] - x[1]).slice(0, limit);\n"
                f"}}\n"
            )
        elif lang == "rust":
            body = (
                f"fn {a}(items: &[Item], limit: usize) -> Vec<(String, usize)> {{\n"
                f"    let mut seen: HashMap<String, usize> = HashMap::new();\n"
                f"    for item in items {{\n"
                f"        if let Some(key) = item.{b}.as_ref() {{\n"
                f"            *seen.entry(key.clone()).or_insert(0) += 1;\n"
                f"        }}\n"
                f"    }}\n"
                f"    let mut out: Vec<_> = seen.into_iter().collect();\n"
                f"    out.sort_by(|l, r| r.1.cmp(&l.1));\n"
                f"    out.truncate(limit.min({num}));\n"
                f"    out\n"
                f"}}\n"
            )
        elif lang == "sql":
            body = (
                f"-- {a}: 按 {b} 汇总，只取前 {num} 条\n"
                f"SELECT {b}, COUNT(*) AS hits\n"
                f"FROM messages\n"
                f"WHERE conversation_id = ?\n"
                f"GROUP BY {b}\n"
                f"ORDER BY hits DESC\n"
                f"LIMIT {num};\n"
            )
        elif lang == "bash":
            body = (
                f"#!/usr/bin/env bash\n"
                f"set -euo pipefail\n"
                f"# {a}: 把 {b} 的产物搬到临时目录\n"
                f"root=\"${{1:-/tmp/{b}}}\"\n"
                f"for f in \"$root\"/*.json; do\n"
                f"  [ -f \"$f\" ] || continue\n"
                f"  jq -c '.[] | {{id, title}}' \"$f\" >> \"$root/{a}.ndjson\"\n"
                f"done\n"
                f"wc -l \"$root/{a}.ndjson\" | awk '{{print $1}}' | head -n {num}\n"
            )
        elif lang == "go":
            body = (
                f"func {a}(items []Item, limit int) []Pair {{\n"
                f"\tseen := map[string]int{{}}\n"
                f"\tfor _, item := range items {{\n"
                f"\t\tkey := item.{b}\n"
                f"\t\tif key == \"\" {{\n"
                f"\t\t\tcontinue\n"
                f"\t\t}}\n"
                f"\t\tseen[key]++\n"
                f"\t}}\n"
                f"\tout := make([]Pair, 0, len(seen))\n"
                f"\tfor k, v := range seen {{\n"
                f"\t\tout = append(out, Pair{{k, v}})\n"
                f"\t}}\n"
                f"\tsort.Slice(out, func(i, j int) bool {{ return out[i].N > out[j].N }})\n"
                f"\tif len(out) > limit {{ out = out[:limit] }}\n"
                f"\treturn out\n"
                f"}}\n"
            )
        else:
            body = json.dumps(
                {
                    "id": a,
                    "source": b,
                    "limit": num,
                    "steps": [
                        {"name": s, "timeout_ms": rng.randint(10, 4000), "retries": rng.randint(0, 5)}
                        for s in [self._ident() for _ in range(rng.randint(2, 6))]
                    ],
                },
                ensure_ascii=False,
                indent=2,
            ) + "\n"
        if n > 18:
            body += "\n" + f"# 第 {rng.randint(2, 9)} 段：{self.paragraph(2)}"
        return f"```{lang}\n{body}```\n\n"

    def table(self) -> str:
        rng = self.rng
        cols = rng.randint(3, 6)
        rows = rng.randint(4, 14)
        header = "| " + " | ".join(f"字段{i + 1}" for i in range(cols)) + " |"
        sep = "| " + " | ".join("---" for _ in range(cols)) + " |"
        lines = [header, sep]
        for _ in range(rows):
            cells = []
            for _ in range(cols):
                roll = rng.random()
                if roll < 0.35:
                    cells.append(str(rng.randint(0, 99999)))
                elif roll < 0.7:
                    cells.append(rng.choice(TECH))
                else:
                    cells.append(rng.choice(["OK", "FAILED", "PARTIAL", "—", "next"]))
            lines.append("| " + " | ".join(cells) + " |")
        return "\n".join(lines) + "\n\n"

    def listing(self) -> str:
        rng = self.rng
        lines = []
        for i in range(rng.randint(4, 12)):
            indent = "  " * rng.randint(0, 2)
            marker = rng.choice(["-", "*", f"{i + 1}.", "- [ ]", "- [x]"])
            lines.append(f"{indent}{marker} {self._fill(rng.choice(CN_TEMPLATES))[:rng.randint(20, 90)]}")
        return "\n".join(lines) + "\n\n"

    def math(self) -> str:
        rng = self.rng
        a, b, n = rng.randint(1, 9), rng.randint(1, 9), rng.randint(2, 64)
        return (
            f"$$\nT(n) = {a}T\\left(\\frac{{n}}{{{b}}}\\right) + \\Theta(n\\log n),\\quad n \\ge {n}\n$$\n\n"
        )

    def _piece(self, style: str) -> str:
        rng = self.rng
        if style == "prompt":
            roll = rng.random()
            if roll < 0.55:
                return self._fill(rng.choice(CN_TEMPLATES))[: rng.randint(40, 300)] + "\n\n"
            if roll < 0.75:
                return "请帮我看看这段：\n\n" + self.code_block()
            return self.listing()

        roll = rng.random()
        if style == "code":
            return self.code_block()
        if style == "markdown":
            pick = rng.random()
            if pick < 0.3:
                return self.table()
            if pick < 0.5:
                return self.listing()
            if pick < 0.6:
                return self.math()
            if pick < 0.75:
                return "> " + self._fill(rng.choice(CN_TEMPLATES)) + "\n\n"
            return self.paragraph()
        if roll < 0.5:
            return self.paragraph()
        if roll < 0.68:
            return self.code_block()
        if roll < 0.78:
            return self.table()
        if roll < 0.86:
            return self.listing()
        if roll < 0.92:
            return self.math()
        if roll < 0.96:
            return "> " + self._fill(rng.choice(CN_TEMPLATES)) + "\n\n"
        return self.paragraph()

    # ── 定长产出 ───────────────────────────────────────────
    def blob(self, target_bytes: int, style: str = "mixed") -> str:
        target_bytes = max(32, int(target_bytes))
        parts: list[str] = []
        size = 0
        while size < target_bytes:
            piece = self._piece(style)
            parts.append(piece)
            size += len(piece.encode("utf-8"))
        text = "".join(parts)
        if size > target_bytes:
            text = text.encode("utf-8")[:target_bytes].decode("utf-8", "ignore")
        return text

    def user_text(self, target_bytes: int) -> str:
        return self.blob(target_bytes, "prompt").strip() or "继续。"

    def title(self) -> str:
        rng = self.rng
        head = rng.choice([
            "关于{}", "把{}重写一遍", "{}到底慢在哪", "{}的三次尝试", "{}翻车记录",
            "聊聊{}", "{}与{}", "被{}坑了一天", "{}方案对比", "给{}补测试",
        ])
        if head.count("{}") == 2:
            return head.format(rng.choice(TECH), rng.choice(TECH2))
        return head.format(rng.choice(TECH))


def iso(epoch: float) -> str:
    whole = int(epoch)
    millis = int(round((epoch - whole) * 1000))
    if millis >= 1000:
        whole += 1
        millis -= 1000
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime(whole)) + f".{millis:03d}Z"


# ══════════════════════════════════════════════════════════════
#  图片工厂：真实尺寸的真实 JPEG / PNG，按目标字节精确补齐
# ══════════════════════════════════════════════════════════════

def _jpeg_sos_offset(data: bytes) -> int | None:
    i, n = 2, len(data)
    while i + 3 < n:
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if marker == 0xDA:
            return i
        if marker in (0xD8, 0xD9, 0x01) or 0xD0 <= marker <= 0xD7:
            i += 2
        elif marker == 0xFF:
            i += 1
        else:
            length = (data[i + 2] << 8) | data[i + 3]
            if length < 2:
                return None
            i += 2 + length
    return None


def _split_even(total: int, parts: int, cap: int) -> list[int]:
    assert parts >= 1 and total <= parts * cap
    base, extra = divmod(total, parts)
    return [base + (1 if i < extra else 0) for i in range(parts)]


def _png_chunk(kind: bytes, payload: bytes) -> bytes:
    return (
        struct.pack(">I", len(payload))
        + kind
        + payload
        + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
    )


class ImageFactory:
    """生成"看起来正常"的照片：真实像素尺寸 + 随机内容，再用合法填充补到精确字节数。

    JPEG 的填充用 COM 段，插在 SOS 之前 —— 这样 SOF 仍在文件开头几十字节内，
    导入器只读前 64KB 就能拿到宽高（见 ImageSize.kt）。PNG 用自定义 ancillary chunk。
    """

    def __init__(self, rng: random.Random):
        self.rng = rng
        self._cache: dict[tuple[str, int, int], bytes] = {}
        self.dims: tuple[int, int] = (0, 0)

    def _dims_for(self, size: int) -> tuple[int, int]:
        rng = self.rng
        edge = 640
        for threshold, candidate in (
            (220_000, 640), (700_000, 960), (1_800_000, 1280), (3_500_000, 1600),
            (7_000_000, 2048), (13_000_000, 2560), (10 ** 12, 3072),
        ):
            if size <= threshold:
                edge = candidate
                break
        aspect = rng.choice([(4, 3), (3, 4), (16, 9), (9, 16), (1, 1), (3, 2), (2, 3)])
        if aspect[0] >= aspect[1]:
            w = edge
            h = max(16, int(edge * aspect[1] / aspect[0]))
        else:
            h = edge
            w = max(16, int(edge * aspect[0] / aspect[1]))
        return w, h

    def _base(self, fmt: str, w: int, h: int) -> bytes:
        key = (fmt, w, h)
        cached = self._cache.get(key)
        if cached is not None:
            return cached
        raw = self.rng.randbytes(w * h * 3)
        image = Image.frombytes("RGB", (w, h), raw)
        out = io.BytesIO()
        if fmt == "jpeg":
            image.save(out, "JPEG", quality=85, subsampling=2)
        else:
            image.save(out, "PNG", compress_level=1)
        data = out.getvalue()
        if len(self._cache) < 96:
            self._cache[key] = data
        return data

    def make(self, size: int, fmt: str | None = None) -> tuple[bytes, int, int]:
        rng = self.rng
        size = max(4096, int(size))
        if fmt is None:
            fmt = "png" if (size < 600_000 and rng.random() < 0.12) else "jpeg"
        w, h = self._dims_for(size)
        base = self._base(fmt, w, h)
        guard = 0
        while len(base) + 256 > size and w > 16 and h > 16 and guard < 12:
            w = max(16, int(w * 0.75))
            h = max(16, int(h * 0.75))
            base = self._base(fmt, w, h)
            guard += 1
        if len(base) + 256 > size:
            # 目标比最小底图还小：退回 1x1 的纯色，保证仍然合法
            image = Image.new("RGB", (2, 2), (rng.randint(0, 255), rng.randint(0, 255), 60))
            out = io.BytesIO()
            image.save(out, "JPEG" if fmt == "jpeg" else "PNG")
            base = out.getvalue()
            w, h = 2, 2
        self.dims = (w, h)
        if fmt == "jpeg":
            return self._pad_jpeg(base, size), w, h
        return self._pad_png(base, size), w, h

    def _pad_jpeg(self, base: bytes, target: int) -> bytes:
        need = target - len(base)
        if need <= 0:
            return base
        k = max(1, math.ceil(need / 65537))
        while need - 4 * k > 65533 * k:
            k += 1
        payload = need - 4 * k
        while payload < 0 and k > 1:
            k -= 1
            payload = need - 4 * k
        if payload < 0:
            return base
        sizes = _split_even(payload, k, 65533)
        filler = b"".join(
            b"\xff\xfe" + struct.pack(">H", len(chunk) + 2) + chunk
            for chunk in (self.rng.randbytes(s) for s in sizes)
        )
        offset = _jpeg_sos_offset(base)
        if offset is None:
            return base
        return base[:offset] + filler + base[offset:]

    def _pad_png(self, base: bytes, target: int) -> bytes:
        need = target - len(base)
        if need <= 0:
            return base
        if need < 12 or not base.endswith(b"IEND\xaeB`\x82"):
            return base
        k = max(1, math.ceil(need / (4_000_012)))
        payload = need - 12 * k
        while payload < 0 and k > 1:
            k -= 1
            payload = need - 12 * k
        if payload < 0:
            return base
        sizes = _split_even(payload, k, 4_000_000)
        filler = b"".join(_png_chunk(b"raNd", self.rng.randbytes(s)) for s in sizes)
        return base[:-12] + filler + base[-12:]


# ══════════════════════════════════════════════════════════════
#  ZIP 写入：精确到字节
# ══════════════════════════════════════════════════════════════

class CountingFile:
    """包一层文件对象，记录"写到的最高位置"。

    zipfile 会 seek 回头修补 local header，所以不能用 tell() 的最终值，
    要记高水位 —— 那才是"中央目录会从哪里开始"。
    """

    def __init__(self, raw):
        self.raw = raw
        self.end = 0

    def write(self, data):
        written = self.raw.write(data)
        position = self.raw.tell()
        if position > self.end:
            self.end = position
        return written

    def seek(self, offset, whence=0):
        return self.raw.seek(offset, whence)

    def tell(self):
        return self.raw.tell()

    def flush(self):
        return self.raw.flush()

    def seekable(self):
        return True

    def writable(self):
        return True

    def readable(self):
        return False

    def close(self):
        return self.raw.close()


ZIP_DATE = (2026, 11, 16, 12, 0, 0)


class Archive:
    def __init__(self, path: Path):
        self.path = path
        self.raw = open(path, "wb")
        self.cf = CountingFile(self.raw)
        self.zf = zipfile.ZipFile(self.cf, "w", zipfile.ZIP_DEFLATED, allowZip64=True)
        self.names: list[str] = []

    def entry(self, name: str, compress_type: int = zipfile.ZIP_DEFLATED):
        info = zipfile.ZipInfo(name, date_time=ZIP_DATE)
        info.compress_type = compress_type
        info.external_attr = 0o600 << 16
        self.names.append(name)
        return self.zf.open(info, "w")

    def write_bytes(self, name: str, data: bytes, compress_type: int = zipfile.ZIP_DEFLATED) -> None:
        with self.entry(name, compress_type) as target:
            target.write(data)

    def written(self) -> int:
        return self.cf.end

    def predicted_total(self, extra_names=()) -> int:
        names = self.names + list(extra_names)
        directory = sum(46 + len(name.encode("utf-8")) for name in names) + 22
        return self.written() + directory

    def close(self) -> None:
        self.zf.close()
        self.raw.close()


# ══════════════════════════════════════════════════════════════
#  会话模型
# ══════════════════════════════════════════════════════════════

@dataclass
class Message:
    mid: int
    parent: int | None
    role: str
    content: str
    status: str = "FINISHED"
    ts: float = 0.0
    think: str | None = None
    think_secs: int = 0
    template: str | None = None
    files: list[dict] = field(default_factory=list)


@dataclass
class Session:
    sid: str
    title: str | None
    created: float
    updated: float
    pinned: bool
    messages: list[Message]
    current: int | None
    image_count: int = 0


def message_json(message: Message) -> dict:
    fragments = []
    if message.think:
        fragments.append({"type": "THINK", "content": message.think,
                          "elapsed_secs": message.think_secs})
    if message.content:
        fragments.append({
            "type": "REQUEST" if message.role == "USER" else "RESPONSE",
            "content": message.content,
        })
    if message.template:
        fragments.append({"type": "TEMPLATE_RESPONSE", "content": message.template})
    if message.files:
        fragments.append({"type": "FILE", "files": message.files})
    return {
        "id": message.mid,
        "parent_id": message.parent,
        "role": message.role,
        "status": message.status,
        "inserted_at": iso(message.ts),
        "updated_at": iso(message.ts),
        "fragments": fragments,
    }


def write_session(archive: Archive, session: Session) -> None:
    name = f"chat_sessions/{session.sid}.json"
    head = {
        "id": session.sid,
        "title": session.title,
        "pinned": session.pinned,
        "current_message_id": session.current,
        "inserted_at": iso(session.created),
        "updated_at": iso(session.updated),
    }
    prefix = "{" + ",".join(
        json.dumps(key) + ":" + json.dumps(value, ensure_ascii=False)
        for key, value in head.items()
    ) + ',"messages":['
    with archive.entry(name, zipfile.ZIP_DEFLATED) as target:
        target.write(prefix.encode("utf-8"))
        for index, message in enumerate(session.messages):
            if index:
                target.write(b",")
            target.write(json.dumps(message_json(message), ensure_ascii=False,
                                    separators=(",", ":")).encode("utf-8"))
        target.write(b"]}")


class Builder:
    """按类别造会话，边造边写，内存里最多一条会话。"""

    def __init__(self, archive: Archive, text: TextFactory, images: ImageFactory,
                 rng: random.Random):
        self.archive = archive
        self.text = text
        self.images = images
        self.rng = rng
        # 只留索引需要的元数据：正文写完就丢，600MB 正文不能全压在内存里
        self.index: list[dict] = []
        self.message_count = 0
        self.image_count = 0
        self.char_count = 0
        self._sid_counter = 0

    # ── 工具 ──────────────────────────────────────────────
    def sid(self, tag: str = "") -> str:
        self._sid_counter += 1
        rng = self.rng
        body = "".join(rng.choice("0123456789abcdef") for _ in range(20))
        return f"{tag}{self._sid_counter:04d}-{body}" if tag else f"{self._sid_counter:04d}-{body}"

    def _times(self) -> tuple[float, float]:
        rng = self.rng
        created = rng.uniform(1_735_689_600, 1_795_000_000)  # 2025-01-01 ~ 2026-11
        return created, created + rng.uniform(600, 86400 * 40)

    def add(self, session: Session) -> None:
        write_session(self.archive, session)
        self.index.append({
            "id": session.sid,
            "title": session.title,
            "pinned": session.pinned,
            "message_count": len(session.messages),
            "inserted_at": iso(session.created),
            "updated_at": iso(session.updated),
        })
        self.message_count += len(session.messages)
        self.image_count += session.image_count
        self.char_count += sum(len(m.content) + (len(m.think or "") if m.think else 0)
                               for m in session.messages)

    # ── 会话内容 ───────────────────────────────────────────
    def _alternating(self, total_bytes: int, rounds: int) -> list[Message]:
        rng = self.rng
        response_total = int(total_bytes * 0.93)
        request_total = total_bytes - response_total
        responses = split_sizes(response_total, rounds, rng)
        requests = split_sizes(request_total, rounds, rng)
        messages: list[Message] = []
        mid = 0
        parent: int | None = None
        ts = self._base_ts
        for index in range(rounds):
            mid += 1
            ts += rng.uniform(20, 900)
            messages.append(Message(mid, parent, "USER", self.text.user_text(requests[index]),
                                    ts=ts))
            parent = mid
            mid += 1
            ts += rng.uniform(3, 120)
            size = responses[index]
            think_bytes = int(size * rng.uniform(0.0, 0.22))
            think = (self.text.blob(think_bytes, "mixed").strip()
                     if think_bytes > 512 else None)
            content = self.text.blob(max(128, size - think_bytes), "mixed")
            status = rng.choices(
                ["FINISHED", "INCOMPLETE", "FAILED", "CONTENT_FILTER"],
                weights=[92, 3, 3, 2],
            )[0]
            message = Message(mid, parent, "ASSISTANT", content, status=status, ts=ts,
                              think=think, think_secs=rng.randint(1, 300)
                              if think else 0)
            if status == "FAILED":
                message.content = ""
                message.template = "请求失败：上游超时（压力测试样本）。"
            elif status == "CONTENT_FILTER":
                message.content = ""
                message.template = "抱歉，这个话题我不能继续。"
            messages.append(message)
            parent = mid
        return messages

    _base_ts: float = 0.0

    def session_text(self, tag: str, total_bytes: int, rounds: int,
                     title: str | None = None, pinned: bool = False) -> Session:
        rng = self.rng
        created, updated = self._times()
        self._base_ts = created
        sid = self.sid(tag)
        messages = self._alternating(total_bytes, max(1, rounds))
        if rng.random() < 0.18:
            # 分支：给某个提问挂一条"重新生成"的兄弟回复，当前分支仍走主干
            anchor = rng.choice([m for m in messages if m.role == "USER"][-8:])
            sibling = Message(max(m.mid for m in messages) + 1, anchor.mid, "ASSISTANT",
                              self.text.blob(max(512, total_bytes // (rounds * 2)), "mixed"),
                              ts=anchor.ts + 5)
            messages.append(sibling)
        session = Session(sid=sid, title=title if title is not None else self.text.title(),
                          created=created, updated=updated, pinned=pinned,
                          messages=messages, current=messages[-1].mid)
        self.add(session)
        return session

    def session_image_heavy(self, total_bytes: int, images: int) -> Session:
        rng = self.rng
        created, updated = self._times()
        self._base_ts = created
        sid = self.sid("img")
        sizes = split_sizes(total_bytes, images, rng)
        messages: list[Message] = []
        mid = 0
        parent: int | None = None
        ts = created
        for index, size in enumerate(sizes):
            files, used = self._image_files(sid, max(24_000, size), index)
            mid += 1
            ts += rng.uniform(5, 60)
            prompt = self.text.user_text(rng.randint(60, 400))
            messages.append(Message(mid, parent, "USER", prompt, ts=ts, files=files))
            parent = mid
            mid += 1
            ts += rng.uniform(2, 40)
            messages.append(Message(mid, parent, "ASSISTANT",
                                    self.text.blob(rng.randint(300, 1800), "mixed"),
                                    ts=ts))
            parent = mid
        session = Session(sid=sid, title=f"一次发 {images} 张图（{self.text.title()}）",
                          created=created, updated=updated, pinned=False,
                          messages=messages, current=parent, image_count=images)
        self.add(session)
        return session

    def _image_files(self, sid: str, total_bytes: int, index: int,
                     count: int = 1) -> tuple[list[dict], int]:
        rng = self.rng
        sizes = split_sizes(total_bytes, count, rng)
        files = []
        for slot, size in enumerate(sizes):
            fmt = "png" if rng.random() < 0.1 else "jpeg"
            data, width, height = self.images.make(size, fmt)
            file_id = "%016x" % rng.getrandbits(64)
            ext = "png" if fmt == "png" else "jpg"
            base = f"photo_{index:04d}_{slot}.{ext}"
            name = f"chat_sessions/{sid}_files/{file_id}_{base}"
            self.archive.write_bytes(name, data, zipfile.ZIP_STORED)
            files.append({
                "id": file_id,
                "file_name": base,
                "file_size": len(data),
                "is_image": True,
                "export_path": name,
                "width": width,
                "height": height,
            })
        return files, len(files)

    # ── 图片会话 ───────────────────────────────────────────
    def image_session_single(self, total_bytes: int, count: int) -> Session:
        return self.session_image_heavy(total_bytes, count)

    def image_sessions_spread(self, total_bytes: int, count: int) -> None:
        rng = self.rng
        per_session = max(1, total_bytes // max(1, count))
        for index in range(count):
            created, updated = self._times()
            self._base_ts = created
            sid = self.sid("mix")
            images = rng.randint(1, 6)
            sizes = split_sizes(per_session, images, rng)
            messages: list[Message] = []
            mid = 0
            parent: int | None = None
            ts = created
            shared_path: str | None = None
            for slot, size in enumerate(sizes):
                files, _ = self._image_files(sid, size, index * 10 + slot)
                if shared_path and slot == 1:
                    # 同一张图在两条消息里各引用一次：导入端应当只读一次
                    files[0]["export_path"] = shared_path
                shared_path = files[0]["export_path"]
                mid += 1
                ts += rng.uniform(5, 60)
                messages.append(Message(mid, parent, "USER",
                                        self.text.user_text(rng.randint(40, 260)),
                                        ts=ts, files=files))
                parent = mid
                mid += 1
                ts += rng.uniform(2, 30)
                messages.append(Message(mid, parent, "ASSISTANT",
                                        self.text.blob(rng.randint(200, 900), "mixed"),
                                        ts=ts))
                parent = mid
            self.add(Session(sid=sid, title=f"看图说话 #{index + 1}", created=created,
                             updated=updated, pinned=False, messages=messages,
                             current=parent, image_count=images))

    # ── 边界样本 ───────────────────────────────────────────
    def edge_sessions(self, budget: int) -> None:
        rng = self.rng
        share = max(2048, budget // 12)

        # 1) 单消息巨长
        self._edge("edge-giant", "单条消息十几 MB 的回复",
                   min(16 * MIB, max(64 * 1024, int(share * 1.6))), giant=True)

        # 2) 单消息单行（无换行）
        created, updated = self._times()
        self._base_ts = created
        sid = self.sid("edge")
        line = ("这是一条没有任何换行的超长单行，用来压测按行切分与软换行逻辑。" * 1200)
        body = self.text.blob(max(1024, share), "mixed").replace("\n", " ")
        messages = [
            Message(1, None, "USER", "给我一段不换行的长文本，我要试滚动。", ts=created),
            Message(2, 1, "ASSISTANT", line + body[: max(1024, share)], ts=created + 3),
            Message(3, 2, "USER", "再来一段。", ts=created + 5),
            Message(4, 3, "ASSISTANT", self.text.blob(max(4096, share // 3), "mixed"),
                    ts=created + 9),
        ]
        self.add(Session(sid=sid, title="单行超长正文（没有换行）", created=created,
                         updated=updated, pinned=True, messages=messages, current=4))

        # 3) Markdown 全量展示
        created, updated = self._times()
        self._base_ts = created
        sid = self.sid("edge")
        md = ["# 压测文档：Markdown 全量元素\n\n"]
        for index in range(24):
            md.append(f"## 第 {index + 1} 节 {self.text.title()}\n\n")
            md.append(self.text.table())
            md.append(self.text.listing())
            md.append(self.text.math())
            md.append("```python\n" + self.text.code_block().split("```python\n", 1)[-1])
            md.append(self.text.paragraph(4))
            md.append("| 行内代码 | `a ? b : c` | **粗体** | *斜体* | ~~删除线~~ |\n")
            md.append("> 引用里还有 [链接](https://example.com/a?b=1&c=2) 与 <b>HTML</b>\n\n")
        md.append(self.text.blob(max(4096, share // 3), "markdown"))
        messages = [
            Message(1, None, "USER", "把 Markdown 的边角都过一遍。", ts=created),
            Message(2, 1, "ASSISTANT", "".join(md), ts=created + 4),
        ]
        self.add(Session(sid=sid, title="Markdown 渲染压测（表格 / 列表 / 代码 / 公式）",
                         created=created, updated=updated, pinned=False,
                         messages=messages, current=2))

        # 4) 代码墙
        created, updated = self._times()
        self._base_ts = created
        sid = self.sid("edge")
        code = []
        while sum(len(x) for x in code) < max(4096, share * 2):
            code.append(self.text.code_block())
        messages = [
            Message(1, None, "USER", "贴一批代码，我要看代码块折叠与高亮。", ts=created),
            Message(2, 1, "ASSISTANT", "".join(code), ts=created + 6),
        ]
        self.add(Session(sid=sid, title="代码墙（多语言、超长代码块）", created=created,
                         updated=updated, pinned=False, messages=messages, current=2))

        # 5) Unicode / 转义边界
        created, updated = self._times()
        self._base_ts = created
        sid = self.sid("edge")
        unicode_body = "\n\n".join([
            "emoji：" + " ".join(EMOJI * 4),
            "罕见字：𠮷 𰻞 龘 䶮 㐀 䶮",
            "RTL：العربية עברית مرحبا שלום",
            "组合字符：e\u0301 a\u0308 n\u0303 汉字\u200b零宽",
            "制表符与转义：a\tb\tc \"引号\" \\反斜杠\\ /斜杠/",
            "控制字符：\u0007\u000b\u001b（JSON 里必须是 \\u 转义）",
            self.text.blob(max(4096, share // 4), "mixed"),
        ])
        messages = [
            Message(1, None, "USER", "测一下奇怪字符。" + "".join(ODD) * 10, ts=created),
            Message(2, 1, "ASSISTANT", unicode_body, ts=created + 3),
            Message(3, 2, "USER", "还有吗？", ts=created + 5),
            Message(4, 3, "ASSISTANT", "".join(ODD) * 40 + "\n" + self.text.blob(2048),
                    ts=created + 8),
        ]
        self.add(Session(sid=sid, title="Unicode / 转义边界（emoji、RTL、组合字符）",
                         created=created, updated=updated, pinned=False,
                         messages=messages, current=4))

        # 6) 状态与模板
        created, updated = self._times()
        self._base_ts = created
        sid = self.sid("edge")
        statuses = ["FINISHED", "INCOMPLETE", "WIP", "FAILED", "TIMEOUT",
                    "CONTEXT_LENGTH_EXCEEDED", "CONTENT_FILTER"]
        messages = []
        parent = None
        for index, status in enumerate(statuses):
            mid = index * 2 + 1
            messages.append(Message(mid, parent, "USER",
                                    f"第 {index + 1} 问：{self.text.user_text(200)}",
                                    ts=created + index * 10))
            parent = mid
            reply = Message(mid + 1, parent, "ASSISTANT",
                            self.text.blob(2048, "mixed"), status=status,
                            ts=created + index * 10 + 3,
                            think=self.text.blob(700, "mixed"),
                            think_secs=3 + index,
                            template="（模板正文：压力测试占位）" if status != "FINISHED" else None)
            if status in ("FAILED", "TIMEOUT"):
                reply.content = ""
            messages.append(reply)
            parent = mid + 1
        self.add(Session(sid=sid, title="状态映射（FINISHED / INCOMPLETE / FAILED / 过滤）",
                         created=created, updated=updated, pinned=False,
                         messages=messages, current=parent))

        # 7) 深分支：每个提问下 3 个版本，当前分支走中间那条
        created, updated = self._times()
        self._base_ts = created
        sid = self.sid("edge")
        messages = []
        mid = 0
        parent = None
        ts = created
        for index in range(30):
            mid += 1
            ts += 7
            messages.append(Message(mid, parent, "USER",
                                    f"分支测试 {index + 1}：{self.text.user_text(160)}", ts=ts))
            parent = mid
            first = mid + 1
            for variant in range(3):
                mid += 1
                ts += 3
                messages.append(Message(mid, parent, "ASSISTANT",
                                        f"版本 {variant + 1}：" + self.text.blob(1200, "mixed"),
                                        ts=ts))
            parent = first + 1
        self.add(Session(sid=sid, title="分支与变体（同父三条回复、当前走中间）",
                         created=created, updated=updated, pinned=True,
                         messages=messages, current=parent))

        # 8) 没有标题：导入端应当拿首条提问当标题
        created, updated = self._times()
        self._base_ts = created
        sid = self.sid("edge")
        messages = [
            Message(1, None, "USER", "这条会话没有 title，导入后应该用这句提问当标题。", ts=created),
            Message(2, 1, "ASSISTANT", self.text.blob(4096, "mixed"), ts=created + 2),
        ]
        self.add(Session(sid=sid, title=None, created=created, updated=updated,
                         pinned=False, messages=messages, current=2))

        # 9) 单会话两万条小消息
        created, updated = self._times()
        self._base_ts = created
        sid = self.sid("edge")
        count = max(200, min(20_000, int(share // 400)))
        messages = []
        parent = None
        ts = created
        for index in range(count):
            mid = index + 1
            ts += 0.5
            role = "USER" if index % 2 == 0 else "ASSISTANT"
            messages.append(Message(mid, parent, role,
                                    f"[{index + 1}] " + self.text.blob(180, "prompt")
                                    if role == "USER" else
                                    f"[{index + 1}] " + self.text.blob(220, "mixed"),
                                    ts=ts))
            parent = mid
        self.add(Session(sid=sid, title=f"单会话 {count} 条短消息（列表与索引压测）",
                         created=created, updated=updated, pinned=False,
                         messages=messages, current=parent))

        # 10) 多轮带图（图在回复里）
        created, updated = self._times()
        self._base_ts = created
        sid = self.sid("edge")
        files, _ = self._image_files(sid, max(256_000, share // 6), 999)
        messages = [
            Message(1, None, "USER", "图在回复里，也测一下。", ts=created),
            Message(2, 1, "ASSISTANT", "好的，这是配图：" + self.text.blob(1024), ts=created + 2,
                    files=files),
            Message(3, 2, "USER", "收到。", ts=created + 4),
        ]
        self.add(Session(sid=sid, title="附件出现在助手消息里", created=created,
                         updated=updated, pinned=False, messages=messages, current=3,
                         image_count=1))

    def _edge(self, tag: str, title: str, size: int, giant: bool = False) -> None:
        created, updated = self._times()
        self._base_ts = created
        sid = self.sid(tag)
        if giant:
            messages = [
                Message(1, None, "USER", "把上一轮的方案完整展开，越细越好。", ts=created),
                Message(2, 1, "ASSISTANT", self.text.blob(size, "mixed"), ts=created + 4),
                Message(3, 2, "USER", "继续。", ts=created + 8),
                Message(4, 3, "ASSISTANT", self.text.blob(4096, "mixed"), ts=created + 12),
            ]
            self.add(Session(sid=sid, title=title, created=created, updated=updated,
                             pinned=False, messages=messages, current=4))
        else:
            self.session_text(tag, size, max(2, size // 20_000), title=title)


def split_sizes(total: int, count: int, rng: random.Random, skew: float = 1.5) -> list[int]:
    count = max(1, count)
    total = max(count * 32, int(total))
    weights = [rng.random() ** skew + 0.05 for _ in range(count)]
    factor = total / sum(weights)
    sizes = [max(48, int(weight * factor)) for weight in weights]
    sizes[-1] += total - sum(sizes)
    if sizes[-1] < 48:
        sizes[0] += sizes[-1] - 48
        sizes[-1] = 48
    return sizes


# ══════════════════════════════════════════════════════════════
#  主流程
# ══════════════════════════════════════════════════════════════

def calibrate(text: TextFactory) -> float:
    sample = text.blob(3 * MIB, "mixed")
    raw = sample.encode("utf-8")
    compressed = zlib.compress(raw, 6)
    ratio = len(compressed) / max(1, len(raw))
    print(f"[calibrate] 样本 {len(raw) / MIB:.1f} MiB → 压缩 {len(compressed) / MIB:.1f} MiB "
          f"(ratio={ratio:.3f})", flush=True)
    return ratio


def build(args) -> Path:
    started = time.time()
    rng = random.Random(args.seed)
    text = TextFactory(rng)
    images = ImageFactory(rng)

    target = args.target_bytes or int(args.target_gib * GIB)
    target = int(target * args.scale)
    out = Path(args.out).expanduser().resolve()
    out.parent.mkdir(parents=True, exist_ok=True)
    part = out.with_suffix(out.suffix + ".part")

    ratio = calibrate(text)
    # 文本按"原始字节"定额：内容量（token 数）才是要压的东西，
    # ZIP 里文本压缩后只占几十 MB，剩下的体积由图片承担。
    raw_text = int(args.text_mib * MIB) if args.text_mib else int(600 * MIB * target / GIB)
    raw_text = max(1 * MIB, raw_text)
    print(f"[plan] 目标 {target / GIB:.3f} GiB | 正文原始 {raw_text / MIB:.0f} MiB"
          f"（压缩后约 {raw_text * ratio / MIB:.0f} MiB）| 其余给图片", flush=True)

    archive = Archive(part)
    builder = Builder(archive, text, images, rng)

    weights = {"ultra": 0.20, "long": 0.36, "many": 0.24, "edge": 0.20}
    raw = {key: int(raw_text * value) for key, value in weights.items()}

    # ① 单窗口超长上下文
    rounds = max(4, int(raw["ultra"] // 260_000))
    print(f"[write] 超长单会话 {rounds} 轮 / {raw['ultra'] / MIB:.0f} MiB", flush=True)
    builder.session_text("ultra", raw["ultra"], rounds,
                         title="单窗口超长上下文（一口气聊到底）", pinned=True)

    # ② 超多长上下文窗口
    long_count = max(2, int(raw["long"] // 1_400_000))
    per_long = max(64_000, raw["long"] // max(1, long_count))
    print(f"[write] 长上下文会话 × {long_count}（每条约 {per_long / MIB:.2f} MiB）", flush=True)
    for index in range(long_count):
        rounds = rng.randint(30, 90)
        builder.session_text("long", int(per_long * rng.uniform(0.6, 1.5)), rounds)
        if (index + 1) % 25 == 0:
            print(f"         … {index + 1}/{long_count}，累计 {archive.written() / MIB:.0f} MiB",
                  flush=True)

    # ③ 超多窗口
    many_count = max(4, int(raw["many"] // 60_000))
    per_many = max(2_000, raw["many"] // max(1, many_count))
    print(f"[write] 小会话 × {many_count}（每条约 {per_many / 1024:.0f} KiB）", flush=True)
    for index in range(many_count):
        rounds = rng.randint(3, 9)
        builder.session_text("many", int(per_many * rng.uniform(0.35, 1.8)), rounds)
    print(f"         … {many_count}/{many_count}，累计 {archive.written() / MIB:.0f} MiB",
          flush=True)

    # ④ 边界样本
    print(f"[write] 边界样本（预算 {raw['edge'] / MIB:.0f} MiB）", flush=True)
    builder.edge_sessions(raw["edge"])

    text_done = archive.written()
    print(f"[write] 文本完成：{len(builder.index)} 个会话 / {builder.message_count} 条消息 / "
          f"{text_done / MIB:.0f} MiB 压缩后（{time.time() - started:.0f}s）", flush=True)

    # ⑤ 图片：先把中央目录与索引的固定开销扣掉，剩下的才给图片
    reserve = 3 * MIB + 160 * (len(builder.index) + 400)
    avail = target - text_done - reserve
    img_budget = max(4 * MIB, int(avail * 0.985))
    single_budget = int(img_budget * 0.60)
    spread_budget = int(img_budget * 0.33)
    pad_single = int(img_budget * 0.07)

    single_count = max(4, min(420, int(single_budget // 1_700_000)))
    print(f"[write] 单窗口多图：{single_count} 张 / {single_budget / MIB:.0f} MiB", flush=True)
    builder.image_session_single(single_budget, single_count)

    spread_count = max(2, min(120, int(spread_budget // 4_000_000)))
    print(f"[write] 多窗口带图：{spread_count} 个会话 / {spread_budget / MIB:.0f} MiB",
          flush=True)
    builder.image_sessions_spread(spread_budget, spread_count)

    # ⑥ 填充会话：图片名先定好，正文写成 STORED（大小可精确预知）
    padding_id = builder.sid("pad")
    pad_count = max(2, min(24, int((target - archive.written()) // (10 * MIB)) + 2))
    pad_names = [
        f"chat_sessions/{padding_id}_files/{'%016x' % rng.getrandbits(64)}_pad_{index:02d}.jpg"
        for index in range(pad_count)
    ]
    files = [{
        "id": "%016x" % rng.getrandbits(32),
        "file_name": f"pad_{index:02d}.jpg",
        "file_size": 0,
        "is_image": True,
        "export_path": name,
        "width": 0,
        "height": 0,
    } for index, name in enumerate(pad_names)]
    created, updated = builder._times()
    pad_messages = [
        Message(1, None, "USER", "这批是体积填充样本，图多、正文少。", ts=created),
        Message(2, 1, "ASSISTANT", "收到，一共 " + str(pad_count) + " 张。", ts=created + 2),
        Message(3, 2, "USER", "继续。", ts=created + 4, files=files),
    ]
    padding_session = Session(sid=padding_id, title="体积填充（精确到字节的图片）",
                              created=created, updated=updated, pinned=False,
                              messages=pad_messages, current=3, image_count=pad_count)
    write_session(archive, padding_session)
    builder.index.append({
        "id": padding_session.sid,
        "title": padding_session.title,
        "pinned": False,
        "message_count": len(pad_messages),
        "inserted_at": iso(created),
        "updated_at": iso(updated),
    })
    builder.message_count += len(pad_messages)
    builder.image_count += pad_count

    # ⑦ 索引（真实导出是数组；顺序 = 用户看到的顺序）
    index_entries = sorted(builder.index, key=lambda entry: entry["updated_at"], reverse=True)
    index_bytes = json.dumps(index_entries, ensure_ascii=False,
                             separators=(",", ":")).encode("utf-8")
    archive.write_bytes("chat_session_index.json", index_bytes, zipfile.ZIP_DEFLATED)
    archive.write_bytes("manifest.json", b'{"export_format_version":3}', zipfile.ZIP_DEFLATED)
    print(f"[write] 索引 {len(index_entries)} 条 / {len(index_bytes) / 1024:.0f} KiB，"
          f"已写 {archive.written() / MIB:.0f} MiB", flush=True)

    # ⑧ 精确填充
    total_names = archive.names + pad_names
    central = sum(46 + len(name.encode("utf-8")) for name in total_names) + 22
    local = sum(30 + len(name.encode("utf-8")) for name in pad_names)
    remaining = target - archive.written() - central - local
    if remaining < pad_count * 8192:
        print(f"[warn] 填充只剩 {remaining} 字节，图片总量略超预算", flush=True)
        remaining = pad_count * 8192
    pad_sizes = _split_even(remaining, pad_count, 34 * MIB)
    print(f"[write] 填充 {pad_count} 张 / {remaining / MIB:.2f} MiB（目标 "
          f"{target / GIB:.3f} GiB）", flush=True)
    for name, size in zip(pad_names, pad_sizes):
        data, _, _ = images.make(max(16384, size), "jpeg")
        if len(data) != size:
            # 尺寸偏差（极小概率）：按实际长度回退，最终整体误差在字节级
            print(f"         [warn] 填充图实际 {len(data)} != {size}", flush=True)
        archive.write_bytes(name, data, zipfile.ZIP_STORED)

    predicted = archive.predicted_total()
    archive.close()
    actual = part.stat().st_size
    print(f"[done] 预测 {predicted} 字节 / 实际 {actual} 字节 / 目标 {target} 字节", flush=True)
    if abs(actual - target) > 4096:
        print(f"[warn] 与目标相差 {actual - target} 字节", flush=True)

    part.replace(out)
    elapsed = time.time() - started
    chars = builder.char_count
    print(f"[done] {out}", flush=True)
    print(f"[done] {actual / GIB:.3f} GiB | 会话 {len(builder.index)} | 消息 "
          f"{builder.message_count} | 图片 {builder.image_count} | 正文 {chars / 1e6:.1f} 百万字符 "
          f"| 约 {chars / 1.6 / 1e8:.2f} 亿 token 量级 | 用时 {elapsed:.0f}s", flush=True)
    return out


def verify(path: Path, target: int) -> int:
    print(f"[verify] {path} …", flush=True)
    problems = 0
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        sessions = [n for n in names if n.startswith("chat_sessions/") and n.endswith(".json")
                    and "/" not in n.removeprefix("chat_sessions/")]
        images = [n for n in names if "_files/" in n]
        manifest = json.loads(archive.read("manifest.json"))
        if manifest.get("export_format_version") != 3:
            print("[verify] manifest 不对", flush=True)
            problems += 1
        index = json.loads(archive.read("chat_session_index.json"))
        if len(index) != len(sessions):
            print(f"[verify] 索引 {len(index)} 条 vs 会话文件 {len(sessions)} 个", flush=True)
            problems += 1
        print(f"[verify] 条目 {len(names)} | 会话 {len(sessions)} | 图片 {len(images)}", flush=True)

        sample = sessions[:: max(1, len(sessions) // 12)][:12]
        for name in sample:
            raw = archive.read(name)
            document = json.loads(raw)
            messages = document.get("messages") or []
            ids = {message["id"] for message in messages}
            roots = [message for message in messages if message.get("parent_id") is None]
            if messages and not roots:
                print(f"[verify] {name} 没有根消息", flush=True)
                problems += 1
            for message in messages:
                parent = message.get("parent_id")
                if parent is not None and parent not in ids:
                    print(f"[verify] {name} 断链 {message['id']}", flush=True)
                    problems += 1
                    break
            if document.get("current_message_id") not in ids and messages:
                print(f"[verify] {name} current_message_id 指向不存在的消息", flush=True)
                problems += 1

        image_sample = images[:6] + images[-3:]
        for name in image_sample:
            data = archive.read(name)
            if data[:2] == b"\xff\xd8":
                width, height = _jpeg_dims(data)
            elif data[:8] == b"\x89PNG\r\n\x1a\n":
                width, height = struct.unpack(">II", data[16:24])
            else:
                print(f"[verify] {name} 不是 JPEG/PNG", flush=True)
                problems += 1
                continue
            if not (width > 0 and height > 0):
                print(f"[verify] {name} 尺寸读不出", flush=True)
                problems += 1
        if any(archive.getinfo(n).file_size > 64 * MIB for n in images):
            print("[verify] 有图片超过导入器的 64MB 单图上限", flush=True)
            problems += 1

        print("[verify] 校验 CRC（可能要几十秒）…", flush=True)
        bad = archive.testzip()
        if bad:
            print(f"[verify] CRC 错误：{bad}", flush=True)
            problems += 1
    size = path.stat().st_size
    print(f"[verify] 大小 {size} 字节（目标 {target}，差 {size - target}）", flush=True)
    print("[verify] OK" if problems == 0 else f"[verify] {problems} 处问题", flush=True)
    return problems


def _jpeg_dims(data: bytes) -> tuple[int, int]:
    i, n = 2, len(data)
    while i + 8 < n:
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
            height = (data[i + 5] << 8) | data[i + 6]
            width = (data[i + 7] << 8) | data[i + 8]
            return width, height
        if marker in (0xD8, 0xD9, 0x01) or 0xD0 <= marker <= 0xD7 or marker == 0xFF:
            i += 2
        else:
            length = (data[i + 2] << 8) | data[i + 3]
            if length < 2:
                break
            i += 2 + length
    return 0, 0


def main() -> int:
    parser = argparse.ArgumentParser(description="生成 DeepSeek 导出格式的压力测试归档")
    parser.add_argument("--out", required=True)
    parser.add_argument("--target-gib", type=float, default=1.0)
    parser.add_argument("--target-bytes", type=int, default=0)
    parser.add_argument("--seed", type=int, default=20261116)
    parser.add_argument("--text-mib", type=float, default=0,
                        help="正文原始体积（MiB）；0 表示按目标体积取 600 MiB/GiB")
    parser.add_argument("--scale", type=float, default=1.0,
                        help="整体缩放（用于冒烟测试），同时缩放体积与各项数量")
    parser.add_argument("--no-verify", action="store_true")
    args = parser.parse_args()

    target = args.target_bytes or int(args.target_gib * GIB)
    target = int(target * args.scale)
    path = build(args)
    if not args.no_verify:
        problems = verify(path, target)
        return 1 if problems else 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
