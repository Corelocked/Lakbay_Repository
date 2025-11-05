# Scenic Navigation

Scenic Navigation is an intelligent Android navigation application designed for travelers who believe the journey is just as important as the destination. Instead of providing the fastest or shortest route, this app finds the most scenic path by discovering and prioritizing roads with beautiful coastlines, majestic mountains, and other points of interest.

## Features

- **Scenic Route Modes**: Choose between **Oceanic** and **Mountain** route preferences to get a journey tailored to your desired scenery.
- **Intelligent POI Discovery**: Automatically finds and scores nearby points of interest (POIs) like viewpoints, beaches, peaks, and nature reserves.
- **Dynamic Route Selection**: The app generates multiple route alternatives and uses a sophisticated scoring algorithm to select the one that is objectively the most scenic.
- **Long-Distance Logic**: For long trips (over 300km) in Oceanic mode, the app will intentionally seek out coastlines, even if it requires a detour.
- **Standard Navigation**: A **Default** mode is available for when you just need a direct, no-fuss route.

## How It Works

The app's magic lies in its two-stage process that combines route generation with real-world data analysis:

1.  **Route Generation (OSRM)**: The app first queries the **Open Source Routing Machine (OSRM)** to generate several potential routes between your start and destination. For scenic modes, it intelligently injects predefined waypoints to guide the routing engine toward coastal or mountainous regions.

2.  **Scoring and Selection (Overpass API)**: For each alternative route, the app uses the **Overpass API** to query the vast OpenStreetMap database for nearby scenic POIs. Each route is then given a "scenic score" based on the quantity, quality, and variety of its points of interest. The route with the highest score is selected as the winner.

## Technologies Used

- **Kotlin**: The application is built entirely in modern Kotlin.
- **osmdroid**: Powers the mapping and navigation display.
- **OkHttp**: Used for making network requests to the routing and POI APIs.
- **OSRM API**: For generating route alternatives.
- **Overpass API**: For discovering points of interest from OpenStreetMap data.

## Getting Started

1.  Clone the repository:
    ```bash
    git clone https://github.com/your-username/scenic-navigation.git
    ```
2.  Open the project in Android Studio.
3.  Build and run the application on an Android device or emulator.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
