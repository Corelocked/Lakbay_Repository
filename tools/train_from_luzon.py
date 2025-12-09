r"""
Train POI reranker using the provided `tools/luzon_dataset.csv` as a seed for POIs.

This script will:
 - read the POI list (Name,Category,Lat,Lon)
 - sample synthetic user impressions by sampling user locations across the POI bbox
 - compute features matching the app's feature order
 - generate labels using a heuristic (so you get a trainable dataset)
 - train a small TF model and export to TFLite + feature_stats.json in the specified out-assets

Usage (PowerShell) from repo root (use your venv python if available):
.venv\Scripts\python.exe tools\train_from_luzon.py --out-assets app\src\main\assets\models --samples 10000 --epochs 6

Options:
  --poi-csv    Path to POI CSV (default: tools/luzon_dataset.csv)
  --samples    Number of synthetic impression rows to generate (default: 10000)
  --epochs     Training epochs (default: 6)
  --out-assets Output folder for tflite and feature_stats.json
  --quantize   If passed, attempt post-training quantization (may require representative data)

Notes:
 - Labels are synthetic (heuristic). For production, replace with real telemetry labels.
"""
import os
from pathlib import Path
import csv
import json
import argparse
import math
import numpy as np

try:
    import tensorflow as tf
    from tensorflow import keras
    from tensorflow.keras import layers
except Exception as e:
    print('TensorFlow import failed:', e)
    raise

FEATURE_ORDER = ["distNorm","timeSin","timeCos","cat_food","cat_sight","scenicScore","hasMunicipality"]

def read_poi_csv(path):
    pois = []
    with open(path, newline='', encoding='utf-8') as f:
        reader = csv.DictReader(f)
        for r in reader:
            try:
                lat = float(r.get('Lat') or r.get('lat') or r.get('Latitude') or 0)
                lon = float(r.get('Lon') or r.get('lon') or r.get('Longitude') or 0)
            except:
                continue
            pois.append({
                'name': r.get('Name') or r.get('name') or '',
                'category': r.get('Category') or r.get('category') or '',
                'lat': lat,
                'lon': lon
            })
    return pois

# Haversine in meters
def haversine_m(lat1, lon1, lat2, lon2):
    R = 6371000.0
    dLat = math.radians(lat2 - lat1)
    dLon = math.radians(lon2 - lon1)
    a = math.sin(dLat/2)**2 + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dLon/2)**2
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1-a))
    return R * c

def sample_user_locations(pois, n_users):
    # Sample uniformly within bounding box of POIs with a small margin
    lats = [p['lat'] for p in pois]
    lons = [p['lon'] for p in pois]
    minlat, maxlat = min(lats), max(lats)
    minlon, maxlon = min(lons), max(lons)
    margin_lat = (maxlat - minlat) * 0.05
    margin_lon = (maxlon - minlon) * 0.05
    rng = np.random.RandomState(1234)
    users = []
    for _ in range(n_users):
        lat = rng.uniform(minlat - margin_lat, maxlat + margin_lat)
        lon = rng.uniform(minlon - margin_lon, maxlon + margin_lon)
        users.append((lat, lon))
    return users

def category_to_flags(cat):
    c = (cat or '').lower()
    cat_food = 1.0 if 'restaurant' in c or 'cafe' in c or 'coffee' in c or 'eatery' in c else 0.0
    cat_sight = 1.0 if any(x in c for x in ['museum','tourist','nature','park','hidden','attraction','view','falls','volcano','beach']) else 0.0
    return cat_food, cat_sight

def category_to_scenic_base(cat):
    c = (cat or '').lower()
    if any(x in c for x in ['nature','park','volcano','falls','beach','mount','ridge','cove','island']):
        return 0.9
    if any(x in c for x in ['tourist','attraction','museum','histor']):
        return 0.7
    if 'restaurant' in c or 'cafe' in c:
        return 0.4
    return 0.5

def build_features(poi, userLat, userLon, hour, scenic_score_override=None):
    distance = haversine_m(userLat, userLon, poi['lat'], poi['lon'])
    maxDist = 50000.0
    distNorm = float(min(distance / maxDist, 1.0))
    angle = 2.0 * math.pi * (hour % 24) / 24.0
    timeSin = math.sin(angle)
    timeCos = math.cos(angle)
    cat_food, cat_sight = category_to_flags(poi['category'])
    scenic_base = category_to_scenic_base(poi['category'])
    scenicScore = float(scenic_score_override if scenic_score_override is not None else max(0.0, min(1.0, np.random.normal(loc=scenic_base, scale=0.15))))
    hasMunicipality = 1.0 if poi.get('name') else 0.0
    return np.array([distNorm, timeSin, timeCos, cat_food, cat_sight, scenicScore, hasMunicipality], dtype=np.float32)

def generate_dataset(pois, n_samples=10000):
    rng = np.random.RandomState(42)
    users = sample_user_locations(pois, max(50, n_samples // 200))
    X = []
    y = []
    for i in range(n_samples):
        # pick a random user and a random POI (simulate impression)
        user = users[rng.randint(len(users))]
        poi = pois[rng.randint(len(pois))]
        hour = rng.randint(0,24)
        features = build_features(poi, user[0], user[1], hour)
        # label heuristic: combine distance, scenic, and category
        # stronger weight for closer and scenic/sight
        dist = features[0]
        scenic = features[5]
        sight = features[4]
        logit = 1.5*(1 - dist) + 1.2*scenic + 0.6*sight + rng.normal(scale=0.3)
        prob = 1.0 / (1.0 + math.exp(-logit))
        label = float(rng.binomial(1, prob))
        X.append(features)
        y.append(label)
    X = np.stack(X, axis=0)
    y = np.array(y, dtype=np.float32)
    return X, y

def build_and_train(X_train, y_train, X_val, y_val, epochs=6, batch_size=256, quantize=False, out_assets='app/src/main/assets/models'):
    n_features = X_train.shape[1]
    model = keras.Sequential([
        layers.Input(shape=(n_features,)),
        layers.Dense(64, activation='relu'),
        layers.Dense(32, activation='relu'),
        layers.Dense(1, activation='sigmoid')
    ])
    model.compile(optimizer='adam', loss='binary_crossentropy', metrics=['AUC'])
    model.fit(X_train, y_train, validation_data=(X_val, y_val), epochs=epochs, batch_size=batch_size)

    os.makedirs(out_assets, exist_ok=True)
    tflite_path = os.path.join(out_assets, 'poi_reranker_from_luzon.tflite')

    # Export
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    if quantize:
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        def rep_gen():
            for i in range(min(100, X_train.shape[0])):
                yield [X_train[i:i+1].astype(np.float32)]
        converter.representative_dataset = rep_gen
    tflite_model = converter.convert()
    with open(tflite_path, 'wb') as f:
        f.write(tflite_model)

    # feature stats
    stats = {}
    for i, name in enumerate(FEATURE_ORDER):
        col = X_train[:, i]
        stats[name] = {
            'min': float(np.min(col)),
            'max': float(np.max(col)),
            'mean': float(np.mean(col)),
            'std': float(np.std(col))
        }
    meta = {
        'model_version': 'luzon_synthetic_v1',
        'feature_order': FEATURE_ORDER,
        'normalization': { k: { 'type': 'standard', 'mean': v['mean'], 'std': v['std'], 'min': v['min'], 'max': v['max']} for k,v in stats.items() }
    }
    with open(os.path.join(out_assets, 'feature_stats_from_luzon.json'), 'w', encoding='utf-8') as f:
        json.dump(meta, f, indent=2)

    print('Wrote', tflite_path)
    print('Wrote feature_stats at', os.path.join(out_assets, 'feature_stats_from_luzon.json'))


def main():
    try:
        parser = argparse.ArgumentParser()
        # Default POI CSV resolves relative to this script file so the script
        # works whether invoked from repo root or the `tools/` folder.
        default_poi = str(Path(__file__).resolve().parent / 'luzon_dataset.csv')
        parser.add_argument('--poi-csv', type=str, default=default_poi)
        parser.add_argument('--samples', type=int, default=10000)
        parser.add_argument('--epochs', type=int, default=6)
        parser.add_argument('--out-assets', type=str, default='app/src/main/assets/models')
        parser.add_argument('--quantize', action='store_true')
        args = parser.parse_args()

        print('train_from_luzon.py starting with args:', args)

        pois = read_poi_csv(args.poi_csv)
        if len(pois) < 5:
            raise SystemExit('Not enough POIs found in CSV: ' + args.poi_csv)
        print('Loaded', len(pois), 'POIs from', args.poi_csv)

        X, y = generate_dataset(pois, n_samples=args.samples)
        # split
        idx = int(0.8 * len(X))
        X_train, X_val = X[:idx], X[idx:]
        y_train, y_val = y[:idx], y[idx:]

        print('Training with', X_train.shape[0], 'train rows and', X_val.shape[0], 'val rows')
        build_and_train(X_train, y_train, X_val, y_val, epochs=args.epochs, quantize=args.quantize, out_assets=args.out_assets)
    except Exception as e:
        import traceback
        print('ERROR in train_from_luzon:', e)
        traceback.print_exc()
        raise

if __name__ == '__main__':
    main()
