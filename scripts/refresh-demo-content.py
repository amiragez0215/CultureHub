# -*- coding: utf-8 -*-
"""Refresh only named synthetic catalog descriptions/categories, not reservations or dates.

Opt-in: overwrites any manual edits to these catalog descriptions in a dedicated _demo DB.
MYSQL_PWD can be used by the mysql client; credentials are never printed.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--mysql', default='mysql')
parser.add_argument('--database', required=True)
parser.add_argument('--port', type=int, default=3306)
parser.add_argument('--user', default='root')
args = parser.parse_args()
if not re.fullmatch(r'[A-Za-z0-9_]+_demo', args.database):
    raise SystemExit('Use a dedicated database ending in _demo')
root = Path(__file__).resolve().parents[1]
catalog = json.loads((root / 'culturehub-api/src/main/resources/demo/catalog.json').read_text(encoding='utf-8'))

def sql_text(value):
    # UTF-8 hex literals avoid shell quoting, SQL mode and escape differences.
    return "CONVERT(0x" + value.encode('utf-8').hex() + " USING utf8mb4) COLLATE utf8mb4_unicode_ci"

statements = ['START TRANSACTION;']
for venue in catalog['venues']:
    statements.append('UPDATE venue SET category=' + sql_text(venue['category']) +
        ',introduction=' + sql_text(venue['introduction']) + ' WHERE name=' + sql_text(venue['name']) + ';')
for activity in catalog['activities']:
    venue = catalog['venues'][activity['venue']]
    statements.append('UPDATE activity a JOIN venue v ON v.id=a.venue_id SET a.description=' +
        sql_text(activity['description']) + ',a.plan=' + sql_text(activity['plan']) +
        ',a.notice=' + sql_text(activity['notice']) + ' WHERE a.title=' + sql_text(activity['title']) +
        ' AND v.name=' + sql_text(venue['name']) + ';')
statements.append('COMMIT;')
subprocess.run([args.mysql, '--no-defaults', '--host=127.0.0.1', '--port=' + str(args.port),
    '--user=' + args.user, '--database=' + args.database, '--default-character-set=utf8mb4'],
    input='\n'.join(statements).encode('utf-8'), check=True)
print('Demo catalog refreshed: 8 venue descriptions/categories and 30 activity descriptions/notices; no participation or session changes.')
