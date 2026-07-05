import os

def replace_in_dir(directory, old_str, new_str):
    for root, _, files in os.walk(directory):
        for f in files:
            if f.endswith('.java'):
                filepath = os.path.join(root, f)
                with open(filepath, 'r') as file:
                    content = file.read()
                
                new_content = content.replace(old_str, new_str)
                
                with open(filepath, 'w') as file:
                    file.write(new_content)

replace_in_dir('query-service/src/main/java', 'com.finance.command', 'com.finance.query')

print("Refactoring complete.")
