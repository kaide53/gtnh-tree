#!/usr/bin/env python3
"""把 GTNH 的 NEI 书签文件 (bookmarks.ini) 解析成合成树。

输出:
  web/tree.json      —— 树的数据（纯 JSON）
  web/tree-data.js   —— 同样的数据包成 window.TREE_DATA，供 viewer.html 双击离线打开

用法:
  python3 tools/bookmarks2tree.py "/path/to/.minecraft/saves/NEI/global/bookmarks.ini" [输出目录]

说明:
  这是"先看到 html 效果"的过渡方案。它忠实还原你在 NEI 书签里选好的每一条配方链，
  数量采用"向上取整的运行次数"模型（ceil(需求/单次产出))，尚未处理容器物品、流体、
  概率输出、副产物与循环依赖等 NEI RecipeChainMath 的细节；精确版由导出模组完成。
"""
import json
import sys
import os
import argparse
import datetime
from collections import defaultdict

# ---- 配方处理器 -> 友好机器名 ----
HANDLER_NAMES = {
    "codechicken.nei.recipe.ShapedRecipeHandler": "工作台 · 有序合成",
    "codechicken.nei.recipe.ShapelessRecipeHandler": "工作台 · 无序合成",
    "codechicken.nei.recipe.FurnaceRecipeHandler": "熔炉 · 烧制",
    "gt.recipe.compressor": "GT 压缩机",
    "gt.recipe.category.macerator_recycling": "GT 研磨机 · 回收",
    "gt.recipe.macerator": "GT 研磨机",
    "gt.recipe.centrifuge": "GT 离心机",
    "gt.recipe.electrolyzer": "GT 电解机",
    "gt.recipe.assembler": "GT 组装机",
    "gt.recipe.chemical_reactor": "GT 化学反应釜",
    "gt.recipe.blast_furnace": "GT 高炉",
    "gt.recipe.alloy_smelter": "GT 合金炉",
    "gt.recipe.extractor": "GT 提取机",
    "gt.recipe.fluid_extractor": "GT 流体提取机",
    "gt.recipe.fluid_solidifier": "GT 流体固化机",
    "gt.recipe.mixer": "GT 搅拌机",
    "gt.recipe.packager": "GT 打包机",
    "gt.recipe.unpackager": "GT 解包机",
    "gt.recipe.wiremill": "GT 线材轧机",
    "gt.recipe.bender": "GT 折弯机",
    "gt.recipe.lathe": "GT 车床",
    "gt.recipe.cutter": "GT 切割机",
    "gt.recipe.sifter": "GT 筛选机",
    "gt.recipe.arc_furnace": "GT 电弧炉",
    "gt.recipe.plasma_arc_furnace": "GT 等离子电弧炉",
}

# ---- 这条链里常见的非 GT 物品 -> 中文名（其余回退到 strId:meta）----
ITEM_NAMES = {
    "minecraft:brick_block:0": "砖块",
    "minecraft:brick:0": "红砖",
    "minecraft:water_bucket:0": "水桶",
    "minecraft:clay_ball:0": "黏土球",
    "minecraft:sand:0": "沙子",
    "dreamcraft:UnfiredClayBrick:0": "未烧制的黏土砖",
    "dreamcraft:WoodenBrickForm:0": "木制砖模具",
    "dreamcraft:dreamcraft_Concrete_bucket:0": "混凝土桶",
    "miscutils:itemDustGypsum:0": "石膏粉",
}


def item_key(stack):
    sid = stack.get("strId") or ("id:" + str(stack.get("id")))
    return "%s:%s" % (sid, stack.get("Damage", 0))


def recipe_key(rid):
    h = rid.get("handlerName", "?")
    r = item_key(rid["result"]) if rid.get("result") else "?"
    ings = sorted(item_key(i) for i in rid.get("ingredients", []))
    return "%s|%s|%s" % (h, r, ",".join(ings))


def friendly_handler(handler):
    if handler in HANDLER_NAMES:
        return HANDLER_NAMES[handler]
    if handler.startswith("gt.recipe."):
        return "GT 机器 · " + handler[len("gt.recipe."):]
    if handler.startswith("codechicken.nei.recipe."):
        return handler[len("codechicken.nei.recipe."):]
    return handler


def parse_bookmarks(path):
    items = []
    group_settings = {}
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line.strip():
                continue
            if line.startswith("; "):
                try:
                    obj = json.loads(line[2:])
                except Exception:
                    continue
                if isinstance(obj, dict) and "groups" in obj and isinstance(obj["groups"], dict):
                    for gid, g in obj["groups"].items():
                        if isinstance(g, dict):
                            group_settings[int(gid)] = g
                continue
            try:
                obj = json.loads(line)
            except Exception:
                continue
            if isinstance(obj, dict) and "item" in obj:
                items.append(obj)
    return items, group_settings


def build_tree(items):
    results = {}        # itemKey -> {factor, multiplier, recipeId}
    ingredients = defaultdict(list)   # recipeKey -> [{key, factor}]
    plain = []          # type 0 纯物品（临时目标/直接采集）

    for it in items:
        typ = it.get("type", 0)
        key = item_key(it["item"])
        rid = it.get("recipeId")
        factor = it.get("factor", 1) or 1
        if typ == 1 and rid:  # RESULT: 该物品由 rid 配方产出
            if key not in results:
                results[key] = {
                    "factor": factor,
                    "multiplier": it.get("multiplier", 1) or 1,
                    "recipeId": rid,
                }
        elif typ == 2 and rid:  # INGREDIENT: 该物品是 rid 配方的原料
            ingredients[recipe_key(rid)].append({"key": key, "factor": factor})
        else:
            plain.append({"key": key, "factor": factor})

    consumed = set()
    for lst in ingredients.values():
        for e in lst:
            consumed.add(e["key"])

    root_keys = [k for k in results if k not in consumed]
    if not root_keys:
        root_keys = list(results.keys())

    # 纯物品（type 0）没有配方，作为额外叶子目标
    extra_roots = [p["key"] for p in plain if p["key"] not in consumed]

    depth_guard = [0]

    def make_node(key, need, depth=0):
        node = {
            "id": key,
            "name": ITEM_NAMES.get(key, ""),
            "amount": need,
            "perCraft": None,   # 在父配方中每次消耗的数量
            "produces": 1,      # 自身配方单次产出的数量
            "runs": 1,
            "leftover": 0,
            "missing": 0,   # 配方里还有几格原料没在书签里记录
            "recipe": None,
            "children": [],
        }
        r = results.get(key)
        depth_guard[0] += 1
        if r and depth < 200:
            prod = r["factor"] or 1
            runs = max(1, -(-need // prod))  # ceil
            node["produces"] = prod
            node["runs"] = runs
            node["leftover"] = runs * prod - need
            node["recipe"] = {
                "handler": r["recipeId"].get("handlerName", ""),
                "machine": friendly_handler(r["recipeId"].get("handlerName", "")),
            }
            rk = recipe_key(r["recipeId"])
            kids = ingredients.get(rk, [])
            for ing in sorted(kids, key=lambda e: e["key"]):
                child_need = runs * ing["factor"]
                child = make_node(ing["key"], child_need, depth + 1)
                child["perCraft"] = ing["factor"]
                node["children"].append(child)
            slot_count = len(r["recipeId"].get("ingredients", []))
            tracked_slots = sum(ing["factor"] for ing in kids)
            if tracked_slots < slot_count:
                node["missing"] = slot_count - tracked_slots
        return node

    roots = []
    for k in root_keys:
        r = results[k]
        need = (r["multiplier"] or 1) * (r["factor"] or 1)
        roots.append(make_node(k, need))
    for k in extra_roots:
        roots.append(make_node(k, 1))
    return roots


def node_count(n):
    return 1 + sum(node_count(c) for c in n.get("children", []))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("bookmarks", help="bookmarks.ini 路径")
    ap.add_argument("outdir", nargs="?", default=None,
                    help="输出目录（默认 ./web）")
    args = ap.parse_args()

    if not os.path.isfile(args.bookmarks):
        print("找不到文件:", args.bookmarks, file=sys.stderr)
        sys.exit(1)

    items, group_settings = parse_bookmarks(args.bookmarks)
    if not items:
        print("bookmarks.ini 里没有书签条目（可能是空的）", file=sys.stderr)
        sys.exit(1)

    outdir = args.outdir or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "web")
    os.makedirs(outdir, exist_ok=True)

    roots = build_tree(items)
    doc = {
        "schemaVersion": 1,
        "source": "bookmarks.ini",
        "exportedAt": datetime.datetime.now().astimezone().isoformat(timespec="seconds"),
        "groups": [
            {"id": gid, "name": g.get("viewmode", "?"), "crafting": bool(g.get("crafting"))}
            for gid, g in sorted(group_settings.items())
        ],
        "roots": roots,
    }

    tree_json = os.path.join(outdir, "tree.json")
    with open(tree_json, "w", encoding="utf-8") as f:
        json.dump(doc, f, ensure_ascii=False, indent=2)

    data_js = os.path.join(outdir, "tree-data.js")
    with open(data_js, "w", encoding="utf-8") as f:
        f.write("// 由 bookmarks2tree.py 自动生成，双击 viewer.html 即可查看\n")
        f.write("window.TREE_DATA = ")
        json.dump(doc, f, ensure_ascii=False)
        f.write(";\n")

    total = sum(node_count(r) for r in roots)
    print("OK -> %s" % tree_json)
    print("OK -> %s" % data_js)
    print("根节点 %d 个，节点总数 %d" % (len(roots), total))
    print("打开 %s 即可查看" % os.path.join(outdir, "viewer.html"))


if __name__ == "__main__":
    main()
