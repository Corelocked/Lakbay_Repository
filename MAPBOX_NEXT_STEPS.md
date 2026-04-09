# Mapbox Migration - Next Steps & Testing

## 🎯 Immediate Actions Required

### 1. Verify Configuration
```kotlin
// In app/src/main/java/com/example/scenic_navigation/config/Config.kt
// Ensure this is set with your valid Mapbox token:
const val MAPBOX_ACCESS_TOKEN = "pk_test_YOUR_TOKEN_HERE"

// Get token from: https://account.mapbox.com/tokens/
```

### 2. Update Build Dependencies
Add to `app/build.gradle.kts`:
```kotlin
dependencies {
    // Mapbox Maps SDK
    implementation("com.mapbox.maps:android:11.0.0")  // Use latest version
    
    // Already present (needed for Mapbox):
    // implementation("com.squareup.okhttp3:okhttp:4.x.x")
    // implementation("org.json:json:20.x.x")
}
```

### 3. Verify Permissions
`app/src/main/AndroidManifest.xml` should have:
```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.INTERNET" />
```

---

## 🧪 Testing Plan

### Phase 1: Basic Map Functionality
- [ ] App launches without crashes
- [ ] Map displays with default style (Streets)
- [ ] Map pans smoothly
- [ ] Map zooms smoothly (pinch and buttons)
- [ ] Current location marker appears (with permission)
- [ ] Location arrow shows and rotates

### Phase 2: Route Planning
- [ ] Enter destination and plan route
- [ ] Route appears as blue polyline
- [ ] Start marker (green) shows at origin
- [ ] Destination marker (red) shows at target
- [ ] Route summary appears (distance, ETA)
- [ ] Input card collapses automatically

### Phase 3: Navigation
- [ ] Location tracking starts after route planned
- [ ] Location marker follows real/simulated location
- [ ] Traveled portion becomes dark gray
- [ ] Remaining portion stays blue
- [ ] Off-route detection triggers if available
- [ ] Route recalculates when off-route

### Phase 4: POI Display
- [ ] POIs appear as markers on map
- [ ] Markers cluster at zoom level < 15
- [ ] Markers separate when zooming in > 15
- [ ] POI preview strip shows at high zoom
- [ ] POI detail sheet opens on marker click
- [ ] POI icon matches category

### Phase 5: Map Styles
- [ ] Open Settings
- [ ] Change map style to Satellite
- [ ] Map updates to satellite view
- [ ] Change to Topo
- [ ] Change back to Streets
- [ ] Style persists after app restart

### Phase 6: Performance & Stability
- [ ] Multiple route plans don't crash app
- [ ] Rapid zoom in/out is smooth
- [ ] No memory leaks over 10 minute use
- [ ] API requests complete < 1 second
- [ ] No excessive logging output

---

## 🐛 Debugging Checklist

### If Map is Black/Empty
```
1. Check Mapbox token in Config.MAPBOX_ACCESS_TOKEN
2. Verify internet connectivity
3. Check logcat for errors containing "Mapbox"
4. Ensure style is loading (check logs for "loadStyle")
5. Try with different coordinates (e.g., Times Square)
```

### If Markers Don't Appear
```
1. Verify PointAnnotationManager is created after style loads
2. Check bitmap icon is not null: if (bitmap != null)
3. Ensure Point coordinates are in [lon, lat] order
4. Check annotation create() returns non-null ID
5. Try creating a test marker at known location
```

### If Route Doesn't Render
```
1. Check Mapbox API token has Directions API enabled
2. Verify API quota not exceeded (5000 req/min free tier)
3. Check coordinates sent to API are valid [lon, lat]
4. Ensure PolylineAnnotationManager exists before creating polyline
5. Check network connection (watch network tab)
```

### If Crashes Occur
```
1. Check logcat for full stack trace
2. Search for "RouteFragment" in logs
3. Look for "NullPointerException" on annotation managers
4. Verify lifecycle methods are being called (onStart/onStop)
5. Test with simpler route (e.g., adjacent cities)
```

---

## 📱 Testing Scenarios

### Scenario 1: Simple Route
1. Start app at default location (Manila)
2. Enter destination: "Tagaytay"
3. Select: Seeing = Oceanic, Activity = Sightseeing
4. Click "Plan Trip"
5. Verify: Route renders, summary shows distance/time
6. Verify: Markers show start (green) and end (red)

### Scenario 2: Coastal Route
1. Start at Manila
2. Destination: "Bali"
3. Seeing: OCEANIC
4. Activity: Any
5. Verify: Route follows coastal waypoints
6. Verify: Polyline is long and follows shoreline pattern

### Scenario 3: Mountain Route
1. Start at Manila
2. Destination: "Baguio"
3. Seeing: MOUNTAIN
4. Activity: ADVENTURE
5. Verify: Route includes mountain waypoints
6. Verify: Polyline goes through mountain regions

### Scenario 4: Live Navigation
1. Plan any route
2. Wait for location tracking to start
3. Simulate movement (GPS simulator or change location in settings)
4. Verify: User location marker updates
5. Verify: Arrow rotates to face direction
6. Verify: Route updates (traveled portion changes to gray)

### Scenario 5: POI Interaction
1. Plan any route with POIs
2. Zoom OUT to see clusters
3. Click cluster → see member list
4. Zoom IN to zoom level > 15
5. Click individual POI marker
6. Verify: Detail sheet shows POI info
7. Click "View on map" in detail sheet
8. Verify: Map centers on POI

---

## 📊 Performance Metrics to Monitor

### API Response Times
- Route API call: < 500ms
- Segment fetch: < 300ms each
- Total for full route: < 2 seconds

### Memory Usage
- Base app: ~100MB
- With route: ~120MB
- With POI markers (100+): ~150MB
- After clearing: Should drop back to ~110MB

### Rendering Performance
- Zoom in/out: 60 FPS
- Pan: 60 FPS  
- Polyline draw: <100ms
- Marker create: <50ms each

### API Rate Limiting
- Free tier: 5,000 requests/minute
- Don't exceed with multiple simultaneous requests
- Monitor: Check usage in Mapbox account dashboard

---

## 🔧 Configuration & Setup Guide

### Step 1: Get Mapbox Token
1. Go to https://account.mapbox.com
2. Create account or login
3. Go to Tokens page
4. Create new token (default scopes fine)
5. Copy token (starts with "pk_")

### Step 2: Add to Config
```kotlin
// app/src/main/java/.../config/Config.kt
object Config {
    const val MAPBOX_ACCESS_TOKEN = "pk_test_YOUR_ACTUAL_TOKEN_HERE"
    // ... other configs
}
```

### Step 3: Enable APIs (if needed)
In Mapbox account:
1. Go to Tokens page
2. Click token
3. Ensure these are enabled:
   - Maps SDK: YES
   - Directions API: YES

### Step 4: Update Gradle
```kotlin
// build.gradle.kts
dependencies {
    implementation("com.mapbox.maps:android:11.0.0")
}
```

### Step 5: Sync and Build
```bash
./gradlew clean build
```

---

## 🚀 Deployment Checklist

### Before Release Build
- [ ] All tests passing
- [ ] No crash logs in logcat
- [ ] Map renders correctly
- [ ] Routes plan successfully
- [ ] POI markers display
- [ ] Navigation tracking works
- [ ] No obfuscation issues (Mapbox libs)
- [ ] API token not in logs
- [ ] Performance acceptable

### Proguard Rules (if needed)
```proguard
-keep class com.mapbox.maps.** { *; }
-keep class com.mapbox.common.** { *; }
-keep class com.mapbox.geojson.** { *; }
```

### Release Notes for Users
```
Version X.Y - Major Update
- Switched to Mapbox for improved map rendering
- Enhanced route visualization
- Better POI display with clustering
- Improved navigation accuracy
- Performance optimizations
```

---

## 📞 Support & Debugging Resources

### Official Documentation
- Mapbox Maps SDK: https://docs.mapbox.com/android/maps/
- Mapbox Directions API: https://docs.mapbox.com/api/navigation/directions/
- Android Developer Guide: https://developer.android.com/

### Troubleshooting Resources
1. Check migrationguide in this project
2. Review logcat output (search "Mapbox")
3. Check Mapbox status page: https://status.mapbox.com/
4. Verify API quota in Mapbox account
5. Test with different coordinates

### Common Solutions
```
Black map → Check token validity
No markers → Check managers initialized
Slow route → Check API quota and caching
Crash on startup → Check lifecycle methods
```

---

## 🎓 Migration Reference

### Conversion Patterns

**Pattern: Marker Creation**
```kotlin
// OLD (osmdroid)
val marker = Marker(map).apply {
    position = GeoPoint(lat, lon)
    icon = drawable
    title = "Title"
}
map.overlays.add(marker)

// NEW (Mapbox)
val options = PointAnnotationOptions()
    .withPoint(Point.fromLngLat(lon, lat))
    .withIconImage(bitmap)
val id = pointAnnotationManager?.create(options)?.id
```

**Pattern: Marker Deletion**
```kotlin
// OLD (osmdroid)
map.overlays.remove(marker)

// NEW (Mapbox)
id?.let { pointAnnotationManager?.delete(it) }
```

**Pattern: Map Centering**
```kotlin
// OLD (osmdroid)
map.controller.animateTo(GeoPoint(lat, lon))

// NEW (Mapbox)
mapView.getMapboxMap().setCamera(
    CameraOptions.Builder().center(Point.fromLngLat(lon, lat)).build()
)
```

---

## 📋 Verification Checklist (Before Commit)

- [ ] No osmdroid imports remain (except backup)
- [ ] All Mapbox imports added
- [ ] RouteFragment compiles without errors
- [ ] RoutingService compiles without errors
- [ ] Layout XML uses MapView (not osmdroid)
- [ ] Config has MAPBOX_ACCESS_TOKEN
- [ ] No hardcoded test tokens in code
- [ ] Backup file created (RouteFragment.kt.backup)
- [ ] Migration docs created
- [ ] All test scenarios documented
- [ ] Performance metrics identified
- [ ] Debugging guide prepared

---

## 🎬 Next Steps Summary

1. **This Week**
   - Verify Mapbox token is valid
   - Add dependencies to build.gradle
   - Run app and verify map displays
   - Test route planning

2. **Next Week**
   - Full testing of all scenarios
   - Performance benchmarking
   - Fix any issues found
   - Prepare for release

3. **Release**
   - Final QA pass
   - Update documentation
   - Build release APK
   - Deploy to users

---

**Status**: ✅ Code changes complete, ready for testing
**Estimated Testing Time**: 4-8 hours
**Target Release**: After successful testing and approval

