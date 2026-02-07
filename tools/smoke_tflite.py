import os
import json

root = os.path.dirname(os.path.dirname(__file__))
model_paths = [
    os.path.join(root, 'app', 'src', 'main', 'assets', 'models', 'poi_reranker_from_luzon.tflite'),
    os.path.join(root, 'app', 'src', 'main', 'assets', 'models', 'poi_reranker.tflite.keras')
]
meta_path = os.path.join(root, 'app', 'src', 'main', 'assets', 'models', 'model_metadata.json')

for p in model_paths:
    print('exists:', p, os.path.exists(p))

print('metadata exists:', os.path.exists(meta_path))
if os.path.exists(meta_path):
    with open(meta_path, 'r', encoding='utf-8') as f:
        try:
            j = json.load(f)
            print('model_name:', j.get('model_name'))
            print('metrics:', j.get('metrics'))
        except Exception as e:
            print('metadata parse error:', e)

