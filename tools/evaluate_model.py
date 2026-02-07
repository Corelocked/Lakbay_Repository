#!/usr/bin/env python3
"""
Evaluate a Keras model and update model_metadata.json.

Usage:
  python tools/evaluate_model.py \
    --model app/src/main/assets/models/poi_reranker.tflite.keras \
    --test-csv tools/synthetic_features.csv \
    --tflite app/src/main/assets/models/poi_reranker.tflite \
    --out-metadata app/src/main/assets/models/model_metadata.json

This script supports either a CSV with the feature columns named per FEATURE_ORDER plus optional 'label',
or a .npy features file via --features-npy. If labels are provided (CSV with label column) it computes AUC and accuracy.

Dependencies: numpy, tensorflow, scikit-learn (optional for AUC)
"""
import argparse
import json
import os
import sys
import numpy as np

try:
    import tensorflow as tf
    from tensorflow import keras
except Exception:
    print("ERROR: TensorFlow is required to run this script. Use a Python environment with tensorflow installed.")
    raise

FEATURE_ORDER = ["distNorm","timeSin","timeCos","cat_food","cat_sight","scenicScore","hasMunicipality"]


def load_features_from_csv(path, feature_order):
    import csv
    with open(path, newline='', encoding='utf-8') as fh:
        reader = csv.DictReader(fh)
        headers = reader.fieldnames or []
        has_all = all(name in headers for name in feature_order)
        rows = list(reader)
        if not rows:
            raise RuntimeError("Test CSV is empty.")
        labels = None
        if 'label' in headers:
            labels = np.array([float(r.get('label') or 0.0) for r in rows], dtype=np.float32)
        if has_all:
            X = np.stack([[float(r.get(n, 0.0) or 0.0) for n in feature_order] for r in rows], axis=0).astype(np.float32)
            return X, labels
        else:
            raise RuntimeError("CSV does not contain the required feature columns: {}".format(feature_order))


def load_features_from_npy(path):
    arr = np.load(path)
    return arr.astype(np.float32)


def evaluate_keras(model_path, X, y=None, batch_size=256):
    model = keras.models.load_model(model_path)
    preds = model.predict(X, batch_size=batch_size).reshape(-1)
    results = {}
    results['n_samples'] = int(X.shape[0])
    results['pred_mean'] = float(np.mean(preds))
    results['pred_std'] = float(np.std(preds))
    if y is not None:
        try:
            from sklearn.metrics import roc_auc_score, accuracy_score
            auc = float(roc_auc_score(y, preds))
            acc = float(accuracy_score(y, (preds >= 0.5).astype(int)))
            results['metrics'] = {'auc': auc, 'accuracy': acc}
        except Exception:
            acc = float(np.mean((preds >= 0.5).astype(int) == y.astype(int)))
            results['metrics'] = {'accuracy': acc}
    return preds, results


def evaluate_tflite(tflite_path, X, num_check=20):
    try:
        interpreter = tf.lite.Interpreter(model_path=tflite_path)
        interpreter.allocate_tensors()
        input_details = interpreter.get_input_details()
        output_details = interpreter.get_output_details()
        inp_idx = input_details[0]['index']
        out_idx = output_details[0]['index']
        tflite_preds = []
        for i in range(min(num_check, X.shape[0])):
            sample = X[i:i+1].astype(np.float32)
            interpreter.set_tensor(inp_idx, sample)
            interpreter.invoke()
            out = interpreter.get_tensor(out_idx)
            tflite_preds.append(float(np.squeeze(out)))
        return tflite_preds
    except Exception as e:
        print('TFLite eval failed:', e)
        return None


def merge_metadata(existing_path, out_path, model_name, results, extra):
    meta = {}
    if os.path.exists(existing_path):
        try:
            with open(existing_path, 'r', encoding='utf-8') as fh:
                meta = json.load(fh)
        except Exception:
            meta = {}
    meta['model_name'] = model_name
    meta['last_evaluated'] = extra.get('timestamp')
    # Ensure metrics is a dict so we can update safely even if existing file had null
    if not isinstance(meta.get('metrics'), dict):
        meta['metrics'] = {}
    meta['metrics'].update(results.get('metrics', {}))
    meta['pred_mean'] = results.get('pred_mean')
    meta['pred_std'] = results.get('pred_std')
    meta['n_samples'] = results.get('n_samples')
    meta['notes'] = extra.get('notes', meta.get('notes'))
    with open(out_path, 'w', encoding='utf-8') as fh:
        json.dump(meta, fh, indent=2)
    return meta


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--model', required=True, help='Path to Keras model (e.g. app/.../poi_reranker.tflite.keras)')
    parser.add_argument('--tflite', help='Optional path to corresponding TFLite file to sanity-check')
    parser.add_argument('--test-csv', help='CSV with feature columns (or label). Header must include FEATURE_ORDER names if using CSV.')
    parser.add_argument('--features-npy', help='Path to a .npy file with precomputed features (shape N,F)')
    parser.add_argument('--feature-order', nargs='+', default=FEATURE_ORDER)
    parser.add_argument('--out-metadata', default='app/src/main/assets/models/model_metadata.json', help='Metadata JSON to update')
    parser.add_argument('--batch-size', type=int, default=256)
    args = parser.parse_args()

    if not os.path.exists(args.model):
        print('Model not found:', args.model)
        sys.exit(2)

    X = None
    y = None
    if args.features_npy:
        X = load_features_from_npy(args.features_npy)
    elif args.test_csv:
        X, y = load_features_from_csv(args.test_csv, args.feature_order)
    else:
        print('Either --test-csv with feature columns or --features-npy must be provided.')
        sys.exit(2)

    preds, results = evaluate_keras(args.model, X, y=y, batch_size=args.batch_size)
    print('Evaluation summary:', results)

    if args.tflite:
        t_preds = evaluate_tflite(args.tflite, X, num_check=20)
        if t_preds is not None:
            print('TFLite small-sample preds (first %d): %s' % (len(t_preds), t_preds))
        else:
            print('TFLite evaluation failed or not supported in this environment.')

    import datetime
    meta = merge_metadata(args.out_metadata, args.out_metadata, os.path.basename(args.model), results, {
        'timestamp': datetime.datetime.utcnow().isoformat() + 'Z',
        'notes': 'Evaluation run populated by tools/evaluate_model.py'
    })
    print('Wrote metadata to', args.out_metadata)
    print(json.dumps(meta, indent=2))

if __name__ == '__main__':
    main()

