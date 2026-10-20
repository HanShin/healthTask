package com.hanshin.healthtask.running

/** Keep corrupt/imported coordinates out of the native map and the fallback canvas. */
internal fun routeMapPoints(points: List<RunningPoint>): List<RunningPoint> = points.filter {
    it.latitude.isFinite() && it.longitude.isFinite() &&
        it.latitude in -MAX_MERCATOR_LATITUDE..MAX_MERCATOR_LATITUDE &&
        it.longitude in -180.0..180.0
}

private const val MAX_MERCATOR_LATITUDE = 85.05112878

internal const val OSM_ATTRIBUTION = "© OpenStreetMap contributors"
internal const val OSM_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"

// No remote styles, glyphs, analytics, geocoding or route upload. Only visible raster tiles.
internal const val RUNNING_MAP_STYLE = """
{
  "version": 8,
  "sources": {
    "osm": {
      "type": "raster",
      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "minzoom": 0,
      "maxzoom": 19,
      "attribution": "© OpenStreetMap contributors"
    }
  },
  "layers": [
    {"id": "background", "type": "background", "paint": {"background-color": "#eef1ed"}},
    {"id": "osm", "type": "raster", "source": "osm", "paint": {"raster-fade-duration": 0}}
  ]
}
"""
