This folder contains training and evaluation helpers for the POI reranker.

Quick steps (local, using Python venv):

1) Create virtual environment and install deps

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install --upgrade pip
pip install numpy tensorflow scikit-learn
```

2) Train a synthetic model (produces .keras and .tflite)

```powershell
python tools\train_poi_reranker.py --out-assets app\src\main\assets\models --epochs 8 --samples 20000
```

3) Generate synthetic features (optional, for evaluation/demo)

```powershell
pip install numpy
python tools\generate_synthetic_features.py --out-csv tools\synthetic_features.csv --npy tools\synthetic_features.npy --samples 2000
```

4) Evaluate the Keras model and populate model_metadata.json

```powershell
python tools\evaluate_model.py --model app\src\main\assets\models\poi_reranker.tflite.keras --test-csv tools\synthetic_features.csv --tflite app\src\main\assets\models\poi_reranker_from_luzon.tflite --out-metadata app\src\main\assets\models\model_metadata.json
```

Alternative: run everything inside Docker (no TF install locally):

```powershell
# Train
docker run --rm -v ${PWD}:/workspace -w /workspace tensorflow/tensorflow:2.12.0 python tools/train_poi_reranker.py --out-assets app/src/main/assets/models --epochs 8 --samples 20000
# Evaluate
docker run --rm -v ${PWD}:/workspace -w /workspace tensorflow/tensorflow:2.12.0 python tools/evaluate_model.py --model app/src/main/assets/models/poi_reranker.tflite.keras --test-csv tools/synthetic_features.csv --tflite app/src/main/assets/models/poi_reranker_from_luzon.tflite --out-metadata app/src/main/assets/models/model_metadata.json
```

Notes:
- The evaluation script expects features in the same order as FEATURE_ORDER: distNorm, timeSin, timeCos, cat_food, cat_sight, scenicScore, hasMunicipality.
- If you have a real held-out test set, prefer using it instead of the synthetic features.
- After evaluation, model_metadata.json will be updated with metrics and last_evaluated timestamp.

