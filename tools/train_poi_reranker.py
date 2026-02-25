r"""
Train a small POI reranker on synthetic data and export a TFLite model plus feature_stats.json

Usage (PowerShell):
    python tools\train_poi_reranker.py --out-assets ..\app\src\main\assets\models --epochs 10 --samples 20000

This script creates:
 - poi_reranker.tflite
 - feature_stats.json

Feature order used (matches Android MlInferenceEngine.FEATURE_ORDER):
 ["distNorm","timeSin","timeCos","cat_food","cat_sight","scenicScore","hasMunicipality"]

Notes:
 - This script is for bootstrapping/testing only. Replace with your real training pipeline & data later.
 - Installing TensorFlow may be heavy. Use a virtualenv.
"""
import os
import json
import argparse

# Provide clearer errors for missing native libs
try:
    import numpy as np
except Exception:
    print('\nERROR: numpy is not installed in the active Python environment.')
    print('Please create/activate your venv and run: pip install numpy')
    raise

try:
    import tensorflow as tf
    from tensorflow import keras
    from tensorflow.keras import layers
except Exception:
    print('\nERROR: TensorFlow is not installed or is incompatible with this Python version.')
    print('TensorFlow 2.12 supports Python 3.8-3.11. If you are using Python 3.12+ or 3.7-, install a supported Python (3.11) or use Docker.\n')
    print('Options:')
    print('  - Install Python 3.11 and create a venv:')
    print('      py -3.11 -m venv .venv; .\\.venv\\Scripts\\Activate.ps1')
    print('      python -m pip install --upgrade pip')
    print('      pip install -r tools\\requirements.txt')
    print('  - Or run the trainer in Docker:')
    print("      docker run --rm -v ${PWD}:/workspace -w /workspace tensorflow/tensorflow:2.12.0 python tools/train_poi_reranker.py --out-assets app/src/main/assets/models")
    raise

import numpy as np

FEATURE_ORDER = ["distNorm","timeSin","timeCos","cat_food","cat_sight","scenicScore","hasMunicipality"]


def generate_synthetic_data(n_samples=20000, seed=42):
    rng = np.random.RandomState(seed)
    # distNorm: uniform 0..1 but skew toward nearer
    dist = np.clip(rng.beta(1.5, 3.0, size=n_samples), 0, 1).astype(np.float32)
    hours = rng.randint(0,24,size=n_samples)
    angle = 2.0 * np.pi * hours / 24.0
    timeSin = np.sin(angle).astype(np.float32)
    timeCos = np.cos(angle).astype(np.float32)
    cat_food = rng.binomial(1, 0.1, size=n_samples).astype(np.float32)
    cat_sight = rng.binomial(1, 0.2, size=n_samples).astype(np.float32)
    scenic = np.clip(rng.normal(loc=0.3 + 0.5* (1-dist), scale=0.15, size=n_samples), 0, 1).astype(np.float32)
    hasMunicipality = rng.binomial(1, 0.7, size=n_samples).astype(np.float32)

    X = np.stack([dist, timeSin, timeCos, cat_food, cat_sight, scenic, hasMunicipality], axis=1)

    # Define a synthetic label: users prefer close + scenic + sightseeing
    logits = (1.5*(1-dist) + 1.0*scenic + 0.5*cat_sight + 0.2*hasMunicipality)
    probs = 1 / (1 + np.exp(-logits))
    y = rng.binomial(1, probs).astype(np.float32)

    return X, y


def build_and_train(
    X_train, y_train, X_val, y_val,
    epochs=8, batch_size=256, lr=1e-3, hidden_sizes=(128, 64), dropout=0.2, l2_reg=1e-5
):
    n_features = X_train.shape[1]
    inputs = layers.Input(shape=(n_features,))
    x = inputs
    for i, h in enumerate(hidden_sizes):
        x = layers.Dense(h, activation='relu', kernel_regularizer=keras.regularizers.l2(l2_reg))(x)
        if dropout and dropout > 0.0:
            x = layers.Dropout(dropout)(x)
    outputs = layers.Dense(1, activation='sigmoid')(x)
    model = keras.Model(inputs=inputs, outputs=outputs)

    optimizer = keras.optimizers.Adam(learning_rate=lr)
    model.compile(optimizer=optimizer, loss='binary_crossentropy', metrics=[keras.metrics.AUC(name='auc'), 'accuracy'])

    callbacks = [
        keras.callbacks.EarlyStopping(monitor='val_loss', patience=5, restore_best_weights=True),
        keras.callbacks.ReduceLROnPlateau(monitor='val_loss', factor=0.5, patience=3)
    ]

    model.fit(X_train, y_train, validation_data=(X_val, y_val), epochs=epochs, batch_size=batch_size, callbacks=callbacks)
    return model


def export_tflite(model, out_path, quantize=False, representative_data=None):
    # Save Keras model for debugging/serving
    try:
        keras_path = out_path + '.keras'
        model.save(keras_path)
    except Exception:
        # non-fatal
        pass

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    if quantize:
        converter.optimizations = [tf.lite.Optimize.DEFAULT]
        if representative_data is not None:
            def rep_gen():
                for i in range(min(100, representative_data.shape[0])):
                    # representative dataset yields a plain array or tuple of arrays
                    yield [representative_data[i:i+1].astype(np.float32)]
            converter.representative_dataset = rep_gen
            # prefer int8 full integer quantization when representative dataset provided
            try:
                converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]
                converter.inference_input_type = tf.int8
                converter.inference_output_type = tf.int8
            except Exception:
                # fallback to default quantization if int8 not supported in this env
                pass
    tflite_model = converter.convert()
    with open(out_path, 'wb') as f:
        f.write(tflite_model)


def compute_feature_stats(X, feature_order=FEATURE_ORDER):
    stats = { }
    for i, name in enumerate(feature_order):
        col = X[:, i]
        stats[name] = {
            'min': float(np.min(col)),
            'max': float(np.max(col)),
            'mean': float(np.mean(col)),
            'std': float(np.std(col))
        }
    return stats


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--out-assets', type=str, default='app/src/main/assets/models', help='Output assets folder (will be created if missing)')
    parser.add_argument('--samples', type=int, default=20000)
    parser.add_argument('--epochs', type=int, default=8)
    parser.add_argument('--quantize', action='store_true')
    parser.add_argument('--batch-size', type=int, default=256)
    parser.add_argument('--lr', type=float, default=1e-3, help='Initial learning rate')
    parser.add_argument('--hidden-sizes', type=str, default='128,64', help='Comma-separated hidden layer sizes')
    parser.add_argument('--dropout', type=float, default=0.2, help='Dropout rate between dense layers')
    parser.add_argument('--l2', type=float, default=1e-5, help='L2 regularization coefficient')
    parser.add_argument('--model-version', type=str, default='synthetic_v1', help='Model version string to write into feature_stats.json')
    args = parser.parse_args()

    out_assets = args.out_assets
    os.makedirs(out_assets, exist_ok=True)

    print('Generating synthetic data...')
    X, y = generate_synthetic_data(n_samples=args.samples)
    # split
    idx = int(len(X)*0.8)
    X_train, X_val = X[:idx], X[idx:]
    y_train, y_val = y[:idx], y[idx:]

    print('Building and training model...')
    hidden_sizes = tuple(int(x) for x in args.hidden_sizes.split(',') if x.strip())
    model = build_and_train(
        X_train, y_train, X_val, y_val,
        epochs=args.epochs,
        batch_size=args.batch_size,
        lr=args.lr,
        hidden_sizes=hidden_sizes,
        dropout=args.dropout,
        l2_reg=args.l2
    )

    tflite_path = os.path.join(out_assets, 'poi_reranker.tflite')
    print(f'Exporting TFLite model to {tflite_path} (quantize={args.quantize})...')
    try:
        export_tflite(model, tflite_path, quantize=args.quantize, representative_data=X_train)
    except Exception as ex:
        print('TFLite export failed:', ex)
        print('Attempting to export without quantization...')
        export_tflite(model, tflite_path, quantize=False)

    print('Saving feature_stats.json...')
    stats = compute_feature_stats(X, FEATURE_ORDER)
    meta = {
        'model_version': args.model_version,
        'feature_order': FEATURE_ORDER,
        'normalization': { k: { 'type':'standard', 'mean': v['mean'], 'std': v['std'], 'min': v['min'], 'max': v['max']} for k,v in stats.items() }
    }
    with open(os.path.join(out_assets, 'feature_stats.json'), 'w', encoding='utf-8') as f:
        json.dump(meta, f, indent=2)

    print('Done. Created:')
    print(' -', tflite_path)
    print(' -', os.path.join(out_assets, 'feature_stats.json'))


if __name__ == '__main__':
    main()
