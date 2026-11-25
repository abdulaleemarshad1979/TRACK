"""Parse RMC + Gram Panchayat BB-scheme workbooks into private station records and reconcile counts.
Run: import-booths.py --rmc FILE --panchayat FILE --output DIR. Writes booth-config.json + booth-report.json.
Output uses the same `stations` shape as import-election.py; no coordinates exist in these workbooks.
"""
import argparse, hashlib, json, re, collections
from pathlib import Path
import openpyxl

def clean(v): return re.sub(r'\s+', ' ', str(v if v is not None else '')).strip()
def num(v):
 m = re.search(r'\d+', clean(v)); return int(m.group()) if m else None
def key(value): return 'e26_b_' + hashlib.sha256(value.encode()).hexdigest()[:20]
def cat(v):
 v = clean(v).upper(); return v if v in ('HS', 'S', 'N') else ''
def route_no(row, c):  # 'Route No.' label cell, number two cells right
 return num(row[c + 3]) if c + 3 < len(row) else None

# (label col, ps col, area col or None, name col, cat col, count col, booths col, voters col)
LAYOUT = {
 'RMC': dict(label=0, sno=0, area=1, ps=2, gp=None, name=3, cat=4, cnt=5, booths=6, voters=7, sheet='ROUTES BB ', abstract='RMC - ABSTRACT', election='RMC 2026'),
 'PANCHAYAT': dict(label=1, sno=1, area=None, ps=2, gp=3, name=4, cat=5, cnt=6, booths=7, voters=8, sheet=None, abstract='Gen Info Abstract', election='Panchayat 2026'),
}

MANDAL = {'Routes Rjy Rur': 'Rajahmundry Rural', 'Routes KDM': 'Kadiyam', 'Routes CHA': 'Chagallu', 'Routes KVR': 'Kovvur', 'Routes Talla': 'Tallapudi'}
def cell(row, i): return row[i] if i is not None and i < len(row) else None

def parse(path, kind, sheets):
 L = LAYOUT[kind]; wb = openpyxl.load_workbook(path, read_only=True, data_only=True)
 out, route, last_ps, last_area, last_gp, warnings, seen = [], None, '', '', '', [], set()
 for sheet in sheets:
  last_ps = last_area = last_gp = ''
  for rn, row in enumerate(wb[sheet].iter_rows(values_only=True), 1):
   if clean(cell(row, L['label'])).lower().startswith('route no'):
    route = route_no(row, L['label']); last_area = last_gp = ''; continue
   sno = clean(cell(row, L['sno']))
   name = clean(cell(row, L['name'])); count = num(cell(row, L['cnt']))
   booths_raw = clean(cell(row, L['booths']))
   # source sometimes omits S.No.; a booth-number list still marks a real location row
   if not (sno.isdigit() or (not sno and booths_raw)) or route is None or not name or name.isdigit() or count is None: continue
   if clean(cell(row, L['ps'])): last_ps = clean(cell(row, L['ps']))   # PS/ward/GP merged down in source
   if L['area'] is not None and clean(cell(row, L['area'])): last_area = clean(cell(row, L['area']))
   if L['gp'] is not None and clean(cell(row, L['gp'])): last_gp = clean(cell(row, L['gp']))
   c = cat(cell(row, L['cat']))
   if not c: warnings.append({'sheet': sheet, 'row': rn, 'issue': 'category not HS/S/N', 'value': clean(cell(row, L['cat']))})
   booths = clean(cell(row, L['booths']))
   rec = {'el': L['election'], 'ps': last_ps, 'route': f'Route {route}', 'name': name, 'cat': c, 'count': count,
          'booths': booths, 'voters': num(cell(row, L['voters'])) or 0, 'sources': [{'file': Path(path).name, 'sheet': sheet, 'row': rn}]}
   if last_area: rec['ward'] = last_area
   if last_gp: rec['village'] = last_gp.split('(')[0].strip()
   if kind == 'PANCHAYAT': rec['mandal'] = MANDAL[sheet]
   sid = key('|'.join([kind, sheet, str(route), last_ps, last_gp, name, booths]))
   if sid in seen: warnings.append({'sheet': sheet, 'row': rn, 'issue': 'duplicate row skipped'}); continue
   seen.add(sid); out.append((sid, rec))
 return out

def main():
 p = argparse.ArgumentParser(); p.add_argument('--rmc', type=Path, required=True); p.add_argument('--panchayat', type=Path, required=True); p.add_argument('--output', type=Path, required=True)
 a = p.parse_args()
 rmc = parse(a.rmc, 'RMC', ['ROUTES BB '])
 pan = parse(a.panchayat, 'PANCHAYAT', ['Routes Rjy Rur', 'Routes KDM', 'Routes CHA', 'Routes KVR', 'Routes Talla'])
 report = {}
 for kind, rows, claim in (('RMC', rmc, (207, 494, 463322)), ('PANCHAYAT', pan, (194, 706, 235109))):
  recs = [r for _, r in rows]
  report[kind] = {'locations': len(recs), 'booths': sum(r['count'] for r in recs), 'voters': sum(r['voters'] for r in recs),
                  'expected': dict(zip(('locations', 'booths', 'voters'), claim)),
                  'byPS': {ps: [sum(1 for r in recs if r['ps'] == ps), sum(r['count'] for r in recs if r['ps'] == ps)] for ps in sorted({r['ps'] for r in recs})},
                  'boothNumberListMismatch': [r['sources'][0] for r in recs if r['booths'] and len([x for x in re.split(r'[,;]', r['booths']) if x.strip()]) != r['count']][:50]}
 a.output.mkdir(parents=True, exist_ok=True)
 (a.output / 'booth-config.json').write_text(json.dumps({'stations': dict(rmc + pan)}, indent=1, ensure_ascii=False) + '\n')
 (a.output / 'booth-report.json').write_text(json.dumps(report, indent=1, ensure_ascii=False) + '\n')
 print(json.dumps(report, indent=1, ensure_ascii=False))

if __name__ == '__main__': main()
