#!/usr/bin/env python3
"""Live native-edge contract/security smoke test; uses disposable test accounts.

python3 requests/test_native_migration.py --base-url http://localhost:3001
Optional --spring-session accepts the private session captured before migration.
Credentials and tokens are never included in the report.
"""
import argparse
import concurrent.futures
import json
import time
import uuid
import httpx


def run(base, spring_session=None):
    checks = []
    client = httpx.Client(base_url=base, timeout=30)

    def check(name, response, status=200):
        assert response.status_code == status, f'{name}: expected {status}, got {response.status_code}: {response.text[:300]}'
        checks.append(name)
        return response

    check('auth health', client.get('/api/auth/health'))
    check('anonymous ledger denied', client.get('/api/upsert/entries'), 401)
    check('spoofed identity denied', client.get('/api/upsert/entries', headers={'X-User-Id': str(uuid.uuid4())}), 401)
    check('internal auth endpoint hidden', client.get('/internal/verify'), 200)  # SPA fallback; must not authenticate
    assert 'text/html' in client.get('/internal/verify').headers.get('content-type', '')
    if spring_session:
        state = json.load(open(spring_session))
        check('Spring JWT accepted', client.get('/api/auth/me', headers={'Authorization': 'Bearer '+state['login']['token']}))
        check('Spring BCrypt accepted', client.post('/api/auth/login', json=state['credentials']))
        client.cookies.clear()
        check('Spring refresh accepted', client.post('/api/auth/refresh', json={'refreshToken':state['refresh']}))
        client.cookies.clear()

    users = []
    for _ in range(2):
        name = 'native_' + uuid.uuid4().hex[:12]
        credentials = {'email': name+'@example.invalid', 'username': name, 'password':'Native-Test-Only-123!', 'defaultCurrency':'INR', 'role':'ADMIN'}
        response = check('register', client.post('/api/auth/register', json=credentials), 201)
        assert response.json()['role'] == 'USER'
        check('duplicate account rejected', client.post('/api/auth/register', json=credentials), 400)
        response = check('login', client.post('/api/auth/login', json=credentials))
        assert response.json()['refreshToken'] is None
        cookie = response.cookies.get('pfa_refresh')
        assert cookie and 'HttpOnly' in response.headers['set-cookie'] and 'SameSite=Strict' in response.headers['set-cookie']
        users.append({'credentials':credentials, 'id':response.json()['userId'], 'token':response.json()['token'], 'refresh':cookie})
        client.cookies.clear()
    a,b = users
    auth = {'Authorization':'Bearer '+a['token']}
    other = {'Authorization':'Bearer '+b['token']}
    check('me', client.get('/api/auth/me', headers=auth))
    check('malformed UUID rejected',client.get('/api/upsert/entries?userId=bad',headers=auth),400)
    check('cross-account ledger denied',client.get('/api/upsert/entries',params={'userId':b['id']},headers={**auth,'X-User-Id':b['id']}),403)
    profile = check('peer profile privacy',client.get('/api/auth/users/'+b['id'],headers=auth)).json()
    assert profile['email'] is None
    check('user bulk', client.post('/api/auth/users/bulk',headers=auth,json=[a['id'],b['id']]))
    check('user search',client.get('/api/auth/users/search',headers=auth,params={'q':a['credentials']['username']}))
    check('stored markup rejected',client.put('/api/auth/profile',headers=auth,json={'profilePicture':'javascript:alert(1)'}),400)
    check('currency profile update',client.put('/api/auth/profile',headers=auth,json={'defaultCurrency':'USD'}))
    assert check('profile persistence',client.get('/api/auth/me',headers=auth)).json()['defaultCurrency']=='USD'

    # Each request carries the same old credential; all must converge to one pair.
    def refresh_once(_):
        return httpx.post(base+'/api/auth/refresh',json={'refreshToken':a['refresh']},timeout=30)
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        rotated=list(pool.map(refresh_once,range(4)))
    for response in rotated:check('concurrent refresh',response)
    assert len({r.json()['token'] for r in rotated})==1
    assert len({r.cookies.get('pfa_refresh') for r in rotated})==1
    a['token']=rotated[0].json()['token'];a['refresh']=rotated[0].cookies.get('pfa_refresh')
    auth={'Authorization':'Bearer '+a['token']}
    ticket=check('stream ticket',client.post('/api/auth/stream-ticket',headers=auth)).json()['ticket']
    check('stream ticket cannot authorize REST',client.get('/api/upsert/entries',headers={'Authorization':'Bearer '+ticket}),401)
    check('access token cannot act as query ticket',client.get('/api/upsert/notifications/stream',params={'ticket':a['token']}),401)
    with client.stream('GET','/api/upsert/notifications/stream',params={'ticket':ticket},timeout=10) as response:
        assert response.status_code==200 and 'text/event-stream' in response.headers.get('content-type','')
        next(response.iter_bytes())
        checks.append('SSE delivers first event')
    check('notification inbox',client.get('/api/upsert/notifications',headers=auth))

    png = bytes.fromhex('89504e470d0a1a0a') + b'native migration smoke payload'
    check('false image rejected',client.post('/api/receipt/upload/'+a['id'],headers=auth,files={'file':('fake.png',b'<script>alert(1)</script>','image/png')}),415)
    check('upload owner enforced',client.post('/api/receipt/upload/'+b['id'],headers=auth,files={'file':('receipt.png',png,'image/png')}),403)
    receipt=check('receipt upload',client.post('/api/receipt/upload/'+a['id'],headers=auth,files={'file':('receipt.png',png,'image/png')})).json()['url']
    response=check('private receipt download',client.get(receipt,headers=auth))
    assert response.content==png and 'nosniff' in response.headers.get('x-content-type-options','')
    check('receipt anonymous denied',client.get(receipt),401)
    check('receipt cross-user denied',client.get(receipt,headers=other),404)
    avatar=check('avatar upload',client.post('/api/profile/upload',headers=auth,files={'file':('avatar.png',png,'image/png')})).json()['url']
    response=check('public avatar download',client.get(avatar));assert response.content==png
    check('avatar profile update',client.put('/api/auth/profile',headers=auth,json={'profilePicture':avatar}))
    check('unknown bucket denied',client.get('/api/storage/private/nope',headers=auth),404)
    check('SVG upload denied',client.post('/api/profile/upload',headers=auth,files={'file':('avatar.svg',b'<svg/>','image/svg+xml')}),415)

    entry={'userId':a['id'],'name':'Native migration test','amount':12.34,'type':'EXPENSE','category':'OTHERS','currency':'INR','description':'Migration verification','receiptUrl':receipt}
    response=client.post('/api/upsert/create',headers={**auth,'Idempotency-Key':str(uuid.uuid4())},json=entry)
    assert response.status_code in (200,201),response.text
    checks.append('command write routing');entry_id=response.json()['id']
    for _ in range(30):
        response=client.get('/api/upsert/entries/'+str(entry_id),headers=auth,params={'userId':a['id']})
        if response.status_code==200:break
        time.sleep(.3)
    check('query read routing',response)
    check('analytics routing',client.get('/api/analytics/category-pie-chart',headers=auth,params={'userId':a['id']}))
    check('command delete routing',client.delete('/api/upsert/delete/'+str(entry_id),headers=auth,params={'userId':a['id']}))

    check('wrong old password rejected',client.post('/api/auth/change-password',headers=auth,json={'currentPassword':'wrong','newPassword':'Changed-Native-Test-123!'}),400)
    check('change password',client.post('/api/auth/change-password',headers=auth,json={'currentPassword':a['credentials']['password'],'newPassword':'Changed-Native-Test-123!'}))
    check('password change revokes access',client.get('/api/auth/me',headers=auth),401)
    check('password change revokes refresh grace',client.post('/api/auth/refresh',json={'refreshToken':a['refresh']}),401)
    check('password change revokes stream ticket',client.get('/api/upsert/notifications/stream',params={'ticket':ticket}),401)
    client.cookies.clear()
    check('old password rejected',client.post('/api/auth/login',json=a['credentials']),401)
    a['credentials']['password']='Changed-Native-Test-123!'
    response=check('new password login',client.post('/api/auth/login',json=a['credentials']))
    auth={'Authorization':'Bearer '+response.json()['token']};refresh=response.cookies.get('pfa_refresh');client.cookies.clear()
    check('logout',client.post('/api/auth/logout',headers=auth,json={'refreshToken':refresh}))
    check('logout revokes access',client.get('/api/auth/me',headers=auth),401)
    check('logout revokes refresh',client.post('/api/auth/refresh',json={'refreshToken':refresh}),401)
    return {'passed':len(checks),'checks':checks,'base_url':base}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url',default='http://localhost:3001')
    parser.add_argument('--spring-session')
    parser.add_argument('--out',default='/tmp/pfa-native-smoke.json')
    args=parser.parse_args()
    report=run(args.base_url.rstrip('/'),args.spring_session)
    with open(args.out,'w') as file:json.dump(report,file,indent=2)
    print(f"PASS: {report['passed']} live migration checks; report: {args.out}")
