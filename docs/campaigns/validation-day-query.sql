-- Which days qualify as a further out-of-sample day (C.2, step 2).
--
-- The criteria are docs/campaigns/validation-day-criteria.md, committed before this file; every number below is
-- one written there, and this query reads no other. Read-only: one SELECT, nothing written. PostgreSQL.
--
-- Result: one row per day of the population - excluded days included, so that the set can be audited - with each
-- criterion's value and verdict. `qualifies` marks the set; `rank_by_rule` numbers it chronologically, the
-- criteria's section 4: one day wanted, rank 1; n wanted, ranks 1 to n.
--
-- Before reading the candidates, check the query against what is known (criteria, section 3): on the excluded
-- study days, k3_episodes is 0 on 2025-09-22 and at least 1 on the fifteen others.
--
-- Tested only on a throwaway PostgreSQL 14 instance holding synthetic rows in the table's layout, never against the
-- detector database. Assumed there and not checked: `datum` is a timestamp without time zone in local time, and the
-- table holds one row per minute and position.

WITH params AS (
    SELECT 'AS Freiburg-Nord'::text AS knotenpunkt,
           'Karlsruhe'::text        AS fahrtrichtung,
           TIME '13:00'             AS t_from,           -- window start
           TIME '22:00'             AS t_to,             -- window end, exclusive: 540 minutes, 108 intervals
           TIME '13:45'             AS t_detect,         -- K3: the evaluation's 45-minute warm-up
           108                      AS n_intervals,
           540                      AS n_minutes,
           0                        AS k1_max_incomplete,
           0.95                     AS k2_min_coverage,
           86.2                     AS k3_v_crit,        -- km/h, study-wide, not refitted
           5.0                      AS k3_dv_min,        -- km/h
           3                        AS k3_min_intervals,
           1                        AS k3_min_episodes
),
excluded(day) AS (
    VALUES
        (DATE '2025-01-02'), (DATE '2025-01-03'), (DATE '2025-01-06'), (DATE '2025-01-07'), (DATE '2025-01-08'), (DATE '2025-01-09'),
        (DATE '2025-01-10'), (DATE '2025-01-12'), (DATE '2025-01-13'), (DATE '2025-01-14'), (DATE '2025-01-15'), (DATE '2025-01-16'),
        (DATE '2025-01-17'), (DATE '2025-01-20'), (DATE '2025-01-21'), (DATE '2025-01-22'), (DATE '2025-01-23'), (DATE '2025-01-24'),
        (DATE '2025-01-25'), (DATE '2025-01-27'), (DATE '2025-01-28'), (DATE '2025-01-29'), (DATE '2025-01-30'), (DATE '2025-01-31'),
        (DATE '2025-02-01'), (DATE '2025-02-02'), (DATE '2025-02-03'), (DATE '2025-02-04'), (DATE '2025-02-05'), (DATE '2025-02-06'),
        (DATE '2025-02-07'), (DATE '2025-02-08'), (DATE '2025-02-09'), (DATE '2025-02-10'), (DATE '2025-02-11'), (DATE '2025-02-12'),
        (DATE '2025-02-13'), (DATE '2025-02-14'), (DATE '2025-02-15'), (DATE '2025-02-17'), (DATE '2025-02-18'), (DATE '2025-02-19'),
        (DATE '2025-02-20'), (DATE '2025-02-21'), (DATE '2025-02-23'), (DATE '2025-02-24'), (DATE '2025-02-25'), (DATE '2025-02-26'),
        (DATE '2025-02-27'), (DATE '2025-02-28'), (DATE '2025-03-02'), (DATE '2025-03-03'), (DATE '2025-03-04'), (DATE '2025-03-05'),
        (DATE '2025-03-06'), (DATE '2025-03-07'), (DATE '2025-03-08'), (DATE '2025-03-09'), (DATE '2025-03-10'), (DATE '2025-03-11'),
        (DATE '2025-03-12'), (DATE '2025-03-13'), (DATE '2025-03-14'), (DATE '2025-03-15'), (DATE '2025-03-16'), (DATE '2025-03-17'),
        (DATE '2025-03-18'), (DATE '2025-03-19'), (DATE '2025-03-20'), (DATE '2025-03-21'), (DATE '2025-03-23'), (DATE '2025-03-24'),
        (DATE '2025-03-25'), (DATE '2025-03-26'), (DATE '2025-03-27'), (DATE '2025-03-28'), (DATE '2025-03-29'), (DATE '2025-03-30'),
        (DATE '2025-03-31'), (DATE '2025-04-01'), (DATE '2025-04-02'), (DATE '2025-04-03'), (DATE '2025-04-04'), (DATE '2025-04-05'),
        (DATE '2025-04-06'), (DATE '2025-04-07'), (DATE '2025-04-08'), (DATE '2025-04-09'), (DATE '2025-04-10'), (DATE '2025-04-11'),
        (DATE '2025-04-13'), (DATE '2025-04-14'), (DATE '2025-04-15'), (DATE '2025-04-16'), (DATE '2025-04-17'), (DATE '2025-04-18'),
        (DATE '2025-04-22'), (DATE '2025-04-23'), (DATE '2025-04-24'), (DATE '2025-04-25'), (DATE '2025-04-27'), (DATE '2025-04-28'),
        (DATE '2025-04-29'), (DATE '2025-05-02'), (DATE '2025-05-04'), (DATE '2025-05-05'), (DATE '2025-05-06'), (DATE '2025-05-07'),
        (DATE '2025-05-08'), (DATE '2025-05-09'), (DATE '2025-05-10'), (DATE '2025-05-13'), (DATE '2025-05-15'), (DATE '2025-05-16'),
        (DATE '2025-05-17'), (DATE '2025-05-19'), (DATE '2025-05-20'), (DATE '2025-05-21'), (DATE '2025-05-22'), (DATE '2025-05-23'),
        (DATE '2025-05-24'), (DATE '2025-05-25'), (DATE '2025-05-26'), (DATE '2025-06-11'), (DATE '2025-07-09'), (DATE '2025-09-15'),
        (DATE '2025-09-16'), (DATE '2025-09-17'), (DATE '2025-09-18'), (DATE '2025-09-19'), (DATE '2025-09-20'), (DATE '2025-09-21'),
        (DATE '2025-09-22'), (DATE '2025-09-23'), (DATE '2025-09-24'), (DATE '2025-09-25'), (DATE '2025-09-26'), (DATE '2025-10-01'),
        (DATE '2025-10-02'), (DATE '2025-10-07'), (DATE '2025-10-08'), (DATE '2025-10-09'), (DATE '2025-10-10'), (DATE '2025-10-13'),
        (DATE '2025-10-14'), (DATE '2025-10-15'), (DATE '2025-10-16'), (DATE '2025-10-17'), (DATE '2025-10-21'), (DATE '2025-10-22'),
        (DATE '2025-10-23'), (DATE '2025-10-24'), (DATE '2025-10-26'), (DATE '2025-10-27'), (DATE '2025-10-29'), (DATE '2025-10-30'),
        (DATE '2025-10-31')
),
holidays(day) AS (  -- public holidays in Baden-Wuerttemberg
    VALUES
        (DATE '2024-01-01'), (DATE '2024-01-06'), (DATE '2024-03-29'), (DATE '2024-04-01'), (DATE '2024-05-01'), (DATE '2024-05-09'),
        (DATE '2024-05-20'), (DATE '2024-05-30'), (DATE '2024-10-03'), (DATE '2024-11-01'), (DATE '2024-12-25'), (DATE '2024-12-26'),
        (DATE '2025-01-01'), (DATE '2025-01-06'), (DATE '2025-04-18'), (DATE '2025-04-21'), (DATE '2025-05-01'), (DATE '2025-05-29'),
        (DATE '2025-06-09'), (DATE '2025-06-19'), (DATE '2025-10-03'), (DATE '2025-11-01'), (DATE '2025-12-25'), (DATE '2025-12-26'),
        (DATE '2026-01-01'), (DATE '2026-01-06'), (DATE '2026-04-03'), (DATE '2026-04-06'), (DATE '2026-05-01'), (DATE '2026-05-14'),
        (DATE '2026-05-25'), (DATE '2026-06-04'), (DATE '2026-10-03'), (DATE '2026-11-01'), (DATE '2026-12-25'), (DATE '2026-12-26')
),
-- One row per minute, position and lane.
lane_minutes AS (
    SELECT d.position, d.datum::timestamp AS ts, d.datum::date AS day, l.fs, l.n, l.q, l.v
    FROM detektoren_autobahn_freiburg d
    CROSS JOIN params p
    CROSS JOIN LATERAL (VALUES
        (1, d.pruefziffer_fs1, d.q_kfz_fs1, d.v_kfz_fs1),
        (2, d.pruefziffer_fs2, d.q_kfz_fs2, d.v_kfz_fs2),
        (3, d.pruefziffer_fs3, d.q_kfz_fs3, d.v_kfz_fs3),
        (4, d.pruefziffer_fs4, d.q_kfz_fs4, d.v_kfz_fs4),
        (5, d.pruefziffer_fs5, d.q_kfz_fs5, d.v_kfz_fs5)
    ) AS l(fs, n, q, v)
    WHERE d.knotenpunkt = p.knotenpunkt
      AND d.fahrtrichtung = p.fahrtrichtung
      AND d.position IN ('Hauptfahrbahn', 'Einfahrt')
),
-- A lane of a position: measured at least once anywhere in the table.
lanes AS (
    SELECT position, fs
    FROM lane_minutes
    GROUP BY position, fs
    HAVING bool_or(coalesce(n, 0) >= 1)
),
lane_count AS (
    SELECT position, count(*) AS n_lanes FROM lanes GROUP BY position
),
-- The population: every day with a row at either position.
days AS (
    SELECT DISTINCT day FROM lane_minutes
),
in_window AS (
    SELECT m.*,
           date_trunc('hour', m.ts) + floor(extract(minute FROM m.ts) / 5) * INTERVAL '5 minutes' AS bucket
    FROM lane_minutes m
    JOIN lanes USING (position, fs)
    CROSS JOIN params p
    WHERE m.ts::time >= p.t_from AND m.ts::time < p.t_to
),
-- Five-minute aggregates per lane, formed as fetch.aggregate_detectors forms them: flow over the measured minutes,
-- speed weighted by flow over the minutes with flow and a speed.
lane_intervals AS (
    SELECT position, day, bucket, fs,
           sum(coalesce(n, 0)) AS n_sum,
           sum(CASE WHEN coalesce(n, 0) > 0 THEN coalesce(q, 0) ELSE 0 END)
               / nullif(sum(coalesce(n, 0)), 0) AS q_lane,
           sum(CASE WHEN coalesce(n, 0) > 0 AND q > 0 AND v IS NOT NULL THEN q * v END)
               / nullif(sum(CASE WHEN coalesce(n, 0) > 0 AND q > 0 AND v IS NOT NULL THEN q END), 0) AS v_lane
    FROM in_window
    GROUP BY position, day, bucket, fs
),
-- K1: incomplete intervals per position and day; complete = every lane of the position measured at least once.
k1 AS (
    SELECT d.day, lc.position,
           p.n_intervals - count(*) FILTER (WHERE c.lanes_measured = lc.n_lanes) AS incomplete
    FROM days d
    CROSS JOIN lane_count lc
    CROSS JOIN params p
    LEFT JOIN (
        SELECT position, day, bucket, count(*) FILTER (WHERE n_sum >= 1) AS lanes_measured
        FROM lane_intervals
        GROUP BY position, day, bucket
    ) c ON c.day = d.day AND c.position = lc.position
    GROUP BY d.day, lc.position, p.n_intervals
),
-- K2: measured lane-minutes over lanes x 540.
k2 AS (
    SELECT d.day, lc.position,
           coalesce(m.measured, 0)::numeric / (lc.n_lanes * p.n_minutes) AS coverage
    FROM days d
    CROSS JOIN lane_count lc
    CROSS JOIN params p
    LEFT JOIN (
        SELECT position, day, count(DISTINCT (fs, ts)) AS measured
        FROM in_window
        WHERE coalesce(n, 0) >= 1
        GROUP BY position, day
    ) m ON m.day = d.day AND m.position = lc.position
),
-- K3: the cross-section's speed at the Hauptfahrbahn, lane speeds weighted by lane flow, from the warm-up's end.
mainline AS (
    SELECT li.day, li.bucket,
           sum(li.q_lane * li.v_lane) FILTER (WHERE li.q_lane IS NOT NULL AND li.v_lane IS NOT NULL)
               / nullif(sum(li.q_lane) FILTER (WHERE li.q_lane IS NOT NULL AND li.v_lane IS NOT NULL), 0) AS v
    FROM lane_intervals li
    CROSS JOIN params p
    WHERE li.position = 'Hauptfahrbahn' AND li.bucket::time >= p.t_detect
    GROUP BY li.day, li.bucket
),
-- In time order over the intervals that have a speed, as the evaluation's series after its dropna.
ordered AS (
    SELECT m.day, m.bucket, m.v, (m.v < p.k3_v_crit) AS below,
           row_number() OVER (PARTITION BY m.day ORDER BY m.bucket) AS rn
    FROM mainline m
    CROSS JOIN params p
    WHERE m.v IS NOT NULL
),
islands AS (
    SELECT day, below, rn,
           rn - row_number() OVER (PARTITION BY day, below ORDER BY bucket) AS island
    FROM ordered
),
runs AS (
    SELECT day, island, min(rn) AS first_rn, count(*) AS length
    FROM islands
    WHERE below
    GROUP BY day, island
),
-- An episode: a run of at least k3_min_intervals below v_crit, entered from an interval at or above it that is at
-- least k3_dv_min faster. A run at the series' start has no such interval and is no episode, as in the evaluation.
episodes AS (
    SELECT r.day, count(*) AS n
    FROM runs r
    JOIN ordered first_i ON first_i.day = r.day AND first_i.rn = r.first_rn
    JOIN ordered before_i ON before_i.day = r.day AND before_i.rn = r.first_rn - 1
    CROSS JOIN params p
    WHERE r.length >= p.k3_min_intervals
      AND NOT before_i.below
      AND before_i.v - first_i.v >= p.k3_dv_min
    GROUP BY r.day
),
verdicts AS (
    SELECT d.day,
           to_char(d.day, 'Dy') AS weekday,
           (d.day IN (SELECT day FROM excluded)) AS excluded,
           (extract(isodow FROM d.day) <= 5
               AND d.day NOT IN (SELECT day FROM holidays)
               AND extract(year FROM d.day) BETWEEN 2024 AND 2026) AS k0,
           (SELECT incomplete FROM k1 WHERE k1.day = d.day AND k1.position = 'Hauptfahrbahn') AS k1_incomplete_hauptfahrbahn,
           (SELECT incomplete FROM k1 WHERE k1.day = d.day AND k1.position = 'Einfahrt') AS k1_incomplete_einfahrt,
           round((SELECT coverage FROM k2 WHERE k2.day = d.day AND k2.position = 'Hauptfahrbahn'), 4) AS k2_coverage_hauptfahrbahn,
           round((SELECT coverage FROM k2 WHERE k2.day = d.day AND k2.position = 'Einfahrt'), 4) AS k2_coverage_einfahrt,
           coalesce(e.n, 0) AS k3_episodes
    FROM days d
    LEFT JOIN episodes e ON e.day = d.day
),
judged AS (
    SELECT v.*,
           (coalesce(v.k1_incomplete_hauptfahrbahn, p.n_intervals) <= p.k1_max_incomplete
               AND coalesce(v.k1_incomplete_einfahrt, p.n_intervals) <= p.k1_max_incomplete) AS k1,
           (coalesce(v.k2_coverage_hauptfahrbahn, 0) >= p.k2_min_coverage
               AND coalesce(v.k2_coverage_einfahrt, 0) >= p.k2_min_coverage) AS k2,
           (v.k3_episodes >= p.k3_min_episodes) AS k3
    FROM verdicts v
    CROSS JOIN params p
),
qualified AS (
    SELECT j.*, (NOT j.excluded AND j.k0 AND j.k1 AND j.k2 AND j.k3) AS qualifies
    FROM judged j
)
SELECT day, weekday, excluded, k0, k1, k2, k3, qualifies,
       CASE WHEN qualifies THEN row_number() OVER (PARTITION BY qualifies ORDER BY day) END AS rank_by_rule,
       k1_incomplete_hauptfahrbahn, k1_incomplete_einfahrt,
       k2_coverage_hauptfahrbahn, k2_coverage_einfahrt,
       k3_episodes
FROM qualified
ORDER BY day;
