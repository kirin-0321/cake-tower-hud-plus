#!/usr/bin/env python3
"""
gen_stage_name_map.py

扫描 Cake Team Towers 地图数据包 + ctt_lang 资源包，自动生成关卡 ID → 本地化名称
映射表 JSON。HUD 客户端直接读这份硬编码表，根据当前 Minecraft 语言（zh_cn / en_us）
显示对应的关卡名。

逻辑：
  1) 扫 datapack/function/floors/*.mcfunction，按文件名解析 (type, id, slug)。
     文件名形如：dungeon07_downpoor.mcfunction、boss15_demon_queen_devi.mcfunction、
                shop15_florias_garden.mcfunction、miniboss_01_magum.mcfunction、
                dungeon07_downpoor_map_1.mcfunction（_map[_]?N 后缀视为变体，跳过）。
  2) 由 slug 生成多个候选翻译键（Title-case + 小词保护 + 数字全大写 + 撇号变体等）。
  3) 在 ctt_lang/zh_cn.json 里按候选优先级查找，第一个命中的视为正式键。
  4) 用同一个键去 ctt_lang/en_us.json 拿英文名；en 没有就 fallback 到候选 1（Title-case）。
  5) 输出到 src/main/resources/stage_name_map.json。

用法：
  python scripts/gen_stage_name_map.py [--datapack <path>] [--lang <path>] [--out <path>]
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple

# ----------------------------------------------------------------------------
# 默认路径
# ----------------------------------------------------------------------------
DEFAULT_MAP_ROOT = Path(
    r"g:\MC\.minecraft\versions\1.21.4-Fabric_0.19.1"
    r"\saves\Cake Team Towers Chapter 3 Update #4.0.12 (The Heart of Otherside) (Premium)"
)
DEFAULT_DATAPACK = DEFAULT_MAP_ROOT / "datapacks" / "CakeTeamPack" / "data" / "cake_team_tower" / "function"
DEFAULT_LANG_DIR = DEFAULT_MAP_ROOT / "resources" / "assets" / "ctt_lang" / "lang"

DEFAULT_OUT = Path(__file__).resolve().parents[1] / "src" / "main" / "resources" / "stage_name_map.json"

# ----------------------------------------------------------------------------
# 文件名解析
# ----------------------------------------------------------------------------
# 形如 "dungeon07_downpoor"、"boss15_demon_queen_devi"、"shop15_florias_garden"、
# "miniboss02_doctor_mario"、"miniboss_01_magum"、"ally00_universal"、"misc01_muck"。
# 后缀 "_map_1" / "_map1" / "_map_2" 等代表同一关卡的不同地图变体——主条目不带它。
FLOOR_RE = re.compile(
    r"^(?P<type>dungeon|boss|miniboss|shop|ally|misc)_?(?P<id>\d+)_(?P<slug>.+?)(?P<map>_map_?\d+)?$",
    re.IGNORECASE,
)

# 与 hud/StageLocation.Kind 的命名约定对齐（Java 端按 type 字符串查表）
TYPE_KEYS = ("dungeon", "boss", "miniboss", "shop", "ally", "misc")

# 候选键启发：这些"小词"出现在非首词位置时保持小写
LOWERCASE_WORDS = {"of", "the", "and", "by", "in", "on", "a", "to", "or", "for", "is", "at"}

# 显式拼写覆盖：地图作者文件名拼写错误 / 与翻译键不一致时直接强制
# 优先级最高（在启发式之前查）
SLUG_OVERRIDES: Dict[str, str] = {
    # dungeon —— 文件名拼写错误 / 翻译键与 slug 形式差异
    "downpoor":               "Downpour",                       # dungeon07
    "necrovile":              "Necroville",                     # dungeon04
    "2xxx":                   "2XXX",                           # dungeon65
    "sfoth":                  "Sword Fights on the Heights",    # dungeon34（SFOTH 缩写展开）
    "the_skeld":              "The Skeld",                      # dungeon24
    "ant_hill_thrill":        "Anthill Thrill",                 # dungeon26（地图作者把单词拆错）
    "skyball":                "Sky Ball",                       # dungeon63（slug 连写但翻译键有空格）
    # boss
    "doctor_ivy":             "Doctor Ivy",
    "fury_david":             "Fury David",
    "possesed_glacium":       "Possessed Glacium",              # boss04（slug 拼写错误，少一个 s）
    "hush_hush_henry":        "Hush Hush Henry",
    "the_summoner":           "The Summoner",
    # 撇号变体：translation key 有"无撇号"版本
    "joeys_manor":            "Joeys Manor",
    "henrys_lab":             "Henrys Lab",
    "florias_garden":         "Florias Garden",
    "davids_pinball_machine": "Davids Pinball Machine",
    "devis_castle":           "Devis Castle",
    "marios_shop":            "Marios Shop",
    "spamton_shop":           "Spamton's Shop",                 # shop09 翻译键带撇号
    # zh 完全没翻译的，直接给英文 fallback（Java 端会用 en 兜底）
    "a_partys_world":         "A Party's World",                # dungeon22
    "muck":                   "Muck",                           # misc01
    "muck_level_up_room":     "Muck Level Up Room",             # misc10
    "bug_shop":               "Bug Shop",                       # shop10
    "sweet_canyon_cake":      "Sweet Canyon Cake",              # dungeon67
}

# 这些 slug → 最终输出 type/id 时跳过（数据包里有但游戏不会用，或者纯属占位）
SLUG_BLACKLIST = {
    "draft_dungeon",   # dungeon00 占位
    "universal",       # ally00 通用 / _floor_universal
    "practice_mode",   # misc02 练习模式（界面里用别的名字）
}


def slug_to_candidate_keys(slug: str) -> List[str]:
    """生成翻译键候选列表（按优先级降序）。

    对 slug "demon_queen_devi"：
      1) "Demon Queen Devi"            ← 全部首字母大写
      2) "Demon queen devi"            ← 仅首词大写（保险 fallback）
      3) "demon_queen_devi"            ← 原 slug
    对 slug "dojo_of_glory"：
      1) "Dojo Of Glory"
      2) "Dojo of Glory"               ← of 小写
      3) "Dojo of glory"
    对 slug "the_lab"：
      1) "The Lab"
      2) "The lab"
    """
    words = [w for w in slug.split("_") if w]
    if not words:
        return [slug]

    cands: List[str] = []

    # 1) 全部首字母大写
    cands.append(" ".join(w.capitalize() for w in words))

    # 2) 小词保护：除首词外，LOWERCASE_WORDS 内的词保持小写
    if any(w.lower() in LOWERCASE_WORDS for w in words[1:]):
        protected = [
            words[0].capitalize(),
            *[w.lower() if w.lower() in LOWERCASE_WORDS else w.capitalize()
              for w in words[1:]],
        ]
        cands.append(" ".join(protected))

    # 3) 仅首词大写
    head_only = [words[0].capitalize(), *[w.lower() for w in words[1:]]]
    cands.append(" ".join(head_only))

    # 4) 原始 slug 替换下划线为空格
    cands.append(slug.replace("_", " "))

    # 5) 全大写（针对 SFOTH / 2XXX 这种缩写）
    cands.append(slug.upper())

    seen = set()
    out: List[str] = []
    for c in cands:
        if c in seen:
            continue
        seen.add(c)
        out.append(c)
    return out


def parse_floor_filename(stem: str) -> Optional[Tuple[str, int, str, bool]]:
    """解析文件名 stem（不含扩展名），返回 (type, id_int, slug, is_map_variant) 或 None。"""
    m = FLOOR_RE.match(stem)
    if not m:
        return None
    typ = m.group("type").lower()
    if typ == "miniboss":
        # StageLocation 里叫 mboss，statisticly 一致即可
        typ = "mboss"
    try:
        id_int = int(m.group("id"))
    except ValueError:
        return None
    slug = m.group("slug").lower()
    is_map = m.group("map") is not None
    return typ, id_int, slug, is_map


def load_lang_file(path: Path) -> Dict[str, str]:
    if not path.exists():
        print(f"[WARN] lang file not found: {path}", file=sys.stderr)
        return {}
    try:
        text = path.read_text(encoding="utf-8")
        return json.loads(text)
    except json.JSONDecodeError as e:
        # ctt_lang 里允许重复键 / 注释——尝试最大努力解析失败时降级
        print(f"[WARN] lang file JSON parse error: {e}", file=sys.stderr)
        return {}


def find_translation_key(slug: str, zh: Dict[str, str]) -> Optional[str]:
    """在 zh_cn.json 中查 slug 对应的翻译键。命中返回该键，未命中返回 None。"""
    override = SLUG_OVERRIDES.get(slug)
    if override and override in zh:
        return override
    for cand in slug_to_candidate_keys(slug):
        if cand in zh:
            return cand
    return None


def fallback_english_name(slug: str) -> str:
    """zh 找不到键时，用启发式生成一个英文 fallback（默认 Title-case，保护小词）。"""
    if slug in SLUG_OVERRIDES:
        return SLUG_OVERRIDES[slug]
    words = [w for w in slug.split("_") if w]
    if not words:
        return slug
    out = [words[0].capitalize()]
    for w in words[1:]:
        out.append(w.lower() if w.lower() in LOWERCASE_WORDS else w.capitalize())
    return " ".join(out)


# ----------------------------------------------------------------------------
# 主逻辑
# ----------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--datapack", type=Path, default=DEFAULT_DATAPACK,
                        help="data/<namespace>/function 根目录")
    parser.add_argument("--lang", type=Path, default=DEFAULT_LANG_DIR,
                        help="ctt_lang/lang 目录（含 zh_cn.json / en_us.json）")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT,
                        help="输出 stage_name_map.json 路径")
    args = parser.parse_args()

    floors_dir = args.datapack / "floors"
    if not floors_dir.exists():
        print(f"[ERR] floors dir not found: {floors_dir}", file=sys.stderr)
        return 2

    zh_path = args.lang / "zh_cn.json"
    en_path = args.lang / "en_us.json"
    print(f"[gen-stage] datapack:    {args.datapack}")
    print(f"[gen-stage] lang/zh_cn:  {zh_path}")
    print(f"[gen-stage] lang/en_us:  {en_path}")

    zh = load_lang_file(zh_path)
    en = load_lang_file(en_path)
    print(f"[gen-stage] zh_cn keys:  {len(zh)}")
    print(f"[gen-stage] en_us keys:  {len(en)}")

    # type → id → entry。优先级：非 _map 主文件 > _map 变体（仅作 fallback）
    by_type: Dict[str, Dict[int, dict]] = {t: {} for t in TYPE_KEYS}

    miss_log: List[str] = []
    hit_count = 0
    blacklist_count = 0
    total = 0

    for path in sorted(floors_dir.glob("*.mcfunction")):
        parsed = parse_floor_filename(path.stem)
        if parsed is None:
            continue
        typ, id_int, slug, is_map_variant = parsed
        if typ not in by_type:
            continue

        # 黑名单
        if slug in SLUG_BLACKLIST:
            blacklist_count += 1
            continue

        # 主文件优先：若该 (type,id) 已有非变体条目，跳过 _map 变体
        existing = by_type[typ].get(id_int)
        if existing and not is_map_variant:
            # 当前是主文件，但 existing 必定也是主文件（之前已写入），
            # 这种情况理论上不应发生（同 type+id 应该唯一）；以最早写入为准。
            continue
        if existing and is_map_variant:
            continue
        if existing is None and is_map_variant:
            # 暂时记下，等同 type+id 的主文件出现替换；最终 _map 变体会被覆盖
            pass

        total += 1

        key = find_translation_key(slug, zh)
        zh_name: Optional[str]
        en_name: Optional[str]

        if key is not None:
            zh_name = zh.get(key)
            en_name = en.get(key) or key  # en 没翻译就用键本身（即英文原名）
            hit_count += 1
        else:
            # zh 完全未命中：用启发式英文 fallback；zh_name 留 None 以便 Java 端使用 en
            zh_name = None
            en_name = fallback_english_name(slug)
            miss_log.append(f"  {typ}{id_int:02d} '{slug}' → no zh_cn key, fallback en='{en_name}'")

        entry = {
            "id": id_int,
            "slug": slug,
            "key": key,           # 翻译表里命中的键（可能为 null）
            "en": en_name,
            "zh_cn": zh_name,     # 可能为 null
            "isMapVariant": is_map_variant,
        }
        by_type[typ][id_int] = entry

    # 最终扁平化
    out = {
        "version": 1,
        "generatedBy": Path(__file__).name,
        "source": str(args.datapack).replace("\\", "/"),
        "kinds": {},
        "stats": {
            "total": total,
            "matched": hit_count,
            "missed": total - hit_count,
            "blacklisted": blacklist_count,
        },
    }

    for typ in TYPE_KEYS:
        entries = by_type[typ]
        out["kinds"][typ] = {
            str(eid): {
                "en":    e["en"],
                "zh_cn": e["zh_cn"],
                "slug":  e["slug"],
                "key":   e["key"],
            }
            for eid, e in sorted(entries.items())
        }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps(out, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    print(f"[gen-stage] total entries:  {total}")
    print(f"[gen-stage] matched (zh):   {hit_count}")
    print(f"[gen-stage] missed:         {total - hit_count}")
    print(f"[gen-stage] blacklisted:    {blacklist_count}")
    if miss_log:
        print(f"[gen-stage] === MISS LIST ({len(miss_log)} entries, fallback to en heuristic) ===")
        for line in miss_log:
            print(line)
        print(f"[gen-stage] === END MISS LIST ===")
    print(f"[gen-stage] wrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
