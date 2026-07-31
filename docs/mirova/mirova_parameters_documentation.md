# MiRoVA Framework: Parameter Documentation

This document describes the parameters used within the **MiRoVA** tactical planner framework, including standard OTS parameters (`ParameterTypes`), tactical planning parameters (`MirovaParameters`), and the specific calibration configurations used for Cars and Trucks.

---

## 1. Core Car-Following Parameters (`ParameterTypes`)

These standard parameters are inherited from OpenTrafficSim's base classes and are used within the reactive car-following layer.

| Parameter | ID | Default Value (OTS) | Description / Role |
| :--- | :--- | :--- | :--- |
| **Desired Headway (`T`)** | `T` | `1.2 s` | The desired time headway to the leading vehicle. This is the primary driver of safety margins. |
| **Max Acceleration (`A`)** | `a` | `1.25 m/s²` | The maximum comfortable acceleration capability of the vehicle under normal car-following. |
| **Comfortable Deceleration (`B`)** | `b` | `2.09 m/s²` | The maximum comfortable braking rate under normal conditions (non-emergency). |
| **Critical Deceleration (`BCRIT`)** | `bCrit` | `3.5 m/s²` | Maximum critical braking rate (e.g., stopping for a yellow light at an intersection). |
| **Standstill Distance (`S0`)** | `s0` | `3.0 m` | The desired gap distance to the leading vehicle when completely stopped in a queue. |
| **Reaction Time (`TR`)** | `Tr` | `0.5 s` | The driver's reaction time delay before executing tactical or reactive decisions. |

---

## 2. MiRoVA Tactical & Social Parameters (`MirovaParameters`)

These parameters define the cognitive and tactical layers of the MiRoVA framework, including lane changing, social interactions, and cooperation thresholds.

### 2.1 Lane Changing Desires (LMRS-based)
These thresholds govern when a vehicle transitions from free driving to searching for a gap and executing a lane change.

* **`DFREE` (Default: `0.365`)**: Desire threshold for free lane changes (e.g., passing a slower vehicle to maintain desired speed).
* **`DMAND` (Default: `0.577`)**: Desire threshold for mandatory lane changes (e.g., routing, lane drops). Above this threshold, lane change search becomes active.
* **`DSEARCH` (Default: `0.788`)**: Desire threshold for active gap search. Above this value, the vehicle actively influences adjacent traffic to create a gap.
* **`LCDUR` (Default: `3.0 s`)**: Regular lane change execution duration.

### 2.2 Deceleration & Safety Thresholds
These parameters define acceptable braking rates for ego and follower vehicles during lane changes.

* **`B_CRIT` (Default: `-3.5 m/s²`)**: Critical deceleration limit (strictly negative to match kinematic equations).
* **`B_MAX` (Default: `-6.0 m/s²`)**: Absolute maximum emergency braking limit.
* **`followerDecelerationThreshold` (Default: `-1.5 m/s²`)**: The maximum braking rate the ego vehicle is allowed to impose on a follower vehicle in the target lane during a lane change.
* **`egoDecelerationThreshold` (Default: `-2.0 m/s²`)**: The maximum braking rate the ego vehicle is willing to accept itself to perform a lane change.

### 2.3 Cooperative Driving
Parameters governing cooperative behavior to assist other merging or lane-changing vehicles.

* **`cooperativeLaneChangesEnabled` (Default: `true`)**: Toggle to enable/disable cooperative behavior (such as opening gaps for merging vehicles).
* **`cooperativeDecelerationThreshold` (Default: `-3.0 m/s²`)**: The maximum braking rate a vehicle is willing to apply to open a gap for a cooperative lane change.
* **`preemptiveCooperativeDeceleration` (Default: `-1.0 m/s²`)**: A gentle, preemptive braking rate applied early when a merging vehicle is detected upstream.
* **`farAnticipationEnabled` (Default: `true`)**: A boolean flag to enable or disable the far-range speed anticipation state (`FarAnticipationState`) during downstream merges. If false, the downstream merge cooperation behavior is only activated in the near-range (when the merging ramp becomes a directly adjacent lane).

### 2.4 Social Interactions & Pressures
These parameters model social behaviors, such as tailgating pressure and speed alignment.

* **`vGain` (Default: `69.6 km/h`)**: The speed gain threshold. If the speed difference to the lane leader is larger than `vGain`, the desire to change lanes increases.
* **`vCrit` (Default: `60.0 km/h`)**: Critical speed below which social interaction pressure (e.g. speed-related group behavior) is activated.
* **`socioSpeedSensitivity` (Default: `0.25`)**: Sensitivity factor to speed-related social pressure from surrounding vehicles.
* **`socialInteractionCooldown` (Default: `6.0 s`)**: Cooldown duration to prevent immediate lane change oscillations in opposite directions.

---

## 3. Calibration Configuration in Freiburg Scenario

In the Freiburg scenario (`RunFreiburgParallel.java`), parameters are differentiated between **Cars** and **Trucks** to calibrate their different physical limits and driver behaviors:

| Parameter | Key in Config | Car Base Value | Truck Base Value | Role in Calibration |
| :--- | :--- | :--- | :--- | :--- |
| **Desired Headway (`T`)** | `T` | `1.6 s` *(Sweep: `[1.0, 1.2]`)* | `2.0 s` *(Sweep: `[1.4, 1.6]`)* | Governs safety gap. Cars are tighter, trucks keep longer gaps. |
| **Max Acceleration (`A_MAX`)** | `aMaxMirova` | `3.5 m/s²` | `1.3 m/s²` | Represents physical acceleration capability limits. |
| **Speed Gain (`vGain`)** | `VGAIN` | `15.0 km/h` | `30.0 km/h` | Cars are highly sensitive to small speed drops; trucks require a larger drop to change lanes. |
| **Coop. Decel. Threshold** | `COOPERATIVE_DECELERATION_THRESHOLD` | `-2.0 m/s²` | `-0.5 m/s²` | Cars brake harder to let others merge; trucks avoid heavy braking due to cargo/momentum. |
| **Coop. Lane Changes** | `COOPERATIVE_LANE_CHANGES_ENABLED` | `true` | `false` | Trucks do not perform active lane changes to cooperate (disabled to prevent blocking highway). |
