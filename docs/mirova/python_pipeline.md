# Python Evaluation & Data Processing Pipeline

While the OpenTrafficSim (OTS) simulator runs in Java, the data processing, validation, calibration, and output plotting are driven by a Python pipeline located in the `diss_mvb` repository under the [scripts/](file:///d:/Mitarbeitende/gw2128/repositories/diss_mvb/scripts/) directory.

---

## 📂 Repository & Package Architecture

The simulation evaluation pipeline under `diss_mvb/scripts/simulation/ots/` is organized into modular Python subpackages:

```
diss_mvb/scripts/simulation/ots/
├── __init__.py                        # Top-level package marker
├── cli/                               # Command-line entry points
│   ├── run_evaluation.py              # Main simulation evaluation runner
│   ├── run_calibration_fast.py        # Fast launcher using detector cache
│   └── run_ieee_lc_eval.py            # IEEE Lane Change evaluation runner
├── io/                                # Data loading & parsing
│   ├── empirical.py                   # PostgreSQL empirical detector data loader
│   ├── detector_cache.py              # CSV detector run cache management
│   └── params_parser.py               # runParams.txt parsing & variation scanner
├── analytics/                         # Math modeling & statistical calibration
│   ├── van_aerde.py                   # Van Aerde q-v model fitting (L-BFGS-B)
│   ├── breakdown.py                   # GMM v_crit threshold & breakdown capacity
│   ├── dtw_analysis.py                # Dynamic Time Warping (DTW) onset-lag
│   └── cross_day.py                   # Modules 1-3 & cross-day hypothesis testing
├── plotting/                          # Visualization subpackages
│   ├── detectors/                     # Detector-level plots
│   │   ├── speed_series.py            # Speed time series (det_L3a_speed.png)
│   │   └── qv_diagrams.py             # Fundamental diagrams (det_L3a_qv.png)
│   └── trajectories/                  # Microscopic trajectory plots
│       ├── profiles.py                # Stitched spatial speed/accel/desire profiles
│       └── lane_changes.py            # On-ramp merge position & speed distributions
└── dashboards/                        # Dashboard generation
    ├── html_builder.py                # Multi-date overview_all_scenarios.html builder
    ├── calibration_badges.py          # Metric badge & section HTML injection
    └── interactive/                   # Interactive Plotly / Streamlit dashboards
        ├── comparison.py
        ├── multi_dates.py
        ├── demand.py
        └── trajectory_viz.py
```

---

## 🛠️ Main Packages & Modules

### 1. Data I/O & Parsing (`ots.io`)
*   **Empirical Loader (`io/empirical.py`)**: Connects to PostgreSQL database to fetch 5-minute empirical loop detector counts and harmonic speeds.
*   **Detector Cache (`io/detector_cache.py`)**: Automatically creates and validates `detector_runs_cache.csv` per scenario variation for sub-second re-runs.
*   **Params Parser (`io/params_parser.py`)**: Parses `runParams.txt` configuration files and scans multi-date output directories.

### 2. Analytics & Math Modeling (`ots.analytics`)
*   **Van Aerde Model (`analytics/van_aerde.py`)**: Fits non-linear 4-parameter speed-flow curves using orthogonal L-BFGS-B optimization.
*   **Breakdown & Capacity (`analytics/breakdown.py`)**: Fits 2-component GMM speed distributions to find $v_{\text{crit}}$, detects persistent breakdowns, and computes capacity with 95% Student-t confidence intervals.
*   **DTW Time-Series Analysis (`analytics/dtw_analysis.py`)**: Computes breakdown onset lag (minutes) and timing-corrected aligned speed RMSE.
*   **Cross-Day Consistency (`analytics/cross_day.py`)**: Evaluates fixed parameter set performance stability across varying demand days.

### 3. Plotting & Visualization (`ots.plotting`)
*   **Detector Plots (`plotting/detectors/`)**: Generates `det_L3a_speed.png` time series and `det_L3a_qv.png` fundamental diagrams.
*   **Microscopic Trajectory Plots (`plotting/trajectories/`)**: Parallelized multithreaded reader for spatial speed/accel/desire profiles along the corridor, as well as IEEE on-ramp merge position histograms (`evaluation_trajectories_lc_freq.png`) and merge speed distributions (`evaluation_trajectories_lc_speed.png`).

### 4. Dashboards & Web Reports (`ots.dashboards`)
*   **HTML Dashboard Builder (`dashboards/html_builder.py`)**: Generates responsive, date-grouped `overview_all_scenarios.html` web dashboards.
*   **Calibration Badges (`dashboards/calibration_badges.py`)**: Injects dynamic calibration metric badges into HTML cards.

---

## 💻 Execution Commands

```bash
# 1. Full simulation evaluation & plot generation
python scripts/simulation/ots/plot_scenario_results.py --output-dir "D:/Mitarbeitende/gw2128/repositories/mirova/output/ots/study_9dates_trajectories_10seeds_202510"

# 2. Fast calibration extensions runner (< 5s execution via cache)
python scripts/simulation/ots/run_calibration_extensions_fast.py --output-dir "D:/Mitarbeitende/gw2128/repositories/mirova/output/ots/study_9dates_trajectories_10seeds_202510"

# 3. IEEE On-Ramp Merging evaluation
python scripts/simulation/ots/run_ieee_lane_change_eval.py --output-dir "D:/Mitarbeitende/gw2128/repositories/mirova/output/ots/study_9dates_trajectories_10seeds_202510"
```
