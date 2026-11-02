# -*- coding: utf-8 -*-
"""HTTP regression against a running API. Optional writes are limited to temporary demo drafts."""
import argparse
from datetime import datetime, timedelta
import json
import os
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--base', default='http://127.0.0.1:15173/api/v1')
p.add_argument('--write-demo', action='store_true')
p.add_argument('--mysql', default='mysql')
p.add_argument('--database')
p.add_argument('--db-port', type=int, default=3306)
args = p.parse_args()
if args.write_demo and not re.fullmatch(r'[A-Za-z0-9_]+_demo', args.database or ''):
    raise SystemExit('Write checks require a dedicated --database ending in _demo')
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
checks = []

def call(path, token=None, method='GET', body=None, expected=200):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = urllib.request.Request(args.base + path, headers=headers, method=method,
        data=None if body is None else json.dumps(body).encode('utf-8'))
    try:
        response = opener.open(request, timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    data = json.load(response)
    assert response.code == expected, (path, response.code, data.get('code'))
    return data

def login(name):
    return call('/auth/login', method='POST', body={'username': name,
        'password': os.environ.get('DEMO_PASSWORD', '123')})['token']

def all_pages(path, token=None):
    result = []
    for page in range(1, 101):
        rows = call(path + ('&' if '?' in path else '?') + 'page=' + str(page), token)
        result.extend(rows)
        if len(rows) < 20:
            return result
    raise AssertionError('Too many pages for a demo test')

admin, user, manager = login('admin'), login('user'), login('manager1')
venues = call('/venues')
assert len(venues) >= 8 and len({v['category'] for v in venues}) >= 7
assert all(len(v['introduction']) >= 140 for v in venues)
checks.append('venue categories and enriched descriptions')
public = all_pages('/activities')
assert len(public) == call('/activities/count')['total']
for venue in venues:
    suffix = '?venueId=' + str(venue['id'])
    rows = all_pages('/activities' + suffix)
    assert all(a['venue_id'] == venue['id'] for a in rows)
    assert len(rows) == call('/activities/count' + suffix)['total']
    assert all(len(a['description']) >= 100 for a in rows)
checks.append('activity venue filters and matching pagination totals')
for token in (admin, manager):
    rows = all_pages('/admin/activities?q=', token)
    assert len(rows) == call('/admin/activities/count?q=', token)['total']
allowed = {v['id'] for v in call('/admin/me/venues', manager)}
for venue in venues:
    if venue['id'] not in allowed:
        assert call('/admin/activities/count?venueId=' + str(venue['id']), manager)['total'] == 0
        assert call('/admin/activities?venueId=' + str(venue['id']), manager) == []
call('/admin/activities/count', user, expected=403)
checks.append('admin count/list venue authorization and ordinary-user isolation')
assert len(all_pages('/me/participations', user)) == call('/me/participations/count', user)['total']
checks.append('my participation pagination total')

if args.write_demo:
    title = 'UI-REGRESSION-' + uuid.uuid4().hex
    activity_id = None
    client = [args.mysql, '--no-defaults', '--host=127.0.0.1', '--port=' + str(args.db_port),
        '--user=root', '--database=' + args.database, '--default-character-set=utf8mb4', '--batch', '--skip-column-names']
    try:
        activity_id = call('/admin/activities', admin, 'POST', {'venue_id': venues[0]['id'],
            'title': title, 'category': 'CULTURE', 'audience': 'ALL', 'description': 'Temporary API regression draft'})['id']
        # Verify API and cleanup client point to the same dedicated demo DB before creating sessions.
        assert subprocess.check_output(client + ['-e', "select count(*) from activity where id=" + str(activity_id) + " and title='" + title + "'"]).strip() == b'1'
        now = (datetime.utcnow() + timedelta(hours=8)).replace(microsecond=0, second=0)
        start = now + timedelta(days=2)
        fmt = lambda x: x.isoformat(timespec='minutes')
        payload = {'activity_id': activity_id, 'capacity': 37,
            'registration_start_at': fmt(now), 'registration_end_at': fmt(start-timedelta(hours=1)),
            'start_at': fmt(start), 'end_at': fmt(start+timedelta(hours=2)),
            'checkin_open_at': fmt(start-timedelta(minutes=30)), 'checkin_close_at': fmt(start+timedelta(hours=1))}
        for invalid in (dict(payload, capacity=0), dict(payload, capacity=1.5),
                dict(payload, capacity=2147483648), dict(payload, start_at='not-a-date'),
                dict(payload, registration_end_at=fmt(start+timedelta(hours=3)))):
            call('/admin/sessions', admin, 'POST', invalid, expected=400)
        missing = dict(payload)
        del missing['end_at']
        call('/admin/sessions', admin, 'POST', missing, expected=400)
        sid = call('/admin/sessions', admin, 'POST', payload)['id']
        stored = call('/admin/activities/' + str(activity_id) + '/sessions', admin)[0]
        assert stored['id'] == sid and stored['capacity'] == 37
        assert stored['start_at'][:16] == payload['start_at']
        payload.update(id=sid, capacity=45, start_at=fmt(start+timedelta(minutes=15)))
        call('/admin/sessions', admin, 'POST', payload)
        stored = call('/admin/activities/' + str(activity_id) + '/sessions', admin)[0]
        assert stored['capacity'] == 45 and stored['start_at'][:16] == payload['start_at']
        checks.append('real MySQL session create/edit dates and capacity; invalid/missing input is 400')
    finally:
        if activity_id:
            aid = str(int(activity_id))
            cleanup = "START TRANSACTION; DELETE x FROM activity_session x JOIN activity a ON a.id=x.activity_id WHERE a.id=" + aid + " AND a.title='" + title + "' AND x.status='DRAFT' AND x.occupied_count=0 AND NOT EXISTS(select 1 from participation p where p.session_id=x.id); DELETE FROM activity WHERE id=" + aid + " AND title='" + title + "' AND status='DRAFT' AND NOT EXISTS(select 1 from activity_session x where x.activity_id=" + aid + "); COMMIT;"
            subprocess.run(client + ['-e', cleanup], check=True)
            assert subprocess.check_output(client + ['-e', "select count(*) from activity where title='" + title + "'"]).strip() == b'0'
            checks.append('temporary regression drafts cleaned up')

print(json.dumps({'passed': True, 'checks': checks, 'realModelCalled': False}, ensure_ascii=True, indent=2))
