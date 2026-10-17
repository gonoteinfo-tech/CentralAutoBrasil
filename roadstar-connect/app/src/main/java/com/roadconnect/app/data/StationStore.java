package com.roadconnect.app.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import com.roadconnect.app.R;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class StationStore {
    public static final String PREFERENCES_NAME = "radio_library";
    public static final String STATIONS_KEY = "stations";
    private static final String CATALOG_VERSION_KEY = "bundled_catalog_version";
    private static final int CATALOG_VERSION = 1;
    private final SharedPreferences preferences;
    private final Resources resources;

    public StationStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        resources = context.getResources();
    }

    public List<Station> load() {
        try {
            List<Station> stations = decode(new JSONArray(preferences.getString(STATIONS_KEY, "[]")));
            if (preferences.getInt(CATALOG_VERSION_KEY, 0) < CATALOG_VERSION) {
                StringBuilder json = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        resources.openRawResource(R.raw.radio_catalog), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) json.append(line);
                }
                stations = StationCatalog.merge(stations, decode(new JSONArray(json.toString())));
                write(stations, true);
            }
            return stations;
        } catch (JSONException | IOException | Resources.NotFoundException
                | IllegalArgumentException | ClassCastException invalid) {
            throw new IllegalStateException("Não foi possível ler suas rádios. Os dados salvos foram preservados.", invalid);
        }
    }

    private List<Station> decode(JSONArray encoded) throws JSONException {
        List<Station> stations = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < encoded.length(); i++) {
            JSONObject item = encoded.getJSONObject(i);
            Station station = new Station(item.getString("id"), item.getString("name"),
                    item.getString("url"), item.getBoolean("favorite"));
            if (!ids.add(station.id)) throw new IllegalArgumentException("Identificador repetido.");
            stations.add(station);
        }
        return stations;
    }

    public void save(List<Station> stations) {
        write(stations, false);
    }

    private void write(List<Station> stations, boolean installCatalog) {
        JSONArray encoded = new JSONArray();
        Set<String> ids = new HashSet<>();
        try {
            for (Station station : stations) {
                if (!ids.add(station.id)) throw new IllegalArgumentException("Identificador repetido.");
                new Station(station.id, station.name, station.url, station.favorite);
                JSONObject item = new JSONObject();
                item.put("id", station.id);
                item.put("name", station.name);
                item.put("url", station.url);
                item.put("favorite", station.favorite);
                encoded.put(item);
            }
        } catch (JSONException invalid) {
            throw new IllegalStateException("Não foi possível preparar suas rádios para salvar.", invalid);
        }
        SharedPreferences.Editor editor = preferences.edit().putString(STATIONS_KEY, encoded.toString());
        if (installCatalog) editor.putInt(CATALOG_VERSION_KEY, CATALOG_VERSION);
        if (!editor.commit()) {
            throw new IllegalStateException("Não foi possível salvar. Verifique o armazenamento do celular.");
        }
    }
}
