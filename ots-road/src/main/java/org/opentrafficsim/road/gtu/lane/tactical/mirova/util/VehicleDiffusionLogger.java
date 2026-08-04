package org.opentrafficsim.road.gtu.lane.tactical.mirova.util;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Utility class to record and export vehicle diffusion (removal) events caused by off-ramp or lane-change deadlocks.
 * <p>
 * Copyright (c) 2025 Marvin Baumann / KIT. All rights reserved. <br>
 * BSD-style license. See <a href="https://opentrafficsim.org/docs/license.html">OpenTrafficSim License</a>.
 * </p>
 * @author <a href="https://github.com/baumarv">Marvin Baumann</a>
 */
public final class VehicleDiffusionLogger
{
    /** Record class representing a single vehicle diffusion event. */
    public static class DiffusionEvent
    {
        private final String gtuId;
        private final double simTimeSeconds;
        private final String laneId;
        private final double distToLaneEndMeters;
        private final boolean activeLaneChange;
        private final String reason;

        public DiffusionEvent(final String gtuId, final double simTimeSeconds, final String laneId,
                final double distToLaneEndMeters, final boolean activeLaneChange, final String reason)
        {
            this.gtuId = gtuId;
            this.simTimeSeconds = simTimeSeconds;
            this.laneId = laneId;
            this.distToLaneEndMeters = distToLaneEndMeters;
            this.activeLaneChange = activeLaneChange;
            this.reason = reason;
        }

        public String getGtuId() { return gtuId; }
        public double getSimTimeSeconds() { return simTimeSeconds; }
        public String getLaneId() { return laneId; }
        public double getDistToLaneEndMeters() { return distToLaneEndMeters; }
        public boolean isActiveLaneChange() { return activeLaneChange; }
        public String getReason() { return reason; }
    }

    /** Thread-safe list storing all diffusion events for the current simulation run. */
    private static final List<DiffusionEvent> EVENTS = Collections.synchronizedList(new ArrayList<>());

    private VehicleDiffusionLogger()
    {
        // Utility class
    }

    /**
     * Resets the diffusion logger at the start of a simulation run.
     */
    public static void reset()
    {
        EVENTS.clear();
    }

    /**
     * Logs a vehicle diffusion event.
     * @param gtuId ID of the GTU
     * @param simTimeSeconds simulation time in seconds when diffusion occurred
     * @param laneId ID of the lane where vehicle stopped
     * @param distToLaneEndMeters remaining distance to lane end in meters
     * @param activeLaneChange true if vehicle was executing an active lane change
     * @param reason description of diffusion reason
     */
    public static void logDiffusion(final String gtuId, final double simTimeSeconds, final String laneId,
            final double distToLaneEndMeters, final boolean activeLaneChange, final String reason)
    {
        EVENTS.add(new DiffusionEvent(gtuId, simTimeSeconds, laneId, distToLaneEndMeters, activeLaneChange, reason));
    }

    /**
     * Returns the total number of vehicle diffusion events recorded in the current run.
     * @return number of diffused vehicles
     */
    public static int getDiffusionCount()
    {
        return EVENTS.size();
    }

    /**
     * Returns an unmodifiable copy of all recorded diffusion events.
     * @return list of events
     */
    public static List<DiffusionEvent> getEvents()
    {
        synchronized (EVENTS)
        {
            return new ArrayList<>(EVENTS);
        }
    }

    /**
     * Exports all recorded diffusion events to a CSV file.
     * @param outputFile target File
     */
    public static void exportToCsv(final File outputFile)
    {
        if (outputFile == null)
        {
            return;
        }

        outputFile.getParentFile().mkdirs();
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputFile)))
        {
            writer.println("gtu_id,sim_time_sec,lane_id,dist_to_lane_end_m,active_lane_change,reason");
            synchronized (EVENTS)
            {
                for (DiffusionEvent e : EVENTS)
                {
                    writer.printf("%s,%.2f,%s,%.2f,%b,%s%n",
                            e.getGtuId(), e.getSimTimeSeconds(), e.getLaneId(),
                            e.getDistToLaneEndMeters(), e.isActiveLaneChange(), e.getReason());
                }
            }
            System.out.printf("[DIFFUSION] Exported %d vehicle diffusion events to %s%n", EVENTS.size(), outputFile.getName());
        }
        catch (IOException ex)
        {
            System.err.println("[ERROR] Failed to export diffused_vehicles.csv: " + ex.getMessage());
        }
    }
}
