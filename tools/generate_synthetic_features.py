#!/usr/bin/env python3
"""
Generate synthetic features CSV and .npy using the same feature ordering as the trainer.
This script doesn't require TensorFlow — only NumPy — and is intended to produce a small test file
that you can use with `tools/evaluate_model.py` locally.

Usage:
  py -3 -m venv .venv; .\.venv\Scripts\Activate.ps1
  pip install numpy
  python tools/generate_synthetic_features.py --out-csv tools/synthetic_features.csv --npy tools/synthetic_features.npy --samples 2000

Produces:
 - tools/synthetic_features.csv (header: distNorm,timeSin,timeCos,cat_food,cat_sight,scenicScore,hasMunicipality,label)
 - tools/synthetic_features.npy (feature matrix N x F)
"""
import argparse
import numpy as np
import csv

FEATURE_ORDER = ["distNorm","timeSin","timeCos","cat_food","cat_sight","scenicScore","hasMunicipality"]


def generate_synthetic_data(n_samples=2000, seed=42):
    rng = np.random.RandomState(seed)
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
    logits = (1.5*(1-dist) + 1.0*scenic + 0.5*cat_sight + 0.2*hasMunicipality)
    probs = 1 / (1 + np.exp(-logits))
    y = rng.binomial(1, probs).astype(np.float32)
    return X, y


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--out-csv', default='tools/synthetic_features.csv')
    parser.add_argument('--npy', default='tools/synthetic_features.npy')
    parser.add_argument('--samples', type=int, default=2000)
    args = parser.parse_args()

    X, y = generate_synthetic_data(n_samples=args.samples)
    np.save(args.npy, X)

    header = FEATURE_ORDER + ['label']
    with open(args.out_csv, 'w', newline='', encoding='utf-8') as fh:
        writer = csv.writer(fh)
        writer.writerow(header)
        for i in range(X.shape[0]):
            row = [float(X[i,j]) for j in range(X.shape[1])] + [int(y[i])]
            writer.writerow(row)

    print('Wrote', args.out_csv, 'and', args.npy)

if __name__ == '__main__':
    main()

