"""Compare a private Rust candidate with Spring Query using migration fixtures.
This script is a development harness; candidate traffic must stay private.
"""
import json,csv,httpx
api=httpx.Client(base_url='https://verginjose.cloud',timeout=30)
reader=csv.DictReader(open('/tmp/pfa-native-golden-passed-users.csv'));credentials=list(reader)
client=httpx.Client(timeout=40)
def canonical(v,path=''):
 if isinstance(v,dict):return {k:canonical(x,k) for k,x in v.items()}
 if isinstance(v,list):
  values=[canonical(x,path) for x in v]
  if values and all(isinstance(x,dict) and 'id' in x for x in values):
   if path in ('content','recentTransactions'):values.sort(key=lambda x:(x.get('createdAt',''),x['id']),reverse=True)
   else:values.sort(key=lambda x:x['id'])
  return values
 return v
fail=[];count=0
for account in credentials[:2]:
 r=api.post('/api/auth/login',json={'email':account['email'],'password':account['password']});r.raise_for_status();uid=r.json()['userId'];h={'X-User-Id':uid}
 paths=['/upsert/summary','/upsert/entries','/upsert/search?q=Netflix','/upsert/recurring','/upsert/goals','/upsert/budgets','/upsert/subscriptions','/analytics/category-pie-chart','/analytics/timeline-chart','/analytics/timeline-chart?timelineType=WEEKLY','/analytics/comprehensive','/analytics/budgets/trends','/upsert/groups','/upsert/groups/shared-people']
 old=client.get('http://127.0.0.1:28084/upsert/groups',headers=h).json()
 for g in old:
  for suffix in ['', '/members','/expenses','/balances','/activity']:paths.append('/upsert/groups/'+str(g['id'])+suffix)
 for goal in client.get('http://127.0.0.1:28084/upsert/goals',headers=h,params={'userId':uid}).json():
  paths += [f"/upsert/goals/{goal['id']}/transactions", f"/analytics/goals/{goal['id']}/forecast"]
 for path in paths:
  sep='&' if '?' in path else '?';path+=sep+'userId='+uid
  a=client.get('http://127.0.0.1:28084'+path,headers=h);b=client.get('http://127.0.0.1:28085'+path,headers=h);count+=1
  if a.status_code!=b.status_code or (a.status_code==200 and canonical(a.json())!=canonical(b.json())):
   fail.append({'path':path,'old_status':a.status_code,'new_status':b.status_code,'old':a.text[:12000],'new':b.text[:12000]})
print('Compared',count,'responses; differences',len(fail))
for f in fail:print(f['path'].split('?')[0],f['old_status'],f['new_status'],f['new'][:180])
json.dump(fail,open('/tmp/pfa-query-parity-differences.json','w'),indent=2)
