# OTS Editor Reference & Guide

The OTS Editor (`ots-editor` module) is a graphical desktop application designed to inspect, edit, and validate OpenTrafficSim (OTS) XML configuration and network files. It uses the underlying XML Schema (`ots.xsd`) to guide the editing process, ensuring schema compliance and providing direct visual feedback.

---

## 🏗️ UI Layout & Structure

The editor's layout is split into two main sections:

```
+-------------------------------------------------------+
|  File  Edit  View  Help                               |
+--------------------------+----------------------------+
|                          | Scenario: [Freiburg_1  v] |
|                          | +------------------------+ |
|                          | | (Tree Node Hierarchy)  | |
|  [Map]                   | | > Ots                  | |
|                          | |   > Definitions        | |
|   Displays the 2D        | |   > Network            | |
|   graphical road         | |     > Node             | |
|   network.               | |     > Link             | |
|                          | +------------------------+ |
|                          +----------------------------+
|                          | (Attributes Editor Table)  |
|                          | Name | Value | Type        |
|  [Road Layout] [OD]      | -----+-------+------------ |
|  (In-development tabs)   | Id   | N1_1  | string      |
+--------------------------+----------------------------+
| Status: Valid XML file                                |
+-------------------------------------------------------+
```

### 1. Left Panel (Tabbed Visualization)
*   **Map Tab (`EditorMap`)**: A fully functional 2D graphical representation of the road network using DSOL's `VisualizationPanel`. It renders Nodes, Links, Lanes, Stripes, Shoulders, Traffic Lights, Sinks, and Generators. It supports panning and zooming, and updates in real-time as elements are added or modified in the XML tree.
*   **Road Layout, OD, Route, TrafCod Tabs**: Placeholder/development tabs that hook into specific elements but are currently stubs.

### 2. Right Panel (Controls & Tree Views)
*   **Scenario Selector & Run Controls**:
    *   **Scenario ComboBox**: Selects the active scenario.
    *   **Play buttons**: Triggers single runs, scenario batch runs, or all batch runs (calls the simulation backend).
*   **XSD Tree Table (`JTreeTable`)**: Displays the hierarchical structure of the loaded XML document. Red-highlighted nodes denote validation errors.
*   **Attributes Table (`JTable`)**: Displays the attributes of the selected tree node, allowing the user to edit values, view type information, and see tooltips.

---

## 🚀 How to Run the Editor

The main entry point class is [RunEditor](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-editor/src/main/java/org/opentrafficsim/editor/RunEditor.java).

### Run from IDE
Right-click [RunEditor.java](file:///d:/Mitarbeitende/gw2128/repositories/opentrafficsim/ots-editor/src/main/java/org/opentrafficsim/editor/RunEditor.java) in Eclipse, IntelliJ IDEA, or VS Code and run the `main` method.

### Run via Maven (Command Line)
Execute the following command in the repository root directory:
```bash
mvn exec:java -pl ots-editor -Dexec.mainClass="org.opentrafficsim.editor.RunEditor"
```

---

## 💡 Key Features & Mechanisms

### 1. Schema-Guided Editing
The editor dynamically reads the `ots.xsd` schema. Adding nodes, child elements, and attributes is strictly constrained by the schema rules.
*   **Context Menus**: Right-clicking a node in the tree shows only the child elements that are legally allowed at that path by the XSD.
*   **Dropdown Choice Popups**: Choice groups (e.g. choosing between `<Straight />`, `<Bezier />`, or `<Polyline>` for a Link geometry) are handled interactively through popups.

### 2. Live Validation & Coloring
The editor runs background validators on every edit to highlight issues immediately:
*   **Pink/Red Background (`Color(255, 240, 240)`)**: Signals a validation error (e.g. duplicate IDs, missing required attributes, nodes referencing non-existent layout definitions).
*   **Yellow Background (`Color(252, 250, 239)`)**: Highlights nodes/attributes defined as expressions (using the DJUtils `Eval` parser), enabling dynamic parameters.

### 3. Automated ID Generation
*   **AutomaticLinkId**: When creating a new Link, the editor automatically generates a unique ID (e.g., `Link1`, `Link2`).
*   **AutomaticConnectorId**: Automatically generates IDs for new network Connectors.

### 4. Safety Features
*   **Autosave**: The editor automatically saves the active file to a temp location every 60 seconds (`AUTOSAVE_PERIOD_MS = 60000`).
*   **Undo/Redo**: Full undo/redo capability (`Undo.java`) is integrated into the edit operations.

---

## 📖 Step-by-Step Guide: Editing a Network

Here is how to perform common network edits using the GUI:

### 1. Opening/Creating a File
1.  Go to `File` -> `Open` or `New`.
2.  The right panel tree view will display the root `<Ots>` node.

### 2. Adding a Node
1.  Expand the tree to `Ots` -> `Network`.
2.  Right-click `Network` and select `Add Node`.
3.  Select the newly created `Node` element.
4.  In the attributes table at the bottom:
    *   Set the `Id` attribute (e.g., `N1`).
    *   Set the `Coordinate` (e.g., `(100.0, 50.0)`).
    *   Optionally, specify `Direction` (e.g., `90 deg(E)`).
5.  Watch the 2D map update; the new node should render as a gray circle.

### 3. Adding a Link
1.  Right-click `Network` and select `Add Link`.
2.  Select the new `Link` element.
3.  In the attributes table:
    *   Set `NodeStart` to your start node ID (e.g., `N1`).
    *   Set `NodeEnd` to your end node ID (e.g., `N2`).
    *   Set `Type` to a valid link type (e.g., `HIGHWAY`).
    *   Set `DefinedLayout` to a defined road layout (e.g., `2LaneHighway`).
4.  A choice popup will ask you to select the geometry type. Select `Straight` or `Bezier`.
5.  The 2D map will render the link, showing the lane lines, stripes, and shoulder layouts.

### 4. Correcting Validation Errors
If a link or node turns pink:
1.  Expand the node in the tree and check which child element or attribute is highlighted.
2.  Hover over the attribute label in the bottom table to see a tooltip with the validation error message.
3.  Correct the value in the attributes table (e.g., fix a typo in the `DefinedLayout` name).
4.  The background color will return to white/normal once valid.
