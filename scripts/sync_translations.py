"""Validate translations and regenerate typed Android resource accessors. No network needed."""
from pathlib import Path
from collections import Counter
import json
import re
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/danieldk/splatevpn/ui/theme/AppStrings.kt"
source = SOURCE.read_text(encoding="utf-8")
english = source[source.index("class EnglishAppStrings"):]
strings = {key: json.loads(value) for key, value in re.findall(
    r'override val (\w+)(?:: String)?\s*=\s*("(?:\\.|[^"\\])*")', english)}
russian_source = source[source.index("class RussianAppStrings"):source.index("class EnglishAppStrings")]
russian = {key: json.loads(value) for key, value in re.findall(
    r'override val (\w+)(?:: String)?\s*=\s*("(?:\\.|[^"\\])*")', russian_source)}
assert strings.keys() == russian.keys(), "Russian resource keys differ from English"
translations = {}
for line in (ROOT / "translations/app-zh-fa.psv").read_text(encoding="utf-8-sig").splitlines():
    key, zh, fa = line.split("|", 2)
    assert key not in translations, f"Duplicate: {key}"
    translations[key] = (zh.replace(r"\n", "\n"), fa.replace(r"\n", "\n"))
assert strings.keys() == translations.keys(), (strings.keys() - translations.keys(), translations.keys() - strings.keys())
for key, english in strings.items():
    placeholders = Counter(re.findall(r"%(?:\d+\$)?[dsf%]", english))
    for translated in translations[key]:
        assert placeholders == Counter(re.findall(r"%(?:\d+\$)?[dsf%]", translated)), f"Placeholders: {key}"

def xml(value):
    escaped = escape(value).replace("'", r"\'").replace('"', r'\"').replace("\n", r"\n")
    return '"' + escaped + '"' if value != value.strip() else escaped

for column, folder in [(None, "values"), (None, "values-en"), ("ru", "values-ru"), (0, "values-b+zh+Hans"), (1, "values-fa")]:
    values = {key: value if column is None else russian[key] if column == "ru" else translations[key][column] for key, value in strings.items()}
    path = ROOT / "app/src/main/res" / folder / "app_strings.xml"
    path.parent.mkdir(exist_ok=True)
    path.write_text('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n' + ''.join(
        f'    <string name="app_{key.lower()}" formatted="false">{xml(value)}</string>\n' for key, value in values.items()) + '</resources>\n', encoding="utf-8")

output = ROOT / "app/src/main/java/com/danieldk/splatevpn/ui/theme/ResourceAppStrings.kt"
output.write_text('''// Generated from AppStrings.kt and translations/app-zh-fa.psv by scripts/sync_translations.py.
package com.danieldk.splatevpn.ui.theme

import android.content.res.Resources
import com.danieldk.splatevpn.R

internal class ResourceAppStrings(internal val resources: Resources) : AppStrings {
''' + ''.join(f'    override val {key}: String get() = resources.getString(R.string.app_{key.lower()})\n'
    for key in strings) + '}\n', encoding="utf-8")
print(f"Validated {len(strings)} keys and format placeholders for Chinese and Persian.")
