package com.roadconnect.app.data;

public final class Station {
    public final String id;
    public final String name;
    public final String url;
    public final boolean favorite;

    public Station(String id, String name, String url, boolean favorite) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("Identificador inválido.");
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("Dê um nome para a rádio.");
        this.id = id.trim();
        this.name = name.trim();
        this.url = UrlPolicy.requireStreamUrl(url);
        this.favorite = favorite;
    }
}
