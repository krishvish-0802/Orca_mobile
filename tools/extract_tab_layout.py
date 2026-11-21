#!/usr/bin/env python3
"""Extract the settings-page layout from OrcaSlicer's desktop Tab.cpp.

Prototype written during the upstream analysis. It walks each ``Tab*::build()``
body and records ``add_options_page`` -> ``new_optgroup`` ->
``append_single_option_line`` calls as JSON:

    {"TabPrint": [{"page": "Quality", "groups": [{"group": "Layer height",
                    "options": ["layer_height", ...]}]}]}

Known gaps (to close before this becomes a build step): TabPrinter builds its
pages in helper functions, and some filament lines use other append forms.

Usage: extract_tab_layout.py <path/to/Tab.cpp> <out.json>
"""
import json
import re
import sys

BUILD_FN = re.compile(r'void (Tab\w+)::build\(\)\s*\{')
PAGE = re.compile(r'add_options_page\(\s*L\("([^"]+)"')
GROUP = re.compile(r'new_optgroup\(\s*L\("([^"]+)"')
OPTION = re.compile(r'append_single_option_line\(\s*"([a-z0-9_#]+)"')


def function_body(src: str, start: int) -> str:
    """Return the text between the opening brace at ``start - 1`` and its match."""
    depth, i = 1, start
    while depth and i < len(src):
        depth += {'{': 1, '}': -1}.get(src[i], 0)
        i += 1
    return src[start:i]


def parse_tab(body: str) -> list:
    pages, page, group = [], None, None
    for line in body.splitlines():
        if m := PAGE.search(line):
            page = {'page': m.group(1), 'groups': []}
            pages.append(page)
            group = None
        elif (m := GROUP.search(line)) and page is not None:
            group = {'group': m.group(1), 'options': []}
            page['groups'].append(group)
        elif group is not None:
            group['options'].extend(OPTION.findall(line))
    return pages


def main(tab_cpp: str, out_json: str) -> None:
    with open(tab_cpp, encoding='utf-8') as f:
        src = f.read()
    tabs = {m.group(1): parse_tab(function_body(src, m.end())) for m in BUILD_FN.finditer(src)}
    with open(out_json, 'w', encoding='utf-8') as f:
        json.dump(tabs, f, indent=1)
    for name, pages in tabs.items():
        groups = sum(len(p['groups']) for p in pages)
        options = sum(len(g['options']) for p in pages for g in p['groups'])
        print(f"{name}: {len(pages)} pages, {groups} groups, {options} options")


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
