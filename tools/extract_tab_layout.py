#!/usr/bin/env python3
"""Extract OrcaSlicer's settings layout from the desktop app's Tab.cpp.

Records, for the Process, Filament and Printer tabs, every page, option group
and option in the order the desktop app shows them:

    {"source": "<upstream commit>",
     "tabs": {"process": [{"page": "Quality", "groups": [{"group": "Layer height",
                                                           "options": ["layer_height", ...]}]}],
              "filament": [...], "printer": [...]}}

Options are found in the forms Tab.cpp uses to place them on a page:
``append_single_option_line("key")``, ``get_option("key")`` (an option added to
a custom line) and ``create_single_option_line("key")``. Indexed keys such as
``"nozzle_diameter#0"`` keep their base name. Commented-out lines are skipped.

The app shows this layout and adds, on an "Other" page, any option of the
preset's own list (Preset::print_options() etc.) that the layout does not
place, so nothing the engine accepts is left out.

Lists returned by helper functions in libslic3r/PublishSettings.cpp (such as
the extruder retraction options) are read from that file, next to Tab.cpp's
source tree.

Usage: extract_tab_layout.py <path/to/Tab.cpp> <out.json> [upstream-commit]
"""
import json
import os
import re
import sys

# Which functions build which tab. TabPrinter builds its pages in helpers.
TAB_FUNCTIONS = {
    'process': ['TabPrint::build'],
    'filament': ['TabFilament::build', 'TabFilament::add_filament_overrides_page'],
    'printer': ['TabPrinter::build_fff', 'TabPrinter::build_kinematics_page', 'TabPrinter::build_unregular_pages'],
}

FUNCTION = re.compile(r'^(?:void|PageShp|bool)\s+(Tab\w+::\w+)\s*\(')
PAGE = re.compile(r'add_options_page\(\s*L\("([^"]+)"')
GROUP = re.compile(r'new_optgroup\(\s*L\("([^"]+)"')
OPTION = re.compile(
    r'(?:append_single_option_line|get_option|create_single_option_line|append_option_line\(\s*optgroup\s*,)'
    r'\(?\s*"([a-z0-9_]+)(?:#\d+)?"(?!\s*\+)')
# A named list of option names or name parts: std::vector<std::string> axes{ "x", "y" };
NAMED_LIST = re.compile(r'std::vector<std::string>\s+(\w+)\s*\{([^}]*)\}')
# for (const std::string &axis : axes)
FOR_NAMED = re.compile(r'for\s*\(\s*const\s+std::string\s*&?\s*(\w+)\s*:\s*(\w+)\s*\)')
# for (const std::string opt_key : { "a", "b", ... }) - the list may span lines
FOR_INLINE = re.compile(r'for\s*\(\s*const\s+std::string\s*&?\s*\w+\s*:\s*\{')
# append_option_line(optgroup, "prefix_" + axis, ...) or append_option_line(optgroup, axis, ...)
LOOP_OPTION = re.compile(r'append_option_line\(\s*optgroup\s*,\s*(?:"([a-z0-9_]+)"\s*\+\s*)?(\w+)\s*[,)]')
QUOTED = re.compile(r'"([a-z0-9_]+)"')
# for (const PublishablePrinterOption& opt : publishable_printer_retraction_options())
FOR_FUNCTION = re.compile(r'for\s*\([^:]+:\s*(\w+)\(\)\s*\)')


def helper_lists(tab_cpp: str) -> dict:
    """Option lists that helper functions in libslic3r/PublishSettings.cpp return, by function name."""
    path = os.path.join(os.path.dirname(tab_cpp), '..', '..', 'libslic3r', 'PublishSettings.cpp')
    if not os.path.exists(path):
        return {}
    with open(path, encoding='utf-8') as f:
        source = f.read()
    lists = {}
    for m in re.finditer(r'&\s*(\w+)\(\)\s*\{', source):
        end = source.find('\n}', m.end())
        lists[m.group(1)] = [k for k in QUOTED.findall(source[m.end():end]) if '_' in k or k.isalpha()]
    return lists


HELPER_LISTS = {}


def function_bodies(source: str) -> dict:
    """Map 'Class::method' to the text of its body, for functions defined at column 0."""
    lines = source.splitlines()
    bodies, i = {}, 0
    while i < len(lines):
        m = FUNCTION.match(lines[i])
        if not m:
            i += 1
            continue
        name, depth, body, started = m.group(1), 0, [], False
        while i < len(lines):
            line = lines[i]
            body.append(line)
            depth += line.count('{') - line.count('}')
            started = started or '{' in line
            i += 1
            if started and depth <= 0:
                break
        bodies.setdefault(name, '\n'.join(body))
    return bodies


def parse(body: str, pages: list, seen: set) -> None:
    page = group = None
    named_lists = {name: QUOTED.findall(items) for name, items in NAMED_LIST.findall(body)}
    loop_values = {}           # loop variable -> the list it runs over
    inline_list = False        # inside "for (... : { ... })"

    def add(key):
        nonlocal page, group
        if key in seen or page is None:
            return
        if group is None:
            group = {'group': page['page'], 'options': []}
            page['groups'].append(group)
        group['options'].append(key)
        seen.add(key)

    for raw in body.splitlines():
        line = raw.strip()
        if line.startswith('//'):
            continue
        line = line.split('//', 1)[0]
        if m := PAGE.search(line):
            page = next((p for p in pages if p['page'] == m.group(1)), None)
            if page is None:
                page = {'page': m.group(1), 'groups': []}
                pages.append(page)
            group = None
        if m := GROUP.search(line):
            if page is None:
                page = {'page': 'General', 'groups': []}
                pages.append(page)
            group = {'group': m.group(1), 'options': []}
            page['groups'].append(group)
        if FOR_INLINE.search(line) or inline_list:
            inline_list = '})' not in line
            for key in QUOTED.findall(line.split(':', 1)[-1] if FOR_INLINE.search(line) else line):
                add(key)
            continue
        if m := FOR_FUNCTION.search(line):
            for key in HELPER_LISTS.get(m.group(1), []):
                add(key)
            continue
        if m := FOR_NAMED.search(line):
            loop_values[m.group(1)] = named_lists.get(m.group(2), [])
        for prefix, var in LOOP_OPTION.findall(line):
            for value in loop_values.get(var, []):
                add((prefix or '') + value)
        for key in OPTION.findall(line):
            add(key)


def extract(source: str) -> dict:
    bodies = function_bodies(source)
    tabs = {}
    for tab, functions in TAB_FUNCTIONS.items():
        pages, seen = [], set()
        for function in functions:
            if function not in bodies:
                raise SystemExit(f'{function} not found in Tab.cpp: update TAB_FUNCTIONS')
            parse(bodies[function], pages, seen)
        # Drop groups and pages that ended up empty (widgets without options).
        for page in pages:
            page['groups'] = [g for g in page['groups'] if g['options']]
        tabs[tab] = [p for p in pages if p['groups']]
    return tabs


def main() -> None:
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    HELPER_LISTS.update(helper_lists(sys.argv[1]))
    with open(sys.argv[1], encoding='utf-8') as f:
        tabs = extract(f.read())
    layout = {'source': sys.argv[3] if len(sys.argv) > 3 else '', 'tabs': tabs}
    with open(sys.argv[2], 'w', encoding='utf-8') as f:
        json.dump(layout, f, indent=1, ensure_ascii=False)
        f.write('\n')
    for tab, pages in tabs.items():
        count = sum(len(g['options']) for p in pages for g in p['groups'])
        print(f'{tab}: {len(pages)} pages, {count} options', file=sys.stderr)


if __name__ == '__main__':
    main()
