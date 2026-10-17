package com.roadconnect.app.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Adds bundled stations while keeping the listener's names, order and favorites. */
public final class StationCatalog {
    private StationCatalog() {}

    public static List<Station> merge(List<Station> saved, List<Station> bundled) {
        List<Station> merged = new ArrayList<>(saved);
        Set<String> ids = new HashSet<>();
        Set<String> urls = new HashSet<>();
        for (Station station : saved) {
            ids.add(station.id);
            urls.add(station.url);
        }
        for (Station station : bundled) {
            if (ids.contains(station.id) || urls.contains(station.url)) continue;
            merged.add(station);
            ids.add(station.id);
            urls.add(station.url);
        }
        return merged;
    }
}
