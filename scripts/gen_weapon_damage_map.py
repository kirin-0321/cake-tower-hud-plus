#!/usr/bin/env python3
"""
gen_weapon_damage_map.py

扫描 Cake Team Towers 地图数据包，自动生成武器 → 伤害类型映射表 JSON。

逻辑：
  Pass 1: 扫 items/*.mcfunction 逐文件提取：
    - 物品签名（SelectedItem NBT 里的 id + custom_data key）
    - 本文件里直接写的 *DMG 类型
    - 本文件里 summon 的 entity tags（AK47ShootAI / FireBall / ...）
  Pass 2: 扫全部 .mcfunction（所有子目录）构建 tag → damage types 反向索引
  Pass 3: 关联：武器直接 DMG ∪ 武器 summon 的每个 tag 对应的间接 DMG
  Pass 4: 输出 JSON 到 resources/weapon_damage_seed.json

被追踪的 9 种 *DMG（与 AttackerProbe.TRACKED_OBJECTIVES 一致）：
  MeleeDMG, BulletDMG, ForceDMG, FireDMG, WaterDMG,
  IceDMG, DarkDMG, LightDMG, ElectricDMG

用法：
  python scripts/gen_weapon_damage_map.py [--datapack <path>] [--out <json path>]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Set, Tuple

TRACKED_DMG = {
    "MeleeDMG", "BulletDMG", "ForceDMG", "FireDMG", "WaterDMG",
    "IceDMG", "DarkDMG", "LightDMG", "ElectricDMG",
}

# v6.3.4 · 按 vanilla itemId 强制注入的伤害类型。
# 原因：弓/弩/三叉戟打出的伤害由"箭矢飞行后命中 → 地图 damage.mcfunction"触发，
# 静态分析武器自身的 mcfunction 看不到 BulletDMG 字样；这里打补丁让 seed 自带。
VANILLA_ITEM_DMG_OVERRIDE = {
    "minecraft:bow":      {"BulletDMG"},
    "minecraft:crossbow": {"BulletDMG"},
    "minecraft:trident":  {"BulletDMG", "MeleeDMG"},
}

DEFAULT_DATAPACK = Path(
    r"g:\MC\.minecraft\versions\1.21.4-Fabric_0.19.1"
    r"\saves\Cake Team Towers Chapter 3 Update #4.0.12 (The Heart of Otherside) (Premium)"
    r"\datapacks\CakeTeamPack\data\cake_team_tower\function"
)

DEFAULT_OUT = Path(__file__).resolve().parents[1] / "src" / "main" / "resources" / "weapon_damage_seed.json"


DMG_WRITE_RE = re.compile(
    r"scoreboard\s+players\s+(?:set|add|remove|operation)\s+"
    r"(?P<target>\S+)\s+(?P<dmg>\w+DMG)\b"
)

SUMMON_TAGS_RE = re.compile(
    r"summon\s+minecraft:\S+[^\n]*?Tags:\s*\[(?P<tags>[^\]]+)\]",
    re.IGNORECASE,
)

SUMMON_ENTITY_RE = re.compile(
    r"summon\s+(minecraft:\w+)",
    re.IGNORECASE,
)

NON_SUMMON_ENTITIES = {
    "minecraft:armor_stand",
    "minecraft:marker",
    "minecraft:interaction",
    "minecraft:block_display",
    "minecraft:item_display",
    "minecraft:text_display",
    "minecraft:arrow",
    "minecraft:spectral_arrow",
    "minecraft:snowball",
    "minecraft:egg",
    "minecraft:ender_pearl",
    "minecraft:eye_of_ender",
    "minecraft:trident",
    "minecraft:thrown_trident",
    "minecraft:fishing_bobber",
    "minecraft:firework_rocket",
    "minecraft:fireball",
    "minecraft:small_fireball",
    "minecraft:dragon_fireball",
    "minecraft:wither_skull",
    "minecraft:llama_spit",
    "minecraft:shulker_bullet",
    "minecraft:experience_orb",
    "minecraft:experience_bottle",
    "minecraft:item",
    "minecraft:lightning_bolt",
    "minecraft:potion",
    "minecraft:lingering_potion",
    "minecraft:splash_potion",
    "minecraft:area_effect_cloud",
    "minecraft:falling_block",
    "minecraft:tnt",
    "minecraft:leash_knot",
    "minecraft:painting",
    "minecraft:item_frame",
    "minecraft:glow_item_frame",
}

TAG_SINGLE_RE = re.compile(r'"([^"]+)"')

SELECTED_ITEM_RE = re.compile(r"SelectedItem\s*:\s*\{")

CUSTOM_DATA_KEY_RE = re.compile(
    r'"minecraft:custom_data"\s*:\s*\{(?P<body>[^{}]*)\}'
)

ITEM_ID_RE = re.compile(r'\bid\s*:\s*"([^"]+)"')

ENTITY_TAG_RE = re.compile(r"@\w+\[[^\]]*?\btag=([^,\]]+)")

KEY_VALUE_PAIRS_RE = re.compile(r"(\w+)\s*:\s*([^,}]+)")

FUNCTION_CALL_RE = re.compile(
    r"function\s+(?:(?P<ns>\w+):)?(?P<path>[\w/]+)"
)


@dataclass
class ItemSignature:
    """一把武器（custom_data 键 + 可选的 item id）。"""
    custom_data_key: str
    item_id: str | None = None

    def key(self) -> str:
        return self.custom_data_key


@dataclass
class WeaponEntry:
    file_rel: str
    signature: ItemSignature
    direct_dmg: Set[str] = field(default_factory=set)
    summoned_tags: Set[str] = field(default_factory=set)
    summoned_entities: Set[str] = field(default_factory=set)
    indirect_dmg: Set[str] = field(default_factory=set)
    call_chain_dmg: Set[str] = field(default_factory=set)
    kind: str = "weapon"

    def all_dmg(self) -> Set[str]:
        return (self.direct_dmg | self.indirect_dmg | self.call_chain_dmg) & TRACKED_DMG

    def to_json(self) -> dict:
        return {
            "file": self.file_rel.replace("\\", "/"),
            "kind": self.kind,
            "itemId": self.signature.item_id,
            "customDataKey": self.signature.custom_data_key,
            "damageTypes": sorted(self.all_dmg()),
            "directDmg": sorted(self.direct_dmg & TRACKED_DMG),
            "viaTagDmg": sorted(self.indirect_dmg & TRACKED_DMG),
            "viaCallDmg": sorted(self.call_chain_dmg & TRACKED_DMG),
            "viaTags": sorted(self.summoned_tags),
            "viaEntities": sorted(self.summoned_entities),
        }


def find_balanced(text: str, start: int, open_ch: str = "{", close_ch: str = "}") -> int:
    """给一个 open_ch 位置，返回对应 close_ch 的位置（+1 可用作 slice 结束）。找不到返回 -1。"""
    depth = 0
    i = start
    n = len(text)
    while i < n:
        c = text[i]
        if c == open_ch:
            depth += 1
        elif c == close_ch:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def extract_item_signature(line: str) -> ItemSignature | None:
    """从一行 mcfunction 提取 SelectedItem 内的 custom_data key + item id。"""
    m = SELECTED_ITEM_RE.search(line)
    if not m:
        return None
    brace_start = line.find("{", m.start())
    if brace_start < 0:
        return None
    brace_end = find_balanced(line, brace_start)
    if brace_end < 0:
        return None

    selected = line[brace_start : brace_end + 1]

    custom = CUSTOM_DATA_KEY_RE.search(selected)
    if not custom:
        return None

    body = custom.group("body")
    kv_match = None
    for km in KEY_VALUE_PAIRS_RE.finditer(body):
        k = km.group(1)
        v = km.group(2).strip()
        if v.endswith("b") or v.endswith("B") or v in {"1", "true", "True"}:
            kv_match = k
            break
    if kv_match is None:
        m2 = re.search(r"(\w+)\s*:", body)
        if m2:
            kv_match = m2.group(1)

    if not kv_match:
        return None

    id_m = ITEM_ID_RE.search(selected)
    item_id = id_m.group(1) if id_m else None

    return ItemSignature(custom_data_key=kv_match, item_id=item_id)


def extract_summon_tags(text: str) -> Set[str]:
    tags: Set[str] = set()
    for m in SUMMON_TAGS_RE.finditer(text):
        body = m.group("tags")
        for tm in TAG_SINGLE_RE.finditer(body):
            tags.add(tm.group(1))
    return tags


def extract_summoned_entities(text: str) -> Set[str]:
    """所有被 `summon minecraft:<entity>` 引用的实体 id。"""
    out: Set[str] = set()
    for m in SUMMON_ENTITY_RE.finditer(text):
        out.add(m.group(1))
    return out


def classify_kind(file_name: str, summoned_entities: Set[str]) -> str:
    """区分 "weapon" vs "summon"。
    - 文件名以 summon 前缀开头 → summon
    - 或 summon 了任何不在 NON_SUMMON_ENTITIES 黑名单的实体（= 召唤了生物）→ summon
    - 否则 → weapon
    """
    if file_name.lower().startswith("summon"):
        return "summon"
    for e in summoned_entities:
        if e not in NON_SUMMON_ENTITIES:
            return "summon"
    return "weapon"


def extract_dmg_writes(text: str) -> List[Tuple[str, str]]:
    """Return list of (target-selector, dmg-type)."""
    out: List[Tuple[str, str]] = []
    for m in DMG_WRITE_RE.finditer(text):
        out.append((m.group("target"), m.group("dmg")))
    return out


def extract_targets_tags(selector: str) -> Set[str]:
    return {m.group(1).strip() for m in ENTITY_TAG_RE.finditer(selector)}


def walk_mcfunctions(root: Path) -> List[Path]:
    return sorted(root.rglob("*.mcfunction"))


def build_tag_to_dmg(all_files: List[Path]) -> Dict[str, Set[str]]:
    """对每个 *.mcfunction，找出 'DMG 写入所在行中 selector 出现的 tag'，建立 tag→DMG 索引。

    例：misc_11_ak47_1.mcfunction 里 `@e[tag=PistolShootHit,limit=1,sort=nearest]` 写 BulletDMG
        → PistolShootHit → BulletDMG
    """
    idx: Dict[str, Set[str]] = {}
    for p in all_files:
        try:
            text = p.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            continue

        for line in text.splitlines():
            if "DMG" not in line:
                continue
            for dm in DMG_WRITE_RE.finditer(line):
                dmg = dm.group("dmg")
                if dmg not in TRACKED_DMG:
                    continue
                for sel_match in re.finditer(r"@[ae]\[[^\]]*\]", line):
                    sel = sel_match.group(0)
                    for tag in extract_targets_tags(sel):
                        t = tag.lstrip("!").strip()
                        if t:
                            idx.setdefault(t, set()).add(dmg)
    return idx


def scan_items_pass1(items_dir: Path, datapack_root: Path) -> List[WeaponEntry]:
    weapons: List[WeaponEntry] = []
    for path in sorted(items_dir.glob("*.mcfunction")):
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            continue

        sig = None
        for line in text.splitlines():
            if "SelectedItem" in line and "custom_data" in line:
                sig = extract_item_signature(line)
                if sig:
                    break
        if sig is None:
            continue

        direct: Set[str] = set()
        for _target, dmg in extract_dmg_writes(text):
            if dmg in TRACKED_DMG:
                direct.add(dmg)

        summoned = extract_summon_tags(text)
        summoned_entities = extract_summoned_entities(text)
        kind = classify_kind(path.name, summoned_entities)

        rel = path.relative_to(datapack_root).as_posix()
        weapons.append(WeaponEntry(
            file_rel=rel,
            signature=sig,
            direct_dmg=direct,
            summoned_tags=summoned,
            summoned_entities=summoned_entities,
            kind=kind,
        ))
    return weapons


def enrich_indirect(weapons: List[WeaponEntry], tag_to_dmg: Dict[str, Set[str]]) -> None:
    for w in weapons:
        for tag in w.summoned_tags:
            dmgs = tag_to_dmg.get(tag)
            if dmgs:
                w.indirect_dmg.update(dmgs & TRACKED_DMG)


def build_call_graph_and_direct_dmg(
    all_files: List[Path], datapack_root: Path
) -> Tuple[Dict[str, Set[str]], Dict[str, Set[str]]]:
    """扫所有 *.mcfunction，返回：
      call_graph:      caller_rel_path_no_ext  →  {callee_rel_path_no_ext}
      direct_dmg_map:  rel_path_no_ext         →  {DMG types}（本文件直接写的）

    caller/callee 都用地图 namespace 内相对路径（如 "items/misc_11_ak47_1"）。
    跨 namespace 的调用被忽略（我们只关心 cake_team_tower 自己）。
    """
    call_graph: Dict[str, Set[str]] = {}
    direct_dmg_map: Dict[str, Set[str]] = {}

    for p in all_files:
        try:
            text = p.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            continue
        rel = p.relative_to(datapack_root).with_suffix("").as_posix()
        call_graph.setdefault(rel, set())
        direct_dmg_map.setdefault(rel, set())

        for dm in DMG_WRITE_RE.finditer(text):
            dmg = dm.group("dmg")
            if dmg in TRACKED_DMG:
                direct_dmg_map[rel].add(dmg)

        for m in FUNCTION_CALL_RE.finditer(text):
            ns = m.group("ns")
            path = m.group("path")
            if ns and ns != "cake_team_tower":
                continue
            call_graph[rel].add(path)

    return call_graph, direct_dmg_map


def propagate_call_dmg(
    call_graph: Dict[str, Set[str]],
    direct_dmg_map: Dict[str, Set[str]],
    max_depth: int = 6,
) -> Dict[str, Set[str]]:
    """迭代传播：一个 function 的可达 DMG = 自身直接 DMG ∪ ∪(所有 callee 的可达 DMG)。
    max_depth 防止循环调用导致死循环（虽然 fixed-point 理论上也会收敛）。
    """
    reachable: Dict[str, Set[str]] = {k: set(v) for k, v in direct_dmg_map.items()}

    for _ in range(max_depth):
        changed = False
        for caller, callees in call_graph.items():
            before = len(reachable.get(caller, set()))
            bucket = reachable.setdefault(caller, set())
            for callee in callees:
                if callee in reachable:
                    bucket.update(reachable[callee])
            if len(bucket) > before:
                changed = True
        if not changed:
            break
    return reachable


def enrich_call_chain(
    weapons: List[WeaponEntry],
    call_reachable: Dict[str, Set[str]],
    datapack_root: Path,
) -> None:
    for w in weapons:
        rel_no_ext = w.file_rel.rsplit(".", 1)[0]
        dmgs = call_reachable.get(rel_no_ext)
        if dmgs:
            w.call_chain_dmg.update(dmgs & TRACKED_DMG)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--datapack", type=Path, default=DEFAULT_DATAPACK,
                        help="data/<namespace>/function 根目录")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT,
                        help="输出 JSON 路径")
    parser.add_argument("--pretty", action="store_true", default=True)
    args = parser.parse_args()

    datapack = args.datapack.resolve()
    if not datapack.exists():
        print(f"[ERR] datapack root not found: {datapack}", file=sys.stderr)
        return 2

    items_dir = datapack / "items"
    if not items_dir.exists():
        print(f"[ERR] items dir not found: {items_dir}", file=sys.stderr)
        return 2

    print(f"[gen-weapon] scanning datapack: {datapack}")
    all_files = walk_mcfunctions(datapack)
    print(f"[gen-weapon] total .mcfunction files: {len(all_files)}")

    print("[gen-weapon] Pass 1: scanning items/ for signatures + direct DMG + summon tags")
    weapons = scan_items_pass1(items_dir, datapack)
    print(f"[gen-weapon] found {len(weapons)} weapons with SelectedItem+custom_data signature")

    print("[gen-weapon] Pass 2: building tag → DMG reverse index (all files)")
    tag_to_dmg = build_tag_to_dmg(all_files)
    print(f"[gen-weapon] collected {len(tag_to_dmg)} entity tags that appear in DMG writes")

    print("[gen-weapon] Pass 2b: building function call graph + direct DMG map")
    call_graph, direct_dmg_map = build_call_graph_and_direct_dmg(all_files, datapack)
    print(f"[gen-weapon] call graph has {len(call_graph)} nodes")

    print("[gen-weapon] Pass 2c: propagating DMG through call chains")
    call_reachable = propagate_call_dmg(call_graph, direct_dmg_map)

    print("[gen-weapon] Pass 3a: enriching each weapon's damageTypes via its summoned tags")
    enrich_indirect(weapons, tag_to_dmg)

    print("[gen-weapon] Pass 3b: enriching each weapon's damageTypes via call chain")
    enrich_call_chain(weapons, call_reachable, datapack)

    print("[gen-weapon] Pass 3c: applying vanilla itemId overrides (bow/crossbow/trident → BulletDMG)")
    injected = 0
    for w in weapons:
        override = VANILLA_ITEM_DMG_OVERRIDE.get(w.signature.item_id or "")
        if override:
            before = len(w.direct_dmg)
            w.direct_dmg.update(override)
            if len(w.direct_dmg) > before:
                injected += 1
    print(f"[gen-weapon]   injected on {injected} weapon entries")

    total_with_dmg = sum(1 for w in weapons if w.all_dmg())
    print(f"[gen-weapon] {total_with_dmg}/{len(weapons)} weapons have at least one tracked DMG mapping")

    by_dmg: Dict[str, int] = {d: 0 for d in TRACKED_DMG}
    for w in weapons:
        for d in w.all_dmg():
            by_dmg[d] += 1
    print("[gen-weapon] coverage by DMG type:")
    for d, n in sorted(by_dmg.items(), key=lambda kv: -kv[1]):
        print(f"  {d:<14}{n}")

    by_kind: Dict[str, int] = {}
    by_kind_with_dmg: Dict[str, int] = {}
    for w in weapons:
        by_kind[w.kind] = by_kind.get(w.kind, 0) + 1
        if w.all_dmg():
            by_kind_with_dmg[w.kind] = by_kind_with_dmg.get(w.kind, 0) + 1
    print("[gen-weapon] kind distribution (total / with-dmg):")
    for k in sorted(by_kind.keys()):
        print(f"  {k:<10}{by_kind[k]:>4}  /  {by_kind_with_dmg.get(k, 0):>4}")

    print("[gen-weapon] Pass 4: writing JSON")
    out = {
        "version": 1,
        "source": str(datapack).replace("\\", "/"),
        "trackedDmg": sorted(TRACKED_DMG),
        "generatedBy": Path(__file__).name,
        "weaponCount": len(weapons),
        "weaponCountWithDmg": total_with_dmg,
        "weapons": {w.signature.key(): w.to_json() for w in sorted(weapons, key=lambda x: x.signature.key())},
        "tagToDmg": {k: sorted(v & TRACKED_DMG) for k, v in sorted(tag_to_dmg.items())
                     if (v & TRACKED_DMG)},
    }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps(out, ensure_ascii=False, indent=2 if args.pretty else None),
        encoding="utf-8",
    )
    print(f"[gen-weapon] wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
