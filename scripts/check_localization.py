"""Read-only key and printf-argument validation for every localized string resource."""
from pathlib import Path
from collections import Counter
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1] / 'app/src/main/res'
folders = ['values-en', 'values-ru', 'values-b+zh+Hans', 'values-fa']
format_token = re.compile(r'%(?:(\d+)\$)?[-#+0,(]*\d*(?:\.(\d+))?([dsf%])')


def signature(value):
    sequential = 0
    arguments = Counter()
    for token in format_token.finditer(value):
        position, precision, conversion = token.groups()
        if conversion == '%':
            arguments['literal-percent'] += 1
            continue
        if position is None:
            sequential += 1
            position = str(sequential)
        arguments[(position, precision, conversion)] += 1
    return arguments


def read(folder):
    result = {}
    optional = set()
    for path in sorted((root / folder).glob('*.xml')):
        for node in ET.parse(path).getroot():
            if node.tag not in {'string', 'plurals', 'string-array'}:
                continue
            key = (node.tag, node.attrib['name'])
            assert key not in result, f'Duplicate key: {folder}/{key[1]}'
            if node.tag == 'string':
                values = {None: ''.join(node.itertext())}
            else:
                values = {child.attrib.get('quantity', str(index)): ''.join(child.itertext())
                          for index, child in enumerate(node)}
            assert values, f'Empty resource: {folder}/{key[1]}'
            for value in values.values():
                assert value.strip(), f'Empty string: {folder}/{key[1]}'
            result[key] = values
            if node.attrib.get('translatable') == 'false':
                optional.add(key)
    return result, optional


original, optional = read('values')
required = original.keys() - optional
checked = 0
for folder in folders:
    translated, _ = read(folder)
    missing = required - translated.keys()
    unknown = translated.keys() - original.keys()
    assert not missing, f'Missing keys in {folder}: {sorted(missing)}'
    assert not unknown, f'Unknown keys in {folder}: {sorted(unknown)}'
    for key in required:
        source = original[key]
        target = translated[key]
        if key[0] == 'plurals':
            assert 'other' in target, f'Missing plural other: {folder}/{key[1]}'
        else:
            assert source.keys() == target.keys(), f'Item count differs: {folder}/{key[1]}'
        for item, value in target.items():
            baseline = source.get(item, source.get('other'))
            assert baseline is not None, f'Unexpected item: {folder}/{key[1]}/{item}'
            assert signature(value) == signature(baseline), f'Format arguments: {folder}/{key[1]}/{item}'
            checked += 1
print(f'Validated {checked} localized strings across all resource XML files in EN, RU, zh-Hans and fa.')
