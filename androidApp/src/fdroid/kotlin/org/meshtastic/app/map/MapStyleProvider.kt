package org.meshtastic.app.map

object MapStyleProvider {
    fun getOfflineStyleJson(mbtilesPath: String): String {
        return """
        {
          "version": 8,
          "sources": {
            "openmaptiles": {
              "type": "vector",
              "url": "mbtiles://$mbtilesPath"
            }
          },
          "glyphs": "asset://fonts/{fontstack}/{range}.pbf",
          "layers": [
            {
              "id": "background",
              "type": "background",
              "paint": { "background-color": "#F8F4F0" }
            },
            {
              "id": "water",
              "type": "fill",
              "source": "openmaptiles",
              "source-layer": "water",
              "paint": { "fill-color": "#A0C8F0" }
            },
            {
              "id": "landcover",
              "type": "fill",
              "source": "openmaptiles",
              "source-layer": "landcover",
              "paint": { "fill-color": "#D8F0D0" }
            },
            {
              "id": "park",
              "type": "fill",
              "source": "openmaptiles",
              "source-layer": "park",
              "paint": { "fill-color": "#C8E8C0" }
            },
            {
              "id": "landuse",
              "type": "fill",
              "source": "openmaptiles",
              "source-layer": "landuse",
              "paint": { "fill-color": "#E8E0D8" }
            },
            {
              "id": "building",
              "type": "fill",
              "source": "openmaptiles",
              "source-layer": "building",
              "paint": { "fill-color": "#D0C8C0", "fill-outline-color": "#B8B0A8" }
            },
            {
              "id": "roads-minor",
              "type": "line",
              "source": "openmaptiles",
              "source-layer": "transportation",
              "filter": ["in", "class", "minor", "service", "track"],
              "paint": { "line-color": "#FFFFFF", "line-width": 1.5 }
            },
            {
              "id": "roads-main",
              "type": "line",
              "source": "openmaptiles",
              "source-layer": "transportation",
              "filter": ["in", "class", "primary", "secondary", "tertiary", "trunk"],
              "paint": { "line-color": "#FFC880", "line-width": 2.5 }
            },
            {
              "id": "roads-highway",
              "type": "line",
              "source": "openmaptiles",
              "source-layer": "transportation",
              "filter": ["==", "class", "motorway"],
              "paint": { "line-color": "#FF9040", "line-width": 4 }
            },
            {
              "id": "boundary",
              "type": "line",
              "source": "openmaptiles",
              "source-layer": "boundary",
              "paint": { "line-color": "#A080C0", "line-width": 1, "line-dasharray": [4, 2] }
            },
            {
              "id": "place-labels",
              "type": "symbol",
              "source": "openmaptiles",
              "source-layer": "place",
              "layout": {
                "text-field": "{name}",
                "text-font": ["Open Sans Regular"],
                "text-size": 13,
                "text-max-width": 8
              },
              "paint": {
                "text-color": "#333333",
                "text-halo-color": "#FFFFFF",
                "text-halo-width": 1.5
              }
            },
            {
              "id": "road-labels",
              "type": "symbol",
              "source": "openmaptiles",
              "source-layer": "transportation_name",
              "layout": {
                "text-field": "{name}",
                "text-font": ["Open Sans Regular"],
                "text-size": 10,
                "symbol-placement": "line"
              },
              "paint": {
                "text-color": "#666666",
                "text-halo-color": "#FFFFFF",
                "text-halo-width": 1
              }
            }
          ]
        }
        """.trimIndent()
    }
}