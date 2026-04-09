# Mapbox Migration Guide - Scenic Navigation

## Overview
This document outlines the comprehensive migration from osmdroid + OSRM to Mapbox Maps SDK + Mapbox Directions API.

## Changes Made

### 1. Layout Changes (fragment_route.xml)
- Replaced `org.osmdroid.views.MapView` with `com.mapbox.maps.MapView`
- Removed manual OSM copyright notice (Mapbox handles attribution automatically)
- No functional changes to UI elements

### 2. RouteFragment Migration Strategy

**Key Architectural Changes:**

#### Map Initialization
- **OLD**: Used osmdroid's `MapView` with tile sources (`TileSourceFactory.MAPNIK`, etc.)
- **NEW**: Mapbox `MapView` with style URLs (`Style.MAPBOX_STREETS`, `Style.SATELLITE`, etc.)

```kotlin
// OLD (osmdroid)
binding.map.apply {
    setTileSource(tileSource)
    controller.setCenter(centerPoint)
    controller.setZoom(16.0)
}

// NEW (Mapbox)
mapView.getMapboxMap().apply {
    loadStyle(styleUrl) { style ->
        pointAnnotationManager = mapView.annotations.createPointAnnotationManager()
        polylineAnnotationManager = mapView.annotations.createPolylineAnnotationManager()
    }
    setCamera(CameraOptions.Builder()
        .center(Point.fromLngLat(lon, lat))
        .zoom(16.0)
        .build())
}
```

#### Markers and Overlays
- **OLD**: Used `Marker` and `Polyline` objects added to `overlays` list
- **NEW**: Uses Annotation managers (`PointAnnotationManager` for markers, `PolylineAnnotationManager` for lines)

```kotlin
// OLD (osmdroid)
val marker = Marker(binding.map).apply {
    position = GeoPoint(lat, lon)
    icon = drawable
}
binding.map.overlays.add(marker)

// NEW (Mapbox)
val options = PointAnnotationOptions()
    .withPoint(Point.fromLngLat(lon, lat))
    .withIconImage(bitmap)
val annotationId = pointAnnotationManager?.create(options)?.id
```

#### Camera Movement
```kotlin
// OLD
binding.map.controller.animateTo(geoPoint)
binding.map.controller.setZoom(16.0)

// NEW
mapView.getMapboxMap().setCamera(CameraOptions.Builder()
    .center(point)
    .zoom(16.0)
    .build())
```

#### Map Click Listeners
```kotlin
// OLD
binding.map.setOnTouchListener { _, event -> ... }

// NEW
mapView.getMapboxMap().addOnMapClickListener { point -> ... }
```

#### Lifecycle Management
- **OLD**: `binding.map.onResume()` / `binding.map.onPause()`
- **NEW**: `mapView.onStart()` / `mapView.onStop()` + `mapView.onDestroy()`

### 3. RoutingService Migration

#### API Endpoints
**OLD (OSRM):**
```
https://router.project-osrm.org/route/v1/driving/{lon},{lat};{lon},{lat}
```

**NEW (Mapbox):**
```
https://api.mapbox.com/directions/v5/mapbox/driving/{lon},{lat};{lon},{lat}?access_token={TOKEN}
```

#### Response Parsing
Both APIs return GeoJSON geometry, but with different structure:

```kotlin
// Response format is identical for geometry
// Just need to update error handling:
// OLD: code != "Ok" means error
// NEW: code != "Ok" OR presence of "message" field means error
```

#### Key Implementation Details
1. **Access Token**: Mapbox API requires `?access_token=` parameter on all requests
2. **Parameters**: 
   - Add `&steps=true&overview=full&alternatives=true` for detailed routing
   - Both support `&geometries=geojson`

3. **Rate Limiting**: Mapbox has rate limits (5,000 requests/minute for free tier)
   - Keep segment caching to prevent redundant calls

### 4. Coordinate System Differences

**Important**: Both systems use GeoJSON [lon, lat] format internally
- **GeoPoint** (osmdroid): Constructor expects (lat, lon)
- **Point** (Mapbox): Factory expects `Point.fromLngLat(lon, lat)`

Always be careful with coordinate ordering!

### 5. Annotation Tracking

Due to Mapbox's annotation ID system, we now track annotations by ID:

```kotlin
// Track route polyline
routePolylineAnnotationId: Long? = null

// When updating
routePolylineAnnotationId?.let { polylineAnnotationManager?.delete(it) }
// Create new annotation
routePolylineAnnotationId = polylineAnnotationManager?.create(options)?.id
```

### 6. Map Styles

Map styles transition from tile sources to style URLs:

| Purpose | osmdroid | Mapbox |
|---------|----------|--------|
| Street Map | `MAPNIK` | `Style.MAPBOX_STREETS` |
| Satellite | `USGS_SAT` | `Style.SATELLITE` |
| Topo Map | `USGS_TOPO` | `Style.SATELLITE_STREETS` |

## Migration Checklist

- [x] Replace MapView in layout XML
- [x] Update imports (remove osmdroid, add mapbox-maps-android)
- [x] Update map initialization with style loading
- [x] Replace marker creation with PointAnnotationManager
- [x] Replace polyline creation with PolylineAnnotationManager
- [x] Update camera movement to use CameraOptions
- [x] Update map click listeners
- [x] Replace OSRM API with Mapbox Directions API
- [x] Update response parsing (primarily error handling)
- [x] Add access token to Mapbox API calls
- [x] Update lifecycle methods
- [x] Fix coordinate ordering issues

## Testing Recommendations

1. **Basic Functionality**
   - Test map display and panning
   - Verify markers appear and are clickable
   - Test polyline rendering for routes

2. **Route Planning**
   - Test oceanic route with waypoints
   - Test mountain route with waypoints
   - Verify route updates in real-time during navigation

3. **Performance**
   - Monitor API call performance (Mapbox vs OSRM)
   - Verify caching is working for segments
   - Check memory usage with multiple annotations

4. **Edge Cases**
   - Test with no location permissions
   - Test with invalid coordinates
   - Test rapid route changes

## Configuration Requirements

Ensure your Mapbox token is properly configured in `Config.kt`:
```kotlin
object Config {
    const val MAPBOX_ACCESS_TOKEN = "pk_test_YOUR_TOKEN_HERE"
    // ... other configs
}
```

## Known Differences from osmdroid

1. **Attribution**: Automatically managed by Mapbox (no manual copyright text needed)
2. **Gesture Handling**: Mapbox provides gesture plugin with different API
3. **Custom Styles**: Mapbox styles are defined server-side (Studio), not client-side
4. **Annotations**: IDs are returned and must be tracked for updates/deletion
5. **Map Controls**: Zoom buttons, compass, etc. are plugins (may need configuration)

## Future Considerations

1. Implement Mapbox's clustering plugin for better performance at low zoom levels
2. Use Mapbox's built-in geolocation plugin for location tracking
3. Consider Mapbox's offline maps for areas without connectivity
4. Explore Mapbox's analytics integration for route tracking

