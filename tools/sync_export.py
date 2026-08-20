#!/usr/bin/env python3
"""把游戏内 /treeexport 的产物同步到 web/ 目录，双击 viewer.html 即可查看（含图标）。

用法:
  python3 tools/sync_export.py "/Users/.../.minecraft/dumps" web

会做三件事:
  1. dumps/tree.json  -> web/tree.json
  2. dumps/icons/     -> web/icons/   （整体复制）
  3. 用 tree.json 重新生成 web/tree-data.js（双击 viewer.html 时内嵌的数据）
"""
import json
import os
import shutil
import sys
import argparse


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dumps_dir", help="游戏实例的 .minecraft/dumps 目录（含 tree.json 和 icons/）")
    ap.add_argument("web_dir", nargs="?", default=None, help="web 目录（默认 ../web）")
    args = ap.parse_args()

    dumps = args.dumps_dir
    web = args.web_dir or os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "web")
    os.makedirs(web, exist_ok=True)

    src_json = os.path.join(dumps, "tree.json")
    if not os.path.isfile(src_json):
        print("找不到 tree.json:", src_json, file=sys.stderr)
        print("请先在游戏里输入 /treeexport 生成导出文件。", file=sys.stderr)
        sys.exit(1)

    # 1) tree.json
    shutil.copy2(src_json, os.path.join(web, "tree.json"))

    # 2) icons
    src_icons = os.path.join(dumps, "icons")
    dst_icons = os.path.join(web, "icons")
    if os.path.isdir(src_icons):
        if os.path.isdir(dst_icons):
            shutil.rmtree(dst_icons)
        shutil.copytree(src_icons, dst_icons)
    else:
        print("警告: 没有 icons/ 目录（" + src_icons + "），网页将显示占位方块。", file=sys.stderr)

    # 3) tree-data.js（内嵌数据，双击 viewer.html 时用）
    with open(src_json, encoding="utf-8") as f:
        doc = json.load(f)
    with open(os.path.join(web, "tree-data.js"), "w", encoding="utf-8") as f:
        f.write("// 由 sync_export.py 从游戏内导出生成\nwindow.TREE_DATA = ")
        json.dump(doc, f, ensure_ascii=False)
        f.write(";\n")

    print("已同步到:", web)
    print("现在双击 %s 即可查看（含图标与物品名）。" % os.path.join(web, "viewer.html"))


if __name__ == "__main__":
    main()
