import os
import re

directory = 'frontend/js'
version = '?v=22'

# Regex to match static and dynamic imports
# e.g. import { x } from './file.js';
# e.g. import('./file.js')
import_pattern = re.compile(r"(from\s+['\"])([^'\"]+\.js)(\?v=\d+)?(['\"])")
dynamic_import_pattern = re.compile(r"(import\s*\(\s*['\"])([^'\"]+\.js)(\?v=\d+)?(['\"])")

for root, _, files in os.walk(directory):
    for file in files:
        if file.endswith('.js'):
            filepath = os.path.join(root, file)
            with open(filepath, 'r') as f:
                content = f.read()
            
            # Replace static imports
            content = import_pattern.sub(lambda m: f"{m.group(1)}{m.group(2)}{version}{m.group(4)}", content)
            
            # Replace dynamic imports
            content = dynamic_import_pattern.sub(lambda m: f"{m.group(1)}{m.group(2)}{version}{m.group(4)}", content)
            
            with open(filepath, 'w') as f:
                f.write(content)

print("Imports updated!")
