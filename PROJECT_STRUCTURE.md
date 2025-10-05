# Scenic Navigation - Project Structure

## 📁 Code Organization

This project follows a clean architecture pattern with clear separation of concerns.

### Package Structure

```
com.example.scenic_navigation/
├── models/                          # Data models
│   ├── DataModels.kt               # Core data classes (Poi, ScenicPoi, etc.)
│   └── RoadTripModels.kt           # Road trip planning models
├── services/                        # Business logic services
│   ├── GeocodingService.kt         # Address to coordinates conversion
│   ├── RoutingService.kt           # Route fetching (OSRM)
│   ├── PoiService.kt               # POI fetching (Overpass API)
│   ├── ScenicRoutePlanner.kt       # Scenic route scoring & selection
│   ├── RoadTripPlanner.kt          # Multi-waypoint trip optimization
│   └── MunicipalityService.kt      # Scenic town discovery
├── utils/                           # Utility functions
│   └── GeoUtils.kt                 # Geographic calculations
├── MainActivity.kt                  # Main UI controller
└── PoiAdapter.kt                   # RecyclerView adapter

res/drawable/                        # Vector drawable icons
├── ic_coastal_town.xml             # Blue coastal town marker
├── ic_mountain_town.xml            # Brown mountain town marker
├── ic_town.xml                     # Gray general town marker
└── ... (other icons)
```

## 🏗️ Architecture Overview

### Models Package (`models/`)
Contains all data classes used throughout the app:
- **Poi**: Standard point of interest
- **ScenicPoi**: Scenic POI with scoring
- **ScenicMunicipality**: Coastal/mountain towns
- **RecommendationItem**: Sealed class for unified recommendations
- **Waypoint, RoadTripSegment, RoadTripPlan**: Road trip planning models
- **GeocodeResult**: Geocoding results
- **CoastalSegment**: Coastal proximity analysis

### Services Package (`services/`)
Business logic organized into focused service classes:

#### GeocodingService
- Converts addresses to coordinates using Nominatim API
- Implements LRU caching with TTL (24 hours)
- Retry logic with exponential backoff

#### RoutingService
- Fetches routes from OSRM API
- Supports route alternatives
- Parses GeoJSON geometry

#### PoiService
- Fetches POIs from Overpass API
- Location-based and route-based queries
- POI categorization and filtering
- Rate limiting

#### ScenicRoutePlanner
- Fetches scenic POIs with parallel processing
- Calculates scenic scores (variety, density, types)
- Selects most scenic route from alternatives
- Dynamic sampling based on route length

#### RoadTripPlanner
- Plans optimal multi-waypoint road trips
- Traveling salesman optimization
- Waypoint sequencing and route segments
- Time estimation

#### MunicipalityService
- Discovers scenic municipalities (coastal/mountain towns)
- Filters by elevation and coastal proximity
- Scores based on characteristics

### Utils Package (`utils/`)
Geographic utility functions:
- Haversine distance calculation
- Route length computation
- Route point sampling
- Lat/lon parsing

### UI Layer
**MainActivity**: Focused on UI and user interactions
- Map visualization
- User input handling
- Service coordination
- Result display

**PoiAdapter**: RecyclerView adapter for recommendations
- Supports both POIs and municipalities
- Custom icons per type
- Click handling

## 🎨 Icon Resources

Vector drawable icons for different location types:
- **ic_coastal_town**: Blue pin with waves and sun
- **ic_mountain_town**: Brown pin with mountain peaks
- **ic_town**: Gray pin with buildings
- Plus 15+ other POI-specific icons

## 🔑 Key Design Principles

1. **Separation of Concerns**: UI, business logic, and data are separated
2. **Single Responsibility**: Each service has one clear purpose
3. **Reusability**: Services can be used across different activities
4. **Testability**: Business logic can be unit tested independently
5. **Maintainability**: Easy to locate and fix issues
6. **Scalability**: Simple to add new features without cluttering code

## 📊 Service Dependencies

```
MainActivity
    ├── GeocodingService
    ├── RoutingService
    ├── PoiService
    ├── MunicipalityService
    └── RoadTripPlanner
            ├── RoutingService
            └── ScenicRoutePlanner
```

## 🚀 Key Features

- **Geocoding**: Address search with caching
- **Route Planning**: OSRM integration with alternatives
- **POI Discovery**: Overpass API integration
- **Scenic Routing**: Multi-criteria scoring algorithm
- **Road Trip Planning**: TSP-based waypoint optimization
- **Municipality Discovery**: Coastal and mountain town detection
- **Unified Recommendations**: POIs and towns in one list

## 📝 Code Statistics

- **Total Services**: 6 focused service classes
- **Total Models**: 10+ data classes
- **MainActivity**: Reduced from ~2500 lines to ~800 lines (UI only)
- **Lines of Code**: ~1800 lines of well-organized business logic

