# Python Evaluation & Data Processing Pipeline

While the OpenTrafficSim (OTS) simulator runs in Java, the data processing, validation, calibration, and output plotting are driven by a Python pipeline located in the `diss_mvb` repository under the [scripts/](file:///d:/Mitarbeitende/gw2128/repositories/diss_mvb/scripts/) directory.

---

## 📂 Repository Structure

The `diss_mvb/scripts` directory is organized into four main packages:

```mermaid
graph TD
    A[scripts/] --> B(database)
    A --> C(evaluation)
    A --> D(format)
    A --> E(simulation)
    
    C --> F(fielddata)
    F --> G(detectors)
    F --> H(trajectories)
    H --> I(imports)
    H --> J(processing)
    H --> K(analysis)
```

---

## 🛠️ Main Packages & Scripts

### 1. Trajectory Imports (`imports/`)
*   **Purpose**: Extracts raw field measurements from datasets (e.g., Freiburg Nord, DLR HT) and structures/imports them into local files or database tables.
*   **Key Script**: [execute_db_import.py](file:///d:/Mitarbeitende/gw2128/repositories/diss_mvb/scripts/evaluation/fielddata/trajectories/imports/datasets/a2_mai23/execute_db_import.py) orchestrates the ingestion of raw trajectory files into a unified PostgreSQL format.

### 2. Trajectory Processing (`processing/`)
*   **Purpose**: Filters outliers, resolves anomalies, and matches raw spatial coordinate trajectories to discrete highway lanes.
*   **Key Algorithms**:
    *   **Lane Tracker (`match_lanes.py`)**: [match_lanes.py](file:///d:/Mitarbeitende/gw2128/repositories/diss_mvb/scripts/evaluation/fielddata/trajectories/processing/match_lanes.py) maps vehicles to lanes using a rolling-window Gaussian Mixture Model (GMM), Hungarian matching, and bi-directional dead reckoning. This solves the "label-switching" problem during section-wise cluster analysis (e.g., at on-ramps and off-ramps).
    *   **Anomaly Checker (`check_trajectory_anomalies.py`)**: [check_trajectory_anomalies.py](file:///d:/Mitarbeitende/gw2128/repositories/diss_mvb/scripts/evaluation/fielddata/trajectories/processing/helpers/check_trajectory_anomalies.py) filters out teleportation spikes, physical acceleration violations, and reverse driving outliers.

### 3. Performance Profiling (`simulation/ots/profiling/`)
*   **Purpose**: Turns a Java Flight Recorder recording of an OTS/MiRoVA run into an architecture-aware performance report, mapping hotspots onto the five layers instead of listing raw method names.
*   **Key Script**: [analyze_jfr_profile.py](file:///d:/Mitarbeitende/gw2128/repositories/diss_mvb/scripts/simulation/ots/analyze_jfr_profile.py) parses `jfr print` output, restricts it to the steady-state window (excluding JVM start-up), and writes a Markdown report.
*   **Separate analyses**: CPU self-time by owning code base, inclusive time by architectural layer, allocation by layer and type, and a check of whether the per-tick car-following cache is effective or bypassed.
*   **Recording** needs JVM flags only, no code changes: `-XX:StartFlightRecording=filename=run.jfr,settings=profile,dumponexit=true -XX:FlightRecorderOptions=stackdepth=128`.
*   See [profiling/README.md](file:///d:/Mitarbeitende/gw2128/repositories/diss_mvb/scripts/simulation/ots/profiling/README.md) for the workflow and the pitfalls it guards against.

### 4. Simulation Verification (`simulation/ots/`)
*   **Purpose**: Configures the execution of OTS simulations from Python and processes the results.
*   **Key Script**: [dashboard_trajectories.py](file:///d:/Mitarbeitende/gw2128/repositories/diss_mvb/scripts/simulation/ots/dashboard_trajectories.py) generates dynamic web-based dashboard plots (using Plotly/Streamlit) to visually inspect simulated vehicle trajectories.

---

## 📈 Calibration & Fundamental Diagram Analysis (Q-V)

The post-run evaluation script [plot_scenario_results.py](file:///d:/Mitarbeitende/gw2128/repositories/diss_mvb/scripts/simulation/ots/plot_scenario_results.py) compares simulated output vs. empirical field data. Recent enhancements focus on robust mathematical modeling of traffic breakdown and capacity.

### 1. Van Aerde Curve Fitting
* **Model Equation**: The Van Aerde model links speed ($v$) and flow ($q$) via:
  $$q = \frac{v}{c_1 + c_2 v + \frac{c_3}{v_f - v}}$$
* **Coefficient Correction**: The $c_2$ coefficient is computed from physical parameters ($v_f$ free flow speed, $v_c$ critical speed, $q_c$ capacity, $k_j$ jam density):
  $$c_2 = \frac{1}{q_c} - \frac{c_1}{v_c} - \frac{c_3}{v_c (v_f - v_c)}$$
  *(Note: A division by $v_c$ in the third term was corrected to ensure robust orthogonal fitting of the curves without optimization degeneracy).*
* **Orthogonal Fitting**: Uses scipy's `minimize` with `L-BFGS-B` to minimize the distance of each point to the nearest point of the discretised curve, with strict penalty values ($10^6$) for invalid parameter spaces where the denominator $\le 0$.
  * The loss formerly measured the residual in $q$ alone although both coordinates carry error, which made the estimate follow the scatter in $q$ — and that scatter grows as the aggregation interval shrinks. Fitting the same runs at $60\text{ s}$ instead of $300\text{ s}$ moved $v_f$ by $+12.6\,\%$ and $v_c$ by $-14.5\,\%$ purely through the aggregation; against the nearest point of the curve the same comparison gives $+1.5\,\%$ and $-1.0\,\%$.
  * What remains is $+7\,\%$ in $q_c$ between the two aggregations, and that one is real rather than an artefact: a one-minute interval reaches higher peak flows than a five-minute mean. **Capacity figures therefore stay on five-minute intervals**, while $v_f$, $v_c$ and $k_j$ are now robust to the choice.
* **Jam Density Bound**: The upper bound on $k_j$ was $150\text{ veh/km}$, a per-lane figure applied to cross-section totals over two lanes. Both the third and the fourth campaign came back sitting exactly on it, so the reported value was a boundary artefact that no amount of extra data in the congested branch could have moved. At a cross-section bound it estimates $265$–$299\text{ veh/km}$, i.e. $133$–$150$ per lane.

### 2. Breakdown Capacity

This estimate used to contradict the fundamental diagram fitted to the same runs. Van Aerde put the simulation *above* the field, $2138$ against $2107\text{ veh/h}$ on the mainline; the breakdown method put it far *below*, $1544$ against $2052$. Two of those four numbers had to be wrong about the same data, and both errors were in the breakdown method.

* **Congestion threshold**: one **study-wide** $v_{\text{crit}}$, shared by simulation and field.
  * A GMM was previously fitted per dataset and each side scored against its own threshold. On this site that puts the empirical value near $67\text{ km/h}$ and the simulated one near $85$, so the simulation was judged by a threshold its own scatter crosses far more often, and every crossing counted as a breakdown.
  * The per-dataset GMM speeds are still fitted and reported, since they describe each cloud, but they no longer decide what counts as a breakdown. The plot labels them as descriptive.
  * Refitting the threshold per run also made it a random variable: most of the apparent spread between seeds was the threshold rather than the runs, and the standard deviation of the simulated capacity fell from $\pm 514$ to $\pm 68\text{ veh/h}$ once it was shared.
* **Breakdown Identification** — one definition, shared by the q-v diagram and the event table, which formerly carried two separate implementations of it:
  * Excludes the first **45 minutes** to discard warm-up transients.
  * An episode is at least **three consecutive intervals** ($15\text{ min}$) below $v_{\text{crit}}$, entered out of free flow with a speed drop $dv \ge dv_{\text{min}}$ (iterating $dv_{\text{min}}$ from $10.0$ down to $5.0\text{ km/h}$). Two intervals was too weak a test: a facility that recovers within ten minutes did not reach its capacity.
* **Capacity Extraction**: the **highest** pre-breakdown flow of the run, i.e. the largest flow in the 5-minute interval preceding any of its episodes.
  * Taking the *first* episode instead compared unlike samples. An empirical day yields one episode, a simulated run one to three, so the field's only draw was compared against the smallest of several — the more often a model broke down, the lower its measured capacity came out. Measured over the fourth campaign the first episode sat at $55$–$82\,\%$ of the run's own maximum flow, with up to $28$ later intervals carrying *more* traffic than the moment of supposed failure; empirically the same figure is $100\,\%$ with nothing higher afterwards.
  * The maximum carries the mirror-image bias — the largest of several draws grows with their number just as the first shrank with it — so the two are comparable only where both sides break down a similar number of times. The median and the first flow remain available so the effect of the rule can be checked. Where the numbers differ, the **product-limit estimate** is the one to use, since it handles censored observations rather than selecting a single draw.
* **Statistical Aggregation**: mean, median, standard deviation and a **95 % Student-t confidence interval** across seed replications.

With both corrections the four estimates agree: simulation $2064 \pm 68$ against an empirical $2052\text{ veh/h}$ on the mainline, $3150 \pm 178$ against $3144$ including the ramp, where the fitted diagram gives $2138$ and $2107$.

### 3. Layout Adjustments & HTML Overview Dashboard
* **Clean Plotting Canvas**: The results annotation box (calibration metrics, fitted coefficients, capacity statistics) is placed outside the main plotting grid (on the right margin using `x=1.02, y=0.70` paper coordinates) by increasing the figure width to $780\text{ px}$ and right margin to $350\text{ px}$.
* **Removal of Zigzag Line**: The non-monotonic `Sim Median` trace was removed from the q-v plot to prevent zigzag clutter.
* **Speed over Time Sparklines Grid**: The top-level `overview_all_scenarios.html` includes a responsive CSS grid section displaying compact Speed-over-Time plots for detector `det_L3a` across all variations. Each plot displays every individual simulation seed run as a thin semi-transparent line, the median trajectory as a bold line, and empirical detector data as a dotted black reference line.
* **CSV Detector Run Cache**: To avoid re-parsing large `detector_periodic.csv.zip` files on repeated pipeline runs, aggregated per-run detector data is automatically cached to `{variation_dir}/plots/detector_runs_cache.csv`. The cache is automatically validated against source zip file modification timestamps (`st_mtime`).

---

## 🔬 Multi-Day Calibration Analysis Extensions (`calibration_analysis_extensions.py`)

The evaluation pipeline includes three advanced calibration extensions and a per-day metrics comparison matrix:

1. **Module 1 — Per-Day & Per-Seed Van Aerde Distributions**:
   * Treats individual fits ($n=6$ empirical, $n=36$ per simulation setting) as the true unit of replication to prevent serial autocorrelation inflation.
   * Outputs: `van_aerde_fits_tidy.csv`, `van_aerde_parameter_distributions.png`.
2. **Module 2 — DTW Time-Series Decomposition**:
   * Employs C-optimized `dtaidistance` to extract breakdown **Onset Lag (minutes)** and timing-corrected **Aligned Speed RMSE / MAE**.
   * Output: `dtw_time_series_metrics.csv` (recorded per individual seed run).
3. **Module 3 — Cross-Day Consistency Hypothesis Test**:
   * Evaluates fixed parameter set stability across varying demand days using Coefficient of Variation ($CV = SD / \text{Mean}$) and Min-Max ranges.
   * Outputs: `cross_day_consistency_summary.csv`, `cross_day_consistency_summary.md`.
4. **Per-Day Metrics Breakdown Matrix**:
   * Compares empirical vs. simulated $q_c, v_c$, Van Aerde fit RMSE, DTW Onset Lag, and Aligned Speed RMSE for **each individual demand date**.
   * Output: `per_day_calibration_metrics.csv`.
5. **Fast Evaluation Runner**:
   * Executable via `python run_calibration_extensions_fast.py --output-dir <path>` to update all metrics and HTML dashboard in $< 5\text{ seconds}$ using detector cache files.

For complete details on execution commands, script parameters, and output artifacts, see the detailed pipeline documentation in [scripts/simulation/ots/README.md](file:///d:/Mitarbeitende/gw2128/repositories/diss_mvb/scripts/simulation/ots/README.md).

---

## 🔄 Simulation & Data Pipeline Flow

```mermaid
sequenceDiagram
    participant F as Raw Field Data
    participant P as Python Processing (GMM Lane Matching)
    participant D as DB / Pickett Files
    participant O as OTS Simulation (Java)
    participant E as Python Evaluation (Dashboard & Plotting)
    
    F->>P: 1. Raw spatial coordinates (X, Y)
    P->>D: 2. Structured trajectories, matched lanes
    D->>O: 3. OD matrices & demand input
    O->>E: 4. Simulation trajectory output (CSV)
    E->>E: 5. Compare simulated vs. field data (Plotting)
```
