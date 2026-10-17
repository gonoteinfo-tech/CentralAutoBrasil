package com.roadconnect.app.data;

import android.content.Context;
import android.content.SharedPreferences;
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
    private final SharedPreferences preferences;

    public StationStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    public List<Station> load() {
        try {
            JSONArray encoded = new JSONArray(preferences.getString(STATIONS_KEY, "[]"));
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
        } catch (JSONException | IllegalArgumentException | ClassCastException invalid) {
            throw new IllegalStateException("Não foi possível ler suas rádios. Os dados salvos foram preservados.", invalid);
        }
    }

    public void save(List<Station> stations) {
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
        if (!preferences.edit().putString(STATIONS_KEY, encoded.toString()).commit()) {
            throw new IllegalStateException("Não foi possível salvar. Verifique o armazenamento do celular.");
        }
    }
}
