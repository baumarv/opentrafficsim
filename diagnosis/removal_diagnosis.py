"""Deletion diagnosis: join the removed vehicles of a run with their trajectories on L5a and the upstream detector speed.

Usage: removal_diagnosis.py <run folder> <output csv>
The run must have sampled L5a (-Dmirova.samplerLinks=L5a) and written removed_vehicles.csv.
"""
import sys, io, csv, zipfile, collections, statistics
from pathlib import Path

run = Path(sys.argv[1])          # run folder with sampler on L5a
out_csv = Path(sys.argv[2])
SINK_FROM_END = 50.0
V_BREAKDOWN = 86.2                # km/h

removed = list(csv.DictReader(open(run / 'removed_vehicles.csv', encoding='utf-8')))
ids = {r['gtu_id'] for r in removed}

def zmember(part, suffix):
    f = [p for p in run.iterdir() if part in p.name and p.suffix == '.zip'][0]
    z = zipfile.ZipFile(f)
    return io.TextIOWrapper(z.open([n for n in z.namelist() if n.endswith(suffix)][0]), encoding='utf-8')

# detector speeds: all-vehicle rows of det_L5a.* per minute
det = collections.defaultdict(dict)
for r in csv.DictReader(zmember('detector_periodic', '.csv')):
    if r['GTU type'] == '' and r['id'].startswith('det_L5a'):
        det[r['id']][float(r['t'])] = (float(r['q']), float(r['vHarm']))
pos = {r['id']: (r['laneId'], float(r['position'])) for r in csv.DictReader(zmember('detector_positions', '.csv'))
       if r['linkId'] == 'L5a'}

# trajectories on L5a: all vehicles (for the totals) and the removed ones in full
traj = collections.defaultdict(list)
last_x = {}
lanes_seen = collections.defaultdict(set)
lane_len = collections.defaultdict(float)
lanes_on_link = {'Lane1', 'Lane2'}
for r in csv.DictReader(zmember('sampler', '.csv')):
    if r['linkId'] != 'L5a':
        continue
    g = r['gtuId']
    x = float(r['x'])
    lane_len[r['laneId']] = max(lane_len[r['laneId']], x)
    last_x[g] = max(last_x.get(g, 0.0), x)
    lanes_seen[g].add(r['laneId'])
    if g in ids:
        traj[g].append((float(r['t']), r['laneId'], x, float(r['v'])))

print('vehicles seen on L5a:', len(last_x), ' removed:', len(removed))
# A loop detector sits at half the lane length, so twice its position is the lane length; the sinks are 50 m before the end.
lane_length = {lane: 2.0 * x for lane, x in pos.values() if lane in lanes_on_link}
sink = {lane: length - SINK_FROM_END for lane, length in lane_length.items()}
print('lane lengths of L5a:', lane_length, ' sink positions:', sink)

rows = []
for r in removed:
    g = r['gtu_id']
    tr = sorted(traj.get(g, []))
    t_rem = float(r['sim_time_s'])
    minute = (t_rem // 60) * 60
    # nearest upstream detector: the L5a detector (mid-link) of the lane the vehicle is nearest to
    cand = [d for d in det if d.endswith(r['lane_id'])] or sorted(det)
    v_det = det[cand[0]].get(minute, (float('nan'), float('nan')))[1]
    breakdown = (v_det == v_det) and v_det < V_BREAKDOWN
    # the sampler records a vehicle on both lanes while it changes lane: the last such sample is where the change ends
    by_time = collections.defaultdict(list)
    for t, lane, x, v in tr:
        by_time[t].append((lane, x))
    both = [(t, max(x for _, x in s)) for t, s in by_time.items() if len(s) > 1]
    x_change = max(both)[1] if both else float('nan')
    final_lane = tr[-1][1] if tr else ''
    s_pos = sink.get(final_lane, float('nan'))
    past = [s for s in tr if s[2] > s_pos]
    rows.append({
        'gtu_id': g, 'gtu_type': r['gtu_type'], 'sim_time_s': r['sim_time_s'], 'route_destination': r['route_destination'],
        'roaming': r['roaming'], 'link_id': r['link_id'], 'lane_id': r['lane_id'], 'position_m': r['position_m'],
        'speed_mps': r['speed_mps'], 'cause': r['exception'].split('.')[-1] + ' at ' + r['thrown_at'],
        'trace_hash': r['trace_hash'], 'upstream_detector': cand[0],
        'upstream_detector_v_kmh': '%.1f' % v_det, 'in_breakdown_interval': str(breakdown).lower(),
        'final_lane_on_L5a': final_lane, 'sink_position_m': '%.2f' % s_pos,
        'lane_change_ends_at_x_m': '%.1f' % x_change, 'lane_change_end_to_sink_m': '%.1f' % (s_pos - x_change),
        'samples_past_sink': str(len(past)), 'last_sample_x_m': ('%.1f' % tr[-1][2]) if tr else '',
    })

with open(out_csv, 'w', newline='', encoding='utf-8') as f:
    w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
    w.writeheader()
    w.writerows(rows)

c = collections.Counter
print('cause:', c(r['cause'] for r in rows))
print('in breakdown interval:', c(r['in_breakdown_interval'] for r in rows))
print('final lane on L5a:', c(r['final_lane_on_L5a'] for r in rows))
d = [float(r['lane_change_end_to_sink_m']) for r in rows]
print('distance from the end of the lane change to the sink [m]: min %.1f median %.1f max %.1f' % (min(d), statistics.median(d), max(d)))
print('samples past the sink per removed vehicle:', c(r['samples_past_sink'] for r in rows))
beyond = [g for g, x in last_x.items() if x > max(sink.values()) + 1.0]
print('vehicles sampled more than 1 m past a sink position:', len(beyond), '(of these removed: %d)' % len([g for g in beyond if g in ids]))
