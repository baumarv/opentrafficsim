# Layer 4: Reactive & Control Layer (Execution)

The **Reactive & Control Layer** (Layer 4) is responsible for the actual execution of longitudinal and lateral commands. It translates high-level decisions (selected maneuver plans) into concrete physical acceleration inputs for the vehicle.

---

## 🔒 The Core Rule: Use `MirovaCarFollowingUtil`

In the MiRoVA framework, **no maneuver pattern or action state may invoke a car-following model directly**. 

Instead, all longitudinal control requests must route through the utility class [MirovaCarFollowingUtil](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/ReactiveLayer/MirovaCarFollowingUtil.java).

### Why?
1.  **Relaxation Injection**: It intercepts the true distance and speed of the leader vehicle and adds virtual buffer values. This implements the 2-parameter relaxation phenomenon (Keane & Gao 2021).
2.  **Tick-Based Caching**: It automatically caches calculated accelerations for a given leader ID in a single tick. This avoids executing the car-following calculations multiple times per tick if multiple patterns analyze the same leader, saving computing resources.

---

## 📉 Keane & Gao (2021) 2-Parameter Relaxation

When a vehicle changes lanes or another vehicle cuts in front, the actual headway drops abruptly. Real human drivers do not instantly slam on the brakes; they temporarily accept a shorter headway and slowly return to their desired spacing over time. 

MiRoVA models this human-like decay using [RelaxationState](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/BeliefLayer/RelaxationState.java):

```mermaid
graph TD
    A[Cut-in or Lane Change Event] --> B[Calculate Headway Deficit gamma_s & Speed Deficit gamma_v]
    B --> C[Create RelaxationState]
    C --> D[Add virtual decaying distance & speed buffers to perceived leader data]
    D --> E[Feed modified perceived distance & speed to CF Model]
```

### Exponential decay formulas:
$$s_{\text{perceived}}(t) = s_{\text{actual}}(t) + \gamma_s \cdot e^{-\frac{t - t_0}{\tau_s}}$$
$$v_{\text{leader, perceived}}(t) = v_{\text{leader, actual}}(t) + \gamma_v \cdot e^{-\frac{t - t_0}{\tau_v}}$$

Where:
*   $\gamma_s$: Space headway deficit (m) at start.
*   $\gamma_v$: Speed deficit (m/s) between old and new leader at start.
*   $\tau_s$: Spatial decay constant (calibrated to $\approx 15\text{ s}$).
*   $\tau_v$: Velocity decay constant (calibrated to $\approx 5\text{ s}$).

---

## 🏎️ Car-Following Models

MiRoVA interfaces with standard OTS car-following structures but relies heavily on the calibrated Wiedemann model for highway merging studies:

### 1. Wiedemann 99 Model (`Wiedemann99`)
*   **Location**: [Wiedemann99.java](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/ReactiveLayer/Wiedemann99.java)
*   **Description**: A physiological-psychological car-following model. It determines the driver's acceleration based on perception thresholds (action points) in the relative-speed vs. distance plane.
*   **Calibration Parameters**: Calibrated via [W99ParameterTypes](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/ReactiveLayer/W99ParameterTypes.java) using German freeway data (e.g. Duisburg A59, Cologne A4).

### 2. IDM Plus Model (`MirovaIdmPlus`)
*   **Location**: [MirovaIdmPlus.java](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-road/src/main/java/org/opentrafficsim/road/gtu/lane/tactical/mirova/core/ReactiveLayer/MirovaIdmPlus.java)
*   **Description**: An extension of the Intelligent Driver Model (IDM) that optimizes the acceleration and deceleration behaviors, adapted to work seamlessly with the MiRoVA parameter sets.

---

---

## 🛑 Headway Relaxation-Bound Acceleration Damping

To model realistic human behavior after cut-ins and merging maneuvers without generating artificial shockwaves or emergency braking, MiRoVA links positive acceleration damping directly to active Keane & Gao headway relaxation states:

### Dynamics & Formula
When a vehicle is in an active `RelaxationState` following a leader (e.g. after a cut-in), positive acceleration demands ($a > 0$) are automatically damped proportionally to the remaining virtual headway buffer $s_{\text{buf}}(t)$:

$$f_{\text{relax\_acc}}(t) = 1.0 - (1.0 - a_{\text{relax\_damping}}) \cdot \left(\frac{s_{\text{buf}}(t)}{\gamma_s}\right)$$

$$a_{\text{effective}}(t) = a_{\text{calculated}}(t) \cdot f_{\text{relax\_acc}}(t) \quad \text{for } a_{\text{calculated}} > 0$$

- **At Cut-In ($t = t_0$)**: $s_{\text{buf}} = \gamma_s \Rightarrow f_{\text{relax\_acc}} = a_{\text{relax\_damping}} = 0.40$ ($40\%$ of normal acceleration capability).
- **During Relaxation ($t > t_0$)**: As $s_{\text{buf}}(t)$ decays exponentially towards zero ($\tau_s$), $f_{\text{relax\_acc}}(t)$ smoothly recovers towards $1.00$ ($100\%$).
- **Effect**: The follower vehicle refrains from aggressive acceleration while the leader pulls ahead, restoring the desired equilibrium gap naturally without active braking.

> [!IMPORTANT]
> Damping applies **strictly to positive accelerations** ($a > 0$). Decelerations and emergency braking ($a \le 0$) are completely unconstrained for safety.

### Parameters
*   `RELAXATION_ACC_DAMPING_FACTOR` (`aRelaxDamping`): Acceleration scaling factor when headway relaxation is 100% active (default $0.40 = 40\%$).
*   `RELAXATION_ACC_DAMPING_ENABLED` (`aRelaxDampingEnabled`): Boolean flag to enable or disable acceleration damping during active headway relaxation (default `true`). When set to `false` or when `aRelaxDamping = 1.0`, acceleration damping is completely bypassed, producing numerically identical results.


