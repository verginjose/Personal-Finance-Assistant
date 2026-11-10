"""Score labelled fields while reporting required human review separately."""
import re


def compare_fields(document, expected):
    matched, abstentions, failures = [], [], []
    for field, value in expected.items():
        actual = document.get('evidence', {}).get('items') if field == 'items' else document.get(
            'name' if field == 'vendor' and 'name' in document else field)
        wanted = value
        if field == 'amount':
            try:
                actual = round(float(actual), 2)
            except (TypeError, ValueError):
                actual = None
        if field == 'vendor' and actual and wanted:
            actual, wanted = [re.sub(r'[^a-z0-9]', '', text.lower()) for text in (actual, wanted)]
        if actual == wanted:
            matched.append(field)
        elif ('name' if field == 'vendor' else field) in document.get('reviewFields', []):
            abstentions.append({'field': field, 'expected': value, 'actual': actual})
        else:
            failures.append({'field': field, 'expected': value, 'actual': actual})
    return matched, abstentions, failures
